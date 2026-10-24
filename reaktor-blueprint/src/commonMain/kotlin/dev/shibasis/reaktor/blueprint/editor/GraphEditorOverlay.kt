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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.shibasis.reaktor.blueprint.BlueprintEngine
import dev.shibasis.reaktor.blueprint.Bounds
import dev.shibasis.reaktor.blueprint.Link
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
            ring(card, tone, line, line * 1.4f, if (change == Change.Removed) dash else null)
            badge(Offset((card.x + card.width).toFloat(), card.y.toFloat()), change, tone, line)
        }
    }
    found.forEach { id -> (shown.cards[id] ?: shown.frames.firstOrNull { it.key == id })?.let { ring(it, ink.accent.copy(alpha = .6f), line * 4, line * 1.2f, dash) } }
    problems.forEach { problem ->
        val card = shown.cards[problem.subject]
        if (card != null) { ring(card, ink.refused, line * 3, line * 1.6f); alert(card.origin, ink.refused, line) }
        else link(problem.subject)?.let { trace(it, ink.refused, line * 2.2f, dash) }
    }
    warnings.forEach { warning ->
        if (warning.path.isNotEmpty()) { warning.path.forEach { id -> link(id)?.let { trace(it, ink.changed.copy(alpha = .9f), line * 2.4f, dash) } }; return@forEach }
        val (x, y) = warning.port?.let { anchor(PinEnd(warning.subject, it, false)) } ?: return@forEach
        drawCircle(ink.changed, line * 4.5f, Offset(x.toFloat(), y.toFloat()), style = Stroke(line * 1.4f))
    }
    val text = measurer
    converted.forEach { (id, how) ->
        val points = link(id)?.trace?.takeIf { it.size > 1 } ?: return@forEach
        val (x, y) = points[points.size / 2]
        val at = Offset(x.toFloat(), y.toFloat())
        diamond(at, line * 4.5f, ink.changed)
        if (text != null && zoom > .55) chip(text.say(how, font, ink.text, 10f, 220f, 1), at + Offset(line * 7, -line * 14), ink.surface, ink.changed, line)
    }
    trial?.let { trial ->
        val fade = if (trial.revision == revision) 1f else .45f
        trial.nodes.forEach { result ->
            val id = shape.visible(result.node) ?: result.node
            val card = shown.cards[id] ?: hiddenOf(id)?.let(shown.cards::get) ?: return@forEach
            verdictMark(Offset(card.x.toFloat(), card.y.toFloat()), result.ok, (if (result.ok) ink.added else ink.refused).copy(alpha = fade), line)
        }
    }
    if (text != null && zoom > .3) {
        placement.remarks.forEach { (id, note) -> shown.cards[id]?.let { card -> bubble(text.say(note, font, ink.text, 11f, card.width.toFloat() - 12f, 3), card, ink.surface, ink.accent, line) } }
        runs?.let { runs ->
            watched.forEach { pin ->
                val (x, y) = anchor(pin) ?: return@forEach
                val value = runs.port(pin.node, pin.key)?.last?.let(::redacted)?.replace('\n', ' ') ?: "no value yet"
                val label = text.say(value, font, ink.text, 10f, 240f, 1)
                chip(label, Offset(if (pin.provides) x.toFloat() + line * 8 else x.toFloat() - line * 8 - label.size.width - 12f, y.toFloat() - label.size.height / 2f - 4f), ink.surface, ink.added, line)
            }
        }
    }
    selection.wires.forEach { id -> link(id)?.let { trace(it, ink.accent, line * 2.2f, null) } }
    selection.nodes.forEach { id -> shown.cards[id]?.let { ring(it, ink.accent, line * 2, line * 1.6f) } }
    selection.notes.forEach { id -> shown.cards[id]?.let { ring(it, ink.accent, line * 2, line * 1.6f) } }
    runs?.failures?.forEach { failure -> (shown.cards[failure.node] ?: shape.visible(failure.node)?.let(shown.cards::get))?.let { alert(Offset((it.x + it.width).toFloat(), (it.y + it.height).toFloat()), ink.refused, line) } }
    shown.frames.filter { it.key in selection.notes }.forEach { frame ->
        ring(frame, ink.accent, 0f, line * 1.6f)
        drawCircle(ink.accent, line * 3, Offset((frame.x + frame.width).toFloat(), (frame.y + frame.height).toFloat()))
    }
    if (zoom > .25) placement.bends.values.flatten().groupingBy { it }.eachCount().forEach { (spot, wires) ->
        val radius = (5 / zoom.coerceAtLeast(.5)).toFloat()
        drawCircle(ink.accent, if (wires > 1) radius * 1.5f else radius, Offset(spot.x.toFloat(), spot.y.toFloat()))
        if (wires > 1) drawCircle(ink.text, radius * 2.2f, Offset(spot.x.toFloat(), spot.y.toFloat()), style = Stroke(line))
    }
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

private fun TextMeasurer.say(text: String, font: FontFamily?, color: Color, size: Float, width: Float, lines: Int): TextLayoutResult =
    measure(text, style = TextStyle(color = color, fontSize = size.sp, fontFamily = font), overflow = TextOverflow.Ellipsis, maxLines = lines,
        constraints = Constraints(maxWidth = width.roundToInt().coerceAtLeast(1)), density = Density(1f))

private fun DrawScope.chip(text: TextLayoutResult, at: Offset, back: Color, edge: Color, line: Float) {
    val size = Size(text.size.width + 12f, text.size.height + 8f)
    drawRoundRect(back, at, size, CornerRadius(5f, 5f))
    drawRoundRect(edge, at, size, CornerRadius(5f, 5f), style = Stroke(line))
    drawText(text, topLeft = at + Offset(6f, 4f))
}

private fun DrawScope.bubble(text: TextLayoutResult, card: Bounds, back: Color, edge: Color, line: Float) {
    val size = Size(text.size.width + 16f, text.size.height + 12f)
    val at = Offset(card.x.toFloat(), card.y.toFloat() - size.height - 10f)
    drawRoundRect(back, at, size, CornerRadius(7f, 7f))
    drawRoundRect(edge, at, size, CornerRadius(7f, 7f), style = Stroke(line))
    drawPath(Path().apply { moveTo(at.x + 16f, at.y + size.height); lineTo(at.x + 22f, at.y + size.height + 8f); lineTo(at.x + 28f, at.y + size.height); close() }, back)
    drawText(text, topLeft = at + Offset(8f, 6f))
}

private fun DrawScope.diamond(at: Offset, r: Float, color: Color) =
    drawPath(Path().apply { moveTo(at.x, at.y - r); lineTo(at.x + r, at.y); lineTo(at.x, at.y + r); lineTo(at.x - r, at.y); close() }, color)

private fun DrawScope.verdictMark(at: Offset, ok: Boolean, tone: Color, line: Float) {
    val r = line * 7
    drawCircle(tone, r, at)
    val arm = r * .45f
    if (ok) {
        drawLine(Color.White, at + Offset(-arm, 0f), at + Offset(-arm * .2f, arm * .7f), line * 1.3f, StrokeCap.Round)
        drawLine(Color.White, at + Offset(-arm * .2f, arm * .7f), at + Offset(arm, -arm * .6f), line * 1.3f, StrokeCap.Round)
    } else {
        drawLine(Color.White, at + Offset(-arm, -arm), at + Offset(arm, arm), line * 1.3f, StrokeCap.Round)
        drawLine(Color.White, at + Offset(arm, -arm), at + Offset(-arm, arm), line * 1.3f, StrokeCap.Round)
    }
}

private fun DrawScope.ring(box: Bounds, color: Color, gap: Float, width: Float, effect: PathEffect? = null) =
    drawRoundRect(color, box.origin - Offset(gap, gap), Size(box.width.toFloat() + 2 * gap, box.height.toFloat() + 2 * gap), Radius, style = Stroke(width, pathEffect = effect))

private fun DrawScope.alert(at: Offset, tone: Color, line: Float) {
    val r = line * 6
    drawCircle(tone, r, at)
    drawLine(Color.White, at - Offset(0f, r * .55f), at + Offset(0f, r * .1f), line * 1.1f, StrokeCap.Round)
    drawCircle(Color.White, line * .7f, at + Offset(0f, r * .45f))
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
