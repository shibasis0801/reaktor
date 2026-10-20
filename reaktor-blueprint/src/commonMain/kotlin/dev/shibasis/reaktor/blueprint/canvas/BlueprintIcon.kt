package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath

internal class BlueprintIcon(vector: ImageVector) {
    private class Part(val path: Path, val fill: Float, val stroke: Stroke?, val strokeAlpha: Float)

    private val width = vector.viewportWidth
    private val height = vector.viewportHeight
    private val parts = mutableListOf<Part>().also { collect(vector.root, it) }

    private fun collect(group: VectorGroup, into: MutableList<Part>) {
        val first = into.size
        for (node in group) when (node) {
            is VectorGroup -> collect(node, into)
            is VectorPath -> into += Part(
                node.pathData.toPath().apply { fillType = node.pathFillType },
                if (node.fill != null) node.fillAlpha else 0f,
                if (node.stroke != null && node.strokeLineWidth > 0f) Stroke(node.strokeLineWidth, node.strokeLineMiter, node.strokeLineCap, node.strokeLineJoin) else null,
                node.strokeAlpha,
            )
        }
        if (group.rotation == 0f && group.scaleX == 1f && group.scaleY == 1f && group.translationX == 0f && group.translationY == 0f) return
        val matrix = Matrix().apply {
            translate(group.translationX + group.pivotX, group.translationY + group.pivotY)
            rotateZ(group.rotation)
            scale(group.scaleX, group.scaleY, 1f)
            translate(-group.pivotX, -group.pivotY)
        }
        for (k in first until into.size) into[k].path.transform(matrix)
    }

    fun draw(scope: DrawScope, x: Float, y: Float, size: Float, tint: Color) = with(scope) {
        withTransform({ translate(x, y); scale(size / width, size / height, Offset.Zero) }) {
            for (part in parts) {
                if (part.fill > 0f) drawPath(part.path, tint, alpha = part.fill)
                part.stroke?.let { drawPath(part.path, tint, alpha = part.strokeAlpha, style = it) }
            }
        }
    }
}
