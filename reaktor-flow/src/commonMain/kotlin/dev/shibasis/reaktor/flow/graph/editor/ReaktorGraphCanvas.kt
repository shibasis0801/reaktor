package dev.shibasis.reaktor.flow.graph.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shibasis.composeflow.runtime.ReactFlowState
import dev.shibasis.composeflow.runtime.rememberReactFlowState
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind
import dev.shibasis.reaktor.graph.core.node.Node as GraphNode

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
    val reactFlowState = state ?: rememberReactFlowState()
    val rightInsetPx = with(density) { rightInset.toPx() }

    // Keep this file as the orchestration boundary only: external selection in, generic scene out.
    // Measurement, viewport framing, and overlay composition live in dedicated files so visual edits
    // do not require re-reading the whole editor pipeline.
    ReaktorGraphScene(
        flow = flow,
        selectedNode = selectedNode,
        selectedGraphId = selectedGraphId,
        highlightedKind = highlightedKind,
        onSelectNode = onSelectNode,
        onSelectGraph = onSelectGraph,
        onHighlightKind = onHighlightKind,
        onPaneClick = onPaneClick,
        rightInset = rightInset,
        rightInsetPx = rightInsetPx,
        state = reactFlowState,
        modifier = modifier,
    )
}
