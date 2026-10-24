package dev.shibasis.reaktor.blueprint

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class LayoutMetrics(val crossings: Int, val backward: Int, val overCards: Int, val length: Double, val bends: Int, val width: Double, val height: Double, val curves: Int = 0, val routedCrossings: Int = crossings, val routedOverCards: Int = overCards) {
    override fun toString() = "crossings=$crossings backward=$backward overCards=$overCards length=${length.toInt()} bends=$bends size=${width.toInt()}x${height.toInt()}" +
        if (curves == 0) "" else " curves=$curves routedCrossings=$routedCrossings routedOverCards=$routedOverCards"
}

object LayoutSketch {
    class Box(val id: String, val x: Double, val y: Double, val width: Double, val height: Double, val label: String, val frame: Boolean = false)
    class Line(val from: String, val to: String, val points: List<Pair<Double, Double>>, val faint: Boolean = false, val reversed: Boolean = false, val curve: Boolean = false)

    fun of(layout: BlueprintLayout<*>, labels: Map<String, String> = emptyMap()): Pair<List<Box>, List<Line>> {
        val boxes = layout.frames.map { Box(it.key, it.x, it.y, it.width, it.height, it.label, frame = true) } +
            layout.cards.values.map { Box(it.id, it.x, it.y, it.width, it.height, labels[it.id] ?: it.id) }
        val lines = layout.links.map { Line(it.from, it.to, it.trace, faint = it.cross, reversed = it.reversed, curve = it.curve) }
        return boxes to lines
    }

    fun metrics(boxes: List<Box>, lines: List<Line>): LayoutMetrics {
        val segments = ArrayList<DoubleArray>()
        val owner = ArrayList<Int>()
        lines.forEachIndexed { i, line -> line.points.zipWithNext().forEach { (a, b) -> segments += doubleArrayOf(a.first, a.second, b.first, b.second); owner += i } }
        var crossings = 0
        var routedCrossings = 0
        for (i in segments.indices) for (j in i + 1 until segments.size) {
            if (owner[i] == owner[j]) continue
            val a = lines[owner[i]]; val b = lines[owner[j]]
            if (a.from == b.from || a.to == b.to || a.from == b.to || a.to == b.from) continue
            if (properly(segments[i], segments[j])) { crossings++; if (!a.curve && !b.curve) routedCrossings++ }
        }
        val cards = boxes.filter { !it.frame }
        var over = 0
        var routedOver = 0
        lines.forEach { line ->
            cards.forEach { card ->
                if (card.id == line.from || card.id == line.to) return@forEach
                if (line.points.zipWithNext().any { (a, b) -> touches(card, a, b) }) { over++; if (!line.curve) routedOver++ }
            }
        }
        val backward = lines.count { it.points.size >= 2 && (if (it.reversed) it.points.first().first < it.points.last().first - 1 else it.points.last().first < it.points.first().first - 1) }
        val length = lines.sumOf { line -> line.points.zipWithNext().sumOf { (a, b) -> hypot(b.first - a.first, b.second - a.second) } }
        val bends = lines.sumOf { (it.points.size - 2).coerceAtLeast(0) }
        val width = boxes.maxOfOrNull { it.x + it.width } ?: 0.0
        val height = boxes.maxOfOrNull { it.y + it.height } ?: 0.0
        return LayoutMetrics(crossings, backward, over, length, bends, width, height, lines.count { it.curve }, routedCrossings, routedOver)
    }

    private fun properly(s: DoubleArray, t: DoubleArray): Boolean {
        fun cross(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double) = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        val d1 = cross(t[0], t[1], t[2], t[3], s[0], s[1])
        val d2 = cross(t[0], t[1], t[2], t[3], s[2], s[3])
        val d3 = cross(s[0], s[1], s[2], s[3], t[0], t[1])
        val d4 = cross(s[0], s[1], s[2], s[3], t[2], t[3])
        return ((d1 > 1e-6 && d2 < -1e-6) || (d1 < -1e-6 && d2 > 1e-6)) && ((d3 > 1e-6 && d4 < -1e-6) || (d3 < -1e-6 && d4 > 1e-6))
    }

    private fun touches(box: Box, a: Pair<Double, Double>, b: Pair<Double, Double>): Boolean {
        val inset = 2.0
        val left = box.x + inset; val right = box.x + box.width - inset; val top = box.y + inset; val bottom = box.y + box.height - inset
        fun inside(p: Pair<Double, Double>) = p.first in left..right && p.second in top..bottom
        if (inside(a) || inside(b)) return true
        val edges = listOf(doubleArrayOf(left, top, right, top), doubleArrayOf(right, top, right, bottom), doubleArrayOf(right, bottom, left, bottom), doubleArrayOf(left, bottom, left, top))
        val s = doubleArrayOf(a.first, a.second, b.first, b.second)
        return edges.any { properly(s, it) }
    }

    fun draw(boxes: List<Box>, lines: List<Line>, file: File, title: String = "", limit: Int = 2600) {
        System.setProperty("java.awt.headless", "true")
        val pad = 60.0
        val minX = min(0.0, (boxes.map { it.x } + lines.flatMap { line -> line.points.map { it.first } }).minOrNull() ?: 0.0)
        val minY = min(0.0, (boxes.map { it.y } + lines.flatMap { line -> line.points.map { it.second } }).minOrNull() ?: 0.0)
        val maxX = (boxes.map { it.x + it.width } + lines.flatMap { line -> line.points.map { it.first } }).maxOrNull() ?: 1.0
        val maxY = (boxes.map { it.y + it.height } + lines.flatMap { line -> line.points.map { it.second } }).maxOrNull() ?: 1.0
        val w = maxX - minX + 2 * pad
        val h = maxY - minY + 2 * pad + 40
        val scale = min(1.0, limit / max(w, h))
        val image = BufferedImage((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0xF7, 0xF5, 0xEF)
        g.fillRect(0, 0, image.width, image.height)
        g.scale(scale, scale)
        g.color = Color(0x22, 0x22, 0x22)
        g.font = Font("SansSerif", Font.BOLD, 26)
        g.drawString(title, pad.toFloat(), 34f)
        g.translate(pad - minX, pad + 40 - minY)
        boxes.filter { it.frame }.sortedByDescending { it.width * it.height }.forEach { frame ->
            val shape = RoundRectangle2D.Double(frame.x, frame.y, frame.width, frame.height, 18.0, 18.0)
            g.color = Color(0xE8, 0xEC, 0xF4)
            g.fill(shape)
            g.color = Color(0x9A, 0xA8, 0xC4)
            g.stroke = BasicStroke(1.5f)
            g.draw(shape)
            g.color = Color(0x33, 0x44, 0x66)
            g.font = Font("SansSerif", Font.BOLD, 15)
            g.drawString(frame.label.take(48), (frame.x + 12).toFloat(), (frame.y + 24).toFloat())
        }
        lines.forEach { line -> stroke(g, line) }
        boxes.filter { !it.frame }.forEach { card ->
            val shape = RoundRectangle2D.Double(card.x, card.y, card.width, card.height, 10.0, 10.0)
            g.color = Color.WHITE
            g.fill(shape)
            g.color = Color(0x2E, 0x4A, 0x62)
            g.fill(RoundRectangle2D.Double(card.x, card.y, card.width, min(card.height, 26.0), 10.0, 10.0))
            g.stroke = BasicStroke(1.2f)
            g.draw(shape)
            g.color = Color.WHITE
            g.font = Font("SansSerif", Font.BOLD, 13)
            g.drawString(card.label.take(34), (card.x + 8).toFloat(), (card.y + 18).toFloat())
        }
        ImageIO.write(image, "png", file)
    }

    private fun stroke(g: Graphics2D, line: Line) {
        if (line.points.size < 2) return
        val path = Path2D.Double()
        path.moveTo(line.points[0].first, line.points[0].second)
        line.points.drop(1).forEach { path.lineTo(it.first, it.second) }
        g.color = if (line.faint) Color(0xC0, 0x6A, 0x2B, 170) else Color(0x3F, 0x63, 0x8A, 200)
        g.stroke = BasicStroke(1.6f)
        g.draw(path)
        val (end, before) = if (line.reversed) line.points[0] to line.points[1] else line.points.last() to line.points[line.points.size - 2]
        val angle = atan2(end.second - before.second, end.first - before.first)
        val arrow = Path2D.Double()
        arrow.moveTo(end.first, end.second)
        arrow.lineTo(end.first - 11 * cos(angle - 0.45), end.second - 11 * sin(angle - 0.45))
        arrow.lineTo(end.first - 11 * cos(angle + 0.45), end.second - 11 * sin(angle + 0.45))
        arrow.closePath()
        g.fill(arrow)
    }
}
