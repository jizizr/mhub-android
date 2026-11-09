package io.github.mhub.app.ui.player

import android.content.Context
import android.media.AudioManager
import android.media.MediaRouter2
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.tween
import io.github.mhub.app.ui.LocalSharedScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle
import io.github.mhub.app.R
import io.github.mhub.app.ui.LocalGraph
import io.github.mhub.app.ui.components.Artwork
import io.github.mhub.app.ui.components.TrackMenu
import io.github.mhub.core.Quality
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.glass.GlasmioBackdropHost
import io.github.neoruaa.glasmio.glass.GlassSurface
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.ui.GlasmioMenu
import io.github.neoruaa.glasmio.ui.GlasmioMenuItem
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class Pane { Artwork, Lyrics, Queue }

/**
 * The full player, laid out like Apple Music and like the iOS NowPlayingView:
 * artwork (or lyrics, or the queue), title, progress, transport, volume and
 * the bottom button row. Dragging down dismisses it.
 */
@Composable
fun FullPlayer(visible: Boolean, onDismiss: () -> Unit) {
    val shared = LocalSharedScope.current
    AnimatedVisibility(
        visible = visible,
        // The bounds animate from the mini player (shared bounds); without a
        // shared scope it falls back to sliding up.
        enter = if (shared != null) fadeIn(tween(200)) else slideInVertically(spring(dampingRatio = 0.86f, stiffness = 380f)) { it },
        exit = if (shared != null) fadeOut(tween(220, delayMillis = 60)) else slideOutVertically(spring(dampingRatio = 1f, stiffness = 500f)) { it },
    ) {
        val visibility = this
        BackHandler(onBack = onDismiss)
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        val drag = remember { Animatable(0f) }
        Box(
            Modifier.fillMaxSize()
                .playerBounds(shared, visibility)
                .offset { IntOffset(0, drag.value.roundToInt()) }
                .graphicsLayer {
                    val progress = (drag.value / with(density) { 600.dp.toPx() }).coerceIn(0f, 1f)
                    val scale = 1f - 0.06f * progress
                    scaleX = scale
                    scaleY = scale
                    clip = true
                    shape = ContinuousRoundedRectangle(with(density) { (progress * 40f).toDp() } + 0.dp)
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            scope.launch { drag.snapTo((drag.value + amount).coerceAtLeast(0f)) }
                        },
                        onDragEnd = {
                            scope.launch {
                                // Keep the dragged position while the player shrinks back into
                                // the mini player; the next opening starts a fresh offset.
                                if (drag.value > with(density) { 140.dp.toPx() }) onDismiss()
                                else drag.animateTo(0f, spring(dampingRatio = 0.8f))
                            }
                        },
                    )
                },
        ) {
            // The player is always dark, like the iPhone one, so its menus and
            // glass controls use the dark material.
            io.github.mhub.app.ui.MHubTheme(io.github.mhub.app.Appearance.Dark) { PlayerContent(onDismiss, visibility) }
        }
    }
}

@Composable
private fun PlayerContent(onDismiss: () -> Unit, visibility: AnimatedVisibilityScope) {
    val shared = LocalSharedScope.current
    val graph = LocalGraph.current
    val state by graph.player.state.collectAsStateWithLifecycle()
    val track = state.current
    var pane by remember { mutableStateOf(Pane.Artwork) }
    val palette = rememberPalette(track?.cover)

    GlasmioBackdropHost(
        modifier = Modifier.fillMaxSize(),
        sampledContent = { PlayerBackground(track?.cover, palette) },
        overlayContent = {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp),
            ) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(38.dp, 5.dp).background(Color.White.copy(alpha = 0.4f), ContinuousCapsule))
                }
                // Switching panes moves the artwork between its large and
                // compact places (shared element), as in Apple Music.
                AnimatedContent(
                    pane,
                    transitionSpec = { fadeIn(tween(260, delayMillis = 80)) togetherWith fadeOut(tween(160)) },
                    modifier = Modifier.weight(1f),
                    label = "pane",
                ) { shown ->
                    val paneScope = this
                    when (shown) {
                        Pane.Artwork -> Column {
                            Spacer(Modifier.weight(1f))
                            LargeArtwork(
                                track?.cover, state.isPlaying,
                                Modifier.playerArtwork(shared, paneScope, "pane-artwork"),
                            )
                            Spacer(Modifier.weight(1f))
                            TitleRow()
                            Spacer(Modifier.height(18.dp))
                        }
                        Pane.Lyrics -> Column {
                            CompactHeader(Modifier.playerArtwork(shared, paneScope, "pane-artwork")) { pane = Pane.Artwork }
                            LyricsPane(Modifier.weight(1f))
                        }
                        Pane.Queue -> Column {
                            CompactHeader(Modifier.playerArtwork(shared, paneScope, "pane-artwork")) { pane = Pane.Artwork }
                            QueuePane(Modifier.weight(1f))
                        }
                    }
                }
                Progress()
                Transport(Modifier.padding(vertical = 14.dp))
                Volume()
                BottomBar(pane) { pane = if (pane == it) Pane.Artwork else it }
            }
        },
    )
}

/** Average colours of the artwork, for the flowing background (system Palette). */
@Composable
private fun rememberPalette(cover: String?): List<Color> {
    val context = LocalContext.current
    var colors by remember(cover) { mutableStateOf(emptyList<Color>()) }
    LaunchedEffect(cover) {
        if (cover.isNullOrEmpty()) return@LaunchedEffect
        val result = context.imageLoader.execute(ImageRequest.Builder(context).data(cover).size(128).allowHardware(false).build())
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return@LaunchedEffect
        val palette = Palette.from(bitmap).generate()
        colors = listOfNotNull(palette.darkVibrantSwatch, palette.vibrantSwatch, palette.darkMutedSwatch, palette.mutedSwatch, palette.dominantSwatch)
            .map { Color(it.rgb) }.distinct().take(4)
    }
    return colors
}

@Composable
private fun PlayerBackground(cover: String?, palette: List<Color>) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!cover.isNullOrEmpty()) {
            AsyncImage(
                model = cover, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().scale(1.6f).blur(80.dp).graphicsLayer { alpha = 0.9f },
            )
        }
        if (palette.isNotEmpty()) {
            var phase by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(Unit) {
                val start = withFrameMillis { it }
                while (true) withFrameMillis { phase = (it - start) / 9000f }
            }
            // A soft flowing wash like the iPhone mesh: overlapping gradients
            // from each palette colour drift slowly; no hard centre spot.
            Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = 0.6f }.blur(60.dp)) {
                palette.forEachIndexed { i, color ->
                    val angle = phase + i * 1.7f
                    val center = Offset(
                        size.width * (0.5f + 0.4f * kotlin.math.cos(angle)),
                        size.height * (0.5f + 0.35f * kotlin.math.sin(angle * 0.8f)),
                    )
                    drawRect(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center = center, radius = size.maxDimension * 0.6f))
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f)))
    }
}

@Composable
private fun LargeArtwork(cover: String?, playing: Boolean, shared: Modifier = Modifier) {
    val scale by animateFloatAsState(if (playing) 1f else 0.82f, spring(dampingRatio = 0.68f, stiffness = 300f), label = "artwork")
    Artwork(
        cover,
        Modifier.fillMaxWidth().aspectRatio(1f).then(shared).scale(scale)
            .shadow(if (playing) 30.dp else 14.dp, ContinuousRoundedRectangle(12.dp)),
        corner = 12.dp,
    )
}

@Composable
private fun TitleRow() {
    val graph = LocalGraph.current
    val state by graph.player.state.collectAsStateWithLifecycle()
    val track = state.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(track?.title ?: stringResource(R.string.not_playing), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track?.artist.orEmpty(), color = Color.White.copy(alpha = 0.6f), fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (track != null) {
            GlassSurface(Modifier.size(32.dp), shape = ContinuousCapsule) { TrackMenu(listOf(track), tint = Color.White) }
        }
    }
}

@Composable
private fun CompactHeader(artwork: Modifier = Modifier, onClick: () -> Unit) {
    val graph = LocalGraph.current
    val state by graph.player.state.collectAsStateWithLifecycle()
    val track = state.current
    Row(Modifier.fillMaxWidth().padding(vertical = 18.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Artwork(track?.cover, Modifier.size(60.dp).then(artwork))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(track?.title.orEmpty(), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track?.artist.orEmpty(), color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Seek bar with elapsed/remaining time and the quality badge, like iOS PlayerProgress. */
@Composable
private fun Progress() {
    val graph = LocalGraph.current
    val state by graph.player.state.collectAsStateWithLifecycle()
    var position by remember { mutableLongStateOf(graph.player.positionMs) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { }
            if (dragging == null) position = graph.player.positionMs
            kotlinx.coroutines.delay(250)
        }
    }
    val duration = state.durationMs.coerceAtLeast(0)
    val fraction = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shown = (fraction * duration).toLong()
    val height by animateFloatAsState(if (dragging != null) 12f else 7f, label = "bar")
    val description = stringResource(R.string.progress)
    Column {
        Canvas(
            Modifier.fillMaxWidth().height(24.dp).semantics { contentDescription = description }
                .pointerInput(duration) {
                    detectTapGestures { offset -> graph.player.seek((offset.x / size.width * duration).toLong()) }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                        onHorizontalDrag = { change, _ -> dragging = (change.position.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { dragging?.let { graph.player.seek((it * duration).toLong()) }; position = ((dragging ?: 0f) * duration).toLong(); dragging = null },
                        onDragCancel = { dragging = null },
                    )
                },
        ) {
            val h = height.dp.toPx()
            val top = (size.height - h) / 2
            drawRoundRect(Color.White.copy(alpha = 0.2f), Offset(0f, top), Size(size.width, h), CornerRadius(h / 2))
            drawRoundRect(Color.White.copy(alpha = if (dragging == null) 0.75f else 1f), Offset(0f, top), Size(size.width * fraction, h), CornerRadius(h / 2))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(format(shown), color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, modifier = Modifier.width(60.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { QualityBadge(state.playingQuality) }
            Text("-" + format((duration - shown).coerceAtLeast(0)), color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp,
                modifier = Modifier.width(60.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

@Composable
private fun QualityBadge(quality: Quality?) {
    if (quality == null) return
    val graph = LocalGraph.current
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    // Same as iOS: opens centred above the badge, highest quality on top, the
    // current one ticked.
    val options = Quality.entries.reversed()
    // Limit the menu to the area above the badge so it opens upwards, as on iOS.
    var above by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val windowWidth = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toFloat()
    GlasmioMenu(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        itemCount = options.size,
        centerHorizontally = true,
        viewportBounds = above,
        anchor = { open ->
            GlassSurface(
                Modifier.height(22.dp).onGloballyPositioned {
                    val bounds = it.boundsInWindow()
                    // Symmetric around the badge, so centring in it centres on the badge.
                    val half = minOf(bounds.center.x, windowWidth - bounds.center.x)
                    above = androidx.compose.ui.geometry.Rect(bounds.center.x - half, 0f, bounds.center.x + half, bounds.top - 8f)
                },
                shape = ContinuousCapsule, onClick = open,
            ) {
                Row(Modifier.padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (quality == Quality.Lossless || quality == Quality.HiRes) {
                        SfIcon(SfSymbol.Waveform, null, tint = Color.White, size = 11.dp)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(quality.label, color = Color.White, style = CenteredLabel)
                }
            }
        },
    ) { backdrop, close ->
        options.forEach { option ->
            GlasmioMenuItem(
                context.getString(R.string.replay_in, option.label),
                { close(); if (option != quality) graph.player.reload(option) },
                systemName = if (option == quality) "checkmark" else null,
                backdrop = backdrop,
            )
        }
    }
}

/** A small label whose glyphs sit in the vertical centre (no font padding, trimmed line height). */
private val CenteredLabel = androidx.compose.ui.text.TextStyle(
    fontSize = 11.sp,
    fontWeight = FontWeight.SemiBold,
    lineHeight = 11.sp,
    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
        androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
        androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
    ),
)

@Composable
private fun Transport(modifier: Modifier = Modifier) {
    val graph = LocalGraph.current
    val state by graph.player.state.collectAsStateWithLifecycle()
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        PressButton(72, stringResource(R.string.previous), { graph.player.previous() }) { SfIcon(SfSymbol.BackwardFilled, null, tint = Color.White, size = 46.dp) }
        PressButton(80, stringResource(if (state.isPlaying) R.string.pause else R.string.play), { graph.player.togglePlay() }) {
            SfIcon(if (state.isPlaying) SfSymbol.PauseFilled else SfSymbol.PlayFilled, null, tint = Color.White, size = 62.dp)
        }
        PressButton(72, stringResource(R.string.next), { graph.player.next() }) { SfIcon(SfSymbol.ForwardFilled, null, tint = Color.White, size = 46.dp) }
    }
}

/** Apple Music's transport buttons shrink and show a soft circle while pressed. */
@Composable
private fun PressButton(size: Int, label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, spring(dampingRatio = 0.6f, stiffness = 600f), label = "press")
    Box(
        Modifier.size(size.dp).scale(scale).clip(ContinuousCapsule)
            .background(Color.White.copy(alpha = if (pressed) 0.14f else 0f))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** System media volume, the same value the volume keys change. */
@Composable
private fun Volume() {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var value by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()) }
    DisposableEffect(Unit) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { value = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat() }
        }
        context.contentResolver.registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    val label = stringResource(R.string.volume)
    // The same thin row as the iPhone player: speaker, capsule, loud speaker.
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        SfIcon("speaker.fill", null, tint = Color.White.copy(alpha = 0.6f), size = 14.dp)
        CapsuleSlider(
            value = value,
            onValueChange = {
                value = it
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (it * max).roundToInt(), 0)
            },
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            label = label,
        )
        SfIcon("speaker.wave.3.fill", null, tint = Color.White.copy(alpha = 0.6f), size = 16.dp)
    }
}

/** A horizontal capsule in the player style that thickens while dragged. */
@Composable
private fun CapsuleSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier, label: String) {
    var dragging by remember { mutableStateOf(false) }
    val height by animateFloatAsState(if (dragging) 12f else 7f, label = "capsule")
    Canvas(
        modifier.height(28.dp)
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                detectTapGestures { onValueChange((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true; onValueChange((it.x / size.width).coerceIn(0f, 1f)) },
                    onHorizontalDrag = { change, _ -> onValueChange((change.position.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                )
            },
    ) {
        val h = height.dp.toPx()
        val top = (size.height - h) / 2
        drawRoundRect(Color.White.copy(alpha = 0.2f), Offset(0f, top), Size(size.width, h), CornerRadius(h / 2))
        drawRoundRect(Color.White.copy(alpha = if (dragging) 1f else 0.75f), Offset(0f, top), Size(size.width * value.coerceIn(0f, 1f), h), CornerRadius(h / 2))
    }
}

@Composable
private fun BottomBar(pane: Pane, onSelect: (Pane) -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        PaneButton(pane == Pane.Lyrics, "quote.bubble", stringResource(R.string.lyrics)) { onSelect(Pane.Lyrics) }
        // The system output switcher (Bluetooth, casting) on Android 14+.
        PaneButton(false, "airplay.audio", stringResource(R.string.output), enabled = Build.VERSION.SDK_INT >= 34) {
            if (Build.VERSION.SDK_INT >= 34) MediaRouter2.getInstance(context).showSystemOutputSwitcher()
        }
        PaneButton(pane == Pane.Queue, "list.bullet", stringResource(R.string.queue)) { onSelect(Pane.Queue) }
    }
}

@Composable
private fun PaneButton(active: Boolean, symbol: String, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    if (active) {
        GlassSurface(Modifier.size(44.dp), shape = ContinuousCapsule, onClick = onClick) {
            SfIcon(symbol, label, tint = Color.White, size = 22.dp)
        }
    } else {
        Box(Modifier.size(44.dp).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
            SfIcon(symbol, label, tint = Color.White.copy(alpha = if (enabled) 0.85f else 0.3f), size = 22.dp)
        }
    }
}

internal fun format(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
