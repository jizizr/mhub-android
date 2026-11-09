package io.github.mhub.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Original 24×24 glyphs drawn in the iOS style the iPhone app shows: rounded
 * play/pause, double-triangle skip, speech-bubble lyrics, AirPlay-style
 * output and bullet list. SF Symbols are not redistributable, so these keep
 * both apps visually close without shipping Apple's font. Keys are the SF
 * Symbol names used throughout the UI.
 */
internal object MHubIcons {
    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.fill(path: String, evenOdd: Boolean = false) =
        addPath(addPathNodes(path), fill = SolidColor(Color.Black), pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero)

    private fun ImageVector.Builder.stroke(path: String, width: Float = 2f) =
        addPath(addPathNodes(path), stroke = SolidColor(Color.Black), strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)

    private const val LEFT_TRIANGLE = "C11.5,5.5 10.8,5.1 10.2,5.5 L2.3,11.2 C1.7,11.6 1.7,12.4 2.3,12.8 L10.2,18.5 C10.8,18.9 11.5,18.5 11.5,17.8 Z"

    private val glyphs: Map<String, ImageVector> by lazy {
        mapOf(
            "play.fill" to icon("play") {
                fill("M7.5,5.4 C7.5,4.4 8.6,3.8 9.4,4.3 L19.4,10.8 C20.2,11.3 20.2,12.7 19.4,13.2 L9.4,19.7 C8.6,20.2 7.5,19.6 7.5,18.6 Z")
            },
            "pause.fill" to icon("pause") {
                fill("M6.2,4.5 h3.2 a1.2,1.2 0 0 1 1.2,1.2 v12.6 a1.2,1.2 0 0 1 -1.2,1.2 h-3.2 a1.2,1.2 0 0 1 -1.2,-1.2 v-12.6 a1.2,1.2 0 0 1 1.2,-1.2 Z")
                fill("M14.6,4.5 h3.2 a1.2,1.2 0 0 1 1.2,1.2 v12.6 a1.2,1.2 0 0 1 -1.2,1.2 h-3.2 a1.2,1.2 0 0 1 -1.2,-1.2 v-12.6 a1.2,1.2 0 0 1 1.2,-1.2 Z")
            },
            "backward.fill" to icon("backward") {
                fill("M11.5,6.2 $LEFT_TRIANGLE")
                fill("M21.5,6.2 C21.5,5.5 20.8,5.1 20.2,5.5 L12.3,11.2 C11.7,11.6 11.7,12.4 12.3,12.8 L20.2,18.5 C20.8,18.9 21.5,18.5 21.5,17.8 Z")
            },
            "forward.fill" to icon("forward") {
                fill("M2.5,6.2 C2.5,5.5 3.2,5.1 3.8,5.5 L11.7,11.2 C12.3,11.6 12.3,12.4 11.7,12.8 L3.8,18.5 C3.2,18.9 2.5,18.5 2.5,17.8 Z")
                fill("M12.5,6.2 C12.5,5.5 13.2,5.1 13.8,5.5 L21.7,11.2 C22.3,11.6 22.3,12.4 21.7,12.8 L13.8,18.5 C13.2,18.9 12.5,18.5 12.5,17.8 Z")
            },
            "ellipsis" to icon("ellipsis") {
                fill("M5,10.3 a1.7,1.7 0 1 1 0,3.4 a1.7,1.7 0 1 1 0,-3.4 Z M12,10.3 a1.7,1.7 0 1 1 0,3.4 a1.7,1.7 0 1 1 0,-3.4 Z M19,10.3 a1.7,1.7 0 1 1 0,3.4 a1.7,1.7 0 1 1 0,-3.4 Z")
            },
            "house.fill" to icon("house") {
                fill("M11.1,3.3 C11.6,2.9 12.4,2.9 12.9,3.3 L20.6,9.8 C20.9,10.1 21,10.4 21,10.8 V19.6 C21,20.4 20.4,21 19.6,21 H15.2 V15.4 C15.2,14.8 14.8,14.4 14.2,14.4 H9.8 C9.2,14.4 8.8,14.8 8.8,15.4 V21 H4.4 C3.6,21 3,20.4 3,19.6 V10.8 C3,10.4 3.1,10.1 3.4,9.8 Z")
            },
            "square.stack.fill" to icon("library") {
                fill("M6,8 h12 a3,3 0 0 1 3,3 v7 a3,3 0 0 1 -3,3 h-12 a3,3 0 0 1 -3,-3 v-7 a3,3 0 0 1 3,-3 Z")
                fill("M6,5 h12 a0.8,0.8 0 0 1 0,1.6 h-12 a0.8,0.8 0 0 1 0,-1.6 Z")
                fill("M8,2.4 h8 a0.7,0.7 0 0 1 0,1.4 h-8 a0.7,0.7 0 0 1 0,-1.4 Z")
            },
            "magnifyingglass" to icon("search") {
                stroke("M10.5,4 a6.5,6.5 0 1 1 0,13 a6.5,6.5 0 1 1 0,-13 Z", 2.3f)
                stroke("M15.4,15.4 L20.5,20.5", 2.6f)
            },
            "quote.bubble" to icon("lyrics") {
                stroke("M6.5,3.8 h11 a4,4 0 0 1 4,4 v5.4 a4,4 0 0 1 -4,4 h-6.8 l-4.4,3.6 v-3.6 h0.2 a4,4 0 0 1 -4,-4 v-5.4 a4,4 0 0 1 4,-4 Z", 1.8f)
                fill("M9,8.4 a1.6,1.6 0 1 1 0,3.2 c0,1 -0.5,1.8 -1.4,2.3 l-0.3,-0.6 c0.4,-0.3 0.6,-0.8 0.6,-1.3 a1.6,1.6 0 0 1 1.1,-3.6 Z")
                fill("M15,8.4 a1.6,1.6 0 1 1 0,3.2 c0,1 -0.5,1.8 -1.4,2.3 l-0.3,-0.6 c0.4,-0.3 0.6,-0.8 0.6,-1.3 a1.6,1.6 0 0 1 1.1,-3.6 Z")
            },
            "quote.bubble.fill" to icon("lyrics.fill") {
                fill(
                    "M6.5,3 h11 a4.8,4.8 0 0 1 4.8,4.8 v5.4 a4.8,4.8 0 0 1 -4.8,4.8 h-6.6 l-4.3,3.5 c-0.5,0.4 -1.2,0 -1.2,-0.6 v-2.9 a4.8,4.8 0 0 1 -3.7,-4.8 v-5.4 a4.8,4.8 0 0 1 4.8,-4.8 Z " +
                        "M9,8.4 a1.6,1.6 0 1 0 0,3.2 c0,0.5 -0.2,1 -0.6,1.3 l0.3,0.6 c0.9,-0.5 1.4,-1.3 1.4,-2.3 a1.6,1.6 0 0 0 -1.1,-2.8 Z " +
                        "M15,8.4 a1.6,1.6 0 1 0 0,3.2 c0,0.5 -0.2,1 -0.6,1.3 l0.3,0.6 c0.9,-0.5 1.4,-1.3 1.4,-2.3 a1.6,1.6 0 0 0 -1.1,-2.8 Z",
                    evenOdd = true,
                )
            },
            "airplay.audio" to icon("output") {
                stroke("M5.6,16.4 A8.5,8.5 0 1 1 18.4,16.4", 1.8f)
                stroke("M8.3,13.9 A4.8,4.8 0 1 1 15.7,13.9", 1.8f)
                fill("M12,9.6 a1.6,1.6 0 1 1 0,3.2 a1.6,1.6 0 1 1 0,-3.2 Z")
                fill("M11.2,15.3 C11.6,14.8 12.4,14.8 12.8,15.3 L17,20.3 C17.4,20.9 17,21.6 16.3,21.6 H7.7 C7,21.6 6.6,20.9 7,20.3 Z")
            },
            "list.bullet" to icon("queue") {
                fill("M4.2,4.9 a1.3,1.3 0 1 1 0,2.6 a1.3,1.3 0 1 1 0,-2.6 Z M4.2,10.7 a1.3,1.3 0 1 1 0,2.6 a1.3,1.3 0 1 1 0,-2.6 Z M4.2,16.5 a1.3,1.3 0 1 1 0,2.6 a1.3,1.3 0 1 1 0,-2.6 Z")
                stroke("M8.8,6.2 H20.5 M8.8,12 H20.5 M8.8,17.8 H20.5", 1.9f)
            },
            "speaker.fill" to icon("speaker") {
                fill("M3.5,9 H7 L12.2,4.6 C12.8,4.1 13.6,4.5 13.6,5.3 V18.7 C13.6,19.5 12.8,19.9 12.2,19.4 L7,15 H3.5 C2.9,15 2.5,14.6 2.5,14 V10 C2.5,9.4 2.9,9 3.5,9 Z")
            },
            "speaker.wave.3.fill" to icon("speaker.loud") {
                fill("M2.6,9.4 H5.4 L9.6,5.8 C10.1,5.4 10.8,5.7 10.8,6.4 V17.6 C10.8,18.3 10.1,18.6 9.6,18.2 L5.4,14.6 H2.6 C2.1,14.6 1.8,14.3 1.8,13.8 V10.2 C1.8,9.7 2.1,9.4 2.6,9.4 Z")
                stroke("M13.6,9.5 a3.6,3.6 0 0 1 0,5", 1.6f)
                stroke("M16.4,7.2 a6.8,6.8 0 0 1 0,9.6", 1.6f)
                stroke("M19.2,4.9 a10,10 0 0 1 0,14.2", 1.6f)
            },
            "person.crop.circle" to icon("account") {
                stroke("M12,2.8 a9.2,9.2 0 1 1 0,18.4 a9.2,9.2 0 1 1 0,-18.4 Z", 1.8f)
                fill("M12,6.4 a3.4,3.4 0 1 1 0,6.8 a3.4,3.4 0 1 1 0,-6.8 Z")
                fill("M6.2,17.8 C7.4,15.8 9.5,14.7 12,14.7 C14.5,14.7 16.6,15.8 17.8,17.8 C16.3,19.4 14.3,20.3 12,20.3 C9.7,20.3 7.7,19.4 6.2,17.8 Z")
            },
            "arrow.clockwise" to icon("refresh") {
                stroke("M19.2,12.4 A7.2,7.2 0 1 1 15.2,5.8", 2f)
                fill("M14.2,2.6 L19.4,5.4 C19.8,5.6 19.8,6.2 19.4,6.4 L14.4,9.4 C13.9,9.7 13.4,9.3 13.5,8.8 L14.2,2.6 Z")
            },
            "plus" to icon("plus") { stroke("M12,4.5 V19.5 M4.5,12 H19.5", 2.2f) },
            "chevron.left" to icon("back") { stroke("M15,4.5 L7.5,12 L15,19.5", 2.5f) },
            "chevron.right" to icon("disclosure") { stroke("M9,4.5 L16.5,12 L9,19.5", 2.2f) },
            "arrow.down.circle" to icon("download") {
                stroke("M12,2.8 a9.2,9.2 0 1 1 0,18.4 a9.2,9.2 0 1 1 0,-18.4 Z", 1.8f)
                stroke("M12,7.2 V16.2 M8.3,12.6 L12,16.3 L15.7,12.6", 1.9f)
            },
                        "waveform" to icon("waveform") {
                stroke("M3.5,10.5 V13.5 M7,7.5 V16.5 M10.5,4 V20 M14,8.5 V15.5 M17.5,6 V18 M21,10 V14", 2f)
            },
            "music.note" to icon("note") {
                fill("M9.5,5.6 C9.5,5 9.9,4.5 10.5,4.4 L18.3,2.9 C18.9,2.8 19.5,3.3 19.5,3.9 V6.8 C19.5,7.3 19.2,7.7 18.7,7.8 L11.8,9.1 V17.4 C11.8,19.6 10,21.4 7.8,21.4 C6,21.4 4.6,20.3 4.6,18.8 C4.6,17.1 6.2,15.8 8.2,15.8 C8.7,15.8 9.1,15.9 9.5,16 Z")
            },
        )
    }

    fun resolve(systemName: String): ImageVector? = glyphs[systemName] ?: when (systemName) {
        "house" -> glyphs["house.fill"]
        "music.note.square.stack.fill", "square.stack" -> glyphs["square.stack.fill"]
        "list.bullet.fill" -> glyphs["list.bullet"]
        "hifispeaker", "airplayaudio" -> glyphs["airplay.audio"]
        "person.fill" -> glyphs["person.crop.circle"]
        "chevron.backward" -> glyphs["chevron.left"]
        "chevron.forward" -> glyphs["chevron.right"]
        else -> null
    }
}
