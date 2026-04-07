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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.shibasis.composeflow.compose.components.Panel
import dev.shibasis.composeflow.model.PanelPosition
import dev.shibasis.composeflow.runtime.ReactFlowState
import dev.shibasis.reaktor.flow.graph.layout.DefaultGraphFlowMetrics
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph

@Composable
internal fun BoxScope.GraphViewportToolbar(
    flow: ReaktorFlowGraph,
    state: ReactFlowState,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFitView: () -> Unit,
    onResetZoom: () -> Unit,
) {
    val metrics = DefaultGraphFlowMetrics
    val density = LocalDensity.current
    Panel(position = PanelPosition.BottomRight, modifier = Modifier.padding(GraphUi.overlayPadding)) {
        Surface(
            color = GraphCanvasChrome,
            shape = RoundedCornerShape(GraphUi.panelRadius),
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = GraphUi.shellPadding.horizontal,
                    vertical = GraphUi.shellPadding.vertical,
                ),
                horizontalArrangement = Arrangement.spacedBy(GraphUi.sectionGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(GraphUi.microGap)) {
                    Text(
                        text = "Graph",
                        color = GraphCanvasText,
                        fontSize = with(density) { spOf(metrics.titleFontSize) },
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${flow.nodes.size} nodes • ${flow.edges.size} edges",
                        color = GraphCanvasMuted,
                        fontSize = with(density) { spOf(metrics.portFontSize) },
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(GraphUi.itemGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolbarButton(label = "-", onClick = onZoomOut)
                    ToolbarButton(label = "+", onClick = onZoomIn)
                    ToolbarButton(label = "Fit", onClick = onFitView)
                    ToolbarButton(label = "100%", onClick = onResetZoom)
                    Surface(
                        color = GraphUi.panelSurface,
                        shape = RoundedCornerShape(GraphUi.panelRadius),
                        tonalElevation = 0.dp,
                    ) {
                        Text(
                            text = "${(state.viewport.zoom * 100).toInt()}%",
                            color = GraphCanvasText,
                            fontSize = with(density) { spOf(metrics.portFontSize) },
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(
                                horizontal = GraphUi.controlPadding.horizontal,
                                vertical = GraphUi.controlPadding.vertical,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolbarButton(
    label: String,
    onClick: () -> Unit,
) {
    val metrics = DefaultGraphFlowMetrics
    val density = LocalDensity.current
    Surface(
        color = GraphUi.panelSurface,
        shape = RoundedCornerShape(GraphUi.panelRadius),
        tonalElevation = 0.dp,
    ) {
        Text(
            text = label,
            color = GraphCanvasText,
            fontSize = with(density) { spOf(metrics.portFontSize * 0.92f) },
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(
                    horizontal = GraphUi.controlPadding.horizontal,
                    vertical = GraphUi.controlPadding.vertical,
                ),
        )
    }
}
