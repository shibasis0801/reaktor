package dev.shibasis.reaktor.ui.machinesignal.surface

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import dev.shibasis.reaktor.surface.compose.ComposeFeedback

internal data class FocusRing(val color: Color, val width: Dp, val shape: Shape)

internal data class Label(
    val color: Color,
    val family: FontFamily?,
    val size: TextUnit,
    val weight: FontWeight? = null,
    val lineHeight: TextUnit = TextUnit.Unspecified,
)

internal fun Modifier.focusRing(shown: Boolean, feedback: ComposeFeedback, ring: FocusRing): Modifier =
    if (!shown) this else drawWithContent {
        drawContent()
        val stroke = ring.width.toPx()
        inset(stroke / 2f) {
            drawOutline(ring.shape.createOutline(size, layoutDirection, this), ring.color, alpha = feedback.focus, style = Stroke(stroke))
        }
    }

internal fun Modifier.stateLayer(color: Color, hovered: Boolean, feedback: ComposeFeedback): Modifier = drawWithContent {
    drawContent()
    val alpha = (if (hovered) HoverLayer else 0f) + PressLayer * feedback.press
    if (alpha > 0f) drawRect(color.copy(alpha = alpha))
}

@Composable
internal fun ProvideLabel(label: Label, content: @Composable () -> Unit) = CompositionLocalProvider(
    LocalContentColor provides label.color,
    LocalTextStyle provides LocalTextStyle.current.merge(
        color = label.color,
        fontSize = label.size,
        fontWeight = label.weight,
        fontFamily = label.family,
        lineHeight = label.lineHeight,
    ),
    content = content,
)

@Composable
internal fun ProvideLabel(color: Color, style: TextStyle, content: @Composable () -> Unit) = CompositionLocalProvider(
    LocalContentColor provides color,
    LocalTextStyle provides LocalTextStyle.current.merge(style).merge(color = color),
    content = content,
)

private const val HoverLayer = 0.08f
private const val PressLayer = 0.1f
