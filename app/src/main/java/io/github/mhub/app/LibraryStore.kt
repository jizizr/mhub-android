package io.github.mhub.app

import android.content.Context
import io.github.mhub.core.LibraryOp
import io.github.mhub.core.LibrarySync
import io.github.mhub.core.MHubJson
import io.github.mhub.core.Playlist
import io.github.mhub.core.SyncTrack
import io.github.mhub.core.Track
import io.github.mhub.core.uniqued
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/**
 * Local playlists, persisted as `library.json` (same schema as iOS). While an
 * account is linked every edit is also handed to [recorder] as library
 * operations for upload (docs/design.md「账号与歌单同步」).
 */
class LibraryStore(context: Context) {
    var recorder: ((List<LibraryOp>) -> Unit)? = null

    @Serializable
    private data class Document(val version: Int = 1, val playlists: List<Playlist> = emptyList())

    private val file = File(context.filesDir, "library.json")
    private val _playlists = MutableStateFlow(load())
    val playlists: StateFlow<List<Playlist>> = _playlists

    private fun load(): List<Playlist> = runCatching {
        MHubJson.decodeFromString(Document.serializer(), file.readText()).playlists
    }.getOrDefault(emptyList())

    private fun save(list: List<Playlist>) {
        _playlists.value = list
        val temp = File(file.parentFile, "library.json.tmp")
        temp.writeText(MHubJson.encodeToString(Document.serializer(), Document(playlists = list)))
        temp.renameTo(file)
    }

    fun playlist(id: String) = _playlists.value.firstOrNull { it.id == id }

    /** Replace everything, e.g. with the account's library. */
    fun replaceAll(list: List<Playlist>) {
        if (list != _playlists.value) save(list)
    }

    private fun record(vararg ops: LibraryOp) {
        if (ops.isNotEmpty()) recorder?.invoke(ops.toList())
    }

    fun create(name: String, untitled: String, tracks: List<Track> = emptyList(), sourceUrl: String = "", sourcePlatform: String = ""): Playlist {
        val now = System.currentTimeMillis()
        val playlist = Playlist(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { untitled },
            tracks = tracks.map { it.chosen }.uniqued(),
            sourceUrl = sourceUrl,
            sourcePlatform = sourcePlatform,
            createdAt = now,
            updatedAt = now,
        )
        save(listOf(playlist) + _playlists.value)
        record(LibraryOp.Create(playlist.id, playlist.name, sourceUrl, sourcePlatform, playlist.tracks.map(SyncTrack::of)))
        return playlist
    }

    fun rename(id: String, name: String) {
        if (name.isBlank()) return
        update(id) { it.copy(name = LibrarySync.cleanName(name)) }
        record(LibraryOp.Rename(id, name))
    }

    fun delete(id: String) {
        save(_playlists.value.filter { it.id != id })
        record(LibraryOp.Delete(id))
    }

    /** Returns how many songs were new to the playlist. */
    fun add(tracks: List<Track>, id: String): Int {
        var new = emptyList<Track>()
        update(id) { playlist ->
            val known = playlist.tracks.map { it.id }.toSet()
            new = tracks.map { it.chosen }.uniqued().filter { it.id !in known }
            playlist.copy(tracks = playlist.tracks + new)
        }
        if (new.isNotEmpty()) record(LibraryOp.Append(id, new.map(SyncTrack::of)))
        return new.size
    }

    fun remove(trackIds: Set<String>, id: String) {
        update(id) { it.copy(tracks = it.tracks.filter { track -> track.id !in trackIds }) }
        record(*trackIds.map { LibraryOp.Remove(id, it) }.toTypedArray())
    }

    fun reorder(id: String, tracks: List<Track>) {
        val before = playlist(id)?.tracks?.map { it.id } ?: return
        update(id) { it.copy(tracks = tracks) }
        record(*LibrarySync.reorderMoves(before, tracks.map { it.id }).map { LibraryOp.Move(id, it.first, it.second) }.toTypedArray())
    }

    private fun update(id: String, change: (Playlist) -> Playlist) {
        save(_playlists.value.map { if (it.id == id) change(it).copy(updatedAt = System.currentTimeMillis()) else it })
    }
}
