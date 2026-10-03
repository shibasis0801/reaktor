package dev.shibasis.reaktor.surface.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import kotlin.math.roundToInt

sealed interface OverlayAnchor {
    data class Bounds(val rect: Rect) : OverlayAnchor
    data class Point(val at: Offset) : OverlayAnchor
}

enum class Side { Below, Above, End, Start }

enum class Align { Start, Center, End }

data class Placement(val side: Side = Side.Below, val align: Align = Align.End, val gap: Dp = 6.dp)

fun place(anchor: IntRect, content: IntSize, canvas: IntSize, side: Side, align: Align, gap: Int, direction: LayoutDirection): IntOffset {
    val rightToLeft = direction == LayoutDirection.Rtl
    val vertical = side == Side.Below || side == Side.Above
    val after = when (side) {
        Side.Below -> true
        Side.Above -> false
        Side.End -> !rightToLeft
        Side.Start -> rightToLeft
    }
    val main = if (vertical) Span(anchor.top, anchor.bottom, content.height, canvas.height) else Span(anchor.left, anchor.right, content.width, canvas.width)
    val cross = if (vertical) Span(anchor.left, anchor.right, content.width, canvas.width) else Span(anchor.top, anchor.bottom, content.height, canvas.height)
    val toEnd = if (vertical && rightToLeft) align.mirrored() else align
    val along = main.beside(after, gap).takeIf(main::fits) ?: main.beside(!after, gap)
    val across = cross.aligned(toEnd).takeIf(cross::fits) ?: cross.aligned(toEnd.mirrored()).takeIf(cross::fits) ?: cross.aligned(toEnd)
    val x = (if (vertical) across else along).coerceIn(0, (canvas.width - content.width).coerceAtLeast(0))
    val y = (if (vertical) along else across).coerceIn(0, (canvas.height - content.height).coerceAtLeast(0))
    return IntOffset(x, y)
}

internal fun OverlayAnchor.window(): IntRect = when (this) {
    is OverlayAnchor.Bounds -> IntRect(rect.topLeft.round(), IntSize(rect.width.roundToInt(), rect.height.roundToInt()))
    is OverlayAnchor.Point -> IntRect(at.round(), IntSize.Zero)
}

private class Span(val start: Int, val end: Int, val size: Int, val limit: Int) {
    fun beside(after: Boolean, gap: Int): Int = if (after) end + gap else start - gap - size
    fun aligned(align: Align): Int = when (align) {
        Align.Start -> start
        Align.Center -> (start + end - size) / 2
        Align.End -> end - size
    }
    fun fits(position: Int): Boolean = position >= 0 && position + size <= limit
}

private fun Align.mirrored(): Align = when (this) {
    Align.Start -> Align.End
    Align.Center -> Align.Center
    Align.End -> Align.Start
}
