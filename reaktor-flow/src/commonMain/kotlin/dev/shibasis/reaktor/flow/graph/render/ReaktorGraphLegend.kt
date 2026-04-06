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
import androidx.compose.ui.text.font.FontWeight
import dev.shibasis.composeflow.compose.components.Panel
import dev.shibasis.composeflow.model.PanelPosition
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind

@Composable
internal fun BoxScope.GraphKindLegend(
    flow: ReaktorFlowGraph,
    highlightedKind: ReaktorNodeKind?,
    onHighlightKind: (ReaktorNodeKind?) -> Unit,
) {
    val counts = remember(flow) {
        flow.nodes.groupingBy { (it.data as? ReaktorGraphNodeData)?.kind ?: ReaktorNodeKind.Node }.eachCount()
    }

    Panel(position = PanelPosition.BottomLeft, modifier = Modifier.padding(ReaktorGraphChromeTokens.overlayPadding)) {
        Surface(
            color = GraphCanvasChrome,
            shape = RoundedCornerShape(ReaktorGraphChromeTokens.legendRadius),
            tonalElevation = ReaktorGraphChromeTokens.zeroElevation,
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = ReaktorGraphChromeTokens.legendHorizontalPadding,
                    vertical = ReaktorGraphChromeTokens.legendVerticalPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(ReaktorGraphChromeTokens.legendItemSpacing),
            ) {
                Text(
                    text = "Node Types",
                    color = GraphCanvasText,
                    fontSize = ReaktorGraphChromeTokens.sectionTitleFontSize,
                    fontWeight = FontWeight.SemiBold,
                )
                ReaktorNodeKind.entries.forEach { kind ->
                    val selected = highlightedKind == kind
                    Row(
                        modifier = Modifier
                            .width(ReaktorGraphChromeTokens.legendWidth)
                            .background(
                                if (selected) kind.bodyColor.copy(alpha = 0.95f) else ReaktorGraphChromeTokens.legendItemSurface,
                                RoundedCornerShape(ReaktorGraphChromeTokens.legendItemRadius),
                            )
                            .border(
                                width = ReaktorGraphChromeTokens.legendItemBorderWidth,
                                color = if (selected) kind.borderColor else GraphCanvasBorder,
                                shape = RoundedCornerShape(ReaktorGraphChromeTokens.legendItemRadius),
                            )
                            .clickable { onHighlightKind(if (selected) null else kind) }
                            .padding(
                                horizontal = ReaktorGraphChromeTokens.legendItemHorizontalPadding,
                                vertical = ReaktorGraphChromeTokens.legendItemVerticalPadding,
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(ReaktorGraphChromeTokens.legendDotGap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(ReaktorGraphChromeTokens.legendIndicatorSize)
                                    .background(kind.borderColor, CircleShape),
                            )
                            Text(kind.label, color = GraphCanvasText, fontSize = ReaktorGraphChromeTokens.bodyFontSize)
                        }
                        Text(
                            text = (counts[kind] ?: 0).toString(),
                            color = if (selected) androidx.compose.ui.graphics.Color.White else GraphCanvasMuted,
                            fontSize = ReaktorGraphChromeTokens.bodyFontSize,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}
