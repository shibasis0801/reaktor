package dev.shibasis.composeflow.runtime

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.composeflow.model.XYPosition

@Stable
class ReactFlowState(
    initialViewport: Viewport = Viewport(),
) {
    var viewport by mutableStateOf(initialViewport)
        internal set

    var canvasSize by mutableStateOf(IntSize.Zero)
        internal set

    fun setViewport(next: Viewport) {
        viewport = next
    }

    fun setCanvasSize(next: IntSize) {
        canvasSize = next
    }

    fun panBy(dx: Double, dy: Double) {
        viewport = viewport.copy(x = viewport.x + dx, y = viewport.y + dy)
    }

    fun screenToFlowPosition(screenX: Double, screenY: Double): XYPosition =
        XYPosition((screenX - viewport.x) / viewport.zoom, (screenY - viewport.y) / viewport.zoom)

    fun zoomTo(
        zoom: Double,
        anchorX: Double? = null,
        anchorY: Double? = null,
        minZoom: Double = ViewportTuning.minZoom,
        maxZoom: Double = ViewportTuning.maxZoom,
    ) {
        val nextZoom = zoom.coerceIn(minZoom, maxZoom)
        val current = viewport
        if (nextZoom == current.zoom) return
        if (anchorX == null || anchorY == null) {
            viewport = current.copy(zoom = nextZoom)
            return
        }
        val worldX = (anchorX - current.x) / current.zoom
        val worldY = (anchorY - current.y) / current.zoom
        viewport = Viewport(anchorX - worldX * nextZoom, anchorY - worldY * nextZoom, nextZoom)
    }

    fun zoomBy(
        factor: Double,
        anchorX: Double? = null,
        anchorY: Double? = null,
        minZoom: Double = ViewportTuning.minZoom,
        maxZoom: Double = ViewportTuning.maxZoom,
    ) {
        zoomTo(
            zoom = viewport.zoom * factor,
            anchorX = anchorX,
            anchorY = anchorY,
            // Fit can legitimately land below the manual zoom floor. The first gesture must
            // approach the normal range continuously, not jump up to it (even on zoom-out).
            minZoom = minOf(minZoom, viewport.zoom),
            maxZoom = maxOf(maxZoom, viewport.zoom),
        )
    }
}

internal object ViewportTuning {
    const val minZoom = 0.25
    const val maxZoom = 2.0
    const val wheelPanFactor = 5.0
    const val macWheelPanFactor = 10.0
    const val wheelZoomSensitivity = 0.06
    const val wheelZoomFactorMin = 0.88
    const val wheelZoomFactorMax = 1.16
    const val pinchZoomSensitivity = 2.0
    const val controlZoomFactor = 1.15
}
