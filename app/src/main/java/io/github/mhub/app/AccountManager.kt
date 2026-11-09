package io.github.mhub.app

import android.content.Context
import android.os.Build
import io.github.mhub.core.AccountUser
import io.github.mhub.core.ApiException
import io.github.mhub.core.AuthConfig
import io.github.mhub.core.LibraryOp
import io.github.mhub.core.LibraryOpJson
import io.github.mhub.core.LibrarySnapshot
import io.github.mhub.core.LibrarySync
import io.github.mhub.core.MHubClient
import io.github.mhub.core.MHubJson
import io.github.mhub.core.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

/**
 * The signed-in account and its library sync (docs/design.md「账号与歌单同步」).
 * Local playlists stay the source of the UI; edits go to a persisted queue of
 * operations that is uploaded in batches, and every server snapshot replaces
 * the local library with the still-pending operations replayed on top.
 */
class AccountManager(context: Context, private val model: AppModel, private val library: LibraryStore) {
    enum class Sync { Idle, Syncing, Synced, Offline }

    data class State(
        val config: AuthConfig = AuthConfig(),
        val user: AccountUser? = null,
        val sync: Sync = Sync.Idle,
        val pending: Int = 0,
        val error: String? = null,
    )

    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val queueFile = File(context.filesDir, "pending-ops.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Mutex()
    private var flushJob: Job? = null
    private val opsSerializer = ListSerializer(LibraryOp.serializer())

    /** Session token in app-private storage; read by every request of the shared client. */
    val session = object : SessionStore {
        override var token: String?
            get() = prefs.getString("token", null)
            set(value) { prefs.edit().putString("token", value).apply() }
    }

    val device: MHubClient.Device by lazy {
        val id = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }
        MHubClient.Device(id, listOf(Build.MANUFACTURER.replaceFirstChar(Char::uppercase), Build.MODEL).distinct().joinToString(" ") + " · MHub")
    }

    private var pending: List<LibraryOp> = loadQueue()

    private val _state = MutableStateFlow(State(user = loadUser(), pending = pending.size))
    val state: StateFlow<State> = _state

    init {
        library.recorder = { ops -> if (_state.value.user != null) enqueue(ops) }
    }

    private fun loadUser(): AccountUser? = prefs.getString("user", null)?.let {
        runCatching { MHubJson.decodeFromString(AccountUser.serializer(), it) }.getOrNull()
    }?.takeIf { session.token != null }

    private fun loadQueue(): List<LibraryOp> = runCatching {
        LibraryOpJson.decodeFromString(opsSerializer, queueFile.readText())
    }.getOrDefault(emptyList())

    private fun saveQueue(ops: List<LibraryOp>) {
        pending = ops
        val temp = File(queueFile.parentFile, "pending-ops.json.tmp")
        temp.writeText(LibraryOpJson.encodeToString(opsSerializer, ops))
        temp.renameTo(queueFile)
        _state.update { it.copy(pending = ops.size) }
    }

    private fun setUser(user: AccountUser?) {
        prefs.edit().putString("user", user?.let { MHubJson.encodeToString(AccountUser.serializer(), it) }).apply()
        _state.update { it.copy(user = user) }
    }

    /** Launch and foreground: learn whether the server has accounts, then sync. */
    fun refresh() {
        val client = model.client ?: return
        scope.launch {
            runCatching { client.authConfig() }.onSuccess { config -> _state.update { it.copy(config = config) } }
            if (_state.value.user != null) sync()
        }
    }

    private fun enqueue(ops: List<LibraryOp>) {
        saveQueue(pending + ops)
        // Coalesce a burst of edits (a drag, several removals) into one upload.
        flushJob?.cancel()
        flushJob = scope.launch { delay(800); sync() }
    }

    suspend fun login(username: String, password: String) = signIn { it.login(username.trim(), password, device) }

    suspend fun recover(username: String, code: String) = signIn { it.recoveryLogin(username.trim(), code.trim(), device) }

    /** Returns the recovery codes to show once. */
    suspend fun register(username: String, password: String): List<String> {
        var codes = emptyList<String>()
        signIn { client -> client.register(username.trim(), password, device).also { codes = it.recoveryCodes } }
        return codes
    }

    private suspend fun signIn(request: suspend (MHubClient) -> io.github.mhub.core.LoginResponse) {
        val client = model.client ?: throw ApiException.InvalidServer()
        val response = request(client)
        // Until the local playlists have joined the account, no snapshot may
        // replace them: a failed merge is retried by the next sync instead.
        prefs.edit().putBoolean("merge_pending", true).apply()
        setUser(client.me() ?: response.user)
        saveQueue(emptyList())
        lock.withLock { merge(client) }
    }

    private val mergePending: Boolean get() = prefs.getBoolean("merge_pending", false)

    /** First sync after signing in: local playlists join the account like on the web. */
    private suspend fun merge(client: MHubClient) {
        _state.update { it.copy(sync = Sync.Syncing, error = null) }
        try {
            val cloud = client.librarySnapshot()
            val ops = LibrarySync.mergeOps(LibrarySync.planMerge(library.playlists.value, cloud.library))
            val result = if (ops.isEmpty()) cloud else ops.chunked(500).fold(cloud) { _, chunk -> client.libraryOps(chunk) }
            library.replaceAll(result.library)
            prefs.edit().putBoolean("merge_pending", false).apply()
            _state.update { it.copy(sync = Sync.Synced) }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.update { it.copy(sync = Sync.Offline, error = error.message) }
        }
    }

    /** Upload the queue, then take the server's library. */
    fun syncNow() { scope.launch { sync() } }

    private suspend fun sync() = lock.withLock {
        val client = model.client ?: return@withLock
        if (_state.value.user == null) return@withLock
        if (mergePending) return@withLock merge(client)
        _state.update { it.copy(sync = Sync.Syncing, error = null) }
        try {
            var snapshot: LibrarySnapshot? = null
            while (pending.isNotEmpty()) {
                val batch = pending.take(500)
                snapshot = client.libraryOps(batch)
                // Edits made during the upload stay queued after the batch.
                saveQueue(pending.drop(batch.size))
            }
            val latest = snapshot ?: client.librarySnapshot()
            library.replaceAll(LibrarySync.apply(latest.library, pending, System.currentTimeMillis()))
            _state.update { it.copy(sync = if (pending.isEmpty()) Sync.Synced else Sync.Idle) }
        } catch (error: ApiException.Http) {
            if (error.status == 401) expire() else _state.update { it.copy(sync = Sync.Offline, error = error.message) }
        } catch (error: kotlinx.coroutines.CancellationException) {
            _state.update { it.copy(sync = Sync.Idle) }
            throw error
        } catch (error: Exception) {
            _state.update { it.copy(sync = Sync.Offline, error = error.message) }
        }
    }

    /** The session ended elsewhere (logged out, revoked): back to a local library. */
    private fun expire() {
        session.token = null
        prefs.edit().putBoolean("merge_pending", false).apply()
        setUser(null)
        saveQueue(emptyList())
        _state.update { it.copy(sync = Sync.Idle, error = null) }
    }

    /** Keeps the playlists on this phone as local ones; unsent edits are dropped. */
    suspend fun logout() {
        val client = model.client
        runCatching { client?.logout() }
        expire()
    }
}
