package dev.shibasis.reaktor.flow.graph.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import dev.shibasis.composeflow.compose.ReactFlow
import dev.shibasis.composeflow.compose.interaction.zoomAroundCanvasCenter
import dev.shibasis.composeflow.compose.primitives.EdgePathStyle
import dev.shibasis.composeflow.compose.primitives.NodeTypes
import dev.shibasis.reaktor.flow.graph.ReaktorGraphSelection
import dev.shibasis.reaktor.flow.graph.selectGraphSubject
import dev.shibasis.reaktor.flow.graph.ReaktorGraphEditorState
import dev.shibasis.composeflow.runtime.ReactFlowState
import dev.shibasis.composeflow.runtime.ReactFlowProvider
import dev.shibasis.composeflow.runtime.rememberEdgesState
import dev.shibasis.composeflow.runtime.rememberNodesState
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphLensResult
import dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind
import dev.shibasis.reaktor.flow.graph.model.ReaktorScopeDisclosure
import dev.shibasis.reaktor.flow.graph.render.ReaktorGraphNodeCard
import dev.shibasis.reaktor.flow.graph.render.ReaktorGraphAccessibilityOverlay
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
    lensResult: ReaktorGraphLensResult?,
    onSelectNode: (GraphNode?) -> Unit,
    onSelectGraph: (String?) -> Unit,
    onHighlightKind: (ReaktorNodeKind?) -> Unit,
    onPaneClick: (() -> Unit)?,
    rightInset: Dp,
    rightInsetPx: Float,
    showKindLegend: Boolean = true,
    state: ReactFlowState,
    modifier: Modifier = Modifier,
    editorState: ReaktorGraphEditorState? = null,
    showChrome: Boolean = true,
    selectedSubject: ReaktorGraphSelection? = null,
    onSelectSubject: ((ReaktorGraphSelection?) -> Unit)? = null,
    scopeDisclosure: ReaktorScopeDisclosure? = null,
) {
    val density = LocalDensity.current
    val graphStyle = flow.style
    val selectedFlowId = when (selectedSubject) {
        is ReaktorGraphSelection.Element -> selectedSubject.id
        is ReaktorGraphSelection.Port -> selectedSubject.nodeId
        else -> selectedNode?.let(flow.flowIdsByNode::get)
    }
    val nodesState = rememberNodesState(flow.nodes)
    val edgesState = rememberEdgesState(flow.edges)
    val renderedFlow = editorState?.withNodeLayout(flow)
    val nodeKinds = remember(flow) {
        flow.nodes.associate { node ->
            node.id to ((node.data as? ReaktorGraphNodeData)?.kind ?: ReaktorNodeKind.Node)
        }
    }
    val nodeTypes: NodeTypes = mapOf("graph" to { props ->
        ReaktorGraphNodeCard(props, graphStyle, onSelectPort = onSelectSubject?.let { select ->
            { direction, port ->
                flow.selectGraphSubject(ReaktorGraphSelection.Port(props.id, port.handleId, direction),
                    onSelectNode, onSelectGraph, select)
            }
        }, scopeDisclosure = scopeDisclosure)
    })

    SyncGraphScene(
        flow = flow,
        selectedFlowId = selectedFlowId,
        state = state,
        nodesState = nodesState,
        edgesState = edgesState,
        rightInsetPx = rightInsetPx,
        style = graphStyle,
        editorState = editorState,
    )

    ReactFlowProvider(state = state) {
        ReactFlow(
            nodes = renderedFlow?.nodes?.map { it.copy(selected = it.id == selectedFlowId) } ?: nodesState.nodes,
            edges = edgesState.edges.map { edge ->
                edge.copy(selected = when (val subject = selectedSubject) {
                    is ReaktorGraphSelection.Connection -> subject.id == edge.id
                    is ReaktorGraphSelection.Port -> if (subject.direction == dev.shibasis.reaktor.flow.graph.ReaktorPortDirection.Provider) {
                        edge.source == subject.nodeId && edge.sourceHandle == subject.handleId
                    } else edge.target == subject.nodeId && edge.targetHandle == subject.handleId
                    else -> false
                })
            },
            modifier = modifier,
            state = state,
            nodeTypes = nodeTypes,
            onNodesChange = { changes ->
                if (editorState != null) editorState.onNodesChange(flow, changes) else nodesState.onNodesChange(changes)
            },
            onNodeClick = { node ->
                flow.selectGraphSubject(ReaktorGraphSelection.Element(node.id),
                    onSelectNode, onSelectGraph, { onSelectSubject?.invoke(it) })
            },
            onEdgeClick = onSelectSubject?.let { select ->
                { edge -> select(ReaktorGraphSelection.Connection(edge.id)) }
            },
            fitView = false,
            showControls = false,
            showMiniMap = false,
            showBackground = true,
            backgroundVariant = dev.shibasis.composeflow.model.BackgroundVariant.Dots,
            canvasBackground = graphStyle.canvas.background,
            minZoom = graphStyle.viewport.minZoom,
            maxZoom = graphStyle.viewport.maxZoom,
            defaultNodeWidth = with(density) { dpOf(graphStyle.defaultNodeWidth()) },
            defaultNodeHeight = with(density) { dpOf(graphStyle.defaultNodeHeight()) },
            nodeRenderStyle = { node -> graphNodeRenderStyle(node, highlightedKind, graphStyle, density) },
            edgeRenderStyle = { edge -> graphEdgeRenderStyle(edge, nodeKinds, highlightedKind,
                if (selectedSubject is ReaktorGraphSelection.Port || selectedSubject is ReaktorGraphSelection.Connection) null else selectedFlowId, density) },
            edgePathStyle = if (graphStyle.typedNode != null) EdgePathStyle.Orthogonal else EdgePathStyle.Bezier,
            handleRenderStyle = { node, handle -> graphHandleRenderStyle(node, handle, highlightedKind, graphStyle, density) },
            onPaneClick = {
                onPaneClick?.invoke()
                onSelectSubject?.invoke(null)
            },
            overlay = { reactFlowState ->
                if (showChrome) ReaktorGraphChromeOverlay(
                    flow = renderedFlow ?: flow.copy(nodes = nodesState.nodes),
                    lensResult = lensResult,
                    state = reactFlowState,
                    highlightedKind = highlightedKind,
                    onHighlightKind = onHighlightKind,
                    rightInset = rightInset,
                    showKindLegend = showKindLegend,
                    onZoomIn = {
                        state.zoomAroundCanvasCenter(
                            factor = graphStyle.viewport.zoomStep,
                            minZoom = graphStyle.viewport.minZoom,
                            maxZoom = graphStyle.viewport.maxZoom,
                        )
                    },
                    onZoomOut = {
                        state.zoomAroundCanvasCenter(
                            factor = 1.0 / graphStyle.viewport.zoomStep,
                            minZoom = graphStyle.viewport.minZoom,
                            maxZoom = graphStyle.viewport.maxZoom,
                        )
                    },
                    onFitView = {
                        frameGraph(
                            state = state,
                            flow = renderedFlow ?: flow.copy(nodes = nodesState.nodes),
                            style = graphStyle,
                            rightInsetPx = rightInsetPx,
                            readable = true,
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
                ReaktorGraphAccessibilityOverlay(flow)
                ReaktorGraphViewportOverlay(
                    flow = flow,
                    selectedGraphId = selectedGraphId,
                    onSelectGraph = onSelectGraph,
                    style = graphStyle,
                    scopeDisclosure = scopeDisclosure,
                )
            },
        )
    }
}
