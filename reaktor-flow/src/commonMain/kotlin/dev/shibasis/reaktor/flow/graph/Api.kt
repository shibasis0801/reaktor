package dev.shibasis.reaktor.flow.graph

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import dev.shibasis.composeflow.runtime.ReactFlowState
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node as GraphNode

typealias ReaktorEdgeKind = dev.shibasis.reaktor.flow.graph.model.ReaktorEdgeKind
typealias ReaktorArchitectureElement = dev.shibasis.reaktor.flow.graph.model.ReaktorArchitectureElement
typealias ReaktorArchitectureLevel = dev.shibasis.reaktor.flow.graph.model.ReaktorArchitectureLevel
typealias ReaktorArchitectureOverlay = dev.shibasis.reaktor.flow.graph.model.ReaktorArchitectureOverlay
typealias ReaktorArchitectureRelationship = dev.shibasis.reaktor.flow.graph.model.ReaktorArchitectureRelationship
typealias ReaktorArchitectureScope = dev.shibasis.reaktor.flow.graph.model.ReaktorArchitectureScope
typealias ReaktorArchitectureSnapshot = dev.shibasis.reaktor.flow.graph.model.ReaktorArchitectureSnapshot
typealias ReaktorFlowGraph = dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
typealias ReaktorFlowScopeView = dev.shibasis.reaktor.flow.graph.model.ReaktorFlowScopeView
typealias ReaktorGraphEdgeData = dev.shibasis.reaktor.flow.graph.model.ReaktorGraphEdgeData
typealias ReaktorGraphLens = dev.shibasis.reaktor.flow.graph.model.ReaktorGraphLens
typealias ReaktorGraphLensResult = dev.shibasis.reaktor.flow.graph.model.ReaktorGraphLensResult
typealias ReaktorGraphNodeData = dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
typealias ReaktorGraphRegion = dev.shibasis.reaktor.flow.graph.model.ReaktorGraphRegion
typealias ReaktorGraphStyle = dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle
typealias ReaktorNodeKind = dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind
typealias ReaktorPortData = dev.shibasis.reaktor.flow.graph.model.ReaktorPortData

fun buildReaktorFlowGraph(
    graph: Graph,
    style: ReaktorGraphStyle = dev.shibasis.reaktor.flow.graph.style.DefaultReaktorGraphStyle,
    scopeView: ReaktorFlowScopeView? = null,
): ReaktorFlowGraph =
    dev.shibasis.reaktor.flow.graph.adapter.buildReaktorFlowGraph(graph, style, scopeView)

fun buildReaktorArchitectureSnapshot(graph: Graph): ReaktorArchitectureSnapshot =
    dev.shibasis.reaktor.flow.graph.adapter.buildReaktorArchitectureSnapshot(graph)

fun reaktorNodeKind(node: GraphNode): ReaktorNodeKind =
    dev.shibasis.reaktor.flow.graph.adapter.reaktorNodeKind(node)

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
    rightInset: Dp = Dp.Unspecified,
    style: ReaktorGraphStyle? = null,
    state: ReactFlowState? = null,
    modifier: Modifier = Modifier,
    lensResult: ReaktorGraphLensResult? = null,
) {
    dev.shibasis.reaktor.flow.graph.editor.ReaktorGraphCanvas(
        flow = flow,
        selectedNode = selectedNode,
        selectedGraphId = selectedGraphId,
        highlightedKind = highlightedKind,
        lensResult = lensResult,
        onSelectNode = onSelectNode,
        onSelectGraph = onSelectGraph,
        onHighlightKind = onHighlightKind,
        onPaneClick = onPaneClick,
        rightInset = if (rightInset == Dp.Unspecified) Dp(0f) else rightInset,
        style = style,
        state = state,
        modifier = modifier,
    )
}

@Composable
fun ReaktorGraphEditor(
    flow: ReaktorFlowGraph,
    selectedNode: GraphNode?,
    selectedGraphId: String?,
    highlightedKind: ReaktorNodeKind?,
    onSelectNode: (GraphNode?) -> Unit,
    onSelectGraph: (String?) -> Unit,
    onHighlightKind: (ReaktorNodeKind?) -> Unit,
    onPaneClick: (() -> Unit)? = null,
    rightInset: Dp = Dp.Unspecified,
    style: ReaktorGraphStyle? = null,
    showKindLegend: Boolean = true,
    modifier: Modifier = Modifier,
    state: ReactFlowState = dev.shibasis.composeflow.runtime.rememberReactFlowState(),
    lensResult: ReaktorGraphLensResult? = null,
) {
    dev.shibasis.reaktor.flow.graph.editor.ReaktorGraphEditor(
        flow = flow,
        selectedNode = selectedNode,
        selectedGraphId = selectedGraphId,
        highlightedKind = highlightedKind,
        lensResult = lensResult,
        onSelectNode = onSelectNode,
        onSelectGraph = onSelectGraph,
        onHighlightKind = onHighlightKind,
        onPaneClick = onPaneClick,
        rightInset = if (rightInset == Dp.Unspecified) Dp(0f) else rightInset,
        style = style,
        showKindLegend = showKindLegend,
        modifier = modifier,
        state = state,
    )
}
