package io.github.mhub.core

import kotlinx.coroutines.suspendCancellableCoroutine
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
    class TimedOut : ApiException("等待音频超时，请重试")
}

/** When a playback task can be handed to the system player. Shared with iOS; see docs/api-contract.md. */
sealed interface PlaybackReadiness {
    data class Ready(val url: String) : PlaybackReadiness
    data class Wait(val since: String) : PlaybackReadiness
    data class Failed(val message: String) : PlaybackReadiness

    companion object {
        fun evaluate(task: PlaybackTask, server: HttpUrl): PlaybackReadiness = when {
            task.status == "failed" -> Failed(task.error.orEmpty())
            task.status == "completed" || (task.status == "streaming" && task.streamLength != null) ->
                MHubClient.resolve(task.audioUrl, server)?.let { Ready(it.toString()) } ?: Failed("音频地址无效")
            else -> Wait(task.status)
        }
    }
}

/** HTTP client for one MHub server. */
class MHubClient(val server: HttpUrl, private val http: OkHttpClient = defaultHttp) {

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

    suspend fun collection(platform: String, url: String): Collection =
        get("/api/v1/collections/resolve", mapOf("platform" to platform, "url" to url), timeoutSeconds = 60)

    suspend fun lyrics(track: Track): LyricsPayload {
        val query = linkedMapOf("platform" to track.platform, "track_id" to track.sourceId)
        if (track.title.isNotEmpty()) query["title"] = track.title
        if (track.artist.isNotEmpty()) query["artist"] = track.artist
        if (track.album.isNotEmpty()) query["album"] = track.album
        track.durationMs?.takeIf { it > 0 }?.let { query["duration"] = "${it / 1000}" }
        return get("/api/v1/lyrics", query)
    }

    suspend fun createPlayback(track: Track, quality: Quality): PlaybackTask =
        post<CreatePlaybackResponse>("/api/v1/playbacks", buildJsonObject {
            put("platform", track.platform)
            put("query", track.title)
            put("selected_id", track.sourceId)
            put("quality", quality.wire)
            put("include_cover", false)
        }).playback

    /** Long-poll: returns as soon as the task leaves [since] (at most ~15 s). */
    suspend fun playback(id: String, since: String? = null, waitMs: Long = 15_000): PlaybackTask {
        val query = if (since != null) mapOf("since" to since, "wait_ms" to "$waitMs") else emptyMap()
        return get("/api/v1/playbacks/${encodePath(id)}", query, timeoutSeconds = waitMs / 1000 + 10)
    }

    data class Prepared(val task: PlaybackTask, val url: String)

    /** Creates the task and long-polls until the system player can open it. */
    suspend fun preparePlayback(track: Track, quality: Quality, deadline: Duration = 180.seconds): Prepared {
        val start = TimeSource.Monotonic.markNow()
        var task = createPlayback(track, quality)
        while (true) {
            when (val readiness = PlaybackReadiness.evaluate(task, server)) {
                is PlaybackReadiness.Ready -> return Prepared(task, readiness.url)
                is PlaybackReadiness.Failed -> throw ApiException.PlaybackFailed(readiness.message)
                is PlaybackReadiness.Wait -> {
                    if (start.elapsedNow() > deadline) throw ApiException.TimedOut()
                    task = playback(task.id, since = readiness.since)
                }
            }
        }
    }

    // Transport

    private suspend inline fun <reified T> get(path: String, query: Map<String, String> = emptyMap(), timeoutSeconds: Long = 20): T =
        MHubJson.decodeFromString(serializer<T>(), request(path, query, timeoutSeconds = timeoutSeconds))

    private suspend inline fun <reified T> post(path: String, body: JsonObject): T =
        MHubJson.decodeFromString(serializer<T>(), request(path, body = body.toString(), timeoutSeconds = 30))

    private suspend fun request(path: String, query: Map<String, String> = emptyMap(), body: String? = null, timeoutSeconds: Long): String {
        val url = (origin(server) + path).toHttpUrlOrNull()?.newBuilder()?.apply {
            query.toSortedMap().forEach { (key, value) -> addQueryParameter(key, value) }
        }?.build() ?: throw ApiException.InvalidServer()
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (body != null) builder.post(body.toRequestBody("application/json".toMediaType()))
        val client = http.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).readTimeout(timeoutSeconds, TimeUnit.SECONDS).build()
        val response = client.newCall(builder.build()).await()
        response.use {
            val text = it.body.string()
            if (!it.isSuccessful) {
                val detail = runCatching { MHubJson.parseToJsonElement(text).jsonObject["detail"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                throw ApiException.Http(it.code, detail.orEmpty())
            }
            return text
        }
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
