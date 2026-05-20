package dev.shibasis.composeflow.compose.primitives

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.shibasis.composeflow.compose.theme.FlowEdge
import dev.shibasis.composeflow.compose.theme.FlowSelection
import dev.shibasis.composeflow.compose.theme.FlowSizing
import dev.shibasis.composeflow.model.Edge
import dev.shibasis.composeflow.model.EdgeMarker
import dev.shibasis.composeflow.model.HandleType
import dev.shibasis.composeflow.model.MarkerType
import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.Position
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

internal fun DrawScope.drawFlowEdge(
    source: Node,
    target: Node,
    edge: Edge,
    renderStyle: EdgeRenderStyle,
    pathStyle: EdgePathStyle,
    defaultNodeWidth: Double,
    defaultNodeHeight: Double,
) {
    // React Flow/xyflow uses editor-space anchor points and derives the edge path from those
    // anchors after node measurement. Keep the same separation here: node layout decides anchors,
    // edge rendering only consumes those resolved points.
    val start = anchorFor(source, edge.sourceHandle, HandleType.Source, defaultNodeWidth, defaultNodeHeight)
    val end = anchorFor(target, edge.targetHandle, HandleType.Target, defaultNodeWidth, defaultNodeHeight)
    val pathData = when (pathStyle) {
        EdgePathStyle.Bezier -> bezierEdgePath(start, end)
        EdgePathStyle.Orthogonal -> orthogonalEdgePath(start, end)
        EdgePathStyle.Straight -> straightEdgePath(start, end)
        EdgePathStyle.SmoothStep -> smoothStepEdgePath(start, end)
        EdgePathStyle.SimpleBezier -> simpleBezierEdgePath(start, end)
    }

    drawPath(
        path = pathData.path,
        color = (renderStyle.color ?: if (edge.selected) FlowSelection else FlowEdge).copy(alpha = renderStyle.alpha),
        style = Stroke(
            width = renderStyle.width ?: if (edge.animated) {
                FlowSizing.animatedEdgeStrokePx
            } else {
                FlowSizing.defaultEdgeStrokePx
            }
        ),
    )

    edge.markerStart?.let { marker ->
        val dir = end.point - start.point
        val len = sqrt(dir.x * dir.x + dir.y * dir.y).coerceAtLeast(1f)
        val markerAnchor = Offset(start.point.x + dir.x / len * 20f, start.point.y + dir.y / len * 20f)
        drawMarker(
            end = start.point,
            start = markerAnchor,
            marker = marker,
            color = (renderStyle.color ?: FlowEdge).copy(alpha = renderStyle.alpha),
        )
    }

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
    val rawDx = end.point.x - start.point.x
    val rawDy = end.point.y - start.point.y
    val dx = abs(rawDx)
    val dy = abs(rawDy)
    var startControl = controlPoint(
        anchor = start,
        distance = tangentDistance(start, dx, dy),
        outgoing = true,
    )
    var endControl = controlPoint(
        anchor = end,
        distance = tangentDistance(end, dx, dy),
        outgoing = false,
    )
    if (isNearCollinearVerticalEdge(start, end, dx, dy)) {
        val bias = verticalCollinearBias(start, rawDx)
        startControl = startControl.copy(x = startControl.x + bias)
        endControl = endControl.copy(x = endControl.x + bias)
    }
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

internal fun straightEdgePath(start: FlowAnchor, end: FlowAnchor): FlowEdgePath {
    val path = Path().apply {
        moveTo(start.point.x, start.point.y)
        lineTo(end.point.x, end.point.y)
    }
    return FlowEdgePath(path = path, markerStart = start.point)
}

internal fun smoothStepEdgePath(
    start: FlowAnchor,
    end: FlowAnchor,
    borderRadius: Float = 5f,
): FlowEdgePath {
    val horizontalSource = start.position == Position.Left || start.position == Position.Right
    val markerStart: Offset
    val path = Path().apply {
        moveTo(start.point.x, start.point.y)
        if (horizontalSource) {
            val midX = (start.point.x + end.point.x) / 2f
            val dy = end.point.y - start.point.y
            val r = min(borderRadius, abs(dy) / 2f).let { min(it, abs(midX - start.point.x)) }
            val dirX = if (midX > start.point.x) 1f else -1f
            val dirY = if (dy > 0f) 1f else -1f

            lineTo(midX - dirX * r, start.point.y)
            quadraticTo(midX, start.point.y, midX, start.point.y + dirY * r)
            lineTo(midX, end.point.y - dirY * r)
            quadraticTo(midX, end.point.y, midX + dirX * r, end.point.y)
            markerStart = Offset(midX + dirX * r, end.point.y)
            lineTo(end.point.x, end.point.y)
        } else {
            val midY = (start.point.y + end.point.y) / 2f
            val dx = end.point.x - start.point.x
            val r = min(borderRadius, abs(dx) / 2f).let { min(it, abs(midY - start.point.y)) }
            val dirX = if (dx > 0f) 1f else -1f
            val dirY = if (midY > start.point.y) 1f else -1f

            lineTo(start.point.x, midY - dirY * r)
            quadraticTo(start.point.x, midY, start.point.x + dirX * r, midY)
            lineTo(end.point.x - dirX * r, midY)
            quadraticTo(end.point.x, midY, end.point.x, midY + dirY * r)
            markerStart = Offset(end.point.x, midY + dirY * r)
            lineTo(end.point.x, end.point.y)
        }
    }
    return FlowEdgePath(path = path, markerStart = markerStart)
}

internal fun simpleBezierEdgePath(start: FlowAnchor, end: FlowAnchor): FlowEdgePath {
    val midX = (start.point.x + end.point.x) / 2f
    val midY = (start.point.y + end.point.y) / 2f
    val controlPoint = Offset(midX, midY)
    val path = Path().apply {
        moveTo(start.point.x, start.point.y)
        quadraticTo(controlPoint.x, controlPoint.y, end.point.x, end.point.y)
    }
    return FlowEdgePath(path = path, markerStart = controlPoint)
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
    val length = (marker.width ?: FlowSizing.defaultMarkerWidthPx).toFloat()
    val halfAngle = FlowSizing.markerHalfAngleRadians.toFloat()
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

// Keep bezier tangents aligned with the actual dominant axis of the edge instead of using one
// symmetric distance for every geometry. That preserves the current left-to-right look while
// softening awkward bends when two nodes are almost vertically or horizontally aligned.
internal fun tangentDistance(anchor: FlowAnchor, dx: Float, dy: Float): Float {
    val axisDistance = when (anchor.position) {
        Position.Left, Position.Right -> dx
        Position.Top, Position.Bottom -> dy
    }
    val crossDistance = when (anchor.position) {
        Position.Left, Position.Right -> dy
        Position.Top, Position.Bottom -> dx
    }
    val baseDistance = axisDistance * FlowSizing.bezierControlRatio + FlowSizing.bezierControlBiasPx
    val softenedDistance = if (crossDistance <= FlowSizing.bezierAxisAlignedThresholdPx) {
        baseDistance * FlowSizing.bezierAxisAlignedFactor
    } else {
        baseDistance
    }
    return softenedDistance.coerceIn(
        minimumValue = FlowSizing.bezierMinControlPx,
        maximumValue = FlowSizing.bezierMaxControlPx,
    )
}

internal fun isNearCollinearVerticalEdge(
    start: FlowAnchor,
    end: FlowAnchor,
    dx: Float,
    dy: Float,
): Boolean =
    start.position == Position.Bottom &&
        end.position == Position.Top &&
        dy > dx &&
        dx <= FlowSizing.bezierVerticalCollinearThresholdPx

internal fun verticalCollinearBias(start: FlowAnchor, rawDx: Float): Float {
    val direction = when {
        rawDx > 1f -> rawDx.sign
        rawDx < -1f -> rawDx.sign
        else -> 1f
    }
    return FlowSizing.bezierVerticalCollinearBiasPx * direction
}

internal fun parseColor(value: String): Color = runCatching {
    val hex = value.removePrefix("#")
    val argb = if (hex.length <= 6) hex.toLong(16) or 0xFF000000L else hex.toLong(16)
    Color(argb)
}.getOrDefault(FlowEdge)
