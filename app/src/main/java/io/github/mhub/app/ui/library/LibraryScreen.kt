package io.github.mhub.app.ui.library

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mhub.app.R
import io.github.mhub.app.playback.AudioCache
import io.github.mhub.app.ui.LocalBottomChrome
import io.github.mhub.app.ui.LocalGraph
import io.github.mhub.app.ui.LocalNavigator
import io.github.mhub.app.ui.Route
import io.github.mhub.app.ui.components.Artwork
import io.github.mhub.app.ui.components.CollectionHeader
import io.github.mhub.app.ui.components.EmptyState
import io.github.mhub.app.ui.components.plainBackground
import io.github.mhub.app.ui.components.TextAlert
import io.github.mhub.app.ui.components.TrackMenu
import io.github.mhub.app.ui.components.TrackRow
import io.github.mhub.app.ui.components.byteText
import io.github.mhub.core.RateLimit
import io.github.mhub.core.RateLimitPolicy
import io.github.mhub.core.Links
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.scaffold.GlasmioPinnedListPage
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import io.github.neoruaa.glasmio.ui.GlasmioAlertTextField
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioMenu
import io.github.neoruaa.glasmio.ui.GlasmioMenuItem
import io.github.neoruaa.glasmio.ui.GlasmioReorderableGroup
import io.github.neoruaa.glasmio.ui.GlasmioSectionTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(listState: LazyListState) {
    val graph = LocalGraph.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val playlists by graph.library.playlists.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val untitled = stringResource(R.string.untitled_playlist)

    GlasmioPinnedListPage(
        title = stringResource(R.string.library),
        listState = listState,
        bottomPadding = LocalBottomChrome.current,
        actions = {
            GlasmioMenu(
                expanded = menu,
                onExpandedChange = { menu = it },
                itemCount = 2,
                anchor = { open -> GlasmioIconButton(sampleBackdrop = false, onClick = open) { SfIcon("plus", stringResource(R.string.new_playlist)) } },
            ) { backdrop, close ->
                GlasmioMenuItem(stringResource(R.string.new_playlist), { close(); name = ""; creating = true }, systemName = "plus", backdrop = backdrop)
                GlasmioMenuItem(stringResource(R.string.import_playlist), { close(); importing = true }, systemName = "link", backdrop = backdrop)
            }
            io.github.mhub.app.ui.components.SettingsButton()
        },
    ) {
        item("sections") {
            GlasmioGroupedList {
                GlasmioListRow(stringResource(R.string.local_cache), systemName = "arrow.down.circle", onClick = { navigator.push(Route.Cache) }, showTopSeparator = false)
            }
        }
        item("playlists-title") { GlasmioSectionTitle(stringResource(R.string.playlists)) }
        if (playlists.isEmpty()) {
            item("empty") {
                EmptyState(stringResource(R.string.empty_library), stringResource(R.string.empty_library_hint), "music.note.list",
                    stringResource(R.string.import_playlist) to { importing = true })
            }
        } else item("playlists") {
            GlasmioGroupedList {
                playlists.forEachIndexed { index, playlist ->
                    GlasmioListRow(
                        playlist.name,
                        subtitle = context.getString(R.string.songs_count, playlist.tracks.size),
                        leading = { Artwork(playlist.cover, Modifier.size(52.dp)) },
                        onClick = { navigator.push(Route.Playlist(playlist.id)) },
                        showTopSeparator = index > 0,
                    )
                }
            }
        }
    }

    if (creating) {
        TextAlert(stringResource(R.string.new_playlist), stringResource(R.string.playlist_name), stringResource(R.string.create), name, { name = it },
            onDismiss = { creating = false }, onConfirm = { graph.library.create(name, untitled); creating = false })
    }
    if (importing) ImportAlert(onDismiss = { importing = false })
}

/** Paste a playlist/album link; same-platform imports keep their source link. */
@Composable
private fun ImportAlert(onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var field by remember { mutableStateOf(TextFieldValue()) }
    val link = field.text
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // While the server's quota is used up: when the import resumes (epoch ms).
    var resumesAt by remember { mutableStateOf<Long?>(null) }
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(resumesAt) {
        while (resumesAt != null) {
            clock = System.currentTimeMillis()
            delay(250)
        }
    }
    val untitled = stringResource(R.string.untitled_playlist)
    LaunchedEffect(Unit) {
        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (Links.collectionPlatform(text) != null) field = TextFieldValue(text, TextRange(text.length))
    }
    GlasmioAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.import_playlist),
        message = resumesAt?.let { stringResource(R.string.import_rate_limited, RateLimit.seconds(maxOf(0L, it - clock))) }
            ?: error ?: stringResource(R.string.import_unsupported),
        buttons = listOf(
            GlasmioAlertButtonSpec(stringResource(R.string.cancel), onDismiss, role = GlasmioAlertButtonRole.Cancel),
            GlasmioAlertButtonSpec(stringResource(if (working) R.string.loading else R.string.import_button), {
                val platform = Links.collectionPlatform(link) ?: return@GlasmioAlertButtonSpec
                val client = graph.model.client ?: return@GlasmioAlertButtonSpec
                working = true
                error = null
                // An import is background work: it waits out the quota and shows the wait.
                val policy = RateLimitPolicy.importing { resumesAt = System.currentTimeMillis() + it }
                // Leaving the dialog cancels this scope, the import and any wait.
                scope.launch {
                    runCatching { client.collection(platform, link.trim(), policy) }
                        .also { resumesAt = null }
                        .onSuccess {
                            val playlist = graph.library.create(it.title, untitled, it.items.map { item -> item.track }, it.sourceUrl, it.platform)
                            graph.model.show(context.getString(R.string.imported, playlist.name, playlist.tracks.size))
                            onDismiss()
                        }
                        .onFailure { error = it.message }
                    working = false
                }
            }, enabled = !working && Links.collectionPlatform(link) != null),
        ),
    ) {
        GlasmioAlertTextField(value = field, onValueChange = { field = it }, placeholder = stringResource(R.string.import_link_prompt))
    }
}

@Composable
fun PlaylistScreen(playlistId: String) {
    val graph = LocalGraph.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val playlists by graph.library.playlists.collectAsStateWithLifecycle()
    val playlist = playlists.firstOrNull { it.id == playlistId }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    GlasmioPinnedListPage(
        title = playlist?.name.orEmpty(),
        showsLargeTitle = false,
        bottomPadding = LocalBottomChrome.current,
        backgroundColor = plainBackground(),
        onNavigateBack = { navigator.pop() },
        navigateBackContentDescription = stringResource(R.string.back),
        actions = {
            if (playlist != null) {
                GlasmioMenu(
                    expanded = menu,
                    onExpandedChange = { menu = it },
                    itemCount = 3,
                    anchor = { open -> GlasmioIconButton(sampleBackdrop = false, onClick = open) { SfIcon("ellipsis", stringResource(R.string.more)) } },
                ) { backdrop, close ->
                    GlasmioMenuItem(stringResource(if (editing) R.string.done else R.string.drag_handle), { close(); editing = !editing }, systemName = "line.3.horizontal", backdrop = backdrop)
                    GlasmioMenuItem(stringResource(R.string.rename), { close(); name = playlist.name; renaming = true }, systemName = "pencil", backdrop = backdrop)
                    GlasmioMenuItem(stringResource(R.string.delete_playlist), { close(); deleting = true }, systemName = "trash", destructive = true, backdrop = backdrop)
                }
            }
        },
    ) {
        if (playlist == null) {
            item("missing") { EmptyState(stringResource(R.string.empty_library), symbol = "music.note.list") }
            return@GlasmioPinnedListPage
        }
        item("header") {
            CollectionHeader(
                playlist.name, playlist.cover, context.getString(R.string.songs_count, playlist.tracks.size), playlist.tracks,
                io.github.mhub.core.QueueSource(io.github.mhub.core.QueueSource.PLAYLIST, playlist.name, id = playlist.id),
            )
        }
        if (playlist.tracks.isEmpty()) {
            item("empty") { EmptyState(stringResource(R.string.empty_playlist)) }
        } else if (editing) {
            item("reorder") {
                GlasmioReorderableGroup(
                    items = playlist.tracks,
                    onReorder = { graph.library.reorder(playlist.id, it) },
                    dragHandleContentDescription = stringResource(R.string.drag_handle),
                    moveUpLabel = stringResource(R.string.move_up),
                    moveDownLabel = stringResource(R.string.move_down),
                ) { track ->
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(track.title, style = IosTypography.body, color = LocalGlassColors.current.content, maxLines = 1)
                        Text(track.artist, style = IosTypography.subheadline, color = LocalGlassColors.current.secondaryContent, maxLines = 1)
                    }
                }
            }
        } else {
            items(playlist.tracks, key = { it.id }) { track ->
                TrackRow(track, playlistId = playlist.id, onRemove = { graph.library.remove(setOf(track.id), playlist.id) }, separator = true)
            }
        }
    }

    if (renaming && playlist != null) {
        TextAlert(stringResource(R.string.rename), stringResource(R.string.playlist_name), stringResource(R.string.done), name, { name = it },
            onDismiss = { renaming = false }, onConfirm = { graph.library.rename(playlist.id, name); renaming = false })
    }
    if (deleting && playlist != null) {
        GlasmioAlertDialog(
            onDismissRequest = { deleting = false },
            title = stringResource(R.string.delete_playlist),
            message = stringResource(R.string.delete_playlist_confirm),
            buttons = listOf(
                GlasmioAlertButtonSpec(stringResource(R.string.cancel), { deleting = false }, role = GlasmioAlertButtonRole.Cancel),
                GlasmioAlertButtonSpec(stringResource(R.string.delete), {
                    deleting = false
                    graph.library.delete(playlist.id)
                    navigator.pop()
                }, role = GlasmioAlertButtonRole.Destructive),
            ),
        )
    }
}

@Composable
fun CacheScreen() {
    val graph = LocalGraph.current
    val navigator = LocalNavigator.current
    val index by graph.cache.entries.collectAsStateWithLifecycle()
    val entries = remember(index) { graph.cache.list() }
    val total = remember(index) { graph.cache.totalSize() }
    var confirming by remember { mutableStateOf(false) }

    GlasmioPinnedListPage(
        title = stringResource(R.string.local_cache),
        showsLargeTitle = false,
        bottomPadding = LocalBottomChrome.current,
        onNavigateBack = { navigator.pop() },
        navigateBackContentDescription = stringResource(R.string.back),
        actions = {
            GlasmioIconButton(sampleBackdrop = false, onClick = { confirming = true }, enabled = entries.isNotEmpty()) { SfIcon("trash", stringResource(R.string.clear_cache)) }
        },
    ) {
        item("usage") {
            GlasmioGroupedList { GlasmioListRow(stringResource(R.string.cache_usage), detail = total.byteText(), showTopSeparator = false) }
        }
        if (entries.isEmpty()) item("empty") { EmptyState(stringResource(R.string.cache_empty), symbol = "arrow.down.circle") }
        items(entries, key = AudioCache.Entry::key) { entry ->
            CacheRow(entry, onPlay = { graph.player.play(entries.map { it.track }, entries.indexOf(entry), shuffle = false) })
        }
    }
    if (confirming) {
        GlasmioAlertDialog(
            onDismissRequest = { confirming = false },
            title = stringResource(R.string.clear_cache),
            message = stringResource(R.string.clear_cache_confirm),
            buttons = listOf(
                GlasmioAlertButtonSpec(stringResource(R.string.cancel), { confirming = false }, role = GlasmioAlertButtonRole.Cancel),
                GlasmioAlertButtonSpec(stringResource(R.string.clear_cache), { confirming = false; graph.cache.clear() }, role = GlasmioAlertButtonRole.Destructive),
            ),
        )
    }
}

@Composable
private fun CacheRow(entry: AudioCache.Entry, onPlay: () -> Unit) {
    val colors = LocalGlassColors.current
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Artwork(entry.track.cover, Modifier.size(44.dp), corner = 5.dp)
        Column(Modifier.weight(1f)) {
            Text(entry.track.title, style = IosTypography.body, color = colors.content, maxLines = 1)
            Text("${entry.track.artist} · ${entry.quality.label} · ${entry.size.byteText()}", style = IosTypography.footnote, color = colors.secondaryContent, maxLines = 1)
        }
        TrackMenu(listOf(entry.track))
    }
}
