package dev.shibasis.reaktor.flow.graph.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.shibasis.composeflow.runtime.EdgesState
import dev.shibasis.composeflow.runtime.NodesState
import dev.shibasis.composeflow.runtime.ReactFlowState
import dev.shibasis.reaktor.flow.graph.layout.GraphFlowMetrics
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.render.GraphUi
import kotlinx.coroutines.delay

@Composable
internal fun SyncGraphScene(
    flow: ReaktorFlowGraph,
    selectedFlowId: String?,
    state: ReactFlowState,
    nodesState: NodesState,
    edgesState: EdgesState,
    rightInsetPx: Float,
    metrics: GraphFlowMetrics,
) {
    var hasFramedGraph by remember(flow) { mutableStateOf(false) }

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

    LaunchedEffect(flow, state.canvasSize, hasFramedGraph) {
        if (hasFramedGraph || state.canvasSize.width <= 0 || state.canvasSize.height <= 0) {
            return@LaunchedEffect
        }
        delay(GraphUi.startupFrameDelayMillis)
        if (state.canvasSize.width <= 0 || state.canvasSize.height <= 0) {
            return@LaunchedEffect
        }
        frameGraph(
            state = state,
            flow = flow,
            metrics = metrics,
            rightInsetPx = rightInsetPx,
            readable = true,
        )
        hasFramedGraph = true
    }
}
