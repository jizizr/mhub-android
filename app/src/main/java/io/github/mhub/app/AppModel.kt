package io.github.mhub.app

import io.github.mhub.core.MHubClient
import io.github.mhub.core.MHubJson
import io.github.mhub.core.PlatformInfo
import io.github.mhub.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

/** App-wide state shared by every screen: server client, platforms, recent songs and transient feedback. */
class AppModel(val settings: Settings) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _platforms = MutableStateFlow(PlatformInfo.fallback)
    val platforms: StateFlow<List<PlatformInfo>> = _platforms

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast
    private var toastJob: Job? = null

    private val _recent = MutableStateFlow<List<Track>>(emptyList())
    val recent: StateFlow<List<Track>> = _recent
    private var recentStore: android.content.SharedPreferences? = null

    private var cachedClient: Pair<String, MHubClient>? = null

    /** The account session every request carries (set once by the app graph). */
    var session: io.github.mhub.core.SessionStore? = null

    /** The client for the configured server, or null before setup. */
    val client: MHubClient?
        get() {
            val url = settings.serverUrl ?: return null
            val key = url.toString()
            cachedClient?.takeIf { it.first == key }?.let { return it.second }
            return MHubClient(url, session = session).also { cachedClient = key to it }
        }

    fun attach(context: android.content.Context) {
        recentStore = context.getSharedPreferences("recent", android.content.Context.MODE_PRIVATE)
        _recent.value = runCatching {
            MHubJson.decodeFromString(ListSerializer(Track.serializer()), recentStore?.getString("tracks", "[]") ?: "[]")
        }.getOrDefault(emptyList())
    }

    fun show(message: String) {
        _toast.value = message
        toastJob?.cancel()
        toastJob = scope.launch {
            delay(2600)
            _toast.value = null
        }
    }

    fun dismissToast() { _toast.value = null }

    fun refreshPlatforms() {
        val client = client ?: return
        scope.launch {
            runCatching { client.platforms() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { _platforms.value = it }
        }
    }

    fun label(platform: String) = _platforms.value.firstOrNull { it.id == platform }?.label ?: PlatformInfo.label(platform)

    fun recordRecent(track: Track) {
        val list = (listOf(track.chosen) + _recent.value.filter { it.id != track.id }).take(30)
        _recent.value = list
        recentStore?.edit()?.putString("tracks", MHubJson.encodeToString(ListSerializer(Track.serializer()), list))?.apply()
    }
}
