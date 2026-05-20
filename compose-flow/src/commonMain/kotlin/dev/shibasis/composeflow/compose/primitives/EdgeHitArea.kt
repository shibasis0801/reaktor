package dev.shibasis.composeflow.compose.primitives

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import dev.shibasis.composeflow.model.Edge
import dev.shibasis.composeflow.model.HandleType
import dev.shibasis.composeflow.model.Node
import kotlin.math.abs
import kotlin.math.sqrt

@Composable
internal fun EdgeHitAreaOverlay(
    edges: List<Edge>,
    nodeById: Map<String, Node>,
    defaultNodeWidth: Double,
    defaultNodeHeight: Double,
    onEdgeClick: ((Edge) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (onEdgeClick == null) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(edges, nodeById) {
                detectTapGestures { tapOffset ->
                    val hit = findClosestEdge(
                        tapOffset, edges, nodeById, defaultNodeWidth, defaultNodeHeight,
                    )
                    if (hit != null) {
                        onEdgeClick(hit)
                    }
                }
            },
    )
}

private fun findClosestEdge(
    tap: Offset,
    edges: List<Edge>,
    nodeById: Map<String, Node>,
    defaultNodeWidth: Double,
    defaultNodeHeight: Double,
): Edge? {
    var closest: Edge? = null
    var closestDist = Float.MAX_VALUE

    for (edge in edges) {
        if (edge.hidden) continue
        val source = nodeById[edge.source] ?: continue
        val target = nodeById[edge.target] ?: continue
        val start = anchorFor(source, edge.sourceHandle, HandleType.Source, defaultNodeWidth, defaultNodeHeight)
        val end = anchorFor(target, edge.targetHandle, HandleType.Target, defaultNodeWidth, defaultNodeHeight)
        val dist = distanceToSegment(tap, start.point, end.point)
        val threshold = edge.interactionWidth.toFloat()
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
