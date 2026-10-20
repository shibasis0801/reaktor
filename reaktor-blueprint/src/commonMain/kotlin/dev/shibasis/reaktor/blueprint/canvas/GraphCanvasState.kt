package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.Color
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.composeflow.runtime.ReactFlowState
import dev.shibasis.reaktor.blueprint.Bounds
import kotlin.time.TimeSource

data class LinkStyle(
    val color: Color,
    val width: Float = 1.3f,
    val alpha: Float = 0.5f,
    val glow: Color? = null,
    val glowWidth: Float = 6f,
    val dash: Pair<Float, Float>? = null,
    val flowing: Boolean = false,
)

data class CanvasGrid(val minor: Color, val major: Color, val step: Double = 16.0, val every: Int = 8)

data class CanvasPulse(val link: String, val born: TimeSource.Monotonic.ValueTimeMark, val color: Color, val travelMillis: Long = 900)

@Stable
open class GraphCanvasState(val zoomRange: ClosedFloatingPointRange<Double> = 0.05..4.0) {
    internal val flow = ReactFlowState()
    var insetStart = 0.0
    var insetTop = 0.0
    var insetEnd = 0.0
    var insetBottom = 0.0
    internal var density = 1.0
        private set
    private var fitted: Any? = null
    private var restored: Viewport? = null
    private val history = mutableListOf<Viewport>()
    private var cursor = -1
    private val span get() = flow.canvasSize.width / density - insetStart - insetEnd
    private val room get() = flow.canvasSize.height / density - insetTop - insetBottom
    private val zoomState = derivedStateOf { flow.viewport.zoom / density }

    val zoom: Double get() = zoomState.value

    fun viewport(): Viewport = flow.viewport.let { Viewport(it.x / density, it.y / density, it.zoom / density) }

    fun fit(x: Double, y: Double, width: Double, height: Double, padding: Double = 0.03, maxZoom: Double = 1.0, minZoom: Double = 0.05) {
        if (span <= 0 || room <= 0) return
        look(x + width / 2, y + height / 2, minOf(span * (1 - 2 * padding) / width.coerceAtLeast(1.0), room * (1 - 2 * padding) / height.coerceAtLeast(1.0)).coerceIn(minZoom, maxZoom))
    }

    fun fit(boxes: List<Bounds>, padding: Double = 0.03, maxZoom: Double = 1.0, minZoom: Double = 0.05) {
        if (boxes.isEmpty()) return
        val left = boxes.minOf { it.x }; val top = boxes.minOf { it.y }
        fit(left, top, boxes.maxOf { it.x + it.width } - left, boxes.maxOf { it.y + it.height } - top, padding, maxZoom, minZoom)
    }

    fun centre(x: Double, y: Double, width: Double, height: Double, readable: Double = 0.45, fallback: Double = 0.9) =
        look(x + width / 2, y + height / 2, zoom.takeIf { it >= readable } ?: fallback)

    fun reveal(x: Double, y: Double, width: Double, height: Double) {
        val view = viewport(); val margin = 16.0
        fun shift(near: Double, far: Double, low: Double, high: Double) = when {
            far - near > high - low -> if (far < low || near > high) low - near else 0.0
            near < low -> low - near
            far > high -> high - far
            else -> 0.0
        }
        val dx = shift(view.x + x * view.zoom, view.x + (x + width) * view.zoom, insetStart + margin, insetStart + span - margin)
        val dy = shift(view.y + y * view.zoom, view.y + (y + height) * view.zoom, insetTop + margin, insetTop + room - margin)
        if (dx == 0.0 && dy == 0.0) return
        flow.panBy(dx * density, dy * density)
        record()
    }

    fun zoomTo(zoom: Double) {
        flow.zoomTo(zoom * density, flow.canvasSize.width / 2.0, flow.canvasSize.height / 2.0, zoomRange.start * density, zoomRange.endInclusive * density)
        record()
    }

    fun zoomBy(factor: Double) {
        flow.zoomBy(factor, flow.canvasSize.width / 2.0, flow.canvasSize.height / 2.0, zoomRange.start * density, zoomRange.endInclusive * density)
        record()
    }

    fun restore(x: Double, y: Double, zoom: Double) {
        restored = Viewport(x * density, y * density, zoom.coerceIn(0.002, 3.0) * density).also(flow::setViewport)
        record()
    }

    fun back() { record(); if (cursor > 0) flow.setViewport(history[--cursor]) }

    fun forward() { if (cursor < history.lastIndex) flow.setViewport(history[++cursor]) }

    internal fun look(x: Double, y: Double, zoom: Double) {
        flow.setViewport(Viewport((insetStart + span / 2 - x * zoom) * density, (insetTop + room / 2 - y * zoom) * density, zoom * density))
        record()
    }

    internal fun fitOnce(key: Any?, fit: () -> Unit) {
        if (flow.canvasSize.width <= 0 || flow.canvasSize.height <= 0) return
        val saved = restored; restored = null
        if (saved != null) { flow.setViewport(saved); record() } else if (key != fitted) fit()
        fitted = key
    }

    internal fun density(value: Double) {
        if (value == density) return
        val ratio = value / density
        fun Viewport.scaled() = copy(x = x * ratio, y = y * ratio, zoom = zoom * ratio)
        flow.setViewport(flow.viewport.scaled())
        history.indices.forEach { history[it] = history[it].scaled() }
        restored = restored?.scaled()
        density = value
    }

    internal fun record() {
        if (history.getOrNull(cursor) == flow.viewport) return
        while (history.lastIndex > cursor) history.removeAt(history.lastIndex)
        history += flow.viewport
        if (history.size > 80) history.removeAt(0)
        cursor = history.lastIndex
    }
}
