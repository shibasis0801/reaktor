package dev.shibasis.reaktor.ui.machinesignal.surface

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import dev.shibasis.reaktor.surface.compose.ComposeFeedback

internal data class FocusRing(val color: Color, val width: Dp, val shape: Shape)

internal fun Modifier.focusRing(feedback: ComposeFeedback, ring: FocusRing): Modifier = drawWithContent {
    drawContent()
    val focus = feedback.focus
    if (focus > 0f) {
        val stroke = ring.width.toPx()
        inset(stroke / 2f) {
            drawOutline(ring.shape.createOutline(size, layoutDirection, this), ring.color, alpha = focus, style = Stroke(stroke))
        }
    }
}

internal fun Modifier.stateLayer(color: Color, hovered: Boolean, feedback: ComposeFeedback): Modifier = drawWithContent {
    drawContent()
    val alpha = (if (hovered) HoverLayer else 0f) + PressLayer * feedback.press
    if (alpha > 0f) drawRect(color.copy(alpha = alpha))
}

@Composable
internal fun ProvideLabel(color: Color, style: TextStyle, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalContentColor provides color) {
        ProvideTextStyle(style.merge(TextStyle(color = color)), content)
    }

private const val HoverLayer = 0.08f
private const val PressLayer = 0.1f
