package io.github.mhub.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.launch

/**
 * iOS-style press feedback for every `clickable` in the app: a soft grey tint
 * that appears at once and fades out on release, instead of a Material ripple.
 */
object IosHighlight : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 7

    private class Node(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
        private val alpha = Animatable(0f)

        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> launch { alpha.snapTo(1f) }
                        is PressInteraction.Release, is PressInteraction.Cancel -> launch { alpha.animateTo(0f, tween(260)) }
                    }
                }
            }
        }

        override fun ContentDrawScope.draw() {
            val value = alpha.value
            // Icon-sized targets fade like iOS buttons; only list-sized rows get
            // the grey row highlight, so nothing draws a square over a glyph.
            val buttonLike = size.width <= 96.dp.toPx() && size.height <= 96.dp.toPx()
            if (buttonLike && value > 0f) {
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { this.alpha = 1f - 0.65f * value })
                    drawContent()
                    canvas.restore()
                }
                return
            }
            drawContent()
            if (value > 0f) drawRect(Color(0xFF8E8E93).copy(alpha = 0.18f * value))
        }
    }
}

/** Apple Music cards shrink a little while pressed and spring back. */
@Composable
fun Modifier.pressScale(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}
