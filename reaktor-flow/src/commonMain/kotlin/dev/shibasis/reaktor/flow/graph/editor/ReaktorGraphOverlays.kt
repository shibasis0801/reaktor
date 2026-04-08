package dev.shibasis.reaktor.flow.graph.editor

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.unit.Dp
import dev.shibasis.composeflow.runtime.ReactFlowState
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind
import dev.shibasis.reaktor.flow.graph.render.GraphKindLegend
import dev.shibasis.reaktor.flow.graph.render.GraphMiniMap
import dev.shibasis.reaktor.flow.graph.render.GraphRegionsOverlay
import dev.shibasis.reaktor.flow.graph.render.GraphViewportToolbar
import dev.shibasis.reaktor.flow.graph.style.DefaultReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle

// References:
// - React Flow keeps viewport chrome separate from the scene graph and injects it through panel
//   composition rather than mixing toolbar/minimap logic into node rendering.
// - Compose layout guidance is similar: keep overlays as sibling composition over the canvas so
//   viewport math, graph measurement, and chrome styling stay independently editable.
@androidx.compose.runtime.Composable
internal fun BoxScope.ReaktorGraphChromeOverlay(
    flow: ReaktorFlowGraph,
    state: ReactFlowState,
    highlightedKind: ReaktorNodeKind?,
    onHighlightKind: (ReaktorNodeKind?) -> Unit,
    rightInset: Dp,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFitView: () -> Unit,
    onResetZoom: () -> Unit,
    style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
) {
    GraphViewportToolbar(
        flow = flow,
        state = state,
        onZoomIn = onZoomIn,
        onZoomOut = onZoomOut,
        onFitView = onFitView,
        onResetZoom = onResetZoom,
        style = style,
    )
    GraphKindLegend(
        highlightedKind = highlightedKind,
        flow = flow,
        onHighlightKind = onHighlightKind,
        style = style,
    )
    GraphMiniMap(
        flow = flow,
        state = state,
        rightInset = rightInset,
        style = style,
    )
}

@androidx.compose.runtime.Composable
internal fun BoxScope.ReaktorGraphViewportOverlay(
    flow: ReaktorFlowGraph,
    selectedGraphId: String?,
    onSelectGraph: (String?) -> Unit,
    style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
) {
    GraphRegionsOverlay(
        flow = flow,
        selectedGraphId = selectedGraphId,
        onSelectGraph = onSelectGraph,
        style = style,
    )
}
