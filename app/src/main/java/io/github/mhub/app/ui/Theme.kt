package io.github.mhub.app.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.mhub.app.Appearance
import io.github.neoruaa.glasmio.core.GlassAppearance
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.core.defaultGlassColors
import io.github.neoruaa.glasmio.core.glasmioColorScheme
import io.github.neoruaa.glasmio.icons.GlasmioIconProvider
import io.github.neoruaa.glasmio.icons.GlasmioIconResolver
import io.github.neoruaa.glasmio.icons.MaterialGlasmioIconResolver

/** Apple Music's accent pink, shared with the iOS asset catalog. */
val MHubAccent = Color(0xFFFA2D55)

/**
 * SF Symbol names map to Material icons (SF Pro is not redistributable). The
 * extra names cover the music controls Glasmio's own fallback does not know.
 */
private object MHubIconResolver : GlasmioIconResolver {
    override fun resolve(systemName: String): ImageVector = MHubIcons.resolve(systemName) ?: when (systemName) {
        "shuffle" -> Icons.Default.Shuffle
        "repeat" -> Icons.Default.Repeat
        "repeat.1" -> Icons.Default.RepeatOne
        "infinity" -> Icons.Default.AllInclusive
        "quote.bubble", "quote.bubble.fill" -> Icons.Default.Lyrics
        "airplay.audio", "hifispeaker" -> Icons.Default.SpeakerGroup
        "list.bullet", "list.bullet.fill" -> Icons.AutoMirrored.Filled.QueueMusic
        "text.line.first.and.arrowtriangle.forward" -> Icons.AutoMirrored.Filled.PlaylistPlay
        "text.line.last.and.arrowtriangle.forward", "text.badge.plus" -> Icons.AutoMirrored.Filled.PlaylistAdd
        "link" -> Icons.Default.Link
        "arrow.down.circle" -> Icons.Default.Download
        "square.stack.fill", "square.stack" -> Icons.Default.LibraryMusic
        else -> MaterialGlasmioIconResolver.resolve(systemName)
    }
}

@Composable
fun MHubTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        Appearance.System -> isSystemInDarkTheme()
        Appearance.Light -> false
        Appearance.Dark -> true
    }
    val scheme = glasmioColorScheme(dark, MHubAccent)
    GlasmioIconProvider(resolver = MHubIconResolver) {
        // Glass resolves light/dark from what is behind it, like iOS 26 Liquid Glass.
        GlasmioTheme(isDark = dark, colorScheme = scheme, colors = defaultGlassColors(dark, MHubAccent), appearance = GlassAppearance(adaptiveColorScheme = true)) {
            // iOS press highlight instead of Material ripples, app-wide.
            CompositionLocalProvider(LocalIndication provides IosHighlight, content = content)
        }
    }
}
