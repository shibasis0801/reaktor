package dev.shibasis.reaktor.flow.graph.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.flow.graph.model.ReaktorPortData

@Composable
internal fun RootBadge(
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val badgePadding = rememberRootBadgePadding()
    Surface(
        color = GraphCanvasRootBadge,
        shape = RoundedCornerShape(ReaktorGraphChromeTokens.rootBadgeRadius),
        tonalElevation = 0.dp,
        modifier = modifier,
    ) {
        Text(
            text = "Root",
            color = GraphCanvasRootBadgeText,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.padding(
                horizontal = badgePadding.horizontal,
                vertical = badgePadding.vertical,
            ),
        )
    }
}

@Composable
internal fun ReaktorNodeTitle(
    title: String,
    titleColor: androidx.compose.ui.graphics.Color,
    isRootNode: Boolean,
    metrics: ReaktorNodeRenderMetrics,
    modifier: Modifier = Modifier,
) {
    // Compose custom layout note:
    // The title and the root badge share a single width budget. This follows the same guidance as
    // the official custom layout docs: measure children explicitly when simple Row weighting would
    // make the text/badge contract implicit and brittle.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(metrics.titleHeight)
            .clip(RoundedCornerShape(topStart = metrics.cornerRadius, topEnd = metrics.cornerRadius))
            .background(titleColor),
    ) {
        Layout(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = metrics.titleHorizontalPadding, vertical = metrics.titleTextPadding),
            content = {
                Text(
                    text = title,
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = metrics.titleFontSize,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GraphCanvasMono,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isRootNode) {
                    RootBadge(fontSize = metrics.rootBadgeFontSize)
                }
            },
        ) { measurables, constraints ->
            val spacing = metrics.titleToBadgeGap.roundToPx()
            val badgePlaceable = measurables.getOrNull(1)?.measure(constraints.copy(minWidth = 0, minHeight = 0))
            val titlePlaceable = measurables.first().measure(
                constraints.copy(
                    minWidth = 0,
                    minHeight = 0,
                    maxWidth = (constraints.maxWidth - (badgePlaceable?.width ?: 0) - if (badgePlaceable != null) spacing else 0)
                        .coerceAtLeast(0),
                ),
            )
            val contentHeight = maxOf(titlePlaceable.height, badgePlaceable?.height ?: 0)
            layout(constraints.maxWidth, constraints.maxHeight) {
                val titleY = ((contentHeight - titlePlaceable.height) / 2).coerceAtLeast(0)
                titlePlaceable.placeRelative(0, titleY)
                badgePlaceable?.let { badge ->
                    val badgeY = ((contentHeight - badge.height) / 2).coerceAtLeast(0)
                    badge.placeRelative(constraints.maxWidth - badge.width, badgeY)
                }
            }
        }
    }
}

@Composable
internal fun ReaktorNodePorts(
    consumerPorts: List<ReaktorPortData>,
    providerPorts: List<ReaktorPortData>,
    metrics: ReaktorNodeRenderMetrics,
    modifier: Modifier = Modifier,
) {
    val rowCount = maxOf(1, maxOf(consumerPorts.size, providerPorts.size))
    // The port body uses intrinsic measurement instead of an unconditional 50/50 split. That keeps
    // one-sided nodes from wasting half their width and matches how React Flow nodes effectively
    // size to rendered content before edges/anchors are resolved.
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            repeat(rowCount) { index ->
                PortEntry(port = consumerPorts.getOrNull(index), alignRight = false)
                PortEntry(port = providerPorts.getOrNull(index), alignRight = true)
            }
        },
    ) { measurables, constraints ->
        val rowHeightPx = metrics.rowHeight.roundToPx()
        val columnGapPx = metrics.columnGap.roundToPx()

        val leftMeasurables = buildList { repeat(rowCount) { add(measurables[it * 2]) } }
        val rightMeasurables = buildList { repeat(rowCount) { add(measurables[it * 2 + 1]) } }
        val leftPreferred = leftMeasurables.maxOfOrNull { measurable ->
            measurable.maxIntrinsicWidth(rowHeightPx)
        } ?: 0
        val rightPreferred = rightMeasurables.maxOfOrNull { measurable ->
            measurable.maxIntrinsicWidth(rowHeightPx)
        } ?: 0
        val hasLeftPorts = consumerPorts.isNotEmpty()
        val hasRightPorts = providerPorts.isNotEmpty()

        val maxContentWidth = constraints.maxWidth.coerceAtLeast(0)
        val availableColumnsWidth = (maxContentWidth - if (hasLeftPorts && hasRightPorts) columnGapPx else 0)
            .coerceAtLeast(0)
        val (leftWidth, rightWidth) = when {
            hasLeftPorts && hasRightPorts -> {
                val preferredTotal = leftPreferred + rightPreferred
                if (preferredTotal <= availableColumnsWidth) {
                    leftPreferred to rightPreferred
                } else {
                    val ratio = if (preferredTotal == 0) 0.5 else leftPreferred.toDouble() / preferredTotal.toDouble()
                    val measuredLeft = (availableColumnsWidth * ratio).toInt().coerceAtLeast(availableColumnsWidth / 3)
                    val measuredRight = (availableColumnsWidth - measuredLeft).coerceAtLeast(availableColumnsWidth / 3)
                    measuredLeft to measuredRight
                }
            }
            hasLeftPorts -> availableColumnsWidth to 0
            hasRightPorts -> 0 to availableColumnsWidth
            else -> availableColumnsWidth to 0
        }

        val placeables = measurables.mapIndexed { index, measurable ->
            val isLeft = index % 2 == 0
            val maxWidth = if (isLeft) leftWidth else rightWidth
            measurable.measure(
                constraints.copy(
                    minWidth = 0,
                    minHeight = 0,
                    maxWidth = maxWidth.coerceAtLeast(0),
                )
            )
        }
        val layoutHeight = (rowCount * rowHeightPx).coerceAtLeast(placeables.maxOfOrNull { it.height } ?: 0)
        layout(constraints.maxWidth, layoutHeight) {
            repeat(rowCount) { row ->
                val left = placeables[row * 2]
                val right = placeables[row * 2 + 1]
                val rowTop = row * rowHeightPx
                val leftX = when {
                    hasLeftPorts -> 0
                    else -> 0
                }
                val rightX = when {
                    hasLeftPorts && hasRightPorts -> constraints.maxWidth - right.width
                    hasRightPorts -> constraints.maxWidth - right.width
                    else -> constraints.maxWidth - right.width
                }
                left.placeRelative(leftX, rowTop + ((rowHeightPx - left.height) / 2).coerceAtLeast(0))
                right.placeRelative(rightX, rowTop + ((rowHeightPx - right.height) / 2).coerceAtLeast(0))
            }
        }
    }
}

@Composable
internal fun PortEntry(
    port: ReaktorPortData?,
    alignRight: Boolean,
    modifier: Modifier = Modifier,
) {
    val metrics = rememberReaktorNodeRenderMetrics()
    if (port == null) {
        Spacer(modifier)
        return
    }

    Row(
        modifier = modifier,
        horizontalArrangement = if (alignRight) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (alignRight) {
            Port(port, metrics)
            Spacer(Modifier.width(metrics.portGap))
            PortDot(port, metrics)
        } else {
            PortDot(port, metrics)
            Spacer(Modifier.width(metrics.portGap))
            Port(port, metrics)
        }
    }
}

@Composable
private fun RowScope.Port(
    port: ReaktorPortData,
    metrics: ReaktorNodeRenderMetrics,
) {
    Text(
        text = port.label,
        color = if (port.connected) GraphCanvasText else GraphCanvasMuted,
        fontSize = metrics.portFontSize,
        fontFamily = GraphCanvasMono,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RowScope.PortDot(
    port: ReaktorPortData,
    metrics: ReaktorNodeRenderMetrics,
) {
    Box(
        modifier = Modifier
            .size(metrics.portDotSize)
            .background(port.color.copy(alpha = if (port.connected) 1f else 0.36f), CircleShape)
            .border(ReaktorGraphChromeTokens.legendItemBorderWidth, port.color, CircleShape),
    )
}
