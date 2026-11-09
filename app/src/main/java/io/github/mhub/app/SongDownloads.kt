package io.github.mhub.app

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import io.github.mhub.core.ApiException
import io.github.mhub.core.RateLimit
import io.github.mhub.core.RateLimitPolicy
import io.github.mhub.core.Track
import io.github.mhub.core.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Saves songs to `Music/MHub/` through MediaStore, so music apps see them and
 * no storage permission is needed (docs/design.md「下载」).
 */
class SongDownloads(private val context: Context, private val settings: Settings, private val model: AppModel) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val active = mutableSetOf<String>()

    fun download(track: Track) {
        val song = track.chosen
        if (!active.add(song.id)) {
            model.show(context.getString(R.string.download_in_progress, song.title))
            return
        }
        model.show(context.getString(R.string.downloading, song.title))
        scope.launch {
            try {
                val client = model.client ?: throw ApiException.InvalidServer()
                val policy = RateLimitPolicy.playback { ms -> model.show(context.getString(R.string.server_busy_wait, RateLimit.seconds(ms))) }
                val task = client.completedPlayback(song, settings.quality.value, policy)
                val request = client.audioRequest(task) ?: throw ApiException.PlaybackFailed("")
                val http = client.httpClient.newBuilder().readTimeout(120, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
                withContext(Dispatchers.IO) {
                    http.newCall(request).await().use { response ->
                        if (!response.isSuccessful) throw ApiException.Http(response.code, "")
                        val type = response.header("Content-Type").orEmpty()
                        val extension = extension(response.header("Content-Disposition"), type)
                        save(fileName(song, extension), mime(extension, type)) { output -> response.body.byteStream().copyTo(output) }
                    }
                }
                model.show(context.getString(R.string.download_saved, song.title))
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                model.show(context.getString(R.string.download_failed, error.message ?: ""))
            } finally {
                active.remove(song.id)
            }
        }
    }

    private fun save(name: String, mime: String, write: (java.io.OutputStream) -> Unit) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/MHub")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("无法写入音乐目录")
        try {
            resolver.openOutputStream(uri)?.use(write) ?: throw IllegalStateException("无法写入音乐目录")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    companion object {
        /** `歌手 - 歌名.ext` without characters file systems reject. */
        fun fileName(track: Track, extension: String): String {
            val base = listOf(track.artist, track.title).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { track.sourceId }
            val clean = base.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(150)
            return "$clean.$extension"
        }

        /** From the server's file name, else the content type. */
        fun extension(disposition: String?, contentType: String): String {
            val name = disposition?.let { header ->
                Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    ?: Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
            }
            name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }?.let { return it }
            val type = contentType.substringBefore(';').trim().lowercase()
            return when {
                "flac" in type -> "flac"
                type == "audio/mpeg" || type == "audio/mp3" -> "mp3"
                "ogg" in type -> "ogg"
                "wav" in type -> "wav"
                else -> "m4a"
            }
        }

        fun mime(extension: String, contentType: String): String = when (extension) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            "m4a", "mp4", "aac" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> contentType.substringBefore(';').trim().ifEmpty { "audio/mpeg" }
        }
    }
}
