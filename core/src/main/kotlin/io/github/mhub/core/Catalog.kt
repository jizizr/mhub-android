package io.github.mhub.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** Apple Music's search tabs, in its order. */
enum class CatalogSection(val wire: String, val label: String) {
    Top("top", "最佳结果"),
    Artists("artists", "艺人"),
    Albums("albums", "专辑"),
    Songs("songs", "歌曲"),
    Playlists("playlists", "播放列表");

    companion object {
        fun from(wire: String): CatalogSection? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * One catalog entry of any kind: a song, album, artist or playlist. Every
 * search section, album track list and artist shelf uses this shape.
 */
@Serializable
data class CatalogItem(
    val type: String,
    val platform: String,
    @SerialName("id") val itemId: String,
    /** Song name, album title, artist name or playlist name. */
    val title: String = "",
    /** Song: its artists. Album: its artist. Playlist: its curator. */
    val subtitle: String = "",
    val cover: String = "",
    /** Songs: the album name. */
    val album: String = "",
    /** Albums: `YYYY-MM-DD` or `YYYY`. */
    @SerialName("release_date") val releaseDate: String = "",
    /** Albums and playlists: the link `collections/resolve` accepts. */
    val url: String = "",
    @SerialName("track_number") val trackNumber: Int? = null,
    @SerialName("disc_number") val discNumber: Int? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
) {
    val id: String get() = "$type:$platform:$itemId"
    val year: String get() = releaseDate.take(4)

    /** A song as a playable track. */
    val track: Track get() = Track(platform, itemId, title, subtitle, album = album, cover = cover, durationMs = durationMs)

    /** The line under a card: its kind in best results, else an album's year, then who made it. */
    fun cardSubtitle(withKind: Boolean): String {
        val parts = mutableListOf<String>()
        if (withKind) parts += kindLabel(type) else if (type == ALBUM && year.isNotEmpty()) parts += year
        if (subtitle.isNotEmpty()) parts += subtitle
        return parts.filter { it.isNotEmpty() }.joinToString(" · ")
    }

    companion object {
        const val SONG = "song"
        const val ALBUM = "album"
        const val ARTIST = "artist"
        const val PLAYLIST = "playlist"

        fun kindLabel(type: String) = when (type) {
            SONG -> "歌曲"
            ALBUM -> "专辑"
            ARTIST -> "艺人"
            PLAYLIST -> "播放列表"
            else -> ""
        }
    }
}

/** `GET /api/v1/catalog/search`. */
@Serializable
data class CatalogSearch(
    val platform: String,
    val sections: List<String> = listOf("songs"),
    val top: List<CatalogItem> = emptyList(),
    val artists: List<CatalogItem> = emptyList(),
    val albums: List<CatalogItem> = emptyList(),
    val songs: List<CatalogItem> = emptyList(),
    val playlists: List<CatalogItem> = emptyList(),
) {
    fun items(section: CatalogSection): List<CatalogItem> = when (section) {
        CatalogSection.Top -> top
        CatalogSection.Artists -> artists
        CatalogSection.Albums -> albums
        CatalogSection.Songs -> songs
        CatalogSection.Playlists -> playlists
    }

    /** Tabs to show: the platform's sections that found something; none for a songs-only platform. */
    val visibleSections: List<CatalogSection>
        get() = if (sections.size <= 1) emptyList()
        else sections.mapNotNull(CatalogSection::from).filter { items(it).isNotEmpty() }
}

/** One disc of an album: its tracks with their album-wide positions. */
data class AlbumDisc(val disc: Int, val tracks: List<IndexedValue<CatalogItem>>)

/** `GET /api/v1/albums/:platform/:id`. */
@Serializable
data class AlbumDetail(
    val platform: String,
    val id: String,
    val title: String = "",
    val artist: String = "",
    /** Empty when the platform has no artist page. */
    @SerialName("artist_id") val artistId: String = "",
    val cover: String = "",
    @SerialName("release_date") val releaseDate: String = "",
    val genre: String = "",
    val copyright: String = "",
    /** The link `collections/resolve` accepts; a saved playlist's source. */
    val url: String = "",
    val tracks: List<CatalogItem> = emptyList(),
) {
    /** Discs in order; one unlabeled group (disc 0) for a single-disc album. */
    val discs: List<AlbumDisc>
        get() {
            val groups = tracks.withIndex().groupBy { it.value.discNumber ?: 1 }.map { (disc, items) -> AlbumDisc(disc, items) }
            return if (groups.size == 1) listOf(groups[0].copy(disc = 0)) else groups
        }

    /** The footer: release date, length and copyright, as Apple Music shows them. */
    val footer: List<String> get() = listOf(releaseText(releaseDate), summary(tracks), copyright).filter { it.isNotEmpty() }

    companion object {
        /** "2001-09-14" → "2001年9月14日"; a bare year stays a year. */
        fun releaseText(value: String): String {
            val parts = value.take(10).split("-").mapNotNull { it.toIntOrNull() }
            return when (parts.size) {
                3 -> "${parts[0]}年${parts[1]}月${parts[2]}日"
                2 -> "${parts[0]}年${parts[1]}月"
                1 -> "${parts[0]}年"
                else -> ""
            }
        }

        /** "10 首歌曲，41 分钟". */
        fun summary(tracks: List<CatalogItem>): String {
            if (tracks.isEmpty()) return ""
            val minutes = (tracks.sumOf { it.durationMs ?: 0L } / 60_000.0).roundToInt()
            return when {
                minutes == 0 -> "${tracks.size} 首歌曲"
                minutes < 60 -> "${tracks.size} 首歌曲，$minutes 分钟"
                else -> "${tracks.size} 首歌曲，${minutes / 60} 小时" + (if (minutes % 60 > 0) " ${minutes % 60} 分钟" else "")
            }
        }
    }
}

/** `GET /api/v1/artists/:platform/:id`. */
@Serializable
data class ArtistDetail(
    val platform: String,
    val id: String,
    val name: String = "",
    val cover: String = "",
    @SerialName("top_songs") val topSongs: List<CatalogItem> = emptyList(),
    val albums: List<CatalogItem> = emptyList(),
    val singles: List<CatalogItem> = emptyList(),
)

/** `GET /api/v1/tracks/:platform/:id`: a song and the album and artist pages it links to. */
@Serializable
data class TrackInfo(
    val platform: String,
    val id: String,
    val song: String = "",
    val singer: String = "",
    val album: String = "",
    val cover: String = "",
    @SerialName("album_id") val albumId: String = "",
    @SerialName("artist_id") val artistId: String = "",
)
