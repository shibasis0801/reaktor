package dev.shibasis.reaktor.flow.graph.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shibasis.composeflow.compose.interaction.requestPointerFocusOnFirstDown
import dev.shibasis.composeflow.compose.interaction.zoomAroundCanvasCenter
import dev.shibasis.composeflow.runtime.ReactFlowState
import dev.shibasis.composeflow.runtime.rememberReactFlowState
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.dpOf
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
    style: ReaktorGraphStyle? = null,
    modifier: Modifier = Modifier,
    state: ReactFlowState = rememberReactFlowState(),
) {
    val focusRequester = remember { FocusRequester() }
    val graphStyle = style ?: flow.style
    val density = LocalDensity.current

    // Compose desktop routes keyboard input through the focused subtree. We keep the editor root
    // focusable so graph shortcuts stay local to the editor instead of leaking into window chrome.
    fun zoomGraph(factor: Double) {
        state.zoomAroundCanvasCenter(
            factor = factor,
            minZoom = graphStyle.viewport.minZoom,
            maxZoom = graphStyle.viewport.maxZoom,
        )
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(graphStyle.canvas.background)
            .padding(with(density) { dpOf(graphStyle.chrome.editorPaddingPx) })
            .focusRequester(focusRequester)
            .focusable()
            .requestPointerFocusOnFirstDown { focusRequester.requestFocus() }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || (!event.isMetaPressed && !event.isCtrlPressed)) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.Equals, Key.Plus, Key.NumPadAdd -> {
                        zoomGraph(graphStyle.viewport.zoomStep)
                        true
                    }
                    Key.Minus, Key.NumPadSubtract -> {
                        zoomGraph(1.0 / graphStyle.viewport.zoomStep)
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
            style = graphStyle,
            state = state,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
