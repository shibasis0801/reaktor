package dev.shibasis.composeflow.compose.primitives

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.shibasis.composeflow.compose.theme.FlowEdge
import dev.shibasis.composeflow.model.Edge
import dev.shibasis.composeflow.model.EdgeMarker
import dev.shibasis.composeflow.model.HandleType
import dev.shibasis.composeflow.model.MarkerType
import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.Position
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

internal fun DrawScope.drawFlowEdge(
    source: Node,
    target: Node,
    edge: Edge,
    renderStyle: EdgeRenderStyle,
    pathStyle: EdgePathStyle,
    defaultNodeWidth: Double,
    defaultNodeHeight: Double,
) {
    val start = anchorFor(source, edge.sourceHandle, HandleType.Source, defaultNodeWidth, defaultNodeHeight)
    val end = anchorFor(target, edge.targetHandle, HandleType.Target, defaultNodeWidth, defaultNodeHeight)
    val pathData = when (pathStyle) {
        EdgePathStyle.Bezier -> bezierEdgePath(start, end)
        EdgePathStyle.Orthogonal -> orthogonalEdgePath(start, end)
    }

    drawPath(
        path = pathData.path,
        color = (renderStyle.color ?: if (edge.selected) Color(0xFF93C5FD) else FlowEdge).copy(alpha = renderStyle.alpha),
        style = Stroke(width = renderStyle.width ?: if (edge.animated) 3f else 2f),
    )

    edge.markerEnd?.let { marker ->
        drawMarker(
            end = end.point,
            start = pathData.markerStart,
            marker = marker,
            color = (renderStyle.color ?: FlowEdge).copy(alpha = renderStyle.alpha),
        )
    }
}

internal data class FlowEdgePath(
    val path: Path,
    val markerStart: Offset,
)

internal fun bezierEdgePath(start: FlowAnchor, end: FlowAnchor): FlowEdgePath {
    val controlOffset = max(abs(end.point.x - start.point.x), abs(end.point.y - start.point.y)) * 0.34f + 34f
    val startControl = controlPoint(start, controlOffset, outgoing = true)
    val endControl = controlPoint(end, controlOffset, outgoing = false)
    val path = Path().apply {
        moveTo(start.point.x, start.point.y)
        cubicTo(startControl.x, startControl.y, endControl.x, endControl.y, end.point.x, end.point.y)
    }
    return FlowEdgePath(path = path, markerStart = endControl)
}

internal fun orthogonalEdgePath(start: FlowAnchor, end: FlowAnchor): FlowEdgePath {
    val horizontalSource = start.position == Position.Left || start.position == Position.Right
    val markerStart: Offset
    val path = Path().apply {
        moveTo(start.point.x, start.point.y)
        if (horizontalSource) {
            val midX = (start.point.x + end.point.x) / 2f
            lineTo(midX, start.point.y)
            lineTo(midX, end.point.y)
            markerStart = Offset(midX, end.point.y)
            lineTo(end.point.x, end.point.y)
        } else {
            val midY = (start.point.y + end.point.y) / 2f
            lineTo(start.point.x, midY)
            lineTo(end.point.x, midY)
            markerStart = Offset(end.point.x, midY)
            lineTo(end.point.x, end.point.y)
        }
    }
    return FlowEdgePath(path = path, markerStart = markerStart)
}

internal fun DrawScope.drawMarker(
    end: Offset,
    start: Offset,
    marker: EdgeMarker,
    color: Color,
) {
    if (marker.type != MarkerType.ArrowClosed && marker.type != MarkerType.Arrow) {
        return
    }
    val angle = atan2(end.y - start.y, end.x - start.x)
    val length = (marker.width ?: 12.0).toFloat()
    val halfAngle = (PI / 7f).toFloat()
    val p1 = Offset(
        x = (end.x - length * cos((angle - halfAngle).toDouble())).toFloat(),
        y = (end.y - length * sin((angle - halfAngle).toDouble())).toFloat(),
    )
    val p2 = Offset(
        x = (end.x - length * cos((angle + halfAngle).toDouble())).toFloat(),
        y = (end.y - length * sin((angle + halfAngle).toDouble())).toFloat(),
    )
    val arrowPath = Path().apply {
        moveTo(end.x, end.y)
        lineTo(p1.x, p1.y)
        if (marker.type == MarkerType.ArrowClosed) {
            lineTo(p2.x, p2.y)
            close()
        } else {
            moveTo(end.x, end.y)
            lineTo(p2.x, p2.y)
        }
    }
    drawPath(path = arrowPath, color = marker.color?.let(::parseColor) ?: color)
}

internal fun controlPoint(anchor: FlowAnchor, distance: Float, outgoing: Boolean): Offset {
    val signedDistance = if (outgoing) distance else -distance
    return when (anchor.position) {
        Position.Left -> anchor.point.copy(x = anchor.point.x - signedDistance)
        Position.Right -> anchor.point.copy(x = anchor.point.x + signedDistance)
        Position.Top -> anchor.point.copy(y = anchor.point.y - signedDistance)
        Position.Bottom -> anchor.point.copy(y = anchor.point.y + signedDistance)
    }
}

internal fun parseColor(value: String): Color = runCatching {
    val hex = value.removePrefix("#")
    val argb = if (hex.length <= 6) hex.toLong(16) or 0xFF000000L else hex.toLong(16)
    Color(argb)
}.getOrDefault(FlowEdge)
