package dev.shibasis.reaktor.blueprint.capture

import dev.shibasis.reaktor.blueprint.editor.Spot
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object CaptureHit {
    enum class Handle { Body, NorthWest, NorthEast, SouthWest, SouthEast, Start, End }
    data class Result(val id: String, val handle: Handle)
    data class Bounds(val left: Double, val top: Double, val width: Double, val height: Double) {
        val right get() = left + width
        val bottom get() = top + height
        fun contains(at: Spot, tolerance: Double = 0.0) = at.x >= left - tolerance && at.x <= right + tolerance && at.y >= top - tolerance && at.y <= bottom + tolerance
    }
    data class Geometry(val origin: Spot, val bounds: Bounds, val points: List<Spot> = emptyList(), val angle: Double = 0.0) {
        val center get() = Spot(bounds.left + bounds.width / 2, bounds.top + bounds.height / 2)
        val extent: Bounds get() {
            if (angle == 0.0) return bounds
            val corners = listOf(Spot(bounds.left, bounds.top), Spot(bounds.right, bounds.top), Spot(bounds.left, bounds.bottom), Spot(bounds.right, bounds.bottom)).map { it.rotated(center, angle) }
            val left = corners.minOf { it.x }; val top = corners.minOf { it.y }
            return Bounds(left, top, corners.maxOf { it.x } - left, corners.maxOf { it.y } - top)
        }
    }

    fun find(captures: List<Capture>, at: Spot, zoom: Double, selected: String? = null, metrics: CaptureMetrics = CaptureMetrics(), resolve: (CaptureAnchor) -> Spot = CaptureAnchor::fallbackSpot): Result? {
        require(zoom.isFinite() && zoom > 0 && at.finite())
        val tolerance = metrics.hitTolerance / zoom
        captures.lastOrNull { it.id == selected }?.let { value ->
            handles(value, resolve, metrics).entries.firstOrNull { (_, point) -> hypot(at.x - point.x, at.y - point.y) <= max(tolerance, metrics.handleSize / zoom / 2) }
                ?.let { return Result(value.id, it.key) }
        }
        return captures.asReversed().firstOrNull { contains(it, at, tolerance, resolve, metrics) }?.let { Result(it.id, Handle.Body) }
    }

    fun handles(value: Capture, resolve: (CaptureAnchor) -> Spot = CaptureAnchor::fallbackSpot, metrics: CaptureMetrics = CaptureMetrics()): Map<Handle, Spot> {
        val geometry = geometry(value, resolve, metrics)
        if (value is Capture.Line || value is Capture.Arrow) return mapOf(Handle.Start to geometry.points.first(), Handle.End to geometry.points.last())
        if (value is Capture.Freehand || value is Capture.Text || value is Capture.Comment) return emptyMap()
        val b = geometry.bounds
        return mapOf(Handle.NorthWest to Spot(b.left, b.top), Handle.NorthEast to Spot(b.right, b.top), Handle.SouthWest to Spot(b.left, b.bottom), Handle.SouthEast to Spot(b.right, b.bottom))
            .mapValues { (_, spot) -> spot.rotated(geometry.center, geometry.angle) }
    }

    fun geometry(value: Capture, resolve: (CaptureAnchor) -> Spot = CaptureAnchor::fallbackSpot, metrics: CaptureMetrics = CaptureMetrics()): Geometry {
        val origin = resolve(value.anchor)
        val points = when (value) {
            is Capture.Line -> listOf(origin) + value.bends + resolve(value.end)
            is Capture.Arrow -> listOf(origin) + value.bends + resolve(value.end)
            is Capture.Freehand -> value.points.map { origin + it }
            else -> emptyList()
        }
        if (points.isNotEmpty()) {
            val left = points.minOf { it.x }; val top = points.minOf { it.y }
            return Geometry(origin, Bounds(left, top, points.maxOf { it.x } - left, points.maxOf { it.y } - top), points)
        }
        val width = when (value) {
            is Capture.Box -> value.width
            is Capture.Diamond -> value.width
            is Capture.Ellipse -> value.width
            is Capture.Frame -> value.width
            is Capture.Note -> value.width
            is Capture.Image -> value.width
            else -> metrics.textWidth
        }
        val height = when (value) {
            is Capture.Box -> value.height
            is Capture.Diamond -> value.height
            is Capture.Ellipse -> value.height
            is Capture.Frame -> value.height
            is Capture.Note -> value.height
            is Capture.Image -> value.height
            is Capture.Text -> metrics.textHeight(value.text)
            is Capture.Comment -> metrics.textHeight(value.text) + metrics.lineHeight
            else -> 0.0
        }
        val angle = when (value) { is Capture.Box -> value.angle; is Capture.Diamond -> value.angle; is Capture.Ellipse -> value.angle; else -> 0.0 }
        return Geometry(origin, Bounds(origin.x, origin.y, width, height), angle = angle)
    }

    private fun contains(value: Capture, at: Spot, tolerance: Double, resolve: (CaptureAnchor) -> Spot, metrics: CaptureMetrics): Boolean {
        val g = geometry(value, resolve, metrics)
        if (g.points.isNotEmpty()) return g.points.zipWithNext().any { (a, b) -> segmentDistance(at, a, b) <= tolerance }
        val local = at.rotated(g.center, -g.angle)
        val b = g.bounds
        if (!b.contains(local, tolerance)) return false
        val rx = b.width / 2; val ry = b.height / 2
        return when (value) {
            is Capture.Diamond -> rx > 0 && ry > 0 && abs(local.x - g.center.x) / (rx + tolerance) + abs(local.y - g.center.y) / (ry + tolerance) <= 1
            is Capture.Ellipse -> rx > 0 && ry > 0 && ((local.x - g.center.x) / (rx + tolerance)).let { it * it } + ((local.y - g.center.y) / (ry + tolerance)).let { it * it } <= 1
            is Capture.Frame -> local.y <= b.top + metrics.lineHeight || min(min(abs(local.x - b.left), abs(local.x - b.right)), abs(local.y - b.bottom)) <= tolerance
            else -> true
        }
    }

    private fun segmentDistance(p: Spot, a: Spot, b: Spot): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        val length = dx * dx + dy * dy
        val t = if (length == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / length).coerceIn(0.0, 1.0)
        return hypot(p.x - a.x - t * dx, p.y - a.y - t * dy)
    }
}
