package io.github.mhub.app.ui.player

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.graphicsLayer
import com.kyant.capsule.ContinuousRoundedRectangle
import io.github.mhub.app.ui.LocalSharedScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.capsule.ContinuousCapsule
import io.github.mhub.app.R
import io.github.mhub.app.playback.PlayerController
import io.github.mhub.app.ui.LocalGraph
import io.github.mhub.app.ui.components.Artwork
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.glass.GlassSurface
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol

/** The Liquid Glass capsule above the tab bar, like the iOS tab view accessory. */
@Composable
fun MiniPlayer(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    visibility: AnimatedVisibilityScope? = null,
    inline: State<Float> = remember { mutableFloatStateOf(0f) },
) {
    val shared = LocalSharedScope.current
    val graph = LocalGraph.current
    val state by graph.player.state.collectAsStateWithLifecycle()
    val track = state.current
    val colors = LocalGlassColors.current
    GlassSurface(
        modifier = modifier.height(height).playerBounds(shared, visibility),
        shape = ContinuousCapsule,
        onClick = { if (track != null) onOpen() },
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Artwork(track?.cover, Modifier.size(34.dp), corner = 7.dp)
            Column(Modifier.weight(1f)) {
                Text(track?.title ?: stringResource(R.string.not_playing), style = IosTypography.subheadline, fontWeight = FontWeight.Medium,
                    color = colors.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Inline (minimized) shows the title only, as on iOS. Recompose only
                // when crossing the midpoint, not on every frame of the motion.
                val showsArtist by remember(inline) { derivedStateOf { inline.value < 0.5f } }
                if (track != null && showsArtist) {
                    Text(track.artist, style = IosTypography.footnote, color = colors.secondaryContent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.size(40.dp).clickable(enabled = track != null) { graph.player.togglePlay() }, contentAlignment = Alignment.Center) {
                if (state.phase == PlayerController.Phase.Loading && state.wantsPlay) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.content)
                } else {
                    SfIcon(
                        if (state.isPlaying) SfSymbol.PauseFilled else SfSymbol.PlayFilled,
                        stringResource(if (state.isPlaying) R.string.pause else R.string.play),
                        tint = colors.content, size = 24.dp,
                    )
                }
            }
            // The next button folds away while the capsule moves inline; the
            // width is read during layout so the motion never recomposes.
            Box(
                Modifier
                    .layout { measurable, _ ->
                        val width = (40.dp.toPx() * (1f - inline.value.coerceIn(0f, 1f))).roundToInt()
                        val placeable = measurable.measure(Constraints.fixed(40.dp.roundToPx(), 40.dp.roundToPx()))
                        layout(width, placeable.height) { if (width > 0) placeable.place(0, 0) }
                    }
                    .graphicsLayer { alpha = 1f - inline.value; clip = true }
                    .clickable(enabled = track != null) { graph.player.next() },
                contentAlignment = Alignment.Center,
            ) {
                SfIcon(SfSymbol.ForwardFilled, stringResource(R.string.next), tint = colors.content, size = 24.dp)
            }
        }
    }
}

/** The capsule and the full player share bounds, so opening the player grows it out of the capsule. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.playerBounds(shared: SharedTransitionScope?, visibility: AnimatedVisibilityScope?): Modifier {
    if (shared == null || visibility == null) return this
    return with(shared) {
        this@playerBounds.sharedBounds(
            rememberSharedContentState("player-surface"), visibility,
            boundsTransform = { _, _ -> spring(dampingRatio = 0.86f, stiffness = 300f) },
            clipInOverlayDuringTransition = OverlayClip(ContinuousRoundedRectangle(28.dp)),
        )
    }
}

/** Artwork moving between the large and compact places in the full player. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.playerArtwork(shared: SharedTransitionScope?, visibility: AnimatedVisibilityScope?, key: String = "player-artwork"): Modifier {
    if (shared == null || visibility == null) return this
    return with(shared) {
        this@playerArtwork.sharedElement(
            rememberSharedContentState(key), visibility,
            boundsTransform = { _, _ -> spring(dampingRatio = 0.86f, stiffness = 300f) },
        )
    }
}
