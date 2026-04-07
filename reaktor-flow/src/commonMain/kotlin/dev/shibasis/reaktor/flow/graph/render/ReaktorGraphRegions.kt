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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.flow.graph.layout.DefaultGraphFlowMetrics
import dev.shibasis.reaktor.flow.graph.layout.GraphFlowMetrics
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
    val metrics = DefaultGraphFlowMetrics
    val density = LocalDensity.current
    val regionCornerRadiusPx = with(density) { regionCornerRadiusPx() }
    val regionSelectedStrokePx = with(density) { regionSelectedStrokePx() }
    val regionStrokePx = with(density) { regionStrokePx() }
    val regionDashOnPx = with(density) { regionDashOnPx() }
    val regionDashOffPx = with(density) { regionDashOffPx() }
    val regionLabelOffset = with(density) { regionLabelOffsetPx() }
    Canvas(Modifier.fillMaxSize()) {
        flow.regions.sortedBy { it.depth }.forEach { region ->
            val topLeft = Offset(region.x.toFloat(), region.y.toFloat())
            val size = Size(region.width.toFloat(), region.height.toFloat())
            drawRoundRect(
                color = region.color.copy(alpha = REGION_FILL_ALPHA),
                topLeft = topLeft,
                size = size,
                cornerRadius = CornerRadius(
                    regionCornerRadiusPx,
                    regionCornerRadiusPx,
                ),
            )
            drawRoundRect(
                color = region.color.copy(alpha = REGION_STROKE_ALPHA),
                topLeft = topLeft,
                size = size,
                cornerRadius = CornerRadius(
                    regionCornerRadiusPx,
                    regionCornerRadiusPx,
                ),
                style = Stroke(
                    width = if (selectedGraphId == region.id) {
                        regionSelectedStrokePx
                    } else {
                        regionStrokePx
                    },
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(
                            regionDashOnPx,
                            regionDashOffPx,
                        ),
                    ),
                ),
            )
        }
    }

    flow.regions.sortedBy { it.depth }.forEach { region ->
        Surface(
            color = if (selectedGraphId == region.id) {
                GraphUi.regionSelectedLabelSurface
            } else {
                GraphUi.regionLabelSurface
            },
            shape = RoundedCornerShape(GraphUi.panelRadius),
            tonalElevation = 0.dp,
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = (region.x + regionLabelOffset.x).roundToInt(),
                        y = (region.y + regionLabelOffset.y).roundToInt(),
                    )
                }
                .clickable { onSelectGraph(region.id) },
        ) {
            Text(
                text = region.label,
                color = region.color.copy(alpha = if (selectedGraphId == region.id) 0.96f else 0.72f),
                fontSize = with(density) { spOf(metrics.titleFontSize) },
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(
                    horizontal = GraphUi.regionLabelPadding.horizontal,
                    vertical = GraphUi.regionLabelPadding.vertical,
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
    val metrics = DefaultGraphFlowMetrics
    val left = min(
        flow.nodes.minOfOrNull { it.position.x } ?: 0.0,
        flow.regions.minOfOrNull { it.x } ?: Double.POSITIVE_INFINITY,
    ).takeUnless(Double::isInfinite) ?: 0.0
    val top = min(
        flow.nodes.minOfOrNull { it.position.y } ?: 0.0,
        flow.regions.minOfOrNull { it.y } ?: Double.POSITIVE_INFINITY,
    ).takeUnless(Double::isInfinite) ?: 0.0
    val right = max(
        flow.nodes.maxOfOrNull { it.position.x + (it.width ?: metrics.defaultNodeWidth) } ?: 1.0,
        flow.regions.maxOfOrNull { it.x + it.width } ?: 1.0,
    )
    val bottom = max(
        flow.nodes.maxOfOrNull { it.position.y + (it.height ?: metrics.defaultNodeHeight) } ?: 1.0,
        flow.regions.maxOfOrNull { it.y + it.height } ?: 1.0,
    )
    return FlowBounds(left = left, top = top, width = right - left, height = bottom - top)
}

internal fun readableFlowBounds(
    flow: ReaktorFlowGraph,
    metrics: GraphFlowMetrics = DefaultGraphFlowMetrics,
): FlowBounds {
    val nodeLeft = flow.nodes.minOfOrNull { it.position.x } ?: 0.0
    val nodeTop = flow.nodes.minOfOrNull { it.position.y } ?: 0.0
    val nodeRight = flow.nodes.maxOfOrNull { it.position.x + (it.width ?: metrics.defaultNodeWidth) } ?: metrics.defaultNodeWidth
    val nodeBottom = flow.nodes.maxOfOrNull { it.position.y + (it.height ?: metrics.defaultNodeHeight) } ?: metrics.defaultNodeHeight
    return FlowBounds(
        left = nodeLeft - GraphUi.readablePadding.horizontal,
        top = nodeTop - GraphUi.readablePadding.vertical,
        width = (nodeRight - nodeLeft) + GraphUi.readablePadding.horizontal * 2.0,
        height = (nodeBottom - nodeTop) + GraphUi.readablePadding.vertical * 2.0,
    )
}
