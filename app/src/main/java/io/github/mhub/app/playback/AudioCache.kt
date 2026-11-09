package io.github.mhub.app.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.github.mhub.core.MHubClient
import io.github.mhub.core.MHubJson
import io.github.mhub.core.Quality
import io.github.mhub.core.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * Fully played songs, keyed by `platform:id:quality`. ExoPlayer's SimpleCache
 * stores the bytes (one download serves playback and the cache); this index
 * remembers which keys are complete so they play offline. Audio cache,
 * playlists and playback state are independent of each other.
 */
@OptIn(UnstableApi::class)
class AudioCache(context: Context, limitBytes: Long) {
    @Serializable
    data class Entry(
        val key: String,
        val url: String,
        val track: Track,
        val quality: Quality,
        val size: Long,
        val lastUsed: Long,
    )

    val cache = SimpleCache(
        File(context.cacheDir, "audio"),
        LeastRecentlyUsedCacheEvictor(limitBytes.coerceAtLeast(256L shl 20)),
        StandaloneDatabaseProvider(context),
    )

    val dataSourceFactory: CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(MHubClient.defaultHttp))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private val indexFile = File(context.filesDir, "audio-index.json")
    private val serializer = MapSerializer(String.serializer(), Entry.serializer())
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<Map<String, Entry>> = _entries

    private fun load(): Map<String, Entry> = runCatching { MHubJson.decodeFromString(serializer, indexFile.readText()) }.getOrDefault(emptyMap())

    private fun save(map: Map<String, Entry>) {
        _entries.value = map
        runCatching { indexFile.writeText(MHubJson.encodeToString(serializer, map)) }
    }

    /** The complete cached copy of a song, if the evictor has not removed it. */
    fun complete(key: String): Entry? {
        val entry = _entries.value[key] ?: return null
        if (!cache.isCached(key, 0, entry.size)) {
            save(_entries.value - key)
            return null
        }
        save(_entries.value + (key to entry.copy(lastUsed = System.currentTimeMillis())))
        return entry
    }

    /** Called when ExoPlayer has buffered the whole song through the cache. */
    fun markComplete(key: String, url: String, track: Track, quality: Quality) {
        val size = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (size <= 0 || !cache.isCached(key, 0, size)) return
        save(_entries.value + (key to Entry(key, url, track.chosen, quality, size, System.currentTimeMillis())))
    }

    fun list(): List<Entry> = _entries.value.values.filter { cache.isCached(it.key, 0, it.size) }.sortedByDescending { it.lastUsed }

    fun totalSize(): Long = cache.cacheSpace

    fun remove(key: String) {
        cache.removeResource(key)
        save(_entries.value - key)
    }

    fun clear() {
        cache.keys.toList().forEach { cache.removeResource(it) }
        save(emptyMap())
    }
}
