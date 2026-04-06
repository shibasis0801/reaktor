package dev.shibasis.composeflow.runtime

import androidx.compose.ui.geometry.Rect
import dev.shibasis.composeflow.model.FitViewOptions
import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.Viewport
import kotlin.math.max
import kotlin.math.min

// This mirrors the bounds -> viewport calculation used in React Flow / xyflow's fitView helpers:
// keep the math in a stable pixel-like graph space, then let the renderer project it to the canvas.
internal fun fitViewport(
    nodes: List<Node>,
    viewportWidth: Double,
    viewportHeight: Double,
    defaultNodeWidth: Double,
    defaultNodeHeight: Double,
    options: FitViewOptions,
): Viewport {
    val bounds = graphBounds(nodes, defaultNodeWidth, defaultNodeHeight)
    val contentWidth = max(bounds.width.toDouble(), 1.0)
    val contentHeight = max(bounds.height.toDouble(), 1.0)
    val zoom = min(
        (viewportWidth * (1.0 - options.padding * 2.0)) / contentWidth,
        (viewportHeight * (1.0 - options.padding * 2.0)) / contentHeight,
    ).coerceIn(options.minZoom, options.maxZoom)

    val x = (viewportWidth - contentWidth * zoom) / 2.0 - bounds.left * zoom
    val y = (viewportHeight - contentHeight * zoom) / 2.0 - bounds.top * zoom
    return Viewport(x = x, y = y, zoom = zoom)
}

internal fun graphBounds(
    nodes: List<Node>,
    defaultNodeWidth: Double,
    defaultNodeHeight: Double,
): Rect {
    val left = nodes.minOfOrNull { it.position.x.toFloat() } ?: 0f
    val top = nodes.minOfOrNull { it.position.y.toFloat() } ?: 0f
    val right = nodes.maxOfOrNull {
        (it.position.x + (it.measured?.width ?: it.width ?: defaultNodeWidth)).toFloat()
    } ?: 0f
    val bottom = nodes.maxOfOrNull {
        (it.position.y + (it.measured?.height ?: it.height ?: defaultNodeHeight)).toFloat()
    } ?: 0f
    return Rect(left, top, right, bottom)
}
