package dev.shibasis.reaktor.flow.graph.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shibasis.composeflow.compose.primitives.EdgePathStyle
import dev.shibasis.composeflow.compose.primitives.NodeTypes
import dev.shibasis.composeflow.compose.ReactFlow
import dev.shibasis.composeflow.model.BackgroundVariant
import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.runtime.ReactFlowState
import dev.shibasis.composeflow.runtime.ReactFlowProvider
import dev.shibasis.composeflow.runtime.rememberEdgesState
import dev.shibasis.composeflow.runtime.rememberNodesState
import dev.shibasis.composeflow.runtime.rememberReactFlowState
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind
import dev.shibasis.reaktor.flow.graph.render.GraphKindLegend
import dev.shibasis.reaktor.flow.graph.render.GraphMiniMap
import dev.shibasis.reaktor.flow.graph.render.GraphRegionsOverlay
import dev.shibasis.reaktor.flow.graph.render.GraphViewportToolbar
import dev.shibasis.reaktor.flow.graph.render.ReaktorDefaultNodeHeightPx
import dev.shibasis.reaktor.flow.graph.render.ReaktorDefaultNodeWidthPx
import dev.shibasis.reaktor.flow.graph.render.ReaktorGraphNodeCard
import dev.shibasis.reaktor.flow.graph.render.ReaktorGraphViewportTokens
import dev.shibasis.reaktor.flow.graph.render.graphEdgeRenderStyle
import dev.shibasis.reaktor.flow.graph.render.graphHandleRenderStyle
import dev.shibasis.reaktor.flow.graph.render.graphNodeRenderStyle
import dev.shibasis.reaktor.flow.graph.render.rememberReaktorNodeRenderMetrics
import dev.shibasis.reaktor.graph.core.node.Node as GraphNode
import kotlinx.coroutines.delay

@Composable
fun ReaktorGraphCanvas(
    flow: ReaktorFlowGraph,
    selectedNode: GraphNode?,
    selectedGraphId: String?,
    highlightedKind: ReaktorNodeKind?,
    onSelectNode: (GraphNode?) -> Unit,
    onSelectGraph: (String?) -> Unit,
    onHighlightKind: (ReaktorNodeKind?) -> Unit,
    onPaneClick: (() -> Unit)? = null,
    rightInset: Dp = 0.dp,
    state: ReactFlowState? = null,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val metrics = rememberReaktorNodeRenderMetrics()
    val rightInsetPx = with(density) { rightInset.toPx() }
    val defaultNodeWidthPx = ReaktorDefaultNodeWidthPx
    val defaultNodeHeightPx = ReaktorDefaultNodeHeightPx
    val selectedFlowId = remember(flow, selectedNode) { selectedNode?.let(flow.flowIdsByNode::get) }
    val reactFlowState = state ?: rememberReactFlowState()
    val nodesState = rememberNodesState(flow.nodes)
    val edgesState = rememberEdgesState(flow.edges)
    var hasFramedGraph by remember(flow) { mutableStateOf(false) }
    val nodeKinds = remember(flow) {
        flow.nodes.associate { node ->
            node.id to ((node.data as? dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData)?.kind ?: ReaktorNodeKind.Node)
        }
    }
    val nodeTypes: NodeTypes = remember {
        mapOf("graph" to { props -> ReaktorGraphNodeCard(props) })
    }

    LaunchedEffect(flow) {
        nodesState.replaceNodes(mergeGraphNodes(nodesState.nodes, flow.nodes, selectedFlowId))
        edgesState.replaceEdges(flow.edges)
        hasFramedGraph = false
    }

    LaunchedEffect(selectedFlowId) {
        nodesState.updateNodes { nodes ->
            nodes.map { node -> node.copy(selected = node.id == selectedFlowId) }
        }
    }

    LaunchedEffect(flow, reactFlowState.canvasSize, hasFramedGraph) {
        if (hasFramedGraph || reactFlowState.canvasSize.width <= 0 || reactFlowState.canvasSize.height <= 0) {
            return@LaunchedEffect
        }
        delay(ReaktorGraphViewportTokens.startupFrameDelayMillis)
        if (reactFlowState.canvasSize.width <= 0 || reactFlowState.canvasSize.height <= 0) {
            return@LaunchedEffect
        }
        frameGraph(
            state = reactFlowState,
            flow = flow,
            defaultNodeWidthPx = defaultNodeWidthPx,
            defaultNodeHeightPx = defaultNodeHeightPx,
            rightInsetPx = rightInsetPx,
            readable = true,
        )
        hasFramedGraph = true
    }

    ReactFlowProvider(state = reactFlowState) {
        ReactFlow(
            nodes = nodesState.nodes,
            edges = edgesState.edges,
            modifier = modifier,
            state = reactFlowState,
            nodeTypes = nodeTypes,
            onNodesChange = nodesState::onNodesChange,
            onNodeClick = { node -> onSelectNode(flow.graphNodes[node.id]) },
            fitView = false,
            showControls = false,
            showMiniMap = false,
            showBackground = true,
            backgroundVariant = BackgroundVariant.Cross,
            minZoom = ReaktorGraphViewportTokens.minZoom,
            maxZoom = ReaktorGraphViewportTokens.maxZoom,
            defaultNodeWidth = metrics.defaultNodeWidth,
            defaultNodeHeight = metrics.defaultNodeHeight,
            nodeRenderStyle = { node -> graphNodeRenderStyle(node, highlightedKind) },
            edgeRenderStyle = { edge -> graphEdgeRenderStyle(edge, nodeKinds, highlightedKind) },
            edgePathStyle = EdgePathStyle.Bezier,
            handleRenderStyle = { node, handle -> graphHandleRenderStyle(node, handle, highlightedKind) },
            onPaneClick = onPaneClick,
            overlay = { state ->
                GraphViewportToolbar(
                    flow = flow,
                    state = state,
                    onZoomIn = {
                        reactFlowState.zoomBy(
                            factor = ReaktorGraphViewportTokens.zoomStep,
                            anchorX = reactFlowState.canvasSize.width / 2.0,
                            anchorY = reactFlowState.canvasSize.height / 2.0,
                            minZoom = ReaktorGraphViewportTokens.minZoom,
                            maxZoom = ReaktorGraphViewportTokens.maxZoom,
                        )
                    },
                    onZoomOut = {
                        reactFlowState.zoomBy(
                            factor = 1.0 / ReaktorGraphViewportTokens.zoomStep,
                            anchorX = reactFlowState.canvasSize.width / 2.0,
                            anchorY = reactFlowState.canvasSize.height / 2.0,
                            minZoom = ReaktorGraphViewportTokens.minZoom,
                            maxZoom = ReaktorGraphViewportTokens.maxZoom,
                        )
                    },
                    onFitView = {
                        frameGraph(
                            state = reactFlowState,
                            flow = flow,
                            defaultNodeWidthPx = defaultNodeWidthPx,
                            defaultNodeHeightPx = defaultNodeHeightPx,
                            rightInsetPx = rightInsetPx,
                            readable = false,
                        )
                    },
                    onResetZoom = {
                        reactFlowState.zoomTo(
                            zoom = 1.0,
                            anchorX = reactFlowState.canvasSize.width / 2.0,
                            anchorY = reactFlowState.canvasSize.height / 2.0,
                            minZoom = ReaktorGraphViewportTokens.minZoom,
                            maxZoom = ReaktorGraphViewportTokens.maxZoom,
                        )
                    },
                )
                GraphKindLegend(
                    highlightedKind = highlightedKind,
                    flow = flow,
                    onHighlightKind = onHighlightKind,
                )
                GraphMiniMap(
                    flow = flow,
                    state = state,
                    rightInset = rightInset,
                )
            },
            viewportOverlay = {
                GraphRegionsOverlay(
                    flow = flow,
                    selectedGraphId = selectedGraphId,
                    onSelectGraph = onSelectGraph,
                )
            },
        )
    }
}
