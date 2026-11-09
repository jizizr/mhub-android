package io.github.mhub.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Share links and import links, matching the web `utils/share.js` and iOS `Links`. */
object Links {
    private val shareable = setOf("applemusic", "netease", "tencent", "soda")
    private val shareId = Regex("^[A-Za-z0-9._-]{1,64}$")

    fun canShare(track: Track) = track.platform in shareable && shareId.matches(track.sourceId)

    /** `<server>/?song=<platform>:<id>`; the web app opens it in its player. */
    fun shareUrl(track: Track, server: HttpUrl): String? {
        if (!canShare(track)) return null
        return server.newBuilder().encodedPath("/").query(null)
            // Platform and id are validated to URL-safe characters, so ":" stays literal like the web link.
            .addEncodedQueryParameter("song", "${track.platform}:${track.sourceId}").build().toString()
    }

    /** The platform a pasted playlist/album link belongs to. */
    fun collectionPlatform(text: String): String? {
        val host = text.trim().toHttpUrlOrNull()?.host?.lowercase() ?: return null
        return when {
            host.endsWith("music.apple.com") -> "applemusic"
            host.endsWith("music.163.com") || host.endsWith("163cn.tv") -> "netease"
            else -> null
        }
    }
}

/** Fills metadata the playback task knows better (cover, duration, album). */
fun Track.enriched(task: PlaybackTask, server: HttpUrl?): Track {
    var copy = this
    if (copy.cover.isEmpty() && server != null) {
        task.coverUrl?.let { MHubClient.resolve(it, server) }?.let { copy = copy.copy(cover = it.toString()) }
    }
    task.duration?.takeIf { it > 0 }?.let { copy = copy.copy(durationMs = it * 1000) }
    if (copy.album.isEmpty() && task.album != null) copy = copy.copy(album = task.album)
    if (copy.title.isEmpty() && task.title != null) copy = copy.copy(title = task.title)
    if (copy.artist.isEmpty() && task.artist != null) copy = copy.copy(artist = task.artist)
    return copy
}
