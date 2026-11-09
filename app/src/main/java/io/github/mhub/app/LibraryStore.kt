package io.github.mhub.app

import android.content.Context
import io.github.mhub.core.MHubJson
import io.github.mhub.core.Playlist
import io.github.mhub.core.Track
import io.github.mhub.core.uniqued
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/** Local playlists, persisted as `library.json` (same schema as iOS). */
class LibraryStore(context: Context) {
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
        return playlist
    }

    fun rename(id: String, name: String) = update(id) { if (name.isBlank()) it else it.copy(name = name.trim()) }

    fun delete(id: String) = save(_playlists.value.filter { it.id != id })

    /** Returns how many songs were new to the playlist. */
    fun add(tracks: List<Track>, id: String): Int {
        var added = 0
        update(id) { playlist ->
            val known = playlist.tracks.map { it.id }.toSet()
            val new = tracks.map { it.chosen }.uniqued().filter { it.id !in known }
            added = new.size
            playlist.copy(tracks = playlist.tracks + new)
        }
        return added
    }

    fun remove(trackIds: Set<String>, id: String) = update(id) { it.copy(tracks = it.tracks.filter { track -> track.id !in trackIds }) }

    fun reorder(id: String, tracks: List<Track>) = update(id) { it.copy(tracks = tracks) }

    private fun update(id: String, change: (Playlist) -> Playlist) {
        save(_playlists.value.map { if (it.id == id) change(it).copy(updatedAt = System.currentTimeMillis()) else it })
    }
}
