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
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.style.DefaultReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.dpOf
import dev.shibasis.reaktor.flow.graph.style.spOf

@Composable
internal fun BoxScope.GraphViewportToolbar(
    flow: ReaktorFlowGraph,
    state: ReactFlowState,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFitView: () -> Unit,
    onResetZoom: () -> Unit,
    style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
) {
    val density = LocalDensity.current
    Panel(position = PanelPosition.TopLeft, modifier = Modifier.padding(with(density) { dpOf(style.chrome.overlayPaddingPx) })) {
        Surface(
            color = style.canvas.panelChrome,
            shape = RoundedCornerShape(with(density) { dpOf(style.chrome.panelRadiusPx) }),
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = with(density) { dpOf(style.chrome.shellPaddingXPx) },
                    vertical = with(density) { dpOf(style.chrome.shellPaddingYPx) },
                ),
                horizontalArrangement = Arrangement.spacedBy(with(density) { dpOf(style.chrome.sectionGapPx) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(with(density) { dpOf(style.chrome.microGapPx) })) {
                    Text(
                        text = "Graph",
                        color = style.canvas.text,
                        fontSize = with(density) { spOf(style.chrome.titleFontPx) },
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${flow.nodes.size} nodes • ${flow.edges.size} edges",
                        color = style.canvas.mutedText,
                        fontSize = with(density) { spOf(style.chrome.captionFontPx) },
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(with(density) { dpOf(style.chrome.itemGapPx) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolbarButton(label = "-", onClick = onZoomOut, style = style)
                    ToolbarButton(label = "+", onClick = onZoomIn, style = style)
                    ToolbarButton(label = "Fit", onClick = onFitView, style = style)
                    ToolbarButton(label = "100%", onClick = onResetZoom, style = style)
                    Surface(
                        color = style.canvas.panelSurface,
                        shape = RoundedCornerShape(with(density) { dpOf(style.chrome.panelRadiusPx) }),
                        tonalElevation = 0.dp,
                    ) {
                        Text(
                            text = "${(state.viewport.zoom * 100).toInt()}%",
                            color = style.canvas.text,
                            fontSize = with(density) { spOf(style.chrome.bodyFontPx) },
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(
                                horizontal = with(density) { dpOf(style.chrome.controlPaddingXPx) },
                                vertical = with(density) { dpOf(style.chrome.controlPaddingYPx) },
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
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(
                    horizontal = with(density) { dpOf(style.chrome.controlPaddingXPx) },
                    vertical = with(density) { dpOf(style.chrome.controlPaddingYPx) },
                ),
        )
    }
}
