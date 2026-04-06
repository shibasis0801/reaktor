package dev.shibasis.reaktor.flow.graph

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import dev.shibasis.composeflow.runtime.ReactFlowState
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node as GraphNode

typealias ReaktorEdgeKind = dev.shibasis.reaktor.flow.graph.model.ReaktorEdgeKind
typealias ReaktorFlowGraph = dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
typealias ReaktorGraphEdgeData = dev.shibasis.reaktor.flow.graph.model.ReaktorGraphEdgeData
typealias ReaktorGraphNodeData = dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
typealias ReaktorGraphRegion = dev.shibasis.reaktor.flow.graph.model.ReaktorGraphRegion
typealias ReaktorNodeKind = dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind
typealias ReaktorPortData = dev.shibasis.reaktor.flow.graph.model.ReaktorPortData

fun buildReaktorFlowGraph(graph: Graph): ReaktorFlowGraph =
    dev.shibasis.reaktor.flow.graph.adapter.buildReaktorFlowGraph(graph)

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
    state: ReactFlowState? = null,
    modifier: Modifier = Modifier,
) {
    dev.shibasis.reaktor.flow.graph.editor.ReaktorGraphCanvas(
        flow = flow,
        selectedNode = selectedNode,
        selectedGraphId = selectedGraphId,
        highlightedKind = highlightedKind,
        onSelectNode = onSelectNode,
        onSelectGraph = onSelectGraph,
        onHighlightKind = onHighlightKind,
        onPaneClick = onPaneClick,
        rightInset = if (rightInset == Dp.Unspecified) Dp(0f) else rightInset,
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
    modifier: Modifier = Modifier,
    state: ReactFlowState = dev.shibasis.composeflow.runtime.rememberReactFlowState(),
) {
    dev.shibasis.reaktor.flow.graph.editor.ReaktorGraphEditor(
        flow = flow,
        selectedNode = selectedNode,
        selectedGraphId = selectedGraphId,
        highlightedKind = highlightedKind,
        onSelectNode = onSelectNode,
        onSelectGraph = onSelectGraph,
        onHighlightKind = onHighlightKind,
        onPaneClick = onPaneClick,
        rightInset = if (rightInset == Dp.Unspecified) Dp(0f) else rightInset,
        modifier = modifier,
        state = state,
    )
}
