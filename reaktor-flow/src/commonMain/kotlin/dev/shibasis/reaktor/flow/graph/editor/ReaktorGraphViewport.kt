package dev.shibasis.reaktor.flow.graph.editor

import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.composeflow.model.XYPosition
import dev.shibasis.composeflow.runtime.ReactFlowState
import dev.shibasis.reaktor.flow.graph.ReaktorGraphEditorState
import dev.shibasis.reaktor.flow.graph.ReaktorGraphSelection
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.render.scopeOrigin
import dev.shibasis.reaktor.flow.graph.render.flowBounds
import dev.shibasis.reaktor.flow.graph.style.DefaultReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.defaultNodeHeight
import dev.shibasis.reaktor.flow.graph.style.defaultNodeWidth
import dev.shibasis.reaktor.flow.graph.style.fitPadding
import dev.shibasis.reaktor.flow.graph.style.readablePadding
import kotlin.math.min

internal fun frameGraphIfRequested(
    editorState: ReaktorGraphEditorState,
    flow: ReaktorFlowGraph,
    rightInsetPx: Float = 0f,
    style: ReaktorGraphStyle = flow.style,
) {
    val canvas = editorState.canvas
    if (editorState.completedFrameRequest == editorState.frameRequest ||
        canvas.canvasSize.width <= 0 || canvas.canvasSize.height <= 0 || flow.nodes.isEmpty()
    ) return
    val targetId = editorState.frameNodeId
    val positionedFlow = editorState.withNodeLayout(flow)
    val anchorScopeId = editorState.anchorScopeId
    if (anchorScopeId != null) {
        // Fold/unfold: translate the camera by exactly the distance the anchored scope moved, so
        // it lands back under the same pixel. No re-framing, no zoom change, no jump.
        positionedFlow.scopeOrigin(anchorScopeId, style)?.let { origin ->
            val zoom = canvas.viewport.zoom
            canvas.setViewport(
                Viewport(
                    x = editorState.anchorScreenX - origin.x * zoom,
                    y = editorState.anchorScreenY - origin.y * zoom,
                    zoom = zoom,
                ),
            )
        }
        editorState.completedFrameRequest = editorState.frameRequest
        return
    }
    val connection = editorState.frameSubject as? ReaktorGraphSelection.Connection
    if (connection != null) {
        val edge = positionedFlow.edges.firstOrNull { it.id == connection.id } ?: return
        val ends = positionedFlow.nodes.filter { it.id == edge.source || it.id == edge.target }
        if (ends.none { it.id == edge.source } || ends.none { it.id == edge.target }) return
        frameGraph(canvas, positionedFlow.copy(nodes = ends, regions = emptyList()), style, rightInsetPx)
    } else if (targetId == null) {
        frameGraph(canvas, positionedFlow, style, rightInsetPx, readable = true)
    } else {
        val node = positionedFlow.nodes.firstOrNull { it.id == targetId }
        if (node != null) canvas.focusNode(node, style.defaultNodeWidth(), style.defaultNodeHeight())
        else {
            val region = positionedFlow.regions.firstOrNull { it.id == targetId } ?: return
            canvas.centerOn(XYPosition(region.x + region.width / 2, region.y + region.height / 2))
        }
    }
    editorState.completedFrameRequest = editorState.frameRequest
}

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

    // Startup framing and Fit are containment operations. They must include the complete
    // compound graph (nodes and regions), whether its scopes are collapsed, partially expanded,
    // or fully open. Detail-oriented lenses may frame a readable subset explicitly elsewhere.
    val bounds = flowBounds(flow, style)
    val leftClearance = style.viewport.chromeClearanceLeftPx
    val rightClearance = style.viewport.chromeClearanceRightPx + rightInsetPx
    val viewportWidth = state.canvasSize.width.coerceAtLeast(1).toDouble()
    val viewportHeight = state.canvasSize.height.toDouble()
    val padding = if (readable) style.readablePadding() else style.fitPadding()
    val horizontalPadding = padding.horizontal
    val verticalPadding = padding.vertical
    val availableWidth = (viewportWidth - leftClearance - rightClearance - horizontalPadding * 2.0)
        .coerceAtLeast(1.0)
    val topClearance = style.viewport.chromeClearanceTopPx
    val bottomClearance = style.viewport.chromeClearanceBottomPx
    val availableHeight = (
        viewportHeight - topClearance - bottomClearance - verticalPadding * 2.0
    ).coerceAtLeast(1.0)

    val contentWidth = bounds.width.coerceAtLeast(style.defaultNodeWidth())
    val contentHeight = bounds.height.coerceAtLeast(style.defaultNodeHeight())
    val fitZoom = min(
        availableWidth / contentWidth,
        availableHeight / contentHeight,
    )
    // Manual zoom may enforce [minZoom], but Fit must never enlarge a topology past the scale
    // required to keep every bound inside the viewport.
    val zoom = fitZoom.coerceAtMost(1.2).coerceAtLeast(1e-6)

    val horizontalSlack = (availableWidth - contentWidth * zoom).coerceAtLeast(0.0) / 2.0
    val verticalSlack = (availableHeight - contentHeight * zoom).coerceAtLeast(0.0) / 2.0
    val offsetX = leftClearance + horizontalPadding + horizontalSlack - bounds.left * zoom
    val offsetY = topClearance + verticalPadding + verticalSlack - bounds.top * zoom

    state.setViewport(
        Viewport(
            x = offsetX,
            y = offsetY,
            zoom = zoom,
        ),
    )
}

/**
 * The landing frame: readable first, containment second.
 *
 * The boards land the graph legible — around 1:1 on the focus slice — and leave containment to
 * the explicit Fit gesture. When the whole topology fits above [ReaktorGraphStyle.Viewport
 * .readableMinZoom] this is exactly [frameGraph]; when it does not, the viewport holds the
 * readable floor and centres on the focused node so the first thing on screen is the thing the
 * workbench selected, not a far corner of the layout.
 */
internal fun frameGraphReadable(
    state: ReactFlowState,
    flow: ReaktorFlowGraph,
    style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
    rightInsetPx: Float = 0f,
    focusFlowId: String? = null,
) {
    if (state.canvasSize.width <= 0 || state.canvasSize.height <= 0 || flow.nodes.isEmpty()) {
        return
    }
    val bounds = flowBounds(flow, style)
    val leftClearance = style.viewport.chromeClearanceLeftPx
    val rightClearance = style.viewport.chromeClearanceRightPx + rightInsetPx
    val topClearance = style.viewport.chromeClearanceTopPx
    val bottomClearance = style.viewport.chromeClearanceBottomPx
    val padding = style.readablePadding()
    val availableWidth = (
        state.canvasSize.width.toDouble() - leftClearance - rightClearance - padding.horizontal * 2.0
    ).coerceAtLeast(1.0)
    val availableHeight = (
        state.canvasSize.height.toDouble() - topClearance - bottomClearance - padding.vertical * 2.0
    ).coerceAtLeast(1.0)
    val contentWidth = bounds.width.coerceAtLeast(style.defaultNodeWidth())
    val contentHeight = bounds.height.coerceAtLeast(style.defaultNodeHeight())
    val fitZoom = min(availableWidth / contentWidth, availableHeight / contentHeight)

    if (fitZoom >= style.viewport.readableMinZoom) {
        frameGraph(state = state, flow = flow, style = style, rightInsetPx = rightInsetPx, readable = true)
        return
    }

    val zoom = style.viewport.readableMinZoom.coerceAtMost(style.viewport.maxZoom)
    val focus = focusFlowId?.let { id -> flow.nodes.firstOrNull { it.id == id } }
    val focusX = focus?.let { it.position.x + style.defaultNodeWidth() / 2.0 }
        ?: (bounds.left + contentWidth / 2.0)
    val focusY = focus?.let { it.position.y + style.defaultNodeHeight() / 2.0 }
        ?: (bounds.top + contentHeight / 2.0)
    val viewportCentreX = leftClearance + availableWidth / 2.0
    val viewportCentreY = topClearance + availableHeight / 2.0
    state.setViewport(
        Viewport(
            x = viewportCentreX - focusX * zoom,
            y = viewportCentreY - focusY * zoom,
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
