package dev.shibasis.reaktor.blueprint.product

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.shibasis.composeflow.model.Viewport
import kotlinx.coroutines.channels.Channel
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal expect fun surfaceGraphFrameElapsed(): Double

internal data class SurfaceGraphDash(val on: Float, val off: Float)

internal abstract class SurfaceGraphEngine(protected val graphics: GraphicsContext, kinds: Int, private val scaled: Boolean) {
    val version = mutableIntStateOf(0)
    protected val signal = Channel<Unit>(Channel.CONFLATED)
    protected val unit = Density(1f)
    protected val stroker = SurfaceGraphStroker()
    protected val solid = SurfaceGraphMesh(false)
    protected val dimPaint = Paint().apply { alpha = .2f }
    private val arrowX = FloatArray(3)
    private val arrowY = FloatArray(3)
    private val dashed = LinkedHashMap<SurfaceGraphDash, SurfaceGraphMesh>()
    private val flowing = LinkedHashMap<SurfaceGraphDash, SurfaceGraphMesh>()
    private val shaders = HashMap<SurfaceGraphDash, Shader>()
    private var shaderZoom = Double.NaN
    private var shaderDensity = 0f
    private val sample by lazy { SurfaceGraphMesh(true).also { stroker.clear(); stroker.point(0f, 0f); stroker.point(24f, 0f); stroker.stroke(it, 1f, 1f, 0xFFFFFF, 1f, dashed = true); it.close() } }
    private val meshes = arrayOfNulls<GraphicsLayer>(kinds)
    protected val filled = BooleanArray(kinds)
    protected val dirty = BooleanArray(kinds) { true }
    protected var layers = arrayOfNulls<GraphicsLayer>(0)
    protected var cardBox = FloatArray(0)
    protected var live = 0
    private val retired = ArrayList<GraphicsLayer>()
    protected var camera: Viewport? = null
    protected var viewport = Size.Zero
    protected var zoom = Double.NaN
    protected var density = 0f
    protected var wl = 0f; protected var wt = 0f; protected var wr = 0f; protected var wb = 0f
    private var moved = TimeSource.Monotonic.markNow() - 1.seconds
    protected val moving get() = moved.elapsedNow() < 120.milliseconds
    protected var pending = false
    protected var changed = false
    private var warmed = 0
    private var warming = false

    protected abstract val warmups: Int
    protected abstract val warmable: Boolean
    protected abstract fun tessellate(wired: Boolean)
    protected abstract fun DrawScope.paint(camera: Viewport, deadline: TimeSource.Monotonic.ValueTimeMark)
    protected abstract fun DrawScope.warm(step: Int): Boolean
    protected abstract fun slot(zoom: Double, ahead: Boolean): Int
    protected abstract fun current(i: Int, slot: Int): Boolean
    protected abstract fun record(i: Int, slot: Int, deadline: TimeSource.Monotonic.ValueTimeMark?)
    protected open fun headers(deadline: TimeSource.Monotonic.ValueTimeMark?, zoom: Double, l: Float, t: Float, r: Float, b: Float) = false
    protected open suspend fun load() = false

    protected fun DrawScope.frame(camera: Viewport, viewport: Size, density: Float) {
        if (camera != this@SurfaceGraphEngine.camera) moved = TimeSource.Monotonic.markNow()
        val elapsed = surfaceGraphFrameElapsed()
        val spare = if (elapsed.isNaN()) (if (moving) 2.0 else 3.0) else (5.0 - elapsed).coerceIn(.5, if (moving) 2.0 else 3.0)
        val deadline = TimeSource.Monotonic.markNow() + spare.milliseconds
        this@SurfaceGraphEngine.camera = camera; this@SurfaceGraphEngine.viewport = viewport
        val forced = window(camera, viewport, density)
        if (forced || spare > 1.0) tessellate(forced || spare >= 2.0) else pending = true
        if (warming && !moving && warmable) { if (spare >= 2.5) { warming = false; if (warm(warmed)) warmed++ }; signal.trySend(Unit) }
        paint(camera, deadline)
        if (work(deadline)) pending = true
        if (pending || moving || changed) signal.trySend(Unit)
        pending = false; changed = false
    }

    suspend fun run() {
        for (ignored in signal) {
            while (true) {
                if (load()) continue
                if (warmed < warmups) warming = true
                if (!work(null)) break
                version.intValue++
                withFrameNanos { }
            }
            if (warmed < warmups) { warming = true; version.intValue++ }
        }
    }

    open fun release() {
        meshes.forEach { it?.let(graphics::releaseGraphicsLayer) }; meshes.fill(null)
        (layers.asList() + retired).forEach { it?.let(graphics::releaseGraphicsLayer) }; layers.fill(null); retired.clear(); live = 0
    }

    private fun window(camera: Viewport, viewport: Size, density: Float): Boolean {
        val s = (camera.zoom / zoom).toFloat()
        val l = -camera.x.toFloat() / s; val t = -camera.y.toFloat() / s
        if ((s == 1f || scaled && moving && s in .8f..1.25f) && density == this.density && l >= wl && t >= wt && l + viewport.width / s <= wr && t + viewport.height / s <= wb) return false
        val margin = if (scaled || s == 1f) .25f else 0f
        zoom = camera.zoom; this.density = density
        wl = -camera.x.toFloat() - viewport.width * margin; wt = -camera.y.toFloat() - viewport.height * margin
        wr = wl + viewport.width * (1 + 2 * margin); wb = wt + viewport.height * (1 + 2 * margin)
        dirty.fill(true)
        return true
    }

    protected fun clear(textured: Boolean) {
        solid.clear()
        if (textured) { dashed.values.forEach { it.clear() }; flowing.values.forEach { it.clear() } }
    }

    protected fun dash(on: Float, off: Float, flowing: Boolean = false): SurfaceGraphMesh =
        (if (flowing) this.flowing else dashed).getOrPut(SurfaceGraphDash(on, off)) { SurfaceGraphMesh(true) }

    protected fun commit(kind: Int, textured: Boolean) {
        solid.close()
        if (textured) { dashed.values.forEach { it.close() }; flowing.values.forEach { it.close() } }
        commit(kind, solid.batchCount > 0 || textured && dashed.values.any { it.batchCount > 0 }) {
            if (solid.batchCount > 0) drawSurfaceGraphMesh(solid, null)
            if (textured) for ((dash, mesh) in dashed) if (mesh.batchCount > 0) drawSurfaceGraphMesh(mesh, shader(dash))
        }
    }

    protected fun commit(kind: Int, filled: Boolean, content: DrawScope.() -> Unit) {
        dirty[kind] = false; this.filled[kind] = filled
        if (!filled) return
        val layer = meshes[kind] ?: graphics.createGraphicsLayer().also { meshes[kind] = it }
        layer.record(unit, LayoutDirection.Ltr, IntSize(ceil(wr - wl).toInt(), ceil(wb - wt).toInt()), content)
    }

    private fun shader(dash: SurfaceGraphDash): Shader {
        val scale = if (scaled) zoom else 1.0
        if (scale != shaderZoom || density != shaderDensity) { shaders.clear(); shaderZoom = scale; shaderDensity = density }
        return shaders.getOrPut(dash) { dashShader(dash, 0f) }
    }

    private fun dashShader(dash: SurfaceGraphDash, shift: Float): Shader {
        val z = if (scaled) zoom.toFloat() else 1f; val half = .5f / density
        val on = max(dash.on * z, 3 * half); val period = on + max(dash.off * z, 3 * half)
        return LinearGradientShader(Offset(shift, 0f), Offset(shift + period, 0f), DashColors,
            listOf(0f, half, on - half, on + half, period - half, period).map { it / period }, TileMode.Repeated)
    }

    private inline fun DrawScope.space(camera: Viewport, block: DrawScope.() -> Unit) {
        val s = (camera.zoom / zoom).toFloat()
        withTransform({ translate((camera.x + wl * s).toFloat(), (camera.y + wt * s).toFloat()); if (s != 1f) scale(s, s, Offset.Zero) }) { block() }
    }

    protected fun DrawScope.mesh(kind: Int, camera: Viewport) {
        val layer = meshes[kind]?.takeIf { filled[kind] } ?: return
        space(camera) { drawLayer(layer) }
    }

    protected fun DrawScope.flow(camera: Viewport, shift: Float) {
        if (flowing.values.none { it.batchCount > 0 }) return
        space(camera) { for ((dash, mesh) in flowing) if (mesh.batchCount > 0) drawSurfaceGraphMesh(mesh, dashShader(dash, shift)) }
    }

    protected inline fun DrawScope.world(camera: Viewport, block: DrawScope.() -> Unit) {
        val z = camera.zoom.toFloat()
        withTransform({ translate(camera.x.toFloat(), camera.y.toFloat()); scale(z, z, Offset.Zero) }) { block() }
    }

    protected fun inside(box: FloatArray, i: Int, l: Float, t: Float, r: Float, b: Float) = box[i * 4 + 2] >= l && box[i * 4] <= r && box[i * 4 + 3] >= t && box[i * 4 + 1] <= b

    protected fun DrawScope.paint(layout: TextLayoutResult, x: Float, y: Float) = translate(x, y) { layout.multiParagraph.paint(drawContext.canvas, layout.layoutInput.style.color) }

    protected inline fun GraphicsLayer.place(x: Double, y: Double, width: Double, height: Double, margin: Double = 0.0, crossinline draw: DrawScope.(Float, Float) -> Unit) {
        val ix = floor(x - margin); val iy = floor(y - margin)
        val ox = (x - ix).toFloat(); val oy = (y - iy).toFloat()
        topLeft = IntOffset(ix.toInt(), iy.toInt())
        record(unit, LayoutDirection.Ltr, IntSize(ceil(width + ox + margin).toInt() + 1, ceil(height + oy + margin).toInt() + 1)) { draw(ox, oy) }
    }

    protected inline fun tile(i: Int, slot: Int, x: Double, y: Double, width: Double, height: Double, margin: Double = 0.0, crossinline draw: DrawScope.(Float, Float) -> Unit) =
        (layers[i * 2 + slot] ?: graphics.createGraphicsLayer().also { layers[i * 2 + slot] = it; live++ }).place(x, y, width, height, margin, draw)

    protected fun layer(i: Int, slot: Int, deadline: TimeSource.Monotonic.ValueTimeMark, urgent: Boolean = false): GraphicsLayer? {
        if (current(i, slot)) return layers[i * 2 + slot]
        if (!deadline.hasPassedNow()) record(i, slot, deadline)
        else if (urgent && layers[i * 2 + slot] == null) record(i, slot, null)
        else pending = true
        return layers[i * 2 + slot]
    }

    protected fun remap(previous: IntArray) {
        val next = arrayOfNulls<GraphicsLayer>(previous.size * 2)
        val kept = BooleanArray(layers.size / 2)
        previous.forEachIndexed { i, j -> if (j >= 0) { next[i * 2] = layers[j * 2]; next[i * 2 + 1] = layers[j * 2 + 1]; kept[j] = true } }
        for (j in kept.indices) if (!kept[j]) { layers[j * 2]?.let(retired::add); layers[j * 2 + 1]?.let(retired::add) }
        layers = next
    }

    protected fun arrow(x: Float, y: Float, angle: Float, length: Float, z: Float, fringe: Float, rgb: Int, alpha: Float, half: Float = (PI / 7).toFloat()) {
        arrowX[0] = x * z - wl; arrowY[0] = y * z - wt
        arrowX[1] = (x - length * cos(angle - half)) * z - wl; arrowY[1] = (y - length * sin(angle - half)) * z - wt
        arrowX[2] = (x - length * cos(angle + half)) * z - wl; arrowY[2] = (y - length * sin(angle + half)) * z - wt
        stroker.triangle(solid, arrowX, arrowY, fringe, rgb, alpha)
    }

    protected fun DrawScope.prime(layer: GraphicsLayer, scale: Float, offset: Float = 0f) =
        withTransform({ scale(scale, scale, Offset.Zero); translate(offset - layer.topLeft.x, offset - layer.topLeft.y) }) { drawLayer(layer) }

    protected fun DrawScope.sample() = drawSurfaceGraphMesh(sample, shader(SurfaceGraphDash(6f, 4f)))

    private fun work(deadline: TimeSource.Monotonic.ValueTimeMark?): Boolean {
        val view = camera
        if (deadline == null && view != null && (dirty.any { it } || (view.zoom / zoom).toFloat() != 1f)) return true
        while (retired.isNotEmpty()) {
            if (deadline == null || deadline.hasPassedNow()) return true
            graphics.releaseGraphicsLayer(retired.removeAt(retired.lastIndex)); live--
        }
        val camera = camera ?: return false
        val z = camera.zoom
        val l = (-camera.x / z).toFloat(); val t = (-camera.y / z).toFloat(); val r = ((viewport.width - camera.x) / z).toFloat(); val b = ((viewport.height - camera.y) / z).toFloat()
        val count = cardBox.size / 4
        var visible = false
        for (pass in 0..2) {
            val slot = slot(z, pass == 2)
            if (slot < 0) continue
            val mx = if (pass == 1) r - l else 0f; val my = if (pass == 1) b - t else 0f
            for (i in 0 until count) {
                if (!inside(cardBox, i, l - mx, t - my, r + mx, b + my) || current(i, slot)) continue
                if (deadline == null || deadline.hasPassedNow()) return true
                record(i, slot, deadline)
                if (pass == 0) visible = true
            }
        }
        if (headers(deadline, z, l, t, r, b)) return true
        if (live > 600) {
            val mx = 2 * (r - l); val my = 2 * (b - t)
            for (i in 0 until count) if (!inside(cardBox, i, l - mx, t - my, r + mx, b + my)) for (k in i * 2..i * 2 + 1) layers[k]?.let { graphics.releaseGraphicsLayer(it); layers[k] = null; live-- }
        }
        return visible
    }

    protected companion object {
        val WARMUPS = floatArrayOf(.4f, 1f, 2.5f, .1f, .35f)
        private val DashColors = listOf(.5f, 1f, 1f, 0f, 0f, .5f).map { Color.White.copy(alpha = it) }
    }
}
