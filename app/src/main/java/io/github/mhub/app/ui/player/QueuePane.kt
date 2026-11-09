package io.github.mhub.app.ui.player

import android.icu.text.Transliterator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.capsule.ContinuousCapsule
import io.github.mhub.app.R
import io.github.mhub.app.playback.PlayerController
import io.github.mhub.app.ui.LocalGraph
import io.github.mhub.app.ui.components.Artwork
import io.github.mhub.core.LyricLine
import io.github.mhub.core.LyricScript
import io.github.mhub.core.RepeatMode
import io.github.neoruaa.glasmio.glass.GlassSurface
import io.github.neoruaa.glasmio.icons.SfIcon
import kotlin.math.abs

/** "待播清单": shuffle / repeat / AutoPlay toggles and the upcoming songs. */
@Composable
fun QueuePane(modifier: Modifier = Modifier) {
    val graph = LocalGraph.current
    val state by graph.player.state.collectAsStateWithLifecycle()
    val queue = state.queue
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeToggle(stringResource(R.string.shuffle), "shuffle", queue.shuffled, Modifier.weight(1f)) { graph.player.toggleShuffle() }
            ModeToggle(stringResource(R.string.repeat), if (queue.repeatMode == RepeatMode.One) "repeat.1" else "repeat", queue.repeatMode != RepeatMode.Off, Modifier.weight(1f)) {
                graph.player.cycleRepeat()
            }
            ModeToggle(stringResource(R.string.autoplay), "infinity", queue.autoplay, Modifier.weight(1f)) { graph.player.setAutoplay(!queue.autoplay) }
        }
        val upcoming = queue.items.withIndex().filter { it.index > queue.index }
        val chosen = upcoming.filter { !it.value.isRecommendation }
        val recommended = upcoming.filter { it.value.isRecommendation }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            item { Header(stringResource(R.string.playing_next)) }
            chosen.forEach { (index, track) -> item(key = track.id) { QueueRow(index, track.title, track.artist, track.cover) } }
            if (recommended.isNotEmpty() || state.autoplayLoading) {
                item { Header(stringResource(R.string.from_autoplay)) }
                recommended.forEach { (index, track) -> item(key = track.id) { QueueRow(index, track.title, track.artist, track.cover) } }
            }
        }
    }
}

@Composable
private fun Header(title: String) {
    Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun QueueRow(index: Int, title: String, artist: String, cover: String) {
    val graph = LocalGraph.current
    Row(Modifier.fillMaxWidth().clickable { graph.player.select(index) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(cover, Modifier.size(42.dp), corner = 5.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(36.dp).clickable { graph.player.remove(index) }, contentAlignment = Alignment.Center) {
            SfIcon("minus.circle", stringResource(R.string.remove_from_queue), tint = Color.White.copy(alpha = 0.6f), size = 20.dp)
        }
    }
}

@Composable
private fun ModeToggle(label: String, symbol: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    GlassSurface(modifier.height(36.dp), shape = ContinuousCapsule, onClick = onClick, navigationSurfaceColor = if (on) Color.White else null) {
        SfIcon(symbol, label, tint = if (on) Color.Black else Color.White, size = 18.dp)
    }
}
