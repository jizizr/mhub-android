package io.github.mhub.app.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.ui.CompactBottomControlSize
import io.github.neoruaa.glasmio.ui.NavigationBarSurfaceHeight
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI

/**
 * Geometry of the iOS tab-bar accessory. Regular: a 48 dp capsule 8 dp above
 * the tab bar. Inline (tab bar minimized): the same height, moved down into
 * the tab bar row between the compact tab and the search button, 14 dp from each.
 */
object Accessory {
    val Height = 48.dp
    val Margin = 20.dp
    val Gap = 8.dp
    val InlineMargin = Margin + (NavigationBarSurfaceHeight + CompactBottomControlSize) / 2 + 14.dp
    val InlineTravel = NavigationBarSurfaceHeight / 2 + Gap + Height / 2
}

/**
 * UIKit moves the accessory in two channels: collapsing first takes the new
 * width, then drops into the row; expanding first rises, then widens. The
 * second stage starts 50 ms later so both overlap, as measured on iOS.
 */
@Stable
class AccessoryMotion(compact: Boolean) {
    private val horizontal = Animatable(if (compact) 1f else 0f)
    private val vertical = Animatable(if (compact) 1f else 0f)
    val horizontalProgress: State<Float> get() = horizontal.asState()
    val verticalProgress: State<Float> get() = vertical.asState()

    private fun spring(damping: Float, response: Float) =
        spring<Float>(dampingRatio = damping, stiffness = (2 * PI / response).let { (it * it).toFloat() })

    suspend fun update(compact: Boolean) = coroutineScope {
        val target = if (compact) 1f else 0f
        if (compact) {
            launch { horizontal.animateTo(target, spring(0.72f, 0.36f)) }
            delay(50)
            launch { vertical.animateTo(target, spring(0.76f, 0.42f)) }
        } else {
            launch { vertical.animateTo(target, spring(0.72f, 0.30f)) }
            delay(50)
            launch { horizontal.animateTo(target, spring(0.72f, 0.36f)) }
        }
    }
}

@Composable
fun rememberAccessoryMotion(compact: Boolean): AccessoryMotion {
    val motion = remember { AccessoryMotion(compact) }
    LaunchedEffect(compact) { motion.update(compact) }
    return motion
}
