package io.github.mhub.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.Normalizer
import java.util.regex.Pattern

// Account and library sync. See docs/design.md「账号与歌单同步」and
// docs/api-contract.md「账号与同步」; cases in contract/library-ops-cases.json.

/** Where the session token lives (secure storage on each platform). */
interface SessionStore {
    var token: String?
}

@Serializable
data class AuthConfig(
    val enabled: Boolean = false,
    @SerialName("turnstile_site_key") val turnstileSiteKey: String = "",
) {
    /** Registration needs a human check the app cannot show: send people to the web page. */
    val registersOnWeb: Boolean get() = turnstileSiteKey.isNotEmpty()
}

@Serializable
data class AccountUser(
    val id: Long,
    val username: String,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("has_password") val hasPassword: Boolean? = null,
)

@Serializable
data class MeResponse(val user: AccountUser? = null)

@Serializable
data class LoginResponse(
    val user: AccountUser,
    @SerialName("recovery_codes") val recoveryCodes: List<String> = emptyList(),
)

/** A song as the account library stores it (no quality, no album). */
@Serializable
data class SyncTrack(
    val platform: String,
    @SerialName("source_id") val sourceId: String,
    val title: String = "",
    val artist: String = "",
    val cover: String = "",
) {
    val track: Track get() = Track(platform = platform, sourceId = sourceId, title = title, artist = artist, cover = cover)

    companion object {
        fun of(track: Track) = SyncTrack(track.platform, track.sourceId, track.title, track.artist, track.cover)
    }
}

@Serializable
data class SnapshotPlaylist(
    val id: String,
    val name: String,
    @SerialName("source_url") val sourceUrl: String = "",
    @SerialName("source_platform") val sourcePlatform: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
    val tracks: List<SyncTrack> = emptyList(),
) {
    val playlist: Playlist get() = Playlist(id, name, tracks.map { it.track }, sourceUrl, sourcePlatform, createdAt, updatedAt)
}

@Serializable
data class LibrarySnapshot(val playlists: List<SnapshotPlaylist> = emptyList()) {
    val library: List<Playlist> get() = playlists.map { it.playlist }
}

/** One library edit, the same operations the server applies (`POST /api/v1/sync/library/json`). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
sealed class LibraryOp {
    abstract val id: String

    @Serializable @SerialName("create")
    data class Create(
        override val id: String,
        val name: String,
        @SerialName("source_url") val sourceUrl: String = "",
        @SerialName("source_platform") val sourcePlatform: String = "",
        val tracks: List<SyncTrack> = emptyList(),
    ) : LibraryOp()

    @Serializable @SerialName("append")
    data class Append(override val id: String, val tracks: List<SyncTrack>) : LibraryOp()

    @Serializable @SerialName("rename")
    data class Rename(override val id: String, val name: String) : LibraryOp()

    @Serializable @SerialName("delete")
    data class Delete(override val id: String) : LibraryOp()

    @Serializable @SerialName("remove")
    data class Remove(override val id: String, val key: String) : LibraryOp()

    @Serializable @SerialName("move")
    data class Move(override val id: String, val key: String, @EncodeDefault val after: String? = null) : LibraryOp()
}

@Serializable
data class LibraryOps(val ops: List<LibraryOp>)

/** JSON for library operations: `{"op":"create",…}`. */
val LibraryOpJson = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "op"
    explicitNulls = true
}

object LibrarySync {
    const val UNTITLED = "未命名歌单"
    /** Same playlist when names match and this share of local songs is already in the cloud. */
    const val MERGE_OVERLAP = 0.6

    fun cleanName(name: String): String = name.trim().take(200).ifEmpty { UNTITLED }

    /** Replays [ops] on a local library exactly as the server does (newest playlist first). */
    fun apply(library: List<Playlist>, ops: List<LibraryOp>, now: Long): List<Playlist> =
        ops.fold(library) { list, op -> apply(list, op, now) }

    private fun apply(list: List<Playlist>, op: LibraryOp, now: Long): List<Playlist> {
        fun edit(change: (Playlist) -> Playlist?): List<Playlist> {
            val index = list.indexOfFirst { it.id == op.id }
            if (index < 0) return list
            val changed = change(list[index]) ?: return list
            return list.toMutableList().also { it[index] = changed }
        }
        return when (op) {
            is LibraryOp.Create -> listOf(
                Playlist(op.id, cleanName(op.name), op.tracks.map { it.track }.uniqued(), op.sourceUrl, op.sourcePlatform, now, now),
            ) + list.filter { it.id != op.id }
            is LibraryOp.Append -> edit { playlist ->
                val present = playlist.tracks.mapTo(HashSet()) { it.id }
                val added = op.tracks.map { it.track }.filter { present.add(it.id) }
                if (added.isEmpty()) null else playlist.copy(tracks = playlist.tracks + added, updatedAt = now)
            }
            is LibraryOp.Rename -> edit { it.copy(name = cleanName(op.name), updatedAt = now) }
            is LibraryOp.Delete -> list.filter { it.id != op.id }
            is LibraryOp.Remove -> edit { playlist -> playlist.copy(tracks = playlist.tracks.filter { it.id != op.key }, updatedAt = now) }
            is LibraryOp.Move -> edit { playlist ->
                val moving = playlist.tracks.firstOrNull { it.id == op.key }
                if (moving == null || op.after == op.key) return@edit null
                val rest = playlist.tracks.filter { it.id != op.key }.toMutableList()
                val position = if (op.after == null) 0 else rest.indexOfFirst { it.id == op.after }.let { if (it < 0) rest.size else it + 1 }
                rest.add(position, moving)
                playlist.copy(tracks = rest, updatedAt = now)
            }
        }
    }

    /**
     * The fewest moves that turn [from] into [to]: songs on a longest increasing
     * run stay, every other song moves after its new neighbour, in the new order.
     */
    fun reorderMoves(from: List<String>, to: List<String>): List<Pair<String, String?>> {
        val position = from.withIndex().associate { it.value to it.index }
        val sequence = to.map { position[it] ?: -1 }
        val keep = longestIncreasing(sequence).map { to[it] }.toSet()
        return to.withIndex().filter { it.value !in keep }.map { (index, key) -> key to to.getOrNull(index - 1) }
    }

    /** Indices of one longest strictly increasing subsequence (patience sorting). */
    private fun longestIncreasing(values: List<Int>): List<Int> {
        val tails = mutableListOf<Int>()
        val previous = IntArray(values.size) { -1 }
        for (index in values.indices) {
            if (values[index] < 0) continue
            var low = 0
            var high = tails.size
            while (low < high) {
                val middle = (low + high) / 2
                if (values[tails[middle]] < values[index]) low = middle + 1 else high = middle
            }
            if (low > 0) previous[index] = tails[low - 1]
            if (low == tails.size) tails.add(index) else tails[low] = index
        }
        val result = ArrayDeque<Int>()
        var index = tails.lastOrNull() ?: return emptyList()
        while (index >= 0) {
            result.addFirst(index)
            index = previous[index]
        }
        return result.toList()
    }

    // \p{Z} covers Unicode spaces on both the JVM and Android's ICU regex
    // (which rejects UNICODE_CHARACTER_CLASS).
    private val ignored = Pattern.compile("[\\s\\p{Z}\\p{P}\\p{S}]+")

    /** The web client's `normalizeName`: NFKC, lower case, without spaces, punctuation or symbols. */
    fun normalizeName(name: String): String =
        ignored.matcher(Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase()).replaceAll("")

    data class MergePlan(val create: List<Playlist>, val merge: List<Merge>) {
        data class Merge(val local: Playlist, val cloudId: String, val tracks: List<Track>)
    }

    /** How local playlists join the account library on first login (web `planGuestMerge`). */
    fun planMerge(local: List<Playlist>, cloud: List<Playlist>): MergePlan {
        val create = mutableListOf<Playlist>()
        val merge = mutableListOf<MergePlan.Merge>()
        val byName = cloud.groupBy { normalizeName(it.name) }
        for (playlist in local) {
            val keys = playlist.tracks.map { it.id }.distinct()
            fun overlap(candidate: Playlist): Double {
                if (keys.isEmpty()) return 1.0
                val cloudKeys = candidate.tracks.mapTo(HashSet()) { it.id }
                return keys.count { it in cloudKeys }.toDouble() / keys.size
            }
            val match = (byName[normalizeName(playlist.name)] ?: emptyList())
                .map { it to overlap(it) }
                .filter { it.second >= MERGE_OVERLAP }
                .maxByOrNull { it.second }?.first
                ?: playlist.sourceUrl.takeIf { it.isNotEmpty() }?.let { url -> cloud.firstOrNull { it.sourceUrl == url } }
            if (match == null) {
                create += playlist
                continue
            }
            val present = match.tracks.mapTo(HashSet()) { it.id }
            val missing = playlist.tracks.filter { present.add(it.id) }
            if (missing.isNotEmpty()) merge += MergePlan.Merge(playlist, match.id, missing)
        }
        return MergePlan(create, merge)
    }

    /** Operations that carry out a merge plan. */
    fun mergeOps(plan: MergePlan): List<LibraryOp> =
        plan.create.reversed().map {
            LibraryOp.Create(it.id, it.name, it.sourceUrl, it.sourcePlatform, it.tracks.map(SyncTrack::of))
        } + plan.merge.map { LibraryOp.Append(it.cloudId, it.tracks.map(SyncTrack::of)) }
}
