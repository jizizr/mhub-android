package io.github.mhub.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.listSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Shared JSON settings: unknown fields are ignored so new server fields never break old apps. */
val MHubJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = false
}

/** Listening tier; serial names are the server's `quality` values. */
@Serializable
enum class Quality(val label: String) {
    @SerialName("standard") Standard("标准"),
    @SerialName("high") High("高品质"),
    @SerialName("lossless") Lossless("无损"),
    @SerialName("hires") HiRes("Hi-Res");

    val wire: String get() = when (this) {
        Standard -> "standard"
        High -> "high"
        Lossless -> "lossless"
        HiRes -> "hires"
    }

    companion object {
        fun from(value: String?): Quality? = entries.firstOrNull { it.wire == value }
    }
}

@Serializable
data class PlatformInfo(val id: String, val label: String) {
    companion object {
        val fallback = listOf(
            PlatformInfo("applemusic", "Apple Music"),
            PlatformInfo("netease", "网易云音乐"),
            PlatformInfo("tencent", "QQ音乐"),
        )
        val collectionPlatforms = setOf("applemusic", "netease")
        val autoplayPlatforms = setOf("applemusic")
        fun label(id: String) = fallback.firstOrNull { it.id == id }?.label ?: id
    }
}

/**
 * A song in a queue, playlist or list. Quality is chosen when playback starts,
 * so it is not part of a track. The JSON shape is shared with iOS.
 */
@Serializable
data class Track(
    val platform: String,
    @SerialName("source_id") val sourceId: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val cover: String = "",
    @SerialName("duration_ms") val durationMs: Long? = null,
    /** Added by AutoPlay rather than chosen by the user. */
    @SerialName("autoplay") val isRecommendation: Boolean = false,
) {
    /** `platform:source_id`, the same identity as the web `trackIdentity`. */
    val id: String get() = "$platform:$sourceId"

    /** The same song as a user choice (AutoPlay flag cleared). */
    val chosen: Track get() = if (isRecommendation) copy(isRecommendation = false) else this

    /** Key of the fully cached audio file for this song at a quality. */
    fun cacheKey(quality: Quality) = "$platform:$sourceId:${quality.wire}"
}

/** Drops later duplicates of the same song. */
fun List<Track>.uniqued(): List<Track> {
    val seen = HashSet<String>()
    return filter { seen.add(it.id) }
}

@Serializable
data class SearchItem(
    val platform: String,
    val id: String,
    val song: String = "",
    val singer: String = "",
    val cover: String? = null,
) {
    val track: Track get() = Track(platform, id, song, singer, cover = cover.orEmpty())
}

@Serializable
data class RecommendationTag(
    val id: String,
    val label: String,
    val subtitle: String = "",
    @SerialName("playlist_ids") val playlistIds: List<String> = emptyList(),
)

@Serializable
data class RecommendationPlaylist(
    val id: String,
    @SerialName("resource_type") val resourceType: String = "",
    val name: String,
    val curator: String = "",
    val description: String = "",
    val cover: String = "",
    val url: String = "",
)

@Serializable
data class RecommendationHome(
    val source: String = "",
    val tags: List<RecommendationTag> = emptyList(),
    val playlists: List<RecommendationPlaylist> = emptyList(),
    val tracks: List<SearchItem> = emptyList(),
)

@Serializable
data class Continuation(
    val source: String = "",
    val tracks: List<SearchItem> = emptyList(),
    val station: String? = null,
)

@Serializable
data class Collection(
    val platform: String,
    val id: String,
    val title: String,
    @SerialName("source_url") val sourceUrl: String = "",
    val items: List<SearchItem> = emptyList(),
)

/** `GET /api/v1/playbacks/:id`, and the `playback` member of the create response. */
@Serializable
data class PlaybackTask(
    val id: String,
    val status: String,
    @SerialName("media_revision") val mediaRevision: String? = null,
    /** `unplayable`: no source can ever play this song; players skip it. */
    @SerialName("error_code") val errorCode: String? = null,
    val platform: String? = null,
    val quality: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** Seconds. */
    val duration: Long? = null,
    val format: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    val bitrate: Long? = null,
    @SerialName("byte_size") val byteSize: Long? = null,
    val error: String? = null,
    @SerialName("audio_url") val audioUrl: String = "",
    /** Present only while a progressive stream can serve Range requests. */
    @SerialName("stream_length") val streamLength: Long? = null,
    /** Server-side artwork, used when the list row had none. */
    @SerialName("cover_url") val coverUrl: String? = null,
)

@Serializable
internal data class CreatePlaybackResponse(val playback: PlaybackTask)

/** `[start, end, text]` word timing in milliseconds. */
@Serializable(with = WordTimingSerializer::class)
data class WordTiming(val start: Long, val end: Long, val text: String)

internal object WordTimingSerializer : KSerializer<WordTiming> {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = listSerialDescriptor(JsonPrimitive.serializer().descriptor)

    override fun deserialize(decoder: Decoder): WordTiming {
        val array = (decoder as JsonDecoder).decodeJsonElement() as JsonArray
        return WordTiming(array[0].jsonPrimitive.long, array[1].jsonPrimitive.long, array[2].jsonPrimitive.content)
    }

    override fun serialize(encoder: Encoder, value: WordTiming) {
        (encoder as JsonEncoder).encodeJsonElement(
            JsonArray(listOf(JsonPrimitive(value.start), JsonPrimitive(value.end), JsonPrimitive(value.text))),
        )
    }
}

@Serializable
data class WordLine(
    val start: Long,
    val end: Long,
    val bg: Boolean = false,
    val words: List<WordTiming> = emptyList(),
)

/** `GET /api/v1/lyrics`. */
@Serializable
data class LyricsPayload(
    val available: Boolean = false,
    val plain: String = "",
    val translation: String = "",
    val words: List<WordLine> = emptyList(),
    val roman: String = "",
    val credits: List<String> = emptyList(),
)

/** A local playlist. The JSON shape is shared with the iOS app. */
/**
 * Where the queue came from, shown above it like the web `sourcePlaylist`:
 * `playlist` (local, [id]), `collection` (an album or platform playlist,
 * reopened from [platform] and [url]) or `recommendation`.
 */
@Serializable
data class QueueSource(
    val type: String,
    val name: String,
    val id: String = "",
    val platform: String = "",
    val url: String = "",
    val cover: String = "",
) {
    val isRecommendation: Boolean get() = type == RECOMMENDATION

    companion object {
        const val PLAYLIST = "playlist"
        const val COLLECTION = "collection"
        const val RECOMMENDATION = "recommendation"
    }
}

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val tracks: List<Track> = emptyList(),
    @SerialName("source_url") val sourceUrl: String = "",
    @SerialName("source_platform") val sourcePlatform: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
) {
    val cover: String get() = tracks.firstOrNull { it.cover.isNotEmpty() }?.cover.orEmpty()
}
