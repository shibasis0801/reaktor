package dev.shibasis.reaktor.flow.graph.render

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shibasis.composeflow.compose.components.Panel
import dev.shibasis.composeflow.model.PanelPosition
import dev.shibasis.composeflow.runtime.ReactFlowState
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphLensResult
import dev.shibasis.reaktor.flow.graph.style.DefaultReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.dpOf
import dev.shibasis.reaktor.flow.graph.style.spOf

@Composable
internal fun BoxScope.GraphViewportToolbar(
    flow: ReaktorFlowGraph,
    lensResult: ReaktorGraphLensResult? = null,
    state: ReactFlowState,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFitView: () -> Unit,
    onResetZoom: () -> Unit,
    rightInset: Dp = 0.dp,
    style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
) {
    val density = LocalDensity.current
    // `Chrome / Zoom Cluster` — Fit · 100% · <live zoom> · − · + on one 30px line, sitting to the
    // left of the minimap in the bottom-right corner. Node counts live in the top-left stats
    // chips, not here.
    val miniMapClearance = with(density) {
        dpOf(style.chrome.miniMapWidthPx + style.chrome.overlayPaddingPx + style.chrome.itemGapPx)
    }
    Panel(
        position = PanelPosition.BottomRight,
        modifier = Modifier.padding(
            end = miniMapClearance + rightInset,
            bottom = with(density) { dpOf(style.chrome.overlayPaddingPx) },
        ),
    ) {
        Surface(
            color = style.canvas.panelChrome,
            shape = RoundedCornerShape(with(density) { dpOf(style.chrome.panelRadiusPx) }),
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = with(density) { dpOf(style.chrome.itemGapPx) },
                    vertical = with(density) { dpOf(style.chrome.microGapPx) },
                ),
                horizontalArrangement = Arrangement.spacedBy(with(density) { dpOf(style.chrome.microGapPx) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolbarButton(label = "Fit", testId = "reaktor-graph-fit", onClick = onFitView, style = style)
                ToolbarButton(label = "100%", testId = "reaktor-graph-reset-zoom", onClick = onResetZoom, style = style)
                Text(
                    text = "${(state.viewport.zoom * 100).toInt()}%",
                    color = style.canvas.text,
                    fontSize = with(density) { spOf(style.chrome.bodyFontPx) },
                    fontFamily = style.canvas.monoFont,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = with(density) { dpOf(style.chrome.microGapPx) }),
                )
                ToolbarButton(label = "−", testId = "reaktor-graph-zoom-out", onClick = onZoomOut, style = style)
                ToolbarButton(label = "+", testId = "reaktor-graph-zoom-in", onClick = onZoomIn, style = style)
            }
        }
    }
}

@Composable
private fun ToolbarButton(
    label: String,
    testId: String,
    onClick: () -> Unit,
    style: ReaktorGraphStyle,
) {
    val density = LocalDensity.current
    Surface(
        color = style.canvas.panelSurface,
        shape = RoundedCornerShape(with(density) { dpOf(style.chrome.panelRadiusPx) }),
        tonalElevation = 0.dp,
    ) {
        Text(
            text = label,
            color = style.canvas.text,
            fontSize = with(density) { spOf(style.chrome.bodyFontPx) },
            fontFamily = style.canvas.monoFont,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .testTag(testId)
                .clickable(onClick = onClick)
                .padding(
                    horizontal = with(density) { dpOf(style.chrome.itemGapPx) },
                    vertical = with(density) { dpOf(style.chrome.microGapPx) },
                ),
        )
    }
}
