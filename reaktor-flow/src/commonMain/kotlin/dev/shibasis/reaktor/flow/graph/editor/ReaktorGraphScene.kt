package dev.shibasis.reaktor.flow.graph.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import dev.shibasis.composeflow.compose.ReactFlow
import dev.shibasis.composeflow.compose.primitives.EdgePathStyle
import dev.shibasis.composeflow.compose.primitives.NodeTypes
import dev.shibasis.composeflow.runtime.ReactFlowState
import dev.shibasis.composeflow.runtime.ReactFlowProvider
import dev.shibasis.composeflow.runtime.rememberEdgesState
import dev.shibasis.composeflow.runtime.rememberNodesState
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind
import dev.shibasis.reaktor.flow.graph.render.ReaktorGraphNodeCard
import dev.shibasis.reaktor.flow.graph.render.graphEdgeRenderStyle
import dev.shibasis.reaktor.flow.graph.render.graphHandleRenderStyle
import dev.shibasis.reaktor.flow.graph.render.graphNodeRenderStyle
import dev.shibasis.reaktor.flow.graph.style.defaultNodeHeight
import dev.shibasis.reaktor.flow.graph.style.defaultNodeWidth
import dev.shibasis.reaktor.flow.graph.style.dpOf
import dev.shibasis.reaktor.graph.core.node.Node as GraphNode

@Composable
internal fun ReaktorGraphScene(
    flow: ReaktorFlowGraph,
    selectedNode: GraphNode?,
    selectedGraphId: String?,
    highlightedKind: ReaktorNodeKind?,
    onSelectNode: (GraphNode?) -> Unit,
    onSelectGraph: (String?) -> Unit,
    onHighlightKind: (ReaktorNodeKind?) -> Unit,
    onPaneClick: (() -> Unit)?,
    rightInset: Dp,
    rightInsetPx: Float,
    state: ReactFlowState,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val graphStyle = flow.style
    val selectedFlowId = remember(flow, selectedNode) { selectedNode?.let(flow.flowIdsByNode::get) }
    val nodesState = rememberNodesState(flow.nodes)
    val edgesState = rememberEdgesState(flow.edges)
    val nodeKinds = remember(flow) {
        flow.nodes.associate { node ->
            node.id to ((node.data as? ReaktorGraphNodeData)?.kind ?: ReaktorNodeKind.Node)
        }
    }
    val nodeTypes: NodeTypes = remember(graphStyle) {
        mapOf("graph" to { props -> ReaktorGraphNodeCard(props, graphStyle) })
    }

    SyncGraphScene(
        flow = flow,
        selectedFlowId = selectedFlowId,
        state = state,
        nodesState = nodesState,
        edgesState = edgesState,
        rightInsetPx = rightInsetPx,
        style = graphStyle,
    )

    ReactFlowProvider(state = state) {
        ReactFlow(
            nodes = nodesState.nodes,
            edges = edgesState.edges,
            modifier = modifier,
            state = state,
            nodeTypes = nodeTypes,
            onNodesChange = nodesState::onNodesChange,
            onNodeClick = { node -> onSelectNode(flow.graphNodes[node.id]) },
            fitView = false,
            showControls = false,
            showMiniMap = false,
            showBackground = true,
            backgroundVariant = dev.shibasis.composeflow.model.BackgroundVariant.Cross,
            minZoom = graphStyle.viewport.minZoom,
            maxZoom = graphStyle.viewport.maxZoom,
            defaultNodeWidth = with(density) { dpOf(graphStyle.defaultNodeWidth()) },
            defaultNodeHeight = with(density) { dpOf(graphStyle.defaultNodeHeight()) },
            nodeRenderStyle = { node -> graphNodeRenderStyle(node, highlightedKind, graphStyle) },
            edgeRenderStyle = { edge -> graphEdgeRenderStyle(edge, nodeKinds, highlightedKind) },
            edgePathStyle = EdgePathStyle.Bezier,
            handleRenderStyle = { node, handle -> graphHandleRenderStyle(node, handle, highlightedKind, graphStyle) },
            onPaneClick = onPaneClick,
            overlay = { reactFlowState ->
                ReaktorGraphChromeOverlay(
                    flow = flow,
                    state = reactFlowState,
                    highlightedKind = highlightedKind,
                    onHighlightKind = onHighlightKind,
                    rightInset = rightInset,
                    onZoomIn = {
                        state.zoomBy(
                            factor = graphStyle.viewport.zoomStep,
                            anchorX = state.canvasSize.width / 2.0,
                            anchorY = state.canvasSize.height / 2.0,
                            minZoom = graphStyle.viewport.minZoom,
                            maxZoom = graphStyle.viewport.maxZoom,
                        )
                    },
                    onZoomOut = {
                        state.zoomBy(
                            factor = 1.0 / graphStyle.viewport.zoomStep,
                            anchorX = state.canvasSize.width / 2.0,
                            anchorY = state.canvasSize.height / 2.0,
                            minZoom = graphStyle.viewport.minZoom,
                            maxZoom = graphStyle.viewport.maxZoom,
                        )
                    },
                    onFitView = {
                        frameGraph(
                            state = state,
                            flow = flow,
                            style = graphStyle,
                            rightInsetPx = rightInsetPx,
                            readable = false,
                        )
                    },
                    onResetZoom = {
                        state.zoomTo(
                            zoom = 1.0,
                            anchorX = state.canvasSize.width / 2.0,
                            anchorY = state.canvasSize.height / 2.0,
                            minZoom = graphStyle.viewport.minZoom,
                            maxZoom = graphStyle.viewport.maxZoom,
                        )
                    },
                    style = graphStyle,
                )
            },
            viewportOverlay = {
                ReaktorGraphViewportOverlay(
                    flow = flow,
                    selectedGraphId = selectedGraphId,
                    onSelectGraph = onSelectGraph,
                    style = graphStyle,
                )
            },
        )
    }
}
