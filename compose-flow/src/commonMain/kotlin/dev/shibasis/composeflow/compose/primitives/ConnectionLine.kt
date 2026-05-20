package dev.shibasis.composeflow.compose.primitives

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.shibasis.composeflow.compose.theme.FlowSizing
import dev.shibasis.composeflow.model.Position

internal fun DrawScope.drawConnectionLine(
    start: FlowAnchor,
    endPoint: Offset,
    pathStyle: EdgePathStyle,
    color: Color,
) {
    val end = FlowAnchor(endPoint, Position.Top)
    val pathData = when (pathStyle) {
        EdgePathStyle.Bezier -> bezierEdgePath(start, end)
        EdgePathStyle.Straight -> straightEdgePath(start, end)
        EdgePathStyle.Orthogonal -> orthogonalEdgePath(start, end)
        EdgePathStyle.SmoothStep -> smoothStepEdgePath(start, end)
        EdgePathStyle.SimpleBezier -> simpleBezierEdgePath(start, end)
    }

    drawPath(
        path = pathData.path,
        color = color.copy(alpha = 0.6f),
        style = Stroke(
            width = FlowSizing.defaultEdgeStrokePx * 1.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 4f)),
        ),
    )
}
