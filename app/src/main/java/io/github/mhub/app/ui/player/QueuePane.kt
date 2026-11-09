package io.github.mhub.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle
import io.github.mhub.app.R
import io.github.mhub.app.playback.PlayerController.AutoplayStatus
import io.github.mhub.app.ui.LocalGraph
import io.github.mhub.app.ui.LocalNavigator
import io.github.mhub.app.ui.Route
import io.github.mhub.app.ui.Tab
import io.github.mhub.app.ui.components.Artwork
import io.github.mhub.core.QueueSource
import io.github.mhub.core.RepeatMode
import io.github.mhub.core.Track
import io.github.mhub.app.ui.MHubAccent
import io.github.neoruaa.glasmio.glass.GlassSurface
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import kotlinx.coroutines.flow.first
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * The play queue, laid out like the web `QueuePanel` (docs/design.md「播放队列」):
 * mode toggles, count and source, the AutoPlay notice, then the whole queue
 * with the current song highlighted, drag handles and remove buttons.
 */
@Composable
fun QueuePane(modifier: Modifier = Modifier, onDismissPlayer: () -> Unit) {
    val graph = LocalGraph.current
    val navigator = LocalNavigator.current
    val state by graph.player.state.collectAsStateWithLifecycle()
    val queue = state.queue
    var confirmingClear by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeToggle(stringResource(R.string.shuffle), "shuffle", queue.shuffled, Modifier.weight(1f)) { graph.player.toggleShuffle() }
            ModeToggle(stringResource(R.string.repeat), if (queue.repeatMode == RepeatMode.One) "repeat.1" else "repeat", queue.repeatMode != RepeatMode.Off, Modifier.weight(1f)) {
                graph.player.cycleRepeat()
            }
            ModeToggle(stringResource(R.string.autoplay), "infinity", queue.autoplay, Modifier.weight(1f)) { graph.player.setAutoplay(!queue.autoplay) }
        }

        // 「N 首音乐 · 来自歌单：X」 and clearing the queue.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.queue_count, queue.items.size), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                state.source?.let { source ->
                    Text(" · ", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                    Text(
                        stringResource(if (source.isRecommendation) R.string.source_recommendation else R.string.source_playlist, source.name),
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { openSource(source, graph, navigator, onDismissPlayer) },
                    )
                }
            }
            Box(Modifier.size(36.dp).clickable(enabled = !queue.isEmpty) { confirmingClear = true }, contentAlignment = Alignment.Center) {
                SfIcon("trash", stringResource(R.string.clear_queue), tint = Color.White.copy(alpha = if (queue.isEmpty) 0.3f else 0.75f), size = 19.dp)
            }
        }

        AutoplayNotice(queue.autoplay, state.autoplay)

        if (queue.isEmpty) {
            Column(Modifier.fillMaxWidth().weight(1f).padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SfIcon("music.note.list", null, tint = Color.White.copy(alpha = 0.5f), size = 30.dp)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.queue_empty), color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.queue_empty_hint), color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        } else {
            QueueList(queue.items, queue.current?.id, Modifier.weight(1f))
        }
    }

    if (confirmingClear) {
        GlasmioAlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = stringResource(R.string.clear_queue),
            message = stringResource(R.string.clear_queue_confirm),
            buttons = listOf(
                GlasmioAlertButtonSpec(stringResource(R.string.cancel), { confirmingClear = false }, role = GlasmioAlertButtonRole.Cancel),
                GlasmioAlertButtonSpec(stringResource(R.string.clear), {
                    confirmingClear = false
                    graph.player.clearQueue()
                }, role = GlasmioAlertButtonRole.Destructive),
            ),
        )
    }
}

/** Opens what the queue came from and closes the player, like the web source link. */
private fun openSource(source: QueueSource, graph: io.github.mhub.app.AppGraph, navigator: io.github.mhub.app.ui.Navigator, onDismissPlayer: () -> Unit) {
    when (source.type) {
        QueueSource.PLAYLIST -> {
            if (graph.library.playlist(source.id) == null) return
            navigator.open(Tab.Library, Route.Playlist(source.id))
        }
        QueueSource.COLLECTION -> navigator.open(Tab.Home, Route.Collection(source.platform, source.url, source.name, source.cover))
        else -> navigator.open(Tab.Home)
    }
    onDismissPlayer()
}

@Composable
private fun AutoplayNotice(enabled: Boolean, status: AutoplayStatus) {
    val graph = LocalGraph.current
    if (!enabled) return
    val message = when (status) {
        AutoplayStatus.Loading -> R.string.autoplay_loading
        AutoplayStatus.Empty -> R.string.autoplay_empty
        AutoplayStatus.Unsupported -> R.string.autoplay_unsupported
        AutoplayStatus.Error -> R.string.autoplay_unavailable
        else -> return
    }
    Row(
        Modifier.fillMaxWidth().clip(ContinuousRoundedRectangle(12.dp)).background(Color.White.copy(alpha = 0.1f)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SfIcon(if (status == AutoplayStatus.Loading) "hourglass" else "infinity", null, tint = Color.White.copy(alpha = 0.7f), size = 15.dp)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(message), color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (status != AutoplayStatus.Loading) {
            Text(
                stringResource(R.string.retry), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(ContinuousCapsule).clickable { graph.player.retryAutoplay() }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * Every song in queue order. Dragging a handle reorders a local copy; the
 * player gets one move when the drag ends (web `moveInQueue`).
 */
@Composable
private fun QueueList(tracks: List<Track>, currentId: String?, modifier: Modifier) {
    val graph = LocalGraph.current
    var rows by remember(tracks) { mutableStateOf(tracks) }
    var dragged by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        rows = rows.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    val rowHeight = with(LocalDensity.current) { 56.dp.roundToPx() }
    // Open on the playing song, centred in the list.
    LaunchedEffect(Unit) {
        val index = tracks.indexOfFirst { it.id == currentId }
        if (index < 0) return@LaunchedEffect
        val viewport = snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
        listState.scrollToItem(index, -(viewport - rowHeight) / 2)
    }
    LazyColumn(modifier.fillMaxWidth(), state = listState) {
        items(rows, key = { it.id }) { track ->
            ReorderableItem(reorder, key = track.id) { dragging ->
                val current = track.id == currentId
                Row(
                    Modifier.fillMaxWidth().height(56.dp)
                        .background(if (dragging) Color.White.copy(alpha = 0.12f) else Color.Transparent, ContinuousRoundedRectangle(8.dp))
                        .clickable { graph.player.select(tracks.indexOfFirst { it.id == track.id }) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(36.dp, 56.dp).draggableHandle(
                            onDragStarted = { dragged = track.id },
                            onDragStopped = {
                                val key = dragged ?: return@draggableHandle
                                dragged = null
                                val from = tracks.indexOfFirst { it.id == key }
                                val to = rows.indexOfFirst { it.id == key }
                                if (from >= 0 && to >= 0 && from != to) graph.player.move(from, to)
                            },
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        SfIcon("line.3.horizontal", stringResource(R.string.drag_handle), tint = Color.White.copy(alpha = 0.45f), size = 18.dp)
                    }
                    Box {
                        Artwork(track.cover, Modifier.size(42.dp), corner = 5.dp)
                        if (current) {
                            Box(Modifier.size(42.dp).clip(ContinuousRoundedRectangle(5.dp)).background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                                SfIcon(SfSymbol.Waveform, null, tint = Color.White, size = 18.dp)
                            }
                        }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            track.title, color = if (current) MHubAccent else Color.White, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            track.artist.ifEmpty { stringResource(R.string.unknown_artist) } +
                                if (track.isRecommendation) stringResource(R.string.autoplay_recommended) else "",
                            color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(
                        Modifier.size(36.dp).clickable {
                            // Resolve the position when tapped; earlier removals shift indices.
                            val index = graph.player.state.value.queue.items.indexOfFirst { it.id == track.id }
                            if (index >= 0) graph.player.remove(index)
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        SfIcon("minus.circle", stringResource(R.string.remove_from_queue), tint = Color.White.copy(alpha = 0.6f), size = 20.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeToggle(label: String, symbol: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    GlassSurface(sampleBackdrop = false, modifier = modifier.height(36.dp), shape = ContinuousCapsule, onClick = onClick, navigationSurfaceColor = if (on) Color.White else null) {
        SfIcon(symbol, label, tint = if (on) Color.Black else Color.White, size = 18.dp)
    }
}
