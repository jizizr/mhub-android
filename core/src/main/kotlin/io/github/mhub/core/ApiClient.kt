package io.github.mhub.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

sealed class ApiException(message: String) : Exception(message) {
    class InvalidServer : ApiException("服务器地址无效")
    class Http(val status: Int, detail: String) : ApiException(detail.ifEmpty { "HTTP $status" })
    class PlaybackFailed(message: String) : ApiException(message.ifEmpty { "播放准备失败" })
    /** No source can ever play the song (server code `unplayable`): skip it, never retry. */
    class Unplayable(message: String) : ApiException(message.ifEmpty { "暂时无法播放" })
    class TimedOut : ApiException("等待音频超时，请重试")
    /** HTTP 429 that outlasted the caller's wait limit. */
    class RateLimited(val retryAfterMs: Long, val detail: String) :
        ApiException("${detail.ifEmpty { "请求过于频繁" }}（约 ${RateLimit.seconds(retryAfterMs)} 秒后可重试）")
}

/** When a playback task can be handed to the system player. Shared with iOS; see docs/api-contract.md. */
sealed interface PlaybackReadiness {
    data class Ready(val url: String) : PlaybackReadiness
    data class Wait(val since: String) : PlaybackReadiness
    data class Failed(val message: String, val unplayable: Boolean = false) : PlaybackReadiness

    companion object {
        const val UNPLAYABLE = "unplayable"

        fun evaluate(task: PlaybackTask, server: HttpUrl): PlaybackReadiness = when {
            task.status == "failed" -> Failed(task.error.orEmpty(), task.errorCode == UNPLAYABLE)
            task.status == "completed" || (task.status == "streaming" && task.streamLength != null) ->
                MHubClient.resolve(task.audioUrl, server)?.let { Ready(it.toString()) } ?: Failed("音频地址无效")
            else -> Wait(task.status)
        }
    }
}

/** HTTP client for one MHub server. */
class MHubClient(
    val server: HttpUrl,
    private val http: OkHttpClient = defaultHttp,
    /** The signed-in account's session; null for guests. */
    val session: SessionStore? = null,
    /** Waits out a 429; tests replace it to record waits instead of sleeping. */
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {

    companion object {
        val defaultHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** Parses a user-entered address; a missing scheme defaults to https. */
        fun serverUrl(text: String): HttpUrl? {
            var value = text.trim()
            if (value.isEmpty()) return null
            if (!value.contains("://")) value = "https://$value"
            value = value.trimEnd('/')
            val url = value.toHttpUrlOrNull() ?: return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            if (url.query != null || url.fragment != null) return null
            return url
        }

        /** Resolves server-relative paths; absolute media-origin URLs stay as they are. */
        fun resolve(value: String, server: HttpUrl): HttpUrl? {
            if (value.isEmpty()) return null
            if (value.startsWith("http://") || value.startsWith("https://")) return value.toHttpUrlOrNull()
            if (!value.startsWith("/") || value.startsWith("//")) return null
            return (server.toString().trimEnd('/') + value).toHttpUrlOrNull()
        }

        fun origin(url: HttpUrl): String = url.toString().trimEnd('/')

        const val SESSION_COOKIE = "mhub_session"

        /** The session token a `Set-Cookie` header carries: a value, "" when cleared, null when absent. */
        fun sessionFrom(setCookie: List<String>): String? = setCookie.firstNotNullOfOrNull { header ->
            val pair = header.substringBefore(';').trim()
            if (!pair.startsWith("$SESSION_COOKIE=")) return@firstNotNullOfOrNull null
            val value = pair.removePrefix("$SESSION_COOKIE=")
            val expired = header.split(';').any { it.trim().equals("Max-Age=0", ignoreCase = true) }
            if (expired) "" else value
        }
    }

    suspend fun health() { request("/health", timeoutSeconds = 8) }

    suspend fun platforms(): List<PlatformInfo> = get("/api/v1/platforms")

    suspend fun search(platform: String, query: String, limit: Int = 20): List<Track> =
        get<List<SearchItem>>("/api/v1/search", mapOf("platform" to platform, "q" to query, "limit" to "$limit")).map { it.track }

    suspend fun home(platform: String = "applemusic", limit: Int = 40, refresh: Boolean = false, exclude: List<String> = emptyList()): RecommendationHome {
        val query = linkedMapOf("platform" to platform, "limit" to "$limit")
        if (refresh) query["refresh"] = "true"
        val numeric = exclude.filter { it.isNotEmpty() && it.all(Char::isDigit) }.takeLast(300)
        if (numeric.isNotEmpty()) query["exclude"] = numeric.joinToString(",")
        return get("/api/v1/recommendations/home", query)
    }

    suspend fun continuation(platform: String, seedId: String, seedIds: List<String>, station: String?, exclude: List<String>, limit: Int = 10): Continuation =
        post("/api/v1/recommendations/continue", buildJsonObject {
            put("platform", platform)
            put("seed_id", seedId)
            putJsonArray("seed_ids") { seedIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            if (!station.isNullOrEmpty()) put("station", station)
            putJsonArray("exclude") { exclude.toSet().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            put("limit", limit)
        })

    suspend fun collection(platform: String, url: String, rateLimit: RateLimitPolicy = RateLimitPolicy.Standard): Collection =
        get("/api/v1/collections/resolve", mapOf("platform" to platform, "url" to url), timeoutSeconds = 60, rateLimit = rateLimit)

    suspend fun lyrics(track: Track): LyricsPayload {
        val query = linkedMapOf("platform" to track.platform, "track_id" to track.sourceId)
        if (track.title.isNotEmpty()) query["title"] = track.title
        if (track.artist.isNotEmpty()) query["artist"] = track.artist
        if (track.album.isNotEmpty()) query["album"] = track.album
        track.durationMs?.takeIf { it > 0 }?.let { query["duration"] = "${it / 1000}" }
        return get("/api/v1/lyrics", query)
    }

    suspend fun createPlayback(track: Track, quality: Quality, rateLimit: RateLimitPolicy = RateLimitPolicy.Standard): PlaybackTask =
        post<CreatePlaybackResponse>("/api/v1/playbacks", buildJsonObject {
            put("platform", track.platform)
            put("query", track.title)
            put("selected_id", track.sourceId)
            put("quality", quality.wire)
            put("include_cover", false)
        }, rateLimit).playback

    /** Long-poll: returns as soon as the task leaves [since] (at most ~15 s). */
    suspend fun playback(id: String, since: String? = null, waitMs: Long = 15_000): PlaybackTask {
        val query = if (since != null) mapOf("since" to since, "wait_ms" to "$waitMs") else emptyMap()
        return get("/api/v1/playbacks/${encodePath(id)}", query, timeoutSeconds = waitMs / 1000 + 10)
    }

    data class Prepared(val task: PlaybackTask, val url: String)

    /** Creates the task and long-polls until the system player can open it; [rateLimit] covers creating the task. */
    suspend fun preparePlayback(
        track: Track,
        quality: Quality,
        rateLimit: RateLimitPolicy = RateLimitPolicy.Standard,
        deadline: Duration = 180.seconds,
    ): Prepared {
        var task = createPlayback(track, quality, rateLimit)
        val start = TimeSource.Monotonic.markNow()
        while (true) {
            when (val readiness = PlaybackReadiness.evaluate(task, server)) {
                is PlaybackReadiness.Ready -> return Prepared(task, readiness.url)
                is PlaybackReadiness.Failed ->
                    throw if (readiness.unplayable) ApiException.Unplayable(readiness.message) else ApiException.PlaybackFailed(readiness.message)
                is PlaybackReadiness.Wait -> {
                    if (start.elapsedNow() > deadline) throw ApiException.TimedOut()
                    task = playback(task.id, since = readiness.since)
                }
            }
        }
    }

    // Account and library sync (docs/api-contract.md「账号与同步」)

    suspend fun authConfig(): AuthConfig = get("/api/v1/auth/config")

    suspend fun me(): AccountUser? = get<MeResponse>("/api/v1/auth/me").user

    suspend fun login(username: String, password: String, device: Device): LoginResponse =
        post("/api/v1/auth/password/login", device.json { put("username", username); put("password", password) })

    suspend fun recoveryLogin(username: String, code: String, device: Device): LoginResponse =
        post("/api/v1/auth/recovery/login", device.json { put("username", username); put("code", code) })

    suspend fun register(username: String, password: String, device: Device): LoginResponse =
        post("/api/v1/auth/register", device.json { put("username", username); put("password", password) })

    suspend fun logout() {
        request("/api/v1/auth/logout", body = "{}", timeoutSeconds = 15)
        session?.token = null
    }

    suspend fun librarySnapshot(): LibrarySnapshot = get("/api/v1/sync/library/json")

    suspend fun libraryOps(ops: List<LibraryOp>): LibrarySnapshot {
        val body = LibraryOpJson.encodeToString(LibraryOps.serializer(), LibraryOps(ops))
        val text = request("/api/v1/sync/library/json", body = body, timeoutSeconds = 30)
        return withContext(Dispatchers.Default) { MHubJson.decodeFromString(LibrarySnapshot.serializer(), text) }
    }

    /** This install as the server names it in the session list. */
    data class Device(val id: String, val name: String) {
        fun json(fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
            fields()
            put("device_id", id)
            put("device_name", name)
        }
    }

    // Download

    /** Creates the task at [quality] and waits until the whole file is ready (not just streamable). */
    suspend fun completedPlayback(track: Track, quality: Quality, rateLimit: RateLimitPolicy, deadline: Duration = 600.seconds): PlaybackTask {
        var task = createPlayback(track, quality, rateLimit)
        val start = TimeSource.Monotonic.markNow()
        while (task.status != "completed") {
            if (task.status == "failed") {
                throw if (task.errorCode == PlaybackReadiness.UNPLAYABLE) ApiException.Unplayable(task.error.orEmpty())
                else ApiException.PlaybackFailed(task.error.orEmpty())
            }
            if (start.elapsedNow() > deadline) throw ApiException.TimedOut()
            task = playback(task.id, since = task.status)
        }
        return task
    }

    /** The whole file of a completed task. */
    fun audioRequest(task: PlaybackTask): Request? {
        val url = resolve("/api/v1/playbacks/${encodePath(task.id)}/audio", server) ?: return null
        return Request.Builder().url(url).withSession().build()
    }

    val httpClient: OkHttpClient get() = http

    // Transport

    private suspend inline fun <reified T> get(
        path: String,
        query: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 20,
        rateLimit: RateLimitPolicy = RateLimitPolicy.Standard,
    ): T {
        val text = request(path, query, timeoutSeconds = timeoutSeconds, rateLimit = rateLimit)
        // Large lists (home, collections, lyrics) decode off the main thread.
        return withContext(Dispatchers.Default) { MHubJson.decodeFromString(serializer<T>(), text) }
    }

    private suspend inline fun <reified T> post(path: String, body: JsonObject, rateLimit: RateLimitPolicy = RateLimitPolicy.Standard): T {
        val text = request(path, body = body.toString(), timeoutSeconds = 30, rateLimit = rateLimit)
        return withContext(Dispatchers.Default) { MHubJson.decodeFromString(serializer<T>(), text) }
    }

    /** One request; HTTP 429 is waited out and resent within [rateLimit]. */
    private suspend fun request(
        path: String,
        query: Map<String, String> = emptyMap(),
        body: String? = null,
        timeoutSeconds: Long,
        rateLimit: RateLimitPolicy = RateLimitPolicy.Standard,
    ): String {
        val url = (origin(server) + path).toHttpUrlOrNull()?.newBuilder()?.apply {
            query.toSortedMap().forEach { (key, value) -> addQueryParameter(key, value) }
        }?.build() ?: throw ApiException.InvalidServer()
        val builder = Request.Builder().url(url).header("Accept", "application/json").withSession()
        if (body != null) builder.post(body.toRequestBody("application/json".toMediaType()))
        val client = http.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).readTimeout(timeoutSeconds, TimeUnit.SECONDS).build()
        val request = builder.build()
        return RateLimit.run(rateLimit, sleep) {
            // Reading the body blocks on the socket: never on the caller's (main) thread.
            withContext(Dispatchers.IO) { client.newCall(request).await().use {
                // The server slides the session forward or clears it on logout.
                sessionFrom(it.headers("Set-Cookie"))?.let { token -> session?.token = token.ifEmpty { null } }
                val text = it.body.string()
                if (!it.isSuccessful) {
                    val payload = runCatching { MHubJson.parseToJsonElement(text).jsonObject }.getOrNull()
                    val detail = payload?.get("detail")?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (payload?.get("code")?.jsonPrimitive?.contentOrNull == PlaybackReadiness.UNPLAYABLE) throw ApiException.Unplayable(detail)
                    if (it.code == 429) throw ApiException.RateLimited(RateLimit.retryAfterMs(it.header("Retry-After")), detail)
                    throw ApiException.Http(it.code, detail)
                }
                text
            } }
        }
    }

    /** Account requests carry the session cookie; writes also need the client header. */
    private fun Request.Builder.withSession(): Request.Builder {
        header("x-mhub-client", "1")
        session?.token?.takeIf { it.isNotEmpty() }?.let { header("Cookie", "$SESSION_COOKIE=$it") }
        return this
    }

    private fun encodePath(segment: String) = HttpUrl.Builder().scheme("https").host("x").addPathSegment(segment).build().encodedPath.removePrefix("/")
}

/** Suspends on an OkHttp call; cancelling the coroutine cancels the request. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
