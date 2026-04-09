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
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind
import dev.shibasis.reaktor.flow.graph.style.DefaultReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.dpOf
import dev.shibasis.reaktor.flow.graph.style.legendItemSurface
import dev.shibasis.reaktor.flow.graph.style.spOf

@Composable
internal fun BoxScope.GraphKindLegend(
    flow: ReaktorFlowGraph,
    highlightedKind: ReaktorNodeKind?,
    onHighlightKind: (ReaktorNodeKind?) -> Unit,
    style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
) {
    val density = LocalDensity.current
    val counts = remember(flow) {
        flow.nodes.groupingBy { (it.data as? ReaktorGraphNodeData)?.kind ?: ReaktorNodeKind.Node }.eachCount()
    }

    Panel(position = PanelPosition.BottomLeft, modifier = Modifier.padding(with(density) { dpOf(style.chrome.overlayPaddingPx) })) {
        Column(
            modifier = Modifier.padding(
                horizontal = with(density) { dpOf(style.chrome.shellPaddingXPx) },
                vertical = with(density) { dpOf(style.chrome.shellPaddingYPx) },
            ),
            verticalArrangement = Arrangement.spacedBy(with(density) { dpOf(style.chrome.itemGapPx) }),
        ) {
            ReaktorNodeKind.entries.forEach { kind ->
                val selected = highlightedKind == kind
                Row(
                    modifier = Modifier
                        .width(with(density) { dpOf(style.chrome.legendWidthPx) })
                        .background(
                            if (selected) kind.bodyColor.copy(alpha = 0.95f) else style.legendItemSurface(),
                            RoundedCornerShape(with(density) { dpOf(style.chrome.panelRadiusPx) }),
                        )
                        .border(
                            width = with(density) { dpOf(style.chrome.borderWidthPx) },
                            color = if (selected) kind.borderColor else style.canvas.border,
                            shape = RoundedCornerShape(with(density) { dpOf(style.chrome.panelRadiusPx) }),
                        )
                        .clickable { onHighlightKind(if (selected) null else kind) }
                        .padding(
                            horizontal = with(density) { dpOf(style.chrome.controlPaddingXPx) },
                            vertical = with(density) { dpOf(style.chrome.controlPaddingYPx) },
                        ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(with(density) { dpOf(style.chrome.itemGapPx) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(with(density) { dpOf(style.chrome.indicatorSizePx) })
                                .background(kind.borderColor, CircleShape),
                        )
                        Text(
                            text = kind.label,
                            color = style.canvas.text,
                            fontSize = with(density) { spOf(style.chrome.bodyFontPx) },
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = (counts[kind] ?: 0).toString(),
                        color = if (selected) androidx.compose.ui.graphics.Color.White else style.canvas.mutedText,
                        fontSize = with(density) { spOf(style.chrome.bodyFontPx) },
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
