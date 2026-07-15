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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.flow.graph.model.ReaktorPortData
import dev.shibasis.reaktor.flow.graph.style.DefaultReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.dpOf
import dev.shibasis.reaktor.flow.graph.style.spOf

@Composable
internal fun RootBadge(
    style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Surface(
        color = style.canvas.rootBadge,
        shape = RoundedCornerShape(with(density) { dpOf(style.node.cornerRadiusPx) }),
        tonalElevation = 0.dp,
        modifier = modifier,
    ) {
        Text(
            text = "Root",
            color = style.canvas.rootBadgeText,
            fontSize = with(density) { spOf(style.node.rootBadgeFontPx) },
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.padding(
                horizontal = with(density) { dpOf(style.node.rootBadgePaddingXPx) },
                vertical = with(density) { dpOf(style.node.rootBadgePaddingYPx) },
            ),
        )
    }
}

@Composable
internal fun ReaktorNodeTitle(
    title: String,
    titleColor: Color,
    isRootNode: Boolean,
    style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
    tag: String? = null,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val titleHeight = with(density) { dpOf(style.node.titleHeightPx) }
    val cornerRadius = with(density) { dpOf(style.node.cornerRadiusPx) }
    val titlePaddingX = with(density) { dpOf(style.node.titlePaddingXPx) }
    val titlePaddingY = with(density) { dpOf(style.node.titlePaddingYPx) }
    val titleToBadgeGapPx = with(density) { dpOf(style.node.titleToBadgeGapPx).roundToPx() }
    val titleFontSize = with(density) { spOf(style.node.titleFontPx) }

    // Compose custom layout note:
    // The title and root badge share a single width budget. This keeps the sizing contract
    // explicit instead of relying on weighted Rows and hoping the title/badge split stays stable.
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
                    color = Color.White,
                    fontSize = titleFontSize,
                    fontWeight = FontWeight.Bold,
                    fontFamily = style.canvas.monoFont,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (tag != null || isRootNode) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(with(density) { dpOf(style.node.titleToBadgeGapPx) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (tag != null) {
                            // Uppercase kind tag — the mono type badge from the Machine Signal
                            // node anatomy (instant Blueprint identity per card).
                            Text(
                                text = tag,
                                color = Color.White.copy(alpha = 0.76f),
                                fontSize = with(density) { spOf(style.node.rootBadgeFontPx) },
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = style.canvas.monoFont,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                            )
                        }
                        if (isRootNode) {
                            RootBadge(style = style)
                        }
                    }
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
    style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val rowCount = maxOf(1, maxOf(consumerPorts.size, providerPorts.size))
    val rowHeightPx = with(density) { dpOf(style.port.rowHeightPx).roundToPx() }
    val columnGapPx = with(density) { dpOf(style.port.columnGapPx).roundToPx() }

    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            repeat(rowCount) { index ->
                PortEntry(port = consumerPorts.getOrNull(index), alignRight = false, style = style)
                PortEntry(port = providerPorts.getOrNull(index), alignRight = true, style = style)
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
                ),
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
    style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    if (port == null) {
        Spacer(modifier)
        return
    }

    Row(
        modifier = modifier.semantics {
            val direction = if (alignRight) "provider" else "consumer"
            val connection = if (port.connected) "connected" else "open"
            contentDescription =
                "Graph port: $direction ${port.label}; type ${port.type}; $connection"
        },
        horizontalArrangement = if (alignRight) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (alignRight) {
            Port(port, style)
            Spacer(Modifier.width(with(density) { dpOf(style.port.gapPx) }))
            PortDot(port, style, filled = true)
        } else {
            PortDot(port, style, filled = false)
            Spacer(Modifier.width(with(density) { dpOf(style.port.gapPx) }))
            Port(port, style)
        }
    }
}

@Composable
private fun RowScope.Port(
    port: ReaktorPortData,
    style: ReaktorGraphStyle,
) {
    val density = LocalDensity.current
    Text(
        text = port.label,
        color = if (port.connected) style.canvas.text else style.canvas.mutedText,
        fontSize = with(density) { spOf(style.port.fontPx) },
        fontFamily = style.canvas.monoFont,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RowScope.PortDot(
    port: ReaktorPortData,
    style: ReaktorGraphStyle,
    filled: Boolean,
) {
    val density = LocalDensity.current
    // Blueprint pin convention: providers (outputs) are filled dots, consumers (inputs) are
    // hollow rings; unconnected pins sit dimmed until a wire lands.
    val connectedAlpha = if (port.connected) 1f else 0.45f
    Box(
        modifier = Modifier
            .size(with(density) { dpOf(style.port.dotSizePx) })
            .background(
                if (filled) port.color.copy(alpha = connectedAlpha) else Color.Transparent,
                CircleShape,
            )
            .border(
                with(density) { dpOf(style.chrome.borderWidthPx * 1.35) },
                port.color.copy(alpha = if (port.connected) 1f else 0.6f),
                CircleShape,
            ),
    )
}
