package dev.shibasis.composeflow.compose.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import dev.shibasis.composeflow.compose.theme.FlowEdge
import dev.shibasis.composeflow.compose.theme.FlowPanelSurface
import dev.shibasis.composeflow.compose.theme.FlowSelection
import dev.shibasis.composeflow.compose.theme.FlowSizing
import dev.shibasis.composeflow.compose.theme.FlowSurface
import dev.shibasis.composeflow.model.Edge
import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.XYPosition
import dev.shibasis.composeflow.runtime.graphBounds
import kotlin.math.max
import kotlin.math.min

@Composable
fun MiniMap(
    modifier: Modifier = Modifier,
    nodes: List<Node>,
    edges: List<Edge>,
    defaultNodeWidth: Double = 180.0,
    defaultNodeHeight: Double = 96.0,
) {
    Surface(
        modifier = modifier.width(FlowSizing.minimapWidth).size(width = FlowSizing.minimapWidth, height = FlowSizing.minimapHeight),
        color = FlowPanelSurface,
        shape = RoundedCornerShape(FlowSizing.minimapRadius),
        tonalElevation = FlowSizing.minimapElevation,
    ) {
        Canvas(Modifier.fillMaxSize().padding(FlowSizing.minimapInnerPadding)) {
            if (nodes.isEmpty()) return@Canvas
            val bounds = graphBounds(nodes, defaultNodeWidth, defaultNodeHeight)
            val scaleX = size.width / max(bounds.width, 1f)
            val scaleY = size.height / max(bounds.height, 1f)
            val scale = min(scaleX, scaleY)

            edges.forEach { edge ->
                val source = nodes.firstOrNull { it.id == edge.source } ?: return@forEach
                val target = nodes.firstOrNull { it.id == edge.target } ?: return@forEach
                drawLine(
                    color = FlowEdge.copy(alpha = 0.55f),
                    start = miniMapPoint(source.position, bounds.left, bounds.top, scale),
                    end = miniMapPoint(target.position, bounds.left, bounds.top, scale),
                    strokeWidth = FlowSizing.minimapEdgeStrokePx,
                )
            }

            nodes.forEach { node ->
                val width = ((node.measured?.width ?: node.width ?: defaultNodeWidth) * scale).toFloat().coerceAtLeast(FlowSizing.minimapNodeMinSizePx)
                val height = ((node.measured?.height ?: node.height ?: defaultNodeHeight) * scale).toFloat().coerceAtLeast(FlowSizing.minimapNodeMinSizePx)
                val origin = miniMapPoint(node.position, bounds.left, bounds.top, scale)
                drawRoundRect(
                    color = if (node.selected) FlowSelection else FlowSurface,
                    topLeft = origin,
                    size = Size(width, height),
                    cornerRadius = CornerRadius(FlowSizing.minimapNodeCornerPx, FlowSizing.minimapNodeCornerPx),
                )
            }
        }
    }
}

internal fun miniMapPoint(position: XYPosition, left: Float, top: Float, scale: Float): Offset = Offset(
    x = (position.x.toFloat() - left) * scale,
    y = (position.y.toFloat() - top) * scale,
)
