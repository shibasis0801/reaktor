package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.composeflow.runtime.ReactFlowState
import dev.shibasis.reaktor.blueprint.BlueprintReading
import dev.shibasis.reaktor.blueprint.Bounds
import dev.shibasis.reaktor.blueprint.Card
import dev.shibasis.reaktor.blueprint.PinRef
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.time.TimeSource

data class LinkStyle(
    val color: Color,
    val width: Float = 1.3f,
    val alpha: Float = 0.5f,
    val glow: Color? = null,
    val glowWidth: Float = 6f,
    val dash: Pair<Float, Float>? = null,
    val flowing: Boolean = false,
    val lifted: Boolean = false,
)

data class CanvasGrid(val minor: Color, val major: Color, val step: Double = 16.0, val every: Int = 8, val weight: Float = 1.4f)

data class CanvasPulse(val link: String, val born: TimeSource.Monotonic.ValueTimeMark, val color: Color, val travelMillis: Long = 900)

data class GraphLevels(val meshIn: Double = .2, val meshOut: Double = .16, val farIn: Double = .42, val farOut: Double = .42)

@Stable
open class GraphCanvasState(val zoomRange: ClosedFloatingPointRange<Double> = 0.05..16.0, val levels: GraphLevels = GraphLevels()) {
    internal val flow = ReactFlowState()
    var insetStart = 0.0
    var insetTop = 0.0
    var insetEnd = 0.0
    var insetBottom = 0.0
    var reading: () -> BlueprintReading? = { null }
    internal var readableZoom: (Double, Double, Double, Double) -> Double? = { _, _, _, _ -> null }
    internal var paintedBounds: (Bounds, Double) -> List<Bounds> = { _, _ -> emptyList() }
    internal var pinRequest by mutableStateOf<PinRef?>(null)
        private set
    internal var density = 1.0
        private set
    private var fitted: Any? = null
    private var fittedScene: Any? = null
    private var fittedOnce = false
    private var framed: Viewport? = null
    private var restored: Viewport? = null
    private val history = mutableListOf<Viewport>()
    private var cursor = -1
    private val span get() = flow.canvasSize.width / density - insetStart - insetEnd
    private val room get() = flow.canvasSize.height / density - insetTop - insetBottom
    private val zoomState = derivedStateOf { flow.viewport.zoom / density }
    private var glide by mutableStateOf<Viewport?>(null)
    private var gliders = 0
    private var instant = false
    private val aimed get() = glide ?: flow.viewport

    val zoom: Double get() = zoomState.value
    internal var minimumZoom by mutableStateOf(zoomRange.start)
        private set

    private var far = false
    val distant: Boolean by derivedStateOf { zoomState.value.let { z -> (if (far) z < levels.meshIn else z < levels.meshOut).also { far = it } } }

    fun viewport(): Viewport = flow.viewport.let { Viewport(it.x / density, it.y / density, it.zoom / density) }

    fun seen(): Area? {
        val view = viewport(); val width = flow.canvasSize.width / density; val height = flow.canvasSize.height / density
        if (width <= 0 || height <= 0 || view.zoom <= 0) return null
        return Area((insetStart - view.x) / view.zoom, (insetTop - view.y) / view.zoom, (width - insetStart - insetEnd) / view.zoom, (height - insetTop - insetBottom) / view.zoom)
    }

    fun fit(x: Double, y: Double, width: Double, height: Double, padding: Double = 0.03, maxZoom: Double = 1.0, minZoom: Double = 0.05) {
        if (span <= 0 || room <= 0) return
        val box = Area(x, y, width.coerceAtLeast(1.0), height.coerceAtLeast(1.0))
        val availableWidth = span * (1 - 2 * padding)
        val availableHeight = room * (1 - 2 * padding)
        fun bounds(zoom: Double): Area {
            val all = listOf(box) + paintedBounds(box, zoom).filter { it.x.isFinite() && it.y.isFinite() && it.width.isFinite() && it.height.isFinite() && it.width >= 0 && it.height >= 0 }
            val left = all.minOf { it.x }; val top = all.minOf { it.y }
            return Area(left, top, all.maxOf { it.x + it.width } - left, all.maxOf { it.y + it.height } - top)
        }
        var fitted = minOf(availableWidth / box.width, availableHeight / box.height, maxZoom)
        var area = bounds(fitted)
        if (area.width * fitted > availableWidth || area.height * fitted > availableHeight) {
            var low = 0.0; var high = fitted
            repeat(40) {
                val middle = (low + high) / 2
                val next = bounds(middle)
                if (next.width * middle <= availableWidth && next.height * middle <= availableHeight) low = middle else high = middle
            }
            fitted = low.takeIf { it > 0 } ?: fitted
            area = bounds(fitted)
        }
        minimumZoom = minOf(zoomRange.start, minZoom, fitted)
        look(area.x + area.width / 2, area.y + area.height / 2, fitted)
    }

    fun fit(boxes: List<Bounds>, padding: Double = 0.03, maxZoom: Double = 1.0, minZoom: Double = 0.05) {
        if (boxes.isEmpty()) return
        val left = boxes.minOf { it.x }; val top = boxes.minOf { it.y }
        fit(left, top, boxes.maxOf { it.x + it.width } - left, boxes.maxOf { it.y + it.height } - top, padding, maxZoom, minZoom)
    }

    val untouched: Boolean get() = framed != null && aimed == framed

    fun frame(fit: () -> Unit) { fit(); framed = aimed }

    fun centre(x: Double, y: Double, width: Double, height: Double, readable: Double = 0.45, fallback: Double = 0.9) {
        val minimum = maxOf(readable, readableZoom(x, y, width, height) ?: readable)
        look(x + width / 2, y + height / 2, (aimed.zoom / density).takeIf { it >= minimum } ?: maxOf(fallback, minimum))
    }

    fun reveal(x: Double, y: Double, width: Double, height: Double, pin: PinRef? = null) {
        pinRequest = pin
        if (pin != null) { revealPin(); return }
        val view = aimed.let { Viewport(it.x / density, it.y / density, it.zoom / density) }; val margin = 16.0
        fun shift(near: Double, far: Double, low: Double, high: Double) = when {
            far - near > high - low -> if (far < low || near > high) low - near else 0.0
            near < low -> low - near
            far > high -> high - far
            else -> 0.0
        }
        val dx = shift(view.x + x * view.zoom, view.x + (x + width) * view.zoom, insetStart + margin, insetStart + span - margin)
        val dy = shift(view.y + y * view.zoom, view.y + (y + height) * view.zoom, insetTop + margin, insetTop + room - margin)
        if (dx == 0.0 && dy == 0.0) return
        move(aimed.let { it.copy(x = it.x + dx * density, y = it.y + dy * density) })
    }

    internal fun revealPin() {
        val request = pinRequest ?: return
        if (span <= 0 || room <= 0) return
        val card = extent.filterIsInstance<Card>().firstOrNull { it.id == request.card } ?: return
        val row = card.pins.firstOrNull { request.matches(card.id, it) } ?: return
        val readable = readableZoom(card.x, card.y, card.width, card.height)?.takeIf { it.isFinite() && it > 0 } ?: return
        pinRequest = null
        look(card.x + card.width / 2, card.y + row.y, maxOf(aimed.zoom / density, readable))
    }

    fun pan(dx: Double, dy: Double) = move(aimed.let { it.copy(x = it.x + dx * density, y = it.y + dy * density) })

    fun page(dx: Int, dy: Int) = pan(-dx * span * PageShare, -dy * room * PageShare)

    fun zoomTo(zoom: Double) = scale(zoom * density, minimumZoom * density, zoomRange.endInclusive * density)

    fun zoomTo(zoom: Double, x: Double, y: Double, width: Double, height: Double) =
        look(x + width / 2, y + height / 2, zoom.coerceIn(minimumZoom, zoomRange.endInclusive))

    fun zoomBy(factor: Double) = aimed.zoom.let { scale(it * factor, minOf(minimumZoom * density, it), maxOf(zoomRange.endInclusive * density, it)) }

    fun zoomAt(factor: Double, x: Double, y: Double) {
        require(factor.isFinite() && factor > 0 && x.isFinite() && y.isFinite())
        val view = aimed
        val next = (view.zoom * factor).coerceIn(minOf(minimumZoom * density, view.zoom), maxOf(zoomRange.endInclusive * density, view.zoom))
        val ratio = next / view.zoom
        if (next != view.zoom) move(Viewport(x * density - (x * density - view.x) * ratio,
            y * density - (y * density - view.y) * ratio, next))
    }


    fun restore(x: Double, y: Double, zoom: Double) = restore(Viewport(x, y, zoom.coerceIn(minOf(0.002, minimumZoom), zoomRange.endInclusive)))

    internal fun restore(view: Viewport) {
        require(view.x.isFinite() && view.y.isFinite() && view.zoom.isFinite() && view.zoom > 0)
        restored = Viewport(view.x * density, view.y * density, view.zoom * density).also { glide = null; flow.setViewport(it) }
        record()
    }

    fun back() { record(); if (cursor > 0) go(history[--cursor]) }

    fun forward() { if (cursor < history.lastIndex) go(history[++cursor]) }

    internal fun look(x: Double, y: Double, zoom: Double) =
        move(Viewport((insetStart + span / 2 - x * zoom) * density, (insetTop + room / 2 - y * zoom) * density, zoom * density))

    private fun scale(zoom: Double, min: Double, max: Double) {
        val view = aimed; val next = zoom.coerceIn(min, max); val k = next / view.zoom
        val cx = (insetStart + span / 2) * density; val cy = (insetTop + room / 2) * density
        if (next != view.zoom) move(Viewport(cx - (cx - view.x) * k, cy - (cy - view.y) * k, next))
    }

    private fun move(next: Viewport) { go(next); record(next) }

    private fun go(next: Viewport) { restored = null; if (gliders > 0 && !instant) glide = next else { glide = null; flow.setViewport(next) } }

    private inline fun instantly(block: () -> Unit) { instant = true; try { block() } finally { instant = false } }

    internal suspend fun glides() {
        gliders++
        try { snapshotFlow { glide }.collectLatest { to -> if (to != null) { fly(to); glide = null } } }
        finally { gliders--; glide?.let(flow::setViewport); glide = null }
    }

    private suspend fun fly(to: Viewport) {
        val from = flow.viewport; val width = span * density
        val sx = (insetStart + span / 2) * density; val sy = (insetTop + room / 2) * density
        if (width <= 0 || from == to) return flow.setViewport(to)
        val x0 = (sx - from.x) / from.zoom; val y0 = (sy - from.y) / from.zoom; val w0 = width / from.zoom
        val dx = (sx - to.x) / to.zoom - x0; val dy = (sy - to.y) / to.zoom - y0; val w1 = width / to.zoom; val d = sqrt(dx * dx + dy * dy)
        val r0 = -asinh((w1 * w1 - w0 * w0 + 4 * d * d) / (4 * w0 * d)); val length = if (d < 1e-6) ln(w1 / w0) / Rho else (-asinh((w1 * w1 - w0 * w0 - 4 * d * d) / (4 * w1 * d)) - r0) / Rho
        fun at(t: Double): Viewport {
            val s = Rho * t * length
            val u = if (d < 1e-6) t else w0 / (2 * d) * (cosh(r0) * tanh(s + r0) - sinh(r0))
            val zoom = width / (if (d < 1e-6) w0 * exp(s) else w0 * cosh(r0) / cosh(s + r0))
            return Viewport(sx - (x0 + u * dx) * zoom, sy - (y0 + u * dy) * zoom, zoom)
        }
        val millis = (180 + 160 * ln(1 + abs(length))).coerceAtMost(480.0)
        aim = Viewport(to.x / density, to.y / density, to.zoom / density)
        landing = level(level(this.shown, (0..8).minOf { at(it / 8.0).zoom } / density), to.zoom / density)
        try {
            val start = withFrameNanos { it }
            var shown = from
            while (true) {
                val t = withFrameNanos { ((it - start) / 1e6 / millis).coerceAtMost(1.0) }
                if (flow.viewport != shown) return
                shown = if (t >= 1.0) to else at(if (t < .5) 4 * t * t * t else 1 - (2 - 2 * t).let { it * it * it } / 2)
                flow.setViewport(shown)
                if (t >= 1.0) return
            }
        } finally { aim = null }
    }

    internal var aim: Viewport? = null
        private set
    internal var landing = 0
        private set

    internal var extent: List<Bounds> = emptyList()
    private var shown: Int? = null

    internal fun level(zoom: Double) = level(shown, zoom).also { shown = it }

    private fun level(from: Int?, zoom: Double): Int {
        val (meshIn, meshOut, farIn, farOut) = levels
        return when (from) {
            0 -> if (zoom >= farIn) 2 else if (zoom >= meshIn) 1 else 0
            1 -> if (zoom < meshOut) 0 else if (zoom >= farIn) 2 else 1
            2 -> if (zoom < meshOut) 0 else if (zoom < farOut) 1 else 2
            else -> if (zoom < (meshIn + meshOut) / 2) 0 else if (zoom < (farIn + farOut) / 2) 1 else 2
        }
    }

    internal open fun overview() = fit(extent, minZoom = zoomRange.start)

    private var resizedDensity = 1.0
    private var resizedWindow: IntSize? = null

    internal fun resize(size: IntSize, window: IntSize? = null) {
        val previous = flow.canvasSize
        val previousDensity = resizedDensity
        val previousWindow = resizedWindow
        resizedDensity = density
        resizedWindow = window?.takeIf { it.width > 0 && it.height > 0 }
        if (previous == size && previousDensity == density) return
        flow.setCanvasSize(size)
        if (previous.width <= 0 || previous.height <= 0 || size.width <= 0 || size.height <= 0) return
        val currentWindow = resizedWindow ?: return
        if (previousWindow == null) return
        if (previousWindow.width / previousDensity == currentWindow.width / density &&
            previousWindow.height / previousDensity == currentWindow.height / density) return
        val dx = (size.width / density - previous.width / previousDensity) * density / 2.0
        val dy = (size.height / density - previous.height / previousDensity) * density / 2.0
        fun Viewport.shifted() = copy(x = x + dx, y = y + dy)
        flow.setViewport(flow.viewport.shifted())
        history.indices.forEach { history[it] = history[it].shifted() }
        restored = restored?.shifted()
        framed = framed?.shifted()
        glide = glide?.shifted()
        aim = aim?.let { it.copy(x = it.x + dx / density, y = it.y + dy / density) }
    }

    internal fun fitOnce(key: Any?, scene: Any? = key, fit: () -> Unit) {
        if (flow.canvasSize.width <= 0 || flow.canvasSize.height <= 0) return
        val requested = !fittedOnce && (history.isNotEmpty() || pinRequest != null)
        val saved = restored; restored = null
        if (saved != null) {
            // A controller gesture can arrive before a new projected layout consumes this restore.
            // Its current viewport is authoritative; the pending restore must not replay over it.
            glide = null
            if (flow.viewport == saved) { flow.setViewport(saved); record() }
        }
        else if (!requested && (scene != fittedScene || key != fitted && aimed == framed)) { instantly(fit); framed = flow.viewport }
        fitted = key; fittedScene = scene; fittedOnce = true
    }

    internal fun density(value: Double) {
        if (value == density) return
        val ratio = value / density
        fun Viewport.scaled() = copy(x = x * ratio, y = y * ratio, zoom = zoom * ratio)
        flow.setViewport(flow.viewport.scaled())
        history.indices.forEach { history[it] = history[it].scaled() }
        restored = restored?.scaled()
        framed = framed?.scaled()
        density = value
        glide = glide?.scaled()
    }

    internal fun record(view: Viewport = aimed) {
        if (history.getOrNull(cursor) == view) return
        while (history.lastIndex > cursor) history.removeAt(history.lastIndex)
        history += view
        if (history.size > 80) history.removeAt(0)
        cursor = history.lastIndex
    }
}

private val Rho = sqrt(2.0)

private const val PageShare = 0.8
