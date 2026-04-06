package dev.shibasis.reaktor.flow.graph.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
internal fun BoxScope.GraphRegionsOverlay(
    flow: ReaktorFlowGraph,
    selectedGraphId: String?,
    onSelectGraph: (String?) -> Unit,
) {
    Canvas(Modifier.fillMaxSize()) {
        flow.regions.sortedBy { it.depth }.forEach { region ->
            val topLeft = Offset(region.x.toFloat(), region.y.toFloat())
            val size = Size(region.width.toFloat(), region.height.toFloat())
            drawRoundRect(
                color = region.color.copy(alpha = ReaktorGraphChromeTokens.regionFillAlpha),
                topLeft = topLeft,
                size = size,
                cornerRadius = CornerRadius(
                    ReaktorGraphChromeTokens.regionCornerRadiusPx,
                    ReaktorGraphChromeTokens.regionCornerRadiusPx,
                ),
            )
            drawRoundRect(
                color = region.color.copy(alpha = ReaktorGraphChromeTokens.regionStrokeAlpha),
                topLeft = topLeft,
                size = size,
                cornerRadius = CornerRadius(
                    ReaktorGraphChromeTokens.regionCornerRadiusPx,
                    ReaktorGraphChromeTokens.regionCornerRadiusPx,
                ),
                style = Stroke(
                    width = if (selectedGraphId == region.id) {
                        ReaktorGraphChromeTokens.regionSelectedStrokePx
                    } else {
                        ReaktorGraphChromeTokens.regionStrokePx
                    },
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(
                            ReaktorGraphChromeTokens.regionDashOnPx,
                            ReaktorGraphChromeTokens.regionDashOffPx,
                        ),
                    ),
                ),
            )
        }
    }

    flow.regions.sortedBy { it.depth }.forEach { region ->
        Surface(
            color = if (selectedGraphId == region.id) {
                ReaktorGraphChromeTokens.regionSelectedLabelSurface
            } else {
                ReaktorGraphChromeTokens.regionLabelSurface
            },
            shape = RoundedCornerShape(ReaktorGraphChromeTokens.regionLabelRadius),
            tonalElevation = ReaktorGraphChromeTokens.zeroElevation,
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = (region.x + ReaktorGraphChromeTokens.regionLabelOffsetXPx).roundToInt(),
                        y = (region.y + ReaktorGraphChromeTokens.regionLabelOffsetYPx).roundToInt(),
                    )
                }
                .clickable { onSelectGraph(region.id) },
        ) {
            Text(
                text = region.label,
                color = region.color.copy(alpha = if (selectedGraphId == region.id) 0.96f else 0.72f),
                fontSize = ReaktorGraphChromeTokens.sectionTitleFontSize,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(
                    horizontal = ReaktorGraphChromeTokens.regionLabelHorizontalPadding,
                    vertical = ReaktorGraphChromeTokens.regionLabelVerticalPadding,
                ),
            )
        }
    }
}

internal data class FlowBounds(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
)

internal fun flowBounds(flow: ReaktorFlowGraph): FlowBounds {
    val left = min(
        flow.nodes.minOfOrNull { it.position.x } ?: 0.0,
        flow.regions.minOfOrNull { it.x } ?: Double.POSITIVE_INFINITY,
    ).takeUnless(Double::isInfinite) ?: 0.0
    val top = min(
        flow.nodes.minOfOrNull { it.position.y } ?: 0.0,
        flow.regions.minOfOrNull { it.y } ?: Double.POSITIVE_INFINITY,
    ).takeUnless(Double::isInfinite) ?: 0.0
    val right = max(
        flow.nodes.maxOfOrNull { it.position.x + (it.width ?: ReaktorDefaultNodeWidthPx) } ?: 1.0,
        flow.regions.maxOfOrNull { it.x + it.width } ?: 1.0,
    )
    val bottom = max(
        flow.nodes.maxOfOrNull { it.position.y + (it.height ?: ReaktorDefaultNodeHeightPx) } ?: 1.0,
        flow.regions.maxOfOrNull { it.y + it.height } ?: 1.0,
    )
    return FlowBounds(left = left, top = top, width = right - left, height = bottom - top)
}

internal fun readableFlowBounds(
    flow: ReaktorFlowGraph,
    defaultNodeWidthPx: Double,
    defaultNodeHeightPx: Double,
): FlowBounds {
    val nodeLeft = flow.nodes.minOfOrNull { it.position.x } ?: 0.0
    val nodeTop = flow.nodes.minOfOrNull { it.position.y } ?: 0.0
    val nodeRight = flow.nodes.maxOfOrNull { it.position.x + (it.width ?: defaultNodeWidthPx) } ?: defaultNodeWidthPx
    val nodeBottom = flow.nodes.maxOfOrNull { it.position.y + (it.height ?: defaultNodeHeightPx) } ?: defaultNodeHeightPx
    return FlowBounds(
        left = nodeLeft - ReaktorGraphViewportTokens.readablePaddingXPx,
        top = nodeTop - ReaktorGraphViewportTokens.readablePaddingYPx,
        width = (nodeRight - nodeLeft) + ReaktorGraphViewportTokens.readablePaddingXPx * 2.0,
        height = (nodeBottom - nodeTop) + ReaktorGraphViewportTokens.readablePaddingYPx * 2.0,
    )
}
