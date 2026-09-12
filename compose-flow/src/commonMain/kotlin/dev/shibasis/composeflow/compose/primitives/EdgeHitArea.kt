package dev.shibasis.composeflow.compose.primitives

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathMeasure
import dev.shibasis.composeflow.model.Edge
import dev.shibasis.composeflow.model.HandleType
import dev.shibasis.composeflow.model.Node
import kotlin.math.ceil
import kotlin.math.sqrt

internal fun findClosestEdge(
    tap: Offset,
    edges: List<Edge>,
    nodeById: Map<String, Node>,
    defaultNodeWidth: Double,
    defaultNodeHeight: Double,
    pathStyle: EdgePathStyle = EdgePathStyle.Bezier,
): Edge? {
    var closest: Edge? = null
    var closestDist = Float.MAX_VALUE

    for (edge in edges) {
        if (edge.hidden) continue
        val source = nodeById[edge.source] ?: continue
        val target = nodeById[edge.target] ?: continue
        val start = anchorFor(source, edge.sourceHandle, HandleType.Source, defaultNodeWidth, defaultNodeHeight)
        val end = anchorFor(target, edge.targetHandle, HandleType.Target, defaultNodeWidth, defaultNodeHeight)
        val threshold = edge.interactionWidth.toFloat()
        val path = flowEdgePath(start, end, pathStyle).path
        if (!path.getBounds().inflate(threshold).contains(tap)) continue
        val measure = PathMeasure().apply { setPath(path, false) }
        // Sample the same rendered path, bounded even for pathological distant endpoints.
        val segments = ceil(measure.length / (threshold / 2f).coerceAtLeast(1f)).toInt().coerceIn(1, 4096)
        var previous = start.point
        var dist = Float.MAX_VALUE
        for (index in 1..segments) {
            val next = measure.getPosition(measure.length * index / segments)
            dist = minOf(dist, distanceToSegment(tap, previous, next))
            previous = next
        }
        if (dist < threshold && dist < closestDist) {
            closest = edge
            closestDist = dist
        }
    }
    return closest
}

private fun distanceToSegment(point: Offset, a: Offset, b: Offset): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val lenSq = dx * dx + dy * dy
    if (lenSq < 0.001f) {
        val px = point.x - a.x
        val py = point.y - a.y
        return sqrt(px * px + py * py)
    }
    val t = ((point.x - a.x) * dx + (point.y - a.y) * dy) / lenSq
    val clamped = t.coerceIn(0f, 1f)
    val projX = a.x + clamped * dx
    val projY = a.y + clamped * dy
    val px = point.x - projX
    val py = point.y - projY
    return sqrt(px * px + py * py)
}
