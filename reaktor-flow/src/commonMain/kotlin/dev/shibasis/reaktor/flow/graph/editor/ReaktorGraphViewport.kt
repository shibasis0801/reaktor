package dev.shibasis.reaktor.flow.graph.editor

import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.composeflow.runtime.ReactFlowState
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.render.flowBounds
import dev.shibasis.reaktor.flow.graph.render.readableFlowBounds
import dev.shibasis.reaktor.flow.graph.style.DefaultReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.defaultNodeHeight
import dev.shibasis.reaktor.flow.graph.style.defaultNodeWidth
import dev.shibasis.reaktor.flow.graph.style.fitPadding
import dev.shibasis.reaktor.flow.graph.style.readablePadding
import kotlin.math.min

// Keep fit math in graph-space pixels, then hand the viewport to compose-flow. That matches the
// React Flow / xyflow fitView model and avoids mixing Compose dp with editor-space coordinates.
internal fun frameGraph(
    state: ReactFlowState,
    flow: ReaktorFlowGraph,
    style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
    rightInsetPx: Float = 0f,
    readable: Boolean = false,
) {
    if (state.canvasSize.width <= 0 || state.canvasSize.height <= 0 || flow.nodes.isEmpty()) {
        return
    }

    val bounds = if (readable) readableFlowBounds(flow, style) else flowBounds(flow, style)
    val viewportWidth = (state.canvasSize.width - rightInsetPx).coerceAtLeast(1f).toDouble()
    val viewportHeight = state.canvasSize.height.toDouble()
    val padding = if (readable) style.readablePadding() else style.fitPadding()
    val horizontalPadding = padding.horizontal
    val verticalPadding = padding.vertical
    val availableWidth = (viewportWidth - horizontalPadding * 2.0).coerceAtLeast(1.0)
    val availableHeight = (viewportHeight - verticalPadding * 2.0).coerceAtLeast(1.0)

    val contentWidth = bounds.width.coerceAtLeast(style.defaultNodeWidth())
    val contentHeight = bounds.height.coerceAtLeast(style.defaultNodeHeight())
    val fitZoom = min(
        availableWidth / contentWidth,
        availableHeight / contentHeight,
    )
    val zoom = if (readable) {
        (fitZoom * style.viewport.readableZoomBias)
            .coerceIn(style.viewport.readableMinZoom, 1.2)
    } else {
        fitZoom.coerceIn(style.viewport.minZoom, 1.2)
    }

    val offsetX = horizontalPadding - bounds.left * zoom
    val offsetY = verticalPadding - bounds.top * zoom

    state.setViewport(
        Viewport(
            x = offsetX,
            y = offsetY,
            zoom = zoom,
        ),
    )
}

internal fun mergeGraphNodes(
    existing: List<Node>,
    incoming: List<Node>,
    selectedFlowId: String?,
): List<Node> {
    if (existing.isEmpty()) {
        return incoming.map { it.copy(selected = it.id == selectedFlowId) }
    }

    val existingById = existing.associateBy(Node::id)
    return incoming.map { next ->
        val current = existingById[next.id]
        if (current == null) {
            next.copy(selected = next.id == selectedFlowId)
        } else {
            next.copy(
                position = current.position,
                measured = current.measured,
                selected = next.id == selectedFlowId,
                dragging = current.dragging,
            )
        }
    }
}
