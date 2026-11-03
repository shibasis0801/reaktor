package dev.shibasis.reaktor.blueprint

import dev.shibasis.composeflow.model.Viewport
import kotlin.math.sqrt

internal data class BlueprintFarProjection(val width: Float, val arrow: Float) {
    companion object {
        fun label(supplied: String?, members: Int): String {
            val text = supplied?.takeIf { it.isNotBlank() }
            if (members <= 1) return text?.split(" · ")?.filter { it != "×1" }?.joinToString(" · ").orEmpty()
            val count = "×$members"
            return if (text?.split(" · ")?.contains(count) == true) text else listOfNotNull(text, count).joinToString(" · ")
        }

        fun labelVisible(members: Int, alpha: Float, flowing: Boolean, summary: Boolean): Boolean =
            alpha > 0f && (summary || flowing || alpha >= .9f || members >= 2)

        fun labelOffset(dx: Float, dy: Float, halfWidth: Float, halfHeight: Float, stroke: Float): Pair<Float, Float>? {
            val length = sqrt(dx * dx + dy * dy)
            if (length == 0f) return null
            val nx = -dy / length; val ny = dx / length
            val distance = kotlin.math.abs(nx) * halfWidth + kotlin.math.abs(ny) * halfHeight + stroke / 2f + 3f
            return nx * distance to ny * distance
        }

        data class BoundaryLabel(
            val link: Link,
            val owner: String,
            val port: String?,
            val provides: Boolean,
            val boundary: Pair<Double, Double>,
            val screen: Pair<Double, Double>,
            val anchor: Pair<Double, Double>?,
            val clipped: Boolean,
        )

        fun boundaryLabels(link: Link, camera: Viewport, viewportWidth: Double, viewportHeight: Double,
                           halfWidth: Double, halfHeight: Double, stroke: Double): List<BoundaryLabel> {
            require(camera.zoom.isFinite() && camera.zoom > 0 && viewportWidth > 0 && viewportHeight > 0)
            require(halfWidth > 0 && halfHeight > 0 && stroke >= 0)
            val trace = link.trace
            if (trace.isEmpty()) return emptyList()
            return listOf(true, false).map { provides ->
                val points = if (provides) trace else trace.asReversed()
                val boundary = points.first()
                val screen = camera.x + boundary.first * camera.zoom to camera.y + boundary.second * camera.zoom
                val next = points.firstOrNull { it != boundary }
                val dx = next?.let { (it.first - boundary.first) * camera.zoom } ?: 0.0
                val dy = next?.let { (it.second - boundary.second) * camera.zoom } ?: 0.0
                val length = kotlin.math.hypot(dx, dy)
                val clipped = screen.first !in 0.0..viewportWidth || screen.second !in 0.0..viewportHeight
                var anchor: Pair<Double, Double>? = null
                if (length > 0 && halfWidth * 2 <= viewportWidth && halfHeight * 2 <= viewportHeight) {
                    val tx = dx / length; val ty = dy / length
                    val along = minOf(length / 2, kotlin.math.abs(tx) * halfWidth + kotlin.math.abs(ty) * halfHeight + 3.0)
                    val normal = kotlin.math.abs(ty) * halfWidth + kotlin.math.abs(tx) * halfHeight + stroke / 2 + 3.0
                    for (side in listOf(1.0, -1.0)) {
                        val x = screen.first + tx * along - ty * normal * side
                        val y = screen.second + ty * along + tx * normal * side
                        if (x - halfWidth >= 0 && x + halfWidth <= viewportWidth &&
                            y - halfHeight >= 0 && y + halfHeight <= viewportHeight) {
                            anchor = x to y
                            break
                        }
                    }
                }
                BoundaryLabel(link, if (provides) link.from else link.to,
                    if (provides) link.fromPort else link.toPort, provides, boundary, screen, anchor, clipped)
            }
        }

        fun corridor(members: Int, width: Float, zoom: Double): BlueprintFarProjection {
            require(members > 0 && zoom.isFinite() && zoom > 0)
            val weight = sqrt(members.toDouble()).toFloat()
            val screen = maxOf((width * zoom).toFloat(), weight).coerceIn(1f, 3f)
            val arrow = (weight + 3f).coerceIn(4f, 6f)
            return BlueprintFarProjection((screen / zoom).toFloat(), (arrow / zoom).toFloat())
        }
    }
}
