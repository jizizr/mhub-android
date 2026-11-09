package io.github.mhub.app.ui.player

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.github.mhub.core.Links
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.capsule.ContinuousRoundedRectangle
import io.github.mhub.app.R
import io.github.mhub.app.ui.LocalGraph
import io.github.mhub.app.ui.components.Artwork
import io.github.mhub.core.LyricLine
import io.github.mhub.core.LyricScript
import io.github.mhub.core.Track
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioButtonSize
import io.github.neoruaa.glasmio.ui.GlasmioSheetTopToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_LINES = 5
private val PREVIEW_HEIGHT = 260.dp

/**
 * Pick up to five lines and share them as an image card, like Apple Music
 * (docs/design.md「歌词分享」). [initial] preselects a long-pressed line.
 */
@Composable
fun LyricsShareSheet(track: Track, initial: Int?, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by graph.player.state.collectAsStateWithLifecycle()
    val script by graph.settings.lyricScript.state.collectAsStateWithLifecycle()
    val translation by graph.settings.showTranslation.state.collectAsStateWithLifecycle()
    val lines = remember(state.lyrics) { state.lyrics.lines.filter { !it.isBackground && it.text.isNotBlank() } }
    var selected by remember(lines) { mutableStateOf(listOfNotNull(initial?.takeIf { id -> lines.any { it.id == id } }).toSet()) }
    val palette = rememberPalette(track.cover)
    val layer = rememberGraphicsLayer()
    val title = stringResource(R.string.share_lyrics)
    val colors = LocalGlassColors.current

    GlasmioBottomSheet(onDismissRequest = onDismiss, skipPartiallyExpanded = true, title = title, topBar = { GlasmioSheetTopToolbar(title = title) }) {
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 64.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val chosen = lines.filter { it.id in selected }
            // A fixed-height preview: the card keeps its natural size (that is what
            // gets shared) and is scaled down to fit, so choosing more lines never
            // pushes the list below.
            val previewHeight = with(LocalDensity.current) { PREVIEW_HEIGHT.toPx() }
            var cardHeight by remember { mutableIntStateOf(0) }
            val link = remember(track) { graph.settings.serverUrl?.let { Links.shareUrl(track, it) } }
            Box(Modifier.fillMaxWidth().height(PREVIEW_HEIGHT), contentAlignment = Alignment.TopCenter) {
                Box(
                    Modifier.fillMaxWidth()
                        .wrapContentHeight(Alignment.Top, unbounded = true)
                        .onSizeChanged { cardHeight = it.height }
                        .graphicsLayer {
                            val scale = if (cardHeight > previewHeight) previewHeight / cardHeight else 1f
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0.5f, 0f)
                        }
                        .drawWithContent {
                            // Recorded inside the scaling layer, so the shared image is full size.
                            layer.record { this@drawWithContent.drawContent() }
                            drawLayer(layer)
                        },
                ) {
                    LyricsCard(track, chosen, script, translation, palette, link)
                }
            }
            GlasmioButton(
                onClick = {
                    scope.launch {
                        val file = withContext(Dispatchers.IO) { writeImage(context, layer.toImageBitmap().asAndroidBitmap()) }
                        share(context, file, title)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                size = GlasmioButtonSize.Large,
                emphasis = io.github.neoruaa.glasmio.glass.GlassEmphasis.Prominent,
                enabled = chosen.isNotEmpty(),
            ) { Text(stringResource(R.string.share), fontWeight = FontWeight.SemiBold) }
            Text(stringResource(R.string.share_lyrics_hint, MAX_LINES), style = IosTypography.footnote, color = colors.secondaryContent)
            // Open at the long-pressed line, one line of context above it.
            val listState = androidx.compose.foundation.lazy.rememberLazyListState(
                initialFirstVisibleItemIndex = (lines.indexOfFirst { it.id == initial }).coerceAtLeast(1) - 1,
            )
            LazyColumn(Modifier.fillMaxWidth().height(320.dp), state = listState) {
                items(lines, key = { it.id }) { line ->
                    val on = line.id in selected
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            selected = when {
                                on -> selected - line.id
                                selected.size < MAX_LINES -> selected + line.id
                                else -> selected
                            }
                        }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SfIcon(if (on) "checkmark.circle.fill" else "circle", null, tint = if (on) colors.accent else colors.tertiaryContent, size = 22.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(ScriptConverter.convert(line.text, script), style = IosTypography.body, color = colors.content)
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsCard(track: Track, lines: List<LyricLine>, script: LyricScript, translation: Boolean, palette: List<Color>, link: String?) {
    val background = palette.ifEmpty { listOf(Color(0xFF3A3A3C), Color(0xFF1C1C1E)) }
    Column(
        Modifier.fillMaxWidth().clip(ContinuousRoundedRectangle(24.dp))
            .background(Brush.linearGradient(if (background.size > 1) background.take(2) else listOf(background[0], background[0].copy(alpha = 0.7f))))
            .background(Color.Black.copy(alpha = 0.18f))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(track.cover, Modifier.size(52.dp), corner = 8.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(track.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(4.dp))
        if (lines.isEmpty()) {
            Text(stringResource(R.string.share_lyrics_empty), color = Color.White.copy(alpha = 0.6f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        lines.forEach { line ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ScriptConverter.convert(line.text, script), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 31.sp)
                if (translation && line.translation.isNotEmpty()) {
                    Text(ScriptConverter.convert(line.translation, script), color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("MHub", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            // The song's web link, so whoever sees the card can play it.
            val qr = remember(link) { link?.let(::qrCode) }
            if (qr != null) {
                Image(
                    qr, null,
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.size(64.dp).clip(ContinuousRoundedRectangle(8.dp)).background(Color.White).padding(5.dp),
                )
            }
        }
    }
}

/** A black-on-white QR code of [text], one pixel per module plus a quiet zone. */
private fun qrCode(text: String): ImageBitmap? = runCatching {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix.get(index % matrix.width, index / matrix.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()

/** At least 1080 px wide, as a PNG in the cache's share folder. */
private fun writeImage(context: Context, captured: Bitmap): File {
    // The layer comes back as a hardware bitmap; compressing needs a software copy.
    val bitmap = captured.copy(Bitmap.Config.ARGB_8888, false)
    val image = if (bitmap.width >= 1080) bitmap else Bitmap.createScaledBitmap(bitmap, 1080, bitmap.height * 1080 / bitmap.width, true)
    val folder = File(context.cacheDir, "shared").apply { mkdirs() }
    folder.listFiles()?.forEach { it.delete() }
    return File(folder, "lyrics-${System.currentTimeMillis()}.png").apply { outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
}

private fun share(context: Context, file: File, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, title))
}
