package dev.shibasis.reaktor.blueprint.editor

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.shibasis.reaktor.blueprint.BlueprintEngine
import dev.shibasis.reaktor.blueprint.Bounds
import dev.shibasis.reaktor.blueprint.Link
import kotlin.math.max
import kotlin.math.min

fun GraphEditor.tone(change: Change): Color = when (change) {
    Change.Added -> ink.added
    Change.Changed -> ink.changed
    Change.Removed -> ink.removed
}

internal fun GraphEditor.drawOverlay(scope: DrawScope, zoom: Double) = with(scope) {
    val line = (1.5 / zoom.coerceAtLeast(.05)).toFloat()
    val dash = PathEffect.dashPathEffect(floatArrayOf(line * 5, line * 4))
    val shown = layout
    if (showChanges) {
        diff.wires.forEach { (id, change) ->
            val link = link(id) ?: return@forEach
            trace(link, tone(change).copy(alpha = .95f), line * 1.6f, if (change == Change.Removed) dash else null)
        }
        diff.nodes.forEach { (id, change) ->
            val card = shown.cards[id] ?: return@forEach
            val tone = tone(change)
            if (change == Change.Removed) drawRoundRect(tone.copy(alpha = .14f), card.origin, card.size, Radius)
            drawRoundRect(tone, card.origin - Offset(line, line), Size(card.width.toFloat() + 2 * line, card.height.toFloat() + 2 * line), Radius,
                style = Stroke(line * 1.4f, pathEffect = if (change == Change.Removed) dash else null))
            badge(Offset((card.x + card.width).toFloat(), card.y.toFloat()), change, tone, line)
        }
    }
    selection.wires.forEach { id -> link(id)?.let { trace(it, ink.accent, line * 2.2f, null) } }
    selection.nodes.forEach { id ->
        val card = shown.cards[id] ?: return@forEach
        drawRoundRect(ink.accent, card.origin - Offset(line * 2, line * 2), Size(card.width.toFloat() + 4 * line, card.height.toFloat() + 4 * line), Radius, style = Stroke(line * 1.6f))
    }
    shown.frames.filter { it.key in selection.notes }.forEach { frame ->
        drawRoundRect(ink.accent, frame.origin, frame.size, Radius, style = Stroke(line * 1.6f))
        drawCircle(ink.accent, line * 3, Offset((frame.x + frame.width).toFloat(), (frame.y + frame.height).toFloat()))
    }
    if (zoom > .25) placement.bends.values.flatten().forEach { spot -> drawCircle(ink.accent, (5 / zoom.coerceAtLeast(.5)).toFloat(), Offset(spot.x.toFloat(), spot.y.toFloat())) }
    when (val g = gesture) {
        is Gesture.Marquee -> {
            val left = min(g.from.x, g.to.x).toFloat(); val top = min(g.from.y, g.to.y).toFloat()
            val size = Size(max(g.from.x, g.to.x).toFloat() - left, max(g.from.y, g.to.y).toFloat() - top)
            drawRect(ink.accent.copy(alpha = .08f), Offset(left, top), size)
            drawRect(ink.accent.copy(alpha = .8f), Offset(left, top), size, style = Stroke(line))
        }
        is Gesture.Wiring -> {
            val tone = when (g.verdict?.ok) { true -> ink.added; false -> ink.refused; null -> ink.accent }
            val end = g.target?.let(::anchor) ?: (pointer.x to pointer.y)
            val starts = if (g.carried.size > 1) g.carried.mapNotNull { wire -> anchor(if (g.picked?.provides == true) PinEnd(wire.consumer, wire.consumerPort, false) else PinEnd(wire.provider, wire.providerPort, true)) }
                else listOfNotNull(anchor(g.pin))
            val outward = if (g.carried.size > 1) g.picked?.provides != true else g.pin.provides
            starts.forEach { start -> wire(if (outward) start else end, if (outward) end else start, tone, line * 1.8f) }
        }
        else -> {}
    }
}

private fun DrawScope.badge(at: Offset, change: Change, tone: Color, line: Float) {
    val r = line * 6
    drawCircle(tone, r, at)
    val arm = r * .5f
    val ink = Color.White
    drawLine(ink, at - Offset(arm, 0f), at + Offset(arm, 0f), line * 1.1f, StrokeCap.Round)
    if (change == Change.Added) drawLine(ink, at - Offset(0f, arm), at + Offset(0f, arm), line * 1.1f, StrokeCap.Round)
    if (change == Change.Changed) drawCircle(ink, line * .9f, at - Offset(0f, arm))
}

private fun DrawScope.trace(link: Link, color: Color, width: Float, effect: PathEffect?) {
    val points = link.trace
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points[0].first.toFloat(), points[0].second.toFloat())
        for (k in 1 until points.size) lineTo(points[k].first.toFloat(), points[k].second.toFloat())
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, pathEffect = effect))
}

private fun DrawScope.wire(from: Pair<Double, Double>, to: Pair<Double, Double>, color: Color, width: Float) {
    val c = BlueprintEngine.bezier(from, to)
    val path = Path().apply {
        moveTo(c[0].first.toFloat(), c[0].second.toFloat())
        cubicTo(c[1].first.toFloat(), c[1].second.toFloat(), c[2].first.toFloat(), c[2].second.toFloat(), c[3].first.toFloat(), c[3].second.toFloat())
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round))
    drawCircle(color, width * 2, Offset(to.first.toFloat(), to.second.toFloat()))
}

private val Bounds.origin get() = Offset(x.toFloat(), y.toFloat())
private val Bounds.size get() = Size(width.toFloat(), height.toFloat())
private val Radius = CornerRadius(8f, 8f)
