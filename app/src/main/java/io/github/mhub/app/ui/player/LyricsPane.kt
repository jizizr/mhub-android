package io.github.mhub.app.ui.player

import android.icu.text.Transliterator
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mhub.app.R
import io.github.mhub.app.playback.PlayerController
import io.github.mhub.app.ui.LocalGraph
import io.github.mhub.core.LyricLine
import io.github.mhub.core.LyricScript
import io.github.mhub.core.LyricWord
import io.github.mhub.core.Lyrics
import io.github.mhub.core.applying
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/** 简体 / 繁體 with the system ICU transliterator; no dictionaries are bundled. */
internal object ScriptConverter {
    private val toTraditional by lazy { Transliterator.getInstance("Hans-Hant") }
    private val toSimplified by lazy { Transliterator.getInstance("Hant-Hans") }
    fun convert(text: String, script: LyricScript): String = when (script) {
        LyricScript.Original -> text
        LyricScript.Simplified -> toSimplified.transliterate(text)
        LyricScript.Traditional -> toTraditional.transliterate(text)
    }
}

private data class Interlude(val before: Int, val start: Long, val end: Long)

/** Spring that matches the iPhone lyrics (SwiftUI response 0.62, damping 0.84). */
private fun <T> lyricSpring() = spring<T>(dampingRatio = 0.84f, stiffness = 103f)

/**
 * Apple Music style lyrics, the same behaviour as the iPhone `LyricsPane`:
 * the focused line sits a third down and every line springs to it with a
 * small cascade delay; words fill with a feathered sweep, rise slightly and
 * long held words glow; other lines dim; dragging browses for 3 s; long
 * gaps show breathing dots; tapping a line seeks to it.
 */
@Composable
fun LyricsPane(modifier: Modifier = Modifier) {
    val graph = LocalGraph.current
    val state by graph.player.state.collectAsStateWithLifecycle()
    val mode by graph.settings.lyricWordMode.state.collectAsStateWithLifecycle()
    val script by graph.settings.lyricScript.state.collectAsStateWithLifecycle()
    val translation by graph.settings.showTranslation.state.collectAsStateWithLifecycle()
    val roman by graph.settings.showRoman.state.collectAsStateWithLifecycle()
    val lyrics = remember(state.lyrics, mode) { state.lyrics.applying(mode) }

    when {
        lyrics.isEmpty -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(if (state.lyricsState == PlayerController.LyricsState.Loading) R.string.loading_lyrics else R.string.no_lyrics),
                color = Color.White.copy(alpha = 0.5f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        !lyrics.isSynced -> Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 40.dp)) {
            Text(lyrics.lines.joinToString("\n") { ScriptConverter.convert(it.text, script) }, color = Color.White.copy(alpha = 0.85f),
                fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp)
        }
        else -> SyncedLyrics(lyrics, script, translation, roman, modifier)
    }
}

@Composable
private fun SyncedLyrics(lyrics: Lyrics, script: LyricScript, showsTranslation: Boolean, showsRoman: Boolean, modifier: Modifier) {
    val graph = LocalGraph.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val heights = remember(lyrics) { mutableStateMapOf<Int, Int>() }
    // Start at the current line and place it without motion: opening the
    // lyrics shows that line directly instead of scrolling down to it.
    var focus by remember(lyrics) { mutableIntStateOf(lyrics.activeIndex(graph.player.positionMs) ?: 0) }
    var settled by remember(lyrics) { mutableStateOf(false) }
    val openedAt = remember(lyrics) { System.currentTimeMillis() }
    // Last time a line changed height; the first layouts happen while the pane
    // is still animating in, so positions are only final once heights stop changing.
    var layoutChangedAt by remember(lyrics) { mutableLongStateOf(System.currentTimeMillis()) }
    var interlude by remember(lyrics) { mutableStateOf<Interlude?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val manual = remember { Animatable(0f) }
    var browsing by remember { mutableStateOf(false) }
    var releaseAt by remember { mutableLongStateOf(0L) }
    val spacing = with(density) { 30.dp.toPx() }

    // Poll the player clock cheaply; only the sung line redraws every frame.
    LaunchedEffect(lyrics) {
        val mainLines = lyrics.lines.filter { !it.isBackground }
        while (true) {
            val now = graph.player.positionMs
            val active = lyrics.activeIndex(now)
            var next = active ?: 0
            var gap: Interlude? = null
            val first = mainLines.firstOrNull()
            if (active == null && first != null && first.start > 3000) {
                gap = Interlude(first.id, 0, first.start)
            } else if (active != null) {
                val following = mainLines.firstOrNull { it.id > active }
                if (following != null) {
                    val ended = lyrics.lines.subList(active, following.id).maxOf { it.end }
                    if (following.start - ended > 4000 && now > ended) {
                        gap = Interlude(following.id, ended, following.start)
                        next = following.id
                    }
                }
            }
            focus = next
            interlude = gap
            val clock = System.currentTimeMillis()
            if (!settled && clock - openedAt > 600 && clock - layoutChangedAt > 250 && heights.size >= lyrics.lines.size) {
                // Everything placed while the pane was still appearing jumped
                // into place; from now on line changes animate.
                settled = true
            }
            if (browsing && releaseAt != 0L && System.currentTimeMillis() > releaseAt) {
                browsing = false
                releaseAt = 0L
                launch { manual.animateTo(0f, lyricSpring()) }
            }
            delay(60)
        }
    }

    BoxWithConstraints(
        modifier.fillMaxSize().clipToBounds()
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                // Fade the top and bottom edges, like the iPhone mask.
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 0.1f to Color.Black, 0.85f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            }
            .pointerInput(lyrics) {
                detectVerticalDragGestures(
                    onDragStart = { browsing = true; releaseAt = 0L },
                    onVerticalDrag = { change, amount -> change.consume(); dragOffset += amount },
                    onDragEnd = {
                        scope.launch { manual.snapTo(manual.value + dragOffset); dragOffset = 0f }
                        releaseAt = System.currentTimeMillis() + 3000
                    },
                    onDragCancel = { dragOffset = 0f; releaseAt = System.currentTimeMillis() + 3000 },
                )
            },
    ) {
        val anchor = constraints.maxHeight * 0.3f
        val top = (0 until focus).sumOf { (heights[it] ?: 160) + spacing.toInt() }
        val scroll = anchor - top
        // Hidden until every line is measured, so the first visible frame
        // already has the current line in place.
        // As in Apple Music the lyrics appear once the artwork has moved: they are
        // placed at the current line while hidden, then fade in.
        val shown by animateFloatAsState(if (settled && heights.size >= lyrics.lines.size) 1f else 0f, tween(260), label = "shown")
        Column(
            Modifier.fillMaxWidth().wrapContentHeight(align = Alignment.Top, unbounded = true)
                .graphicsLayer { alpha = shown },
            verticalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            lyrics.lines.forEach { line ->
                val distance = line.id - focus
                val y = remember(line.id) { Animatable(scroll) }
                val placedFocus = remember(line.id) { intArrayOf(focus) }
                // Each line follows a new focus line with its own, slightly later
                // spring; anything else (layout, first placement) jumps into place.
                LaunchedEffect(scroll, settled) {
                    val moved = placedFocus[0] != focus
                    placedFocus[0] = focus
                    if (!settled || !moved) {
                        y.snapTo(scroll)
                        return@LaunchedEffect
                    }
                    delay((distance.coerceIn(0, 8) * 45).toLong())
                    y.animateTo(scroll, lyricSpring())
                }
                val active = interlude == null && (line.id == focus || (line.isBackground && line.id == focus + 1))
                Box(
                    Modifier.fillMaxWidth()
                        .onSizeChanged {
                            if (heights[line.id] != it.height) layoutChangedAt = System.currentTimeMillis()
                            heights[line.id] = it.height
                        }
                        .graphicsLayer { translationY = y.value + manual.value + dragOffset }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            graph.player.seek(line.start)
                            browsing = false
                            scope.launch { manual.animateTo(0f, lyricSpring()) }
                        },
                ) {
                    LyricLineView(
                        line, active, if (browsing) 0 else distance,
                        interlude?.takeIf { it.before == line.id }, script, showsTranslation, showsRoman,
                    )
                }
            }
            if (lyrics.credits.isNotEmpty()) {
                Text(lyrics.credits.joinToString("\n"), color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun LyricLineView(
    line: LyricLine, active: Boolean, distance: Int, interlude: Interlude?, script: LyricScript,
    showsTranslation: Boolean, showsRoman: Boolean,
) {
    val scale by animateFloatAsState(if (active) 1f else 0.97f, lyricSpring(), label = "scale")
    // No blur (it smears on Android); lines further than two away dim a little more.
    val dim by animateFloatAsState(if (active || abs(distance) <= 2) 1f else 0.75f, tween(400), label = "dim")
    val sideAlpha by animateFloatAsState(if (active) 0.65f else 0.28f, tween(400), label = "side")
    Column(
        Modifier.fillMaxWidth()
            .graphicsLayer {
                scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0.5f)
                alpha = dim * if (line.isBackground && !active) 0.7f else 1f
            }
            .semantics { contentDescription = ScriptConverter.convert(line.text, script) },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (interlude != null) InterludeDots(interlude)
        // Finished lines fade back to dim instead of snapping.
        Crossfade(active, animationSpec = tween(450), label = "line") { sung ->
            if (sung) ActiveWords(line, script) else WordFlow(line.words, Long.MIN_VALUE, script, line.isBackground, resting = true)
        }
        if (showsRoman && line.roman.isNotEmpty()) {
            Text(line.roman, color = Color.White.copy(alpha = sideAlpha), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        if (showsTranslation && line.translation.isNotEmpty()) {
            Text(ScriptConverter.convert(line.translation, script), color = Color.White.copy(alpha = sideAlpha), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ActiveWords(line: LyricLine, script: LyricScript) {
    val graph = LocalGraph.current
    val now by produceState(graph.player.positionMs) {
        while (true) withFrameMillis { value = graph.player.positionMs }
    }
    WordFlow(line.words, now, script, line.isBackground)
}

/** Words wrap like text; each is a dim layer under a bright layer revealed by a feathered sweep. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordFlow(words: List<LyricWord>, now: Long, script: LyricScript, background: Boolean, resting: Boolean = false) {
    val size = if (background) 22.sp else 32.sp
    val base = TextStyle(fontSize = size, fontWeight = FontWeight.Bold, lineHeight = if (background) 28.sp else 40.sp)
    FlowRow(verticalArrangement = Arrangement.spacedBy(if (background) 2.dp else 4.dp)) {
        words.forEach { word ->
            val text = ScriptConverter.convert(word.text, script)
            val progress = wordProgress(word, now)
            val held = !resting && word.end - word.start >= 1000 && progress > 0f && progress < 1f
            val swell = if (held) sin(PI * progress).toFloat() else 0f
            Box(
                Modifier.graphicsLayer {
                    // Sung words float up slightly; long held words swell.
                    translationY = -2.5f * density * ease(progress)
                    scaleX = 1f + 0.05f * swell
                    scaleY = 1f + 0.05f * swell
                    transformOrigin = TransformOrigin(0.5f, 1f)
                },
            ) {
                Text(text, style = base, color = Color.White.copy(alpha = 0.3f))
                if (progress > 0f) {
                    Text(
                        text,
                        style = base.copy(shadow = if (swell > 0f) Shadow(Color.White.copy(alpha = 0.55f * swell), blurRadius = 24f) else null),
                        color = Color.White,
                        modifier = Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
                            drawContent()
                            val feather = 0.3f
                            val edge = progress * (1 + feather)
                            drawRect(
                                Brush.horizontalGradient(
                                    (edge - feather).coerceIn(0f, 1f) to Color.Black,
                                    edge.coerceIn(0.0001f, 1f) to Color.Transparent,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                    )
                }
            }
        }
    }
}

private fun wordProgress(word: LyricWord, now: Long): Float = when {
    now >= word.end -> 1f
    now <= word.start -> 0f
    else -> (now - word.start).toFloat() / (word.end - word.start).coerceAtLeast(1)
}

private fun ease(t: Float): Float = 1f - (1f - t.coerceIn(0f, 1f)).pow(3)

/** Three dots that fill one after another across an instrumental gap and breathe while waiting. */
@Composable
private fun InterludeDots(interlude: Interlude) {
    val graph = LocalGraph.current
    val frame by produceState(0L) { while (true) withFrameMillis { value = it } }
    val progress = ((graph.player.positionMs - interlude.start).toFloat() / (interlude.end - interlude.start).coerceAtLeast(1)).coerceIn(0f, 1f)
    val breath = 1f + 0.08f * sin(frame / 1000.0 * 2.4).toFloat()
    val closing = if (progress > 0.94f) 1f - (progress - 0.94f) * 4f else 1f
    Row(
        Modifier.padding(vertical = 6.dp).graphicsLayer {
            scaleX = breath * closing; scaleY = breath * closing; transformOrigin = TransformOrigin(0f, 0.5f)
        },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(3) { index ->
            val fill = (progress * 3 - index).coerceIn(0f, 1f)
            Box(Modifier.size(13.dp).background(Color.White.copy(alpha = 0.3f + 0.7f * fill), CircleShape))
        }
    }
}
