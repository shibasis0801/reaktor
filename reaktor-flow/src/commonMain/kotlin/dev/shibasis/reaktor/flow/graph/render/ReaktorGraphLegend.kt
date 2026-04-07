package dev.shibasis.reaktor.flow.graph.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.shibasis.composeflow.compose.components.Panel
import dev.shibasis.composeflow.model.PanelPosition
import dev.shibasis.reaktor.flow.graph.layout.DefaultGraphFlowMetrics
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind

@Composable
internal fun BoxScope.GraphKindLegend(
    flow: ReaktorFlowGraph,
    highlightedKind: ReaktorNodeKind?,
    onHighlightKind: (ReaktorNodeKind?) -> Unit,
) {
    val metrics = DefaultGraphFlowMetrics
    val density = LocalDensity.current
    val counts = remember(flow) {
        flow.nodes.groupingBy { (it.data as? ReaktorGraphNodeData)?.kind ?: ReaktorNodeKind.Node }.eachCount()
    }

    Panel(position = PanelPosition.BottomLeft, modifier = Modifier.padding(GraphUi.overlayPadding)) {
        Surface(
            color = GraphCanvasChrome,
            shape = RoundedCornerShape(GraphUi.panelRadius),
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = GraphUi.shellPadding.horizontal,
                    vertical = GraphUi.shellPadding.vertical,
                ),
                verticalArrangement = Arrangement.spacedBy(GraphUi.itemGap),
            ) {
                Text(
                    text = "Node Types",
                    color = GraphCanvasText,
                    fontSize = with(density) { spOf(metrics.titleFontSize) },
                    fontWeight = FontWeight.SemiBold,
                )
                ReaktorNodeKind.entries.forEach { kind ->
                    val selected = highlightedKind == kind
                    Row(
                        modifier = Modifier
                            .width(GraphUi.legendWidth)
                            .background(
                                if (selected) kind.bodyColor.copy(alpha = 0.95f) else GraphUi.legendItemSurface,
                                RoundedCornerShape(GraphUi.panelRadius),
                            )
                            .border(
                                width = GraphUi.borderWidth,
                                color = if (selected) kind.borderColor else GraphCanvasBorder,
                                shape = RoundedCornerShape(GraphUi.panelRadius),
                            )
                            .clickable { onHighlightKind(if (selected) null else kind) }
                            .padding(
                                horizontal = GraphUi.controlPadding.horizontal,
                                vertical = GraphUi.controlPadding.vertical,
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(GraphUi.itemGap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(GraphUi.indicatorSize)
                                    .background(kind.borderColor, CircleShape),
                            )
                            Text(
                                text = kind.label,
                                color = GraphCanvasText,
                                fontSize = with(density) { spOf(metrics.portFontSize) },
                            )
                        }
                        Text(
                            text = (counts[kind] ?: 0).toString(),
                            color = if (selected) androidx.compose.ui.graphics.Color.White else GraphCanvasMuted,
                            fontSize = with(density) { spOf(metrics.portFontSize) },
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}
