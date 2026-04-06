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
import androidx.compose.ui.text.font.FontWeight
import dev.shibasis.composeflow.compose.components.Panel
import dev.shibasis.composeflow.model.PanelPosition
import dev.shibasis.composeflow.runtime.ReactFlowState
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
    Panel(position = PanelPosition.TopLeft, modifier = Modifier.padding(ReaktorGraphChromeTokens.overlayPadding)) {
        Surface(
            color = GraphCanvasChrome,
            shape = RoundedCornerShape(ReaktorGraphChromeTokens.toolbarRadius),
            tonalElevation = ReaktorGraphChromeTokens.zeroElevation,
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = ReaktorGraphChromeTokens.toolbarHorizontalPadding,
                    vertical = ReaktorGraphChromeTokens.toolbarVerticalPadding,
                ),
                horizontalArrangement = Arrangement.spacedBy(ReaktorGraphChromeTokens.toolbarSectionSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(ReaktorGraphChromeTokens.toolbarMetadataSpacing)) {
                    Text(
                        text = "Graph",
                        color = GraphCanvasText,
                        fontSize = ReaktorGraphChromeTokens.titleFontSize,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${flow.nodes.size} nodes • ${flow.edges.size} edges",
                        color = GraphCanvasMuted,
                        fontSize = ReaktorGraphChromeTokens.bodyFontSize,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ReaktorGraphChromeTokens.toolbarButtonSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolbarButton(label = "-", onClick = onZoomOut)
                    ToolbarButton(label = "+", onClick = onZoomIn)
                    ToolbarButton(label = "Fit", onClick = onFitView)
                    ToolbarButton(label = "100%", onClick = onResetZoom)
                    Surface(
                        color = ReaktorGraphChromeTokens.panelSurface,
                        shape = RoundedCornerShape(ReaktorGraphChromeTokens.viewportBadgeRadius),
                        tonalElevation = ReaktorGraphChromeTokens.zeroElevation,
                    ) {
                        Text(
                            text = "${(state.viewport.zoom * 100).toInt()}%",
                            color = GraphCanvasText,
                            fontSize = ReaktorGraphChromeTokens.viewportFontSize,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(
                                horizontal = ReaktorGraphChromeTokens.viewportBadgeHorizontalPadding,
                                vertical = ReaktorGraphChromeTokens.viewportBadgeVerticalPadding,
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
    Surface(
        color = ReaktorGraphChromeTokens.panelSurface,
        shape = RoundedCornerShape(ReaktorGraphChromeTokens.toolbarButtonRadius),
        tonalElevation = ReaktorGraphChromeTokens.zeroElevation,
    ) {
        Text(
            text = label,
            color = GraphCanvasText,
            fontSize = ReaktorGraphChromeTokens.buttonFontSize,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(
                    horizontal = ReaktorGraphChromeTokens.toolbarButtonHorizontalPadding,
                    vertical = ReaktorGraphChromeTokens.toolbarButtonVerticalPadding,
                ),
        )
    }
}
