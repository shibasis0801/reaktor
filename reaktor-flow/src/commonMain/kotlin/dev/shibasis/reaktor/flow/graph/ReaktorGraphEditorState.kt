package dev.shibasis.reaktor.flow.graph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.XYPosition
import dev.shibasis.composeflow.model.NodeChange
import dev.shibasis.composeflow.model.NodePositionChange
import dev.shibasis.composeflow.model.applyNodeChanges
import dev.shibasis.composeflow.runtime.ReactFlowState
import dev.shibasis.composeflow.runtime.rememberReactFlowState
import dev.shibasis.reaktor.flow.graph.render.scopeOrigin
import dev.shibasis.reaktor.flow.graph.style.DefaultReaktorGraphStyle

typealias ReaktorGraphViewport = Viewport

/** A host-owned camera survives projection changes and temporary editor unmounts. */
class ReaktorGraphEditorState internal constructor(internal val canvas: ReactFlowState) {
    constructor() : this(ReactFlowState())

    val viewport: ReaktorGraphViewport get() = canvas.viewport
    private var positionedNodes by mutableStateOf<Map<String, Node>>(emptyMap())
    private var pinnedNodeIds by mutableStateOf<Set<String>>(emptySet())

    internal fun withNodeLayout(flow: ReaktorFlowGraph): ReaktorFlowGraph = flow.copy(
        nodes = flow.nodes.map { node ->
            positionedNodes[node.id]?.let { positioned ->
                // A saved measured size belongs to the authored dimensions that produced it.
                // New port rows, card styles or display density must be measured again while the
                // user's position and camera remain stable.
                val sameAuthoredSize = node.width == positioned.width && node.height == positioned.height
                node.copy(position = if (node.id in pinnedNodeIds) positioned.position else node.position,
                    measured = if (sameAuthoredSize) positioned.measured else node.measured,
                    dragging = positioned.dragging)
            } ?: node
        },
    )

    internal fun onNodesChange(flow: ReaktorFlowGraph, changes: List<NodeChange>) {
        pinnedNodeIds = pinnedNodeIds + changes.filterIsInstance<NodePositionChange>().map { it.id }
        val nodes = applyNodeChanges(changes, withNodeLayout(flow).nodes)
        val changedIds = changes.mapTo(mutableSetOf()) { it.id }
        positionedNodes = positionedNodes + nodes.filter { it.id in changedIds }.associateBy { it.id }
    }

    fun resetLayout() {
        pinnedNodeIds = emptySet()
        positionedNodes = emptyMap()
        fit()
    }

    // Disclosure is not navigation. Re-framing the camera on a scope the user just folded makes
    // the whole graph appear to jump, because every card moved AND the camera moved with it.
    // Instead, pin the scope to the screen point it already occupies and let the layout grow or
    // shrink around it: the thing under the pointer is the one thing that does not move.
    internal var anchorScopeId: String? = null
        private set
    internal var anchorScreenX = 0.0
        private set
    internal var anchorScreenY = 0.0
        private set

    /** Hold [scopeId] at its current screen position across the next projection change. */
    fun anchorScope(
        flow: ReaktorFlowGraph,
        scopeId: String,
        style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
    ) {
        val origin = withNodeLayout(flow).scopeOrigin(scopeId, style)
        if (origin == null) {
            frameSelection(scopeId)
            return
        }
        val current = canvas.viewport
        anchorScopeId = scopeId
        anchorScreenX = origin.x * current.zoom + current.x
        anchorScreenY = origin.y * current.zoom + current.y
        frameSubject = null
        frameRequest += 1
    }
    internal var frameRequest by mutableLongStateOf(0L)
        private set
    internal var completedFrameRequest = -1L
    internal var frameSubject: ReaktorGraphSelection? = null
        private set

    internal val frameNodeId: String? get() = when (val selection = frameSubject) {
        is ReaktorGraphSelection.Element -> selection.id
        is ReaktorGraphSelection.Port -> selection.nodeId
        else -> null
    }

    fun fit() {
        anchorScopeId = null
        frameSubject = null
        frameRequest += 1
    }

    fun frameSelection(nodeId: String) {
        frameSelection(ReaktorGraphSelection.Element(nodeId))
    }

    fun frameSelection(selection: ReaktorGraphSelection) {
        anchorScopeId = null
        frameSubject = selection
        frameRequest += 1
    }

    fun restoreViewport(viewport: ReaktorGraphViewport) {
        require(viewport.x.isFinite() && viewport.y.isFinite() && viewport.zoom.isFinite() && viewport.zoom > 0)
        anchorScopeId = null
        canvas.setViewport(viewport)
        completedFrameRequest = frameRequest
    }

    fun zoomBy(factor: Double, style: ReaktorGraphStyle = DefaultReaktorGraphStyle) {
        require(factor.isFinite() && factor > 0)
        anchorScopeId = null
        canvas.zoomBy(factor, canvas.canvasSize.width / 2.0, canvas.canvasSize.height / 2.0,
            style.viewport.minZoom, style.viewport.maxZoom)
        completedFrameRequest = frameRequest
    }

    fun resetZoom(style: ReaktorGraphStyle = DefaultReaktorGraphStyle) {
        anchorScopeId = null
        canvas.zoomTo(1.0, canvas.canvasSize.width / 2.0, canvas.canvasSize.height / 2.0,
            style.viewport.minZoom, style.viewport.maxZoom)
        completedFrameRequest = frameRequest
    }
}

@Composable
fun rememberReaktorGraphEditorState(sessionKey: Any? = Unit): ReaktorGraphEditorState =
    androidx.compose.runtime.key(sessionKey) {
        val canvas = rememberReactFlowState()
        remember(canvas) { ReaktorGraphEditorState(canvas) }
    }
