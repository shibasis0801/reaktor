package dev.shibasis.reaktor.flow.graph.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shibasis.composeflow.runtime.ReactFlowState
import dev.shibasis.composeflow.runtime.rememberReactFlowState
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind
import dev.shibasis.reaktor.flow.graph.render.GraphCanvasBackground
import dev.shibasis.reaktor.flow.graph.render.ReaktorGraphChromeTokens
import dev.shibasis.reaktor.flow.graph.render.ReaktorGraphViewportTokens
import dev.shibasis.reaktor.graph.core.node.Node as GraphNode

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
    rightInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
    state: ReactFlowState = rememberReactFlowState(),
) {
    val focusRequester = remember { FocusRequester() }

    fun zoomGraph(factor: Double) {
        state.zoomBy(
            factor = factor,
            anchorX = state.canvasSize.width / 2.0,
            anchorY = state.canvasSize.height / 2.0,
            minZoom = ReaktorGraphViewportTokens.minZoom,
            maxZoom = ReaktorGraphViewportTokens.maxZoom,
        )
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(GraphCanvasBackground)
            .padding(ReaktorGraphChromeTokens.editorPadding)
            .focusRequester(focusRequester)
            .focusable()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(pass = PointerEventPass.Initial)
                    focusRequester.requestFocus()
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || (!event.isMetaPressed && !event.isCtrlPressed)) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.Equals, Key.Plus, Key.NumPadAdd -> {
                        zoomGraph(ReaktorGraphViewportTokens.zoomStep)
                        true
                    }
                    Key.Minus, Key.NumPadSubtract -> {
                        zoomGraph(1.0 / ReaktorGraphViewportTokens.zoomStep)
                        true
                    }
                    else -> false
                }
            },
    ) {
        ReaktorGraphCanvas(
            flow = flow,
            selectedNode = selectedNode,
            selectedGraphId = selectedGraphId,
            highlightedKind = highlightedKind,
            onSelectNode = onSelectNode,
            onSelectGraph = onSelectGraph,
            onHighlightKind = onHighlightKind,
            onPaneClick = {
                focusRequester.requestFocus()
                onPaneClick?.invoke()
            },
            rightInset = rightInset,
            state = state,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
