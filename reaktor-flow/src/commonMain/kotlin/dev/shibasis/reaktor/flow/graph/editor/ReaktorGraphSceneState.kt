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
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.style.DefaultReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle

@Composable
internal fun SyncGraphScene(
    flow: ReaktorFlowGraph,
    selectedFlowId: String?,
    state: ReactFlowState,
    nodesState: NodesState,
    edgesState: EdgesState,
    rightInsetPx: Float,
    style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
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
        // Frame the moment the canvas has a size. Waiting a wall-clock delay here painted an
        // unframed viewport first — a flash of arbitrary zoom on every open, and permanently so
        // for single-frame offscreen renders.
        // Land contained and centred — the whole topology visible, slack split evenly. A zoom
        // floor that centres on the selection reads as an off-corner accident on sparse layouts;
        // 100% and Fit are one click away for the close-up.
        frameGraph(
            state = state,
            flow = flow,
            style = style,
            rightInsetPx = rightInsetPx,
            readable = true,
        )
        hasFramedGraph = true
    }
}
