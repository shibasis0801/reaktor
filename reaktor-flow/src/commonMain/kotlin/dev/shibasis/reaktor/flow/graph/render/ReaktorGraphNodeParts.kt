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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.flow.graph.layout.GraphFlowMetrics
import dev.shibasis.reaktor.flow.graph.model.ReaktorPortData

@Composable
internal fun RootBadge(
    metrics: GraphFlowMetrics,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Surface(
        color = GraphCanvasRootBadge,
        shape = RoundedCornerShape(with(density) { dpOf(metrics.nodeCornerRadius) }),
        tonalElevation = 0.dp,
        modifier = modifier,
    ) {
        Text(
            text = "Root",
            color = GraphCanvasRootBadgeText,
            fontSize = with(density) { spOf(metrics.rootBadgeFontSize) },
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.padding(
                horizontal = with(density) { dpOf(metrics.rootBadgePaddingX) },
                vertical = with(density) { dpOf(metrics.rootBadgePaddingY) },
            ),
        )
    }
}

@Composable
internal fun ReaktorNodeTitle(
    title: String,
    titleColor: androidx.compose.ui.graphics.Color,
    isRootNode: Boolean,
    metrics: GraphFlowMetrics,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val titleHeight = with(density) { dpOf(metrics.titleHeight) }
    val cornerRadius = with(density) { dpOf(metrics.nodeCornerRadius) }
    val titlePaddingX = with(density) { dpOf(metrics.titlePaddingX) }
    val titlePaddingY = with(density) { dpOf(metrics.titlePaddingY) }
    val titleToBadgeGapPx = with(density) { dpOf(metrics.titleToBadgeGap).roundToPx() }
    val titleFontSize = with(density) { spOf(metrics.titleFontSize) }

    // Compose custom layout note:
    // The title and root badge share a single width budget. This keeps the sizing contract explicit
    // instead of relying on weighted Rows and hoping the title/badge split stays readable.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(titleHeight)
            .clip(RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius))
            .background(titleColor),
    ) {
        Layout(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = titlePaddingX, vertical = titlePaddingY),
            content = {
                Text(
                    text = title,
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = titleFontSize,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GraphCanvasMono,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isRootNode) {
                    RootBadge(metrics = metrics)
                }
            },
        ) { measurables, constraints ->
            val badgePlaceable = measurables.getOrNull(1)?.measure(constraints.copy(minWidth = 0, minHeight = 0))
            val titlePlaceable = measurables.first().measure(
                constraints.copy(
                    minWidth = 0,
                    minHeight = 0,
                    maxWidth = (constraints.maxWidth - (badgePlaceable?.width ?: 0) - if (badgePlaceable != null) titleToBadgeGapPx else 0)
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
    metrics: GraphFlowMetrics,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val rowCount = maxOf(1, maxOf(consumerPorts.size, providerPorts.size))
    val rowHeightPx = with(density) { dpOf(metrics.rowHeight).roundToPx() }
    val columnGapPx = with(density) { dpOf(metrics.columnGap).roundToPx() }

    // Intrinsic measurement keeps one-sided nodes from wasting half their width while still
    // giving both columns a real width budget before placement.
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            repeat(rowCount) { index ->
                PortEntry(port = consumerPorts.getOrNull(index), alignRight = false, metrics = metrics)
                PortEntry(port = providerPorts.getOrNull(index), alignRight = true, metrics = metrics)
            }
        },
    ) { measurables, constraints ->
        val leftMeasurables = buildList { repeat(rowCount) { add(measurables[it * 2]) } }
        val rightMeasurables = buildList { repeat(rowCount) { add(measurables[it * 2 + 1]) } }
        val leftPreferred = leftMeasurables.maxOfOrNull { it.maxIntrinsicWidth(rowHeightPx) } ?: 0
        val rightPreferred = rightMeasurables.maxOfOrNull { it.maxIntrinsicWidth(rowHeightPx) } ?: 0
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
            val maxWidth = if (index % 2 == 0) leftWidth else rightWidth
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
                val rightX = constraints.maxWidth - right.width
                left.placeRelative(0, rowTop + ((rowHeightPx - left.height) / 2).coerceAtLeast(0))
                right.placeRelative(rightX, rowTop + ((rowHeightPx - right.height) / 2).coerceAtLeast(0))
            }
        }
    }
}

@Composable
internal fun PortEntry(
    port: ReaktorPortData?,
    alignRight: Boolean,
    metrics: GraphFlowMetrics,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
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
            Spacer(Modifier.width(with(density) { dpOf(metrics.portGap) }))
            PortDot(port, metrics)
        } else {
            PortDot(port, metrics)
            Spacer(Modifier.width(with(density) { dpOf(metrics.portGap) }))
            Port(port, metrics)
        }
    }
}

@Composable
private fun RowScope.Port(
    port: ReaktorPortData,
    metrics: GraphFlowMetrics,
) {
    val density = LocalDensity.current
    Text(
        text = port.label,
        color = if (port.connected) GraphCanvasText else GraphCanvasMuted,
        fontSize = with(density) { spOf(metrics.portFontSize) },
        fontFamily = GraphCanvasMono,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RowScope.PortDot(
    port: ReaktorPortData,
    metrics: GraphFlowMetrics,
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .size(with(density) { dpOf(metrics.portDotSize) })
            .background(port.color.copy(alpha = if (port.connected) 1f else 0.36f), CircleShape)
            .border(GraphUi.borderWidth, port.color, CircleShape),
    )
}
