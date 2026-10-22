package dev.shibasis.reaktor.blueprint.product

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.reaktor.blueprint.BlueprintEngine
import dev.shibasis.reaktor.blueprint.Frame
import dev.shibasis.reaktor.blueprint.Link
import dev.shibasis.reaktor.blueprint.canvas.CanvasGrid
import dev.shibasis.reaktor.blueprint.canvas.CanvasPulse
import kotlinx.coroutines.channels.Channel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal expect fun surfaceGraphFrameElapsed(): Double
@Composable internal expect fun surfaceGraphIdle(): (((Long) -> Boolean) -> Unit)?
internal expect val surfaceGraphMeshGrid: Boolean

internal data class SurfaceGraphDash(val on: Float, val off: Float)

internal class SurfaceGraphPen {
    var rgb = 0; var alpha = 1f; var width = 1f
    var glow = 0; var glowWidth = 0f
    var on = 0f; var off = 0f; var flowing = false; var round = true
    var head = 0f; var spread = Spread
    var straight = false; var lifted = false; var corner = 7f; var lead = false

    fun clear() {
        rgb = 0; alpha = 1f; width = 1f; glow = 0; glowWidth = 0f; on = 0f; off = 0f; flowing = false; round = true
        head = 0f; spread = Spread; straight = false; lifted = false; corner = 7f; lead = false
    }

    private companion object { val Spread = (PI / 7).toFloat() }
}

internal abstract class SurfaceGraphEngine(protected val graphics: GraphicsContext, private val scaled: Boolean) {
    val version = mutableIntStateOf(0)
    protected val signal = Channel<Unit>(Channel.CONFLATED)
    protected val unit = Density(1f)
    protected val stroker = SurfaceGraphStroker()
    private val scratch = SurfaceGraphMesh(false)
    private val still = SurfaceGraphMesh(false)
    protected var solid = scratch; private set
    protected val dimPaint = Paint().apply { alpha = .2f }
    private val pen = SurfaceGraphPen()
    private val arrowX = FloatArray(3)
    private val arrowY = FloatArray(3)
    private val scratchDashes = LinkedHashMap<SurfaceGraphDash, SurfaceGraphMesh>()
    private val stillDashes = LinkedHashMap<SurfaceGraphDash, SurfaceGraphMesh>()
    private var dashed = scratchDashes
    private val flowing = LinkedHashMap<SurfaceGraphDash, SurfaceGraphMesh>()
    private val litFlowing = LinkedHashMap<SurfaceGraphDash, SurfaceGraphMesh>()
    private val draggedFlowing = LinkedHashMap<SurfaceGraphDash, SurfaceGraphMesh>()
    private val streams = arrayOf(flowing, litFlowing, draggedFlowing)
    private var stream = flowing
    private val shaders = HashMap<SurfaceGraphDash, Shader>()
    private var shaderZoom = Double.NaN
    private var shaderDensity = 0f
    private val sample by lazy { SurfaceGraphMesh(true).also { stroker.clear(); stroker.point(0f, 0f); stroker.point(24f, 0f); stroker.stroke(it, 1f, 1f, 0xFFFFFF, 1f, dashed = true); it.close() } }
    private val meshes = arrayOfNulls<GraphicsLayer>(KINDS)
    private val filled = BooleanArray(KINDS)
    protected val dirty = BooleanArray(KINDS) { true }
    protected var layers = arrayOfNulls<GraphicsLayer>(0)
    protected var cardBox = FloatArray(0)
    protected var halo = IntArray(0)
    protected var dim = BooleanArray(0)
    protected var anyDim = false
    protected var frames: List<Frame> = emptyList()
    protected var frameBox = FloatArray(0)
    protected var links: List<Link> = emptyList()
    protected var linkBox = FloatArray(0)
    protected var labels: Map<String, String> = emptyMap()
    protected var pulses: List<CanvasPulse> = emptyList()
    protected var shift = 0f
    private var linkIndex: Map<String, Int> = emptyMap()
    private var start = IntArray(1)
    private var points = FloatArray(0)
    private var curved = BooleanArray(0)
    private var lift = BooleanArray(0)
    private var seen = BooleanArray(0)
    private var tints = IntArray(0)
    private var alphas = FloatArray(0)
    private var flows = BooleanArray(0)
    private var traceStart: IntArray? = null
    private var trace = FloatArray(0)
    private var lengths = FloatArray(0)
    private var headers = arrayOfNulls<GraphicsLayer>(0)
    private var headerKeys = arrayOfNulls<Any>(0)
    protected var banners = arrayOfNulls<GraphicsLayer>(0); private set
    protected var bannerKeys = arrayOfNulls<Any>(0); private set
    private val pills = HashMap<Pair<String, Color>, GraphicsLayer>()
    private val chips = HashMap<Pair<String, Color>, GraphicsLayer>()
    private var hovered = -1
    private var hoveredPin: String? = null
    private var hoveredLink = -1
    private var leads = BooleanArray(0)
    private var leading = false
    private var dragged = BooleanArray(0)
    private var draggedList = IntArray(0)
    private var draggedCount = 0
    private var marked = BooleanArray(0)
    private var held = IntArray(16)
    private var returning = IntArray(16)
    private var returningCount = 0
    private val parts = ArrayList<Part>()
    private var stale = false
    private var spans = IntArray(0)
    private var others = arrayOfNulls<SurfaceGraphMesh>(0)
    private var main: SurfaceGraphMesh? = null
    private var mainFrom = 0
    private val inset = FloatArray(4)
    private var aim: Viewport? = null
    private var aimSlot = -1
    private var cost = 2.milliseconds
    private val ends = ArrayList<FloatArray>()
    private val aimed = ArrayList<FloatArray>()
    private var idling = false
    private val tail = Path()
    private var ids: HashMap<String, Int>? = null
    private var touching: HashMap<String, MutableList<Int>>? = null
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
    protected var level = 2
        set(value) { if (field != value) { field = value; dirty[CARDS] = true; dirty[OVER] = true; changed = true } }
    private var warmed = 0
    private var warming = false

    protected abstract val warmups: Int
    protected abstract val warmable: Boolean
    protected abstract val canvas: Color
    protected open val grid: CanvasGrid? = null
    protected open val farZoom = .42
    protected open val meshZoom = 0.0
    protected abstract val cardRadius: Float
    protected abstract fun pen(i: Int, pen: SurfaceGraphPen): Boolean
    protected abstract fun body(i: Int, x0: Float, y0: Float, x1: Float, y1: Float, z: Float, fringe: Float, tolerance: Float)
    protected open fun face(i: Int, x0: Float, y0: Float, x1: Float, y1: Float, z: Float, fringe: Float, tolerance: Float) {}
    protected open fun edge(i: Int, x0: Float, y0: Float, x1: Float, y1: Float, z: Float, fringe: Float, tolerance: Float) {}
    protected open fun extra(z: Float, fringe: Float, tolerance: Float) {}
    protected open fun shown(i: Int) = true
    protected abstract fun current(i: Int, slot: Int): Boolean
    protected abstract fun record(i: Int, slot: Int, deadline: TimeSource.Monotonic.ValueTimeMark?)
    protected abstract fun DrawScope.placeholder(i: Int, slot: Int)
    protected open fun headerKey(i: Int): Any? = null
    protected open fun DrawScope.header(i: Int, key: Any, x: Float, y: Float) {}
    protected open fun banner(i: Int, width: Float, zoom: Double): GraphicsLayer? = null

    protected open fun cardId(i: Int): String? = null

    protected open fun end(i: Int, to: Boolean): String? = if (to) links[i].to else links[i].from

    protected open fun endTitle(i: Int, to: Boolean): String? = null

    protected fun cardOf(id: String): Int = (ids ?: HashMap<String, Int>().also { map -> for (i in 0 until cardBox.size / 4) cardId(i)?.let { map[it] = i } }.also { ids = it })[id] ?: -1

    protected open fun hover(pen: SurfaceGraphPen) {
        pen.alpha = 1f; pen.width += .9f; pen.flowing = false
        if (pen.glow ushr 24 == 0) { pen.glow = pen.rgb or 0xFF000000.toInt(); pen.glowWidth = 5f }
    }

    protected open fun bannerLift(height: Float, z: Float) = max(BlueprintEngine.FrameTop.toFloat() * z - height - 2f, -Above * z)
    protected open fun pill(label: String, color: Color): GraphicsLayer? = null
    protected open fun chip(label: String, color: Color): GraphicsLayer? = null
    protected open fun DrawScope.background(camera: Viewport) = drawRect(canvas, size = viewport)
    protected open fun DrawScope.overlay(camera: Viewport) {}
    protected abstract fun DrawScope.warm(step: Int): Boolean
    protected open suspend fun load() = false

    protected fun index(frames: List<Frame>, links: List<Link>, boxes: FloatArray) {
        cardBox = boxes
        val count = boxes.size / 4
        if (halo.size != count) { halo = IntArray(count); dim = BooleanArray(count) }
        this.frames = frames
        frameBox = FloatArray(frames.size * 4).also { box -> frames.forEachIndexed { i, f -> box.put(i, f.x, f.y, f.width, f.height) } }
        (headers + banners).forEach { it?.let(graphics::releaseGraphicsLayer) }
        headers = arrayOfNulls(frames.size); headerKeys = arrayOfNulls(frames.size); banners = arrayOfNulls(frames.size); bannerKeys = arrayOfNulls(frames.size)
        this.links = links
        linkIndex = HashMap<String, Int>(links.size * 2).also { map -> links.forEachIndexed { i, link -> map[link.id] = i } }
        start = IntArray(links.size + 1).also { s -> links.forEachIndexed { i, link -> s[i + 1] = s[i] + link.points.size * 2 } }
        points = FloatArray(start[links.size])
        linkBox = FloatArray(links.size * 4)
        curved = BooleanArray(links.size) { links[it].curve && links[it].points.size == 4 }
        for (i in links.indices) {
            var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY; var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
            links[i].points.forEachIndexed { k, (x, y) ->
                val px = x.toFloat(); val py = y.toFloat()
                points[start[i] + k * 2] = px; points[start[i] + k * 2 + 1] = py
                l = min(l, px); t = min(t, py); r = max(r, px); b = max(b, py)
            }
            linkBox[i * 4] = l; linkBox[i * 4 + 1] = t; linkBox[i * 4 + 2] = r; linkBox[i * 4 + 3] = b
        }
        lift = BooleanArray(links.size); seen = BooleanArray(links.size); tints = IntArray(links.size); alphas = FloatArray(links.size); flows = BooleanArray(links.size)
        traceStart = null; touching = null; hovered = -1; hoveredPin = null; hoveredLink = -1; ids = null; leads = BooleanArray(links.size); leading = false; ends.clear()
        dragged = BooleanArray(links.size); draggedCount = 0; marked = BooleanArray(links.size); returningCount = 0
        spans = IntArray(links.size * 4); others = arrayOfNulls(links.size)
        dirty.fill(true)
    }

    protected fun reroute(frames: List<Frame>, links: List<Link>, rerouted: IntArray, count: Int): Boolean {
        if (frames.size != this.frames.size || links.size != this.links.size) return false
        for (i in frames.indices) if (frames[i].key != this.frames[i].key || frames[i].parent != this.frames[i].parent) return false
        for (i in frames.indices) {
            val frame = frames[i]
            if (frame === this.frames[i] || frame == this.frames[i]) continue
            frameBox.put(i, frame.x, frame.y, frame.width, frame.height)
            headers[i]?.let(graphics::releaseGraphicsLayer); headers[i] = null; headerKeys[i] = null
            banners[i]?.let(graphics::releaseGraphicsLayer); banners[i] = null; bannerKeys[i] = null
            dirty[UNDER] = true
        }
        this.frames = frames
        this.links = links
        for (n in 0 until count) {
            val i = rerouted[n]
            var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY; var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
            links[i].points.forEachIndexed { k, (x, y) ->
                val px = x.toFloat(); val py = y.toFloat()
                points[start[i] + k * 2] = px; points[start[i] + k * 2 + 1] = py
                l = min(l, px); t = min(t, py); r = max(r, px); b = max(b, py)
            }
            linkBox[i * 4] = l; linkBox[i * 4 + 1] = t; linkBox[i * 4 + 2] = r; linkBox[i * 4 + 3] = b
            val curve = links[i].curve && links[i].points.size == 4
            if (curve != curved[i]) { curved[i] = curve; traceStart = null } else traceStart?.let { retrace(i, it) }
        }
        hold(rerouted, count)
        return true
    }

    protected fun ready(cards: Set<String>) {
        var count = 0
        for (id in cards) touching(id)?.forEach { i -> if (count == held.size) held = held.copyOf(count * 2); held[count++] = i }
        hold(held, count)
    }

    private fun hold(list: IntArray, count: Int) {
        for (n in 0 until count) marked[list[n]] = true
        val left = draggedCount
        for (n in 0 until left) draggedList[n].let { i ->
            if (marked[i]) return@let
            dragged[i] = false
            if (returningCount == returning.size) returning = returning.copyOf(returningCount * 2)
            returning[returningCount++] = i
        }
        if (draggedList.size < count) draggedList = IntArray(max(16, count * 2))
        draggedCount = 0
        for (n in 0 until count) {
            val i = list[n]
            if (!marked[i]) continue
            marked[i] = false
            if (!dragged[i]) { dragged[i] = true; hide(i) }
            draggedList[draggedCount++] = i
        }
        if (draggedCount > 0 || left > 0) { dirty[DRAGGED] = true; dirty[DRAGGED_LIT] = true; dirty[HOVER] = true }
    }

    private fun touching(id: String): List<Int>? = (touching ?: HashMap<String, MutableList<Int>>().also { map ->
        links.forEachIndexed { i, link -> map.getOrPut(link.from) { mutableListOf() } += i; map.getOrPut(link.to) { mutableListOf() } += i }
    }.also { touching = it })[id]

    private fun hide(i: Int) {
        if (lift[i]) dirty[LIT] = true
        blank(still, spans[i * 4], spans[i * 4 + 1])
        others[i]?.let { blank(it, spans[i * 4 + 2], spans[i * 4 + 3]) }
        spans[i * 4] = 0; spans[i * 4 + 1] = 0; others[i] = null
    }

    private fun blank(mesh: SurfaceGraphMesh, from: Int, to: Int) {
        if (from >= to) return
        mesh.blank(from, to)
        var b = mesh.batchAt(from)
        while (b < mesh.batchCount && mesh.batches[b * 4 + 2] < to) {
            parts.firstOrNull { it.mesh === mesh && it.batch == b }?.let { it.stale = true; stale = true }
            b++
        }
    }

    protected fun FloatArray.put(i: Int, x: Double, y: Double, width: Double, height: Double) {
        this[i * 4] = x.toFloat(); this[i * 4 + 1] = y.toFloat(); this[i * 4 + 2] = (x + width).toFloat(); this[i * 4 + 3] = (y + height).toFloat()
    }

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
        parts.forEach { graphics.releaseGraphicsLayer(it.layer) }; parts.clear()
        (layers.asList() + retired + headers + banners).forEach { it?.let(graphics::releaseGraphicsLayer) }; layers.fill(null); headers.fill(null); banners.fill(null); retired.clear(); live = 0
        (pills.values + chips.values).forEach(graphics::releaseGraphicsLayer); pills.clear(); chips.clear()
    }

    fun cardIndex(x: Float, y: Float): Int {
        for (i in cardBox.size / 4 - 1 downTo 0) if (shown(i) && x >= cardBox[i * 4] && x <= cardBox[i * 4 + 2] && y >= cardBox[i * 4 + 1] && y <= cardBox[i * 4 + 3]) return i
        return -1
    }

    fun frameAt(x: Float, y: Float): Frame? {
        val z = camera?.zoom?.toFloat() ?: return null
        for (i in frames.indices.reversed()) {
            val left = frameBox[i * 4]; val top = frameBox[i * 4 + 1]
            banners[i]?.takeIf { level < 2 && frames[i].parent == null }?.let { tab ->
                val dx = (x - left) * z; val dy = (y - top) * z - bannerLift(tab.size.height.toFloat(), z)
                if (dx >= 0f && dx <= tab.size.width && dy >= 0f && dy <= tab.size.height) return frames[i]
            }
            if (level == 2 && x >= left && x <= frameBox[i * 4 + 2] && y >= top && y <= top + BlueprintEngine.FrameTop) return frames[i]
        }
        return null
    }

    fun linkAt(x: Float, y: Float, tolerance: Float): Link? = linkIndexAt(x, y, tolerance) { true }.takeIf { it >= 0 }?.let(links::get)

    protected fun linkIndexAt(x: Float, y: Float, tolerance: Float, wanted: (Int) -> Boolean): Int {
        val starts = traced()
        var best = -1; var distance = tolerance
        for (i in links.indices) {
            if (x < linkBox[i * 4] - tolerance || x > linkBox[i * 4 + 2] + tolerance || y < linkBox[i * 4 + 1] - tolerance || y > linkBox[i * 4 + 3] + tolerance || alphas[i] <= 0f || !wanted(i)) continue
            var k = starts[i]
            while (k + 3 < starts[i + 1]) {
                val d = segment(x, y, trace[k], trace[k + 1], trace[k + 2], trace[k + 3])
                if (d < distance) { distance = d; best = i }
                k += 2
            }
        }
        return best
    }

    private fun traced(): IntArray {
        traceStart?.let { return it }
        val starts = IntArray(links.size + 1)
        for (i in links.indices) starts[i + 1] = starts[i] + if (curved[i]) 50 else start[i + 1] - start[i]
        trace = FloatArray(starts[links.size]); lengths = FloatArray(links.size); traceStart = starts
        for (i in links.indices) retrace(i, starts)
        return starts
    }

    private fun retrace(i: Int, starts: IntArray) {
        val at = start[i]; val to = starts[i]
        if (curved[i]) for (step in 0..24) {
            val t = step / 24f; val u = 1 - t
            val a = u * u * u; val b = 3 * u * u * t; val c = 3 * u * t * t; val d = t * t * t
            trace[to + step * 2] = a * points[at] + b * points[at + 2] + c * points[at + 4] + d * points[at + 6]
            trace[to + step * 2 + 1] = a * points[at + 1] + b * points[at + 3] + c * points[at + 5] + d * points[at + 7]
        } else points.copyInto(trace, to, at, start[i + 1])
        var length = 0f; var k = to
        while (k + 3 < starts[i + 1]) { length += hypot(trace[k + 2] - trace[k], trace[k + 3] - trace[k + 1]); k += 2 }
        lengths[i] = length
    }

    private fun segment(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay; val length = dx * dx + dy * dy
        val u = if (length == 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / length).coerceIn(0f, 1f)
        return hypot(ax + u * dx - x, ay + u * dy - y)
    }

    private fun hypot(dx: Float, dy: Float) = sqrt(dx * dx + dy * dy)

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

    private fun tessellate(wired: Boolean) {
        val z = zoom.toFloat(); val fringe = 1f / density; val tolerance = .2f / density
        val l = wl / z; val t = wt / z; val r = wr / z; val b = wb / z
        if (dirty[GRID]) grid(z)
        if (dirty[UNDER]) {
            clear(false)
            for (i in frames.indices) if (inside(frameBox, i, l, t, r, b))
                body(i, frameBox[i * 4] * z - wl, frameBox[i * 4 + 1] * z - wt, frameBox[i * 4 + 2] * z - wl, frameBox[i * 4 + 3] * z - wt, z, fringe, tolerance)
            commit(UNDER, false)
        }
        if (dirty[WIRES]) { if (wired) wires(z, fringe, tolerance) else pending = true }
        else if (returningCount > 0 || stale) { if (wired) settle(z, fringe, tolerance) else pending = true }
        if (dirty[LIT] && !dirty[WIRES]) lit(z, fringe, tolerance)
        if (dirty[DRAGGED] || dirty[DRAGGED_LIT]) dragging(z, fringe, tolerance)
        if (dirty[HOVER]) { if (wired) hovers(z, fringe, tolerance) else pending = true }
        if (dirty[CARDS]) {
            clear(false)
            val radius = cardRadius * z
            for (i in 0 until cardBox.size / 4) {
                if (!shown(i) || !inside(cardBox, i, l, t, r, b)) continue
                val x0 = cardBox[i * 4] * z - wl; val y0 = cardBox[i * 4 + 1] * z - wt; val x1 = cardBox[i * 4 + 2] * z - wl; val y1 = cardBox[i * 4 + 3] * z - wt
                val glow = halo[i]
                if (glow ushr 24 != 0) for (k in 0..2) {
                    val half = GLOWS[k * 2] / 2
                    stroker.roundRect(x0 - half, y0 - half, x1 + half, y1 + half, radius + half, tolerance)
                    stroker.outline(solid, half, fringe, glow, GLOWS[k * 2 + 1] * if (dim[i]) .2f else 1f)
                }
                if (level == 0) face(i, x0, y0, x1, y1, z, fringe, tolerance)
            }
            commit(CARDS, false)
        }
        if (dirty[OVER]) {
            clear(false)
            if (level > 0) for (i in 0 until cardBox.size / 4) if (shown(i) && inside(cardBox, i, l, t, r, b))
                edge(i, cardBox[i * 4] * z - wl, cardBox[i * 4 + 1] * z - wt, cardBox[i * 4 + 2] * z - wl, cardBox[i * 4 + 3] * z - wt, z, fringe, tolerance)
            commit(OVER, false)
        }
    }

    private fun grid(z: Float) {
        val spec = grid ?: return commit(GRID, false) {}
        val minor = (spec.step * z).toFloat(); val major = minor * spec.every
        val near = fade(minor); val far = fade(major)
        if (surfaceGraphMeshGrid) {
            clear(false)
            if (near > 0f) rules(minor, spec.minor, spec.minor.alpha * near, 1f)
            if (far > 0f) rules(major, spec.major, spec.major.alpha * far, spec.weight)
            return commit(GRID, false)
        }
        commit(GRID, near > 0f || far > 0f) {
            if (near > 0f) lines(minor, spec.minor.copy(alpha = spec.minor.alpha * near), 1f / density)
            if (far > 0f) lines(major, spec.major.copy(alpha = spec.major.alpha * far), spec.weight / density)
        }
    }

    private fun rules(spacing: Float, color: Color, alpha: Float, weight: Float) {
        val rgb = color.toArgb(); val fringe = 1f / density; val half = weight * fringe / 2
        val w = wr - wl; val h = wb - wt
        var x = ceil(wl / spacing) * spacing - wl
        while (x <= w) { stroker.clear(); stroker.point(x, 0f); stroker.point(x, h); stroker.stroke(solid, half, fringe, rgb, alpha); x += spacing }
        var y = ceil(wt / spacing) * spacing - wt
        while (y <= h) { stroker.clear(); stroker.point(0f, y); stroker.point(w, y); stroker.stroke(solid, half, fringe, rgb, alpha); y += spacing }
    }

    private fun fade(spacing: Float) = ((spacing - 5f) / 4f).coerceIn(0f, 1f)

    private fun DrawScope.lines(spacing: Float, color: Color, width: Float) {
        val w = wr - wl; val h = wb - wt
        var x = ceil(wl / spacing) * spacing - wl
        while (x <= w) { drawRect(color, Offset(x - width / 2, 0f), Size(width, h)); x += spacing }
        var y = ceil(wt / spacing) * spacing - wt
        while (y <= h) { drawRect(color, Offset(0f, y - width / 2), Size(w, width)); y += spacing }
    }

    private fun wires(z: Float, fringe: Float, tolerance: Float) {
        solid = still; dashed = stillDashes
        clear(true)
        flowing.values.forEach { it.clear() }
        spans.fill(0); others.fill(null); returningCount = 0
        extra(z, fringe, tolerance)
        for (i in links.indices) if (!dragged[i]) rest(i, z, fringe, tolerance)
        seal()
        stage(true)
        dirty[WIRES] = false
        lit(z, fringe, tolerance)
    }

    private fun settle(z: Float, fringe: Float, tolerance: Float) {
        if (returningCount > 0) {
            solid = still; dashed = stillDashes
            for (n in 0 until returningCount) returning[n].let { if (!dragged[it] && spans[it * 4] == spans[it * 4 + 1] && others[it] == null) rest(it, z, fringe, tolerance) }
            returningCount = 0
            seal()
        }
        stage(false)
    }

    private fun rest(i: Int, z: Float, fringe: Float, tolerance: Float) {
        lift[i] = false; alphas[i] = 0f; leads[i] = false
        seen[i] = inside(linkBox, i, wl / z, wt / z, wr / z, wb / z)
        if (!seen[i]) return
        pen.clear()
        if (!pen(i, pen)) return
        if (pen.lifted) { lift[i] = true; dirty[LIT] = true; return }
        val from = solid.indexCount
        main = null
        wire(i, z, fringe, tolerance)
        spans[i * 4] = from; spans[i * 4 + 1] = solid.indexCount
        main?.takeIf { it !== solid }?.let { others[i] = it; spans[i * 4 + 2] = mainFrom; spans[i * 4 + 3] = it.indexCount }
    }

    private fun seal() {
        still.close(); stillDashes.values.forEach { it.close() }; flowing.values.forEach { it.close() }
        solid = scratch; dashed = scratchDashes
    }

    private fun stage(all: Boolean) {
        val spare = ArrayList<GraphicsLayer>()
        if (all) { parts.mapTo(spare) { it.layer }; parts.clear() }
        fun take(mesh: SurfaceGraphMesh, dash: SurfaceGraphDash?) {
            var b = parts.count { it.mesh === mesh }
            while (b < mesh.batchCount) parts += Part(mesh, b++, dash, spare.removeLastOrNull() ?: graphics.createGraphicsLayer())
        }
        take(still, null)
        for ((dash, mesh) in stillDashes) take(mesh, dash)
        spare.forEach(graphics::releaseGraphicsLayer)
        val size = IntSize(ceil(wr - wl).toInt(), ceil(wb - wt).toInt())
        for (part in parts) if (part.stale) {
            part.stale = false
            val shader = part.dash?.let(::shader)
            part.layer.record(unit, LayoutDirection.Ltr, size) { drawSurfaceGraphMesh(part.mesh, shader, part.batch, part.batch + 1) }
        }
        stale = false
    }

    private fun lit(z: Float, fringe: Float, tolerance: Float) {
        stream = litFlowing
        litFlowing.values.forEach { it.clear() }
        clear(true)
        for (i in links.indices) if (lift[i] && !dragged[i]) { pen.clear(); if (pen(i, pen)) wire(i, z, fringe, tolerance) }
        commit(LIT, true)
        stream = flowing
        leading = leads.any { it }
    }

    private fun dragging(z: Float, fringe: Float, tolerance: Float) {
        stream = draggedFlowing
        draggedFlowing.values.forEach { it.clear() }
        clear(true)
        val l = wl / z; val t = wt / z; val r = wr / z; val b = wb / z
        var lifted = false
        for (n in 0 until draggedCount) {
            val i = draggedList[n]
            lift[i] = false; alphas[i] = 0f; leads[i] = false
            seen[i] = inside(linkBox, i, l, t, r, b)
            if (!seen[i]) continue
            pen.clear()
            if (!pen(i, pen)) continue
            if (pen.lifted) { lift[i] = true; lifted = true } else wire(i, z, fringe, tolerance)
        }
        commit(DRAGGED, true)
        clear(true)
        if (lifted) for (n in 0 until draggedCount) draggedList[n].let { i -> if (lift[i]) { pen.clear(); if (pen(i, pen)) wire(i, z, fringe, tolerance) } }
        commit(DRAGGED_LIT, true)
        stream = flowing
        leading = leads.any { it }
    }

    private fun hovers(z: Float, fringe: Float, tolerance: Float) {
        clear(true)
        val l = wl / z; val t = wt / z; val r = wr / z; val b = wb / z
        val id = hovered.takeIf { it >= 0 }?.let(::cardId)
        val touched = if (hoveredLink >= 0) listOf(hoveredLink) else id?.let(::touching)
        touched?.forEach { i ->
            if (hoveredPin != null && !(links[i].from == id && links[i].fromPort == hoveredPin || links[i].to == id && links[i].toPort == hoveredPin)) return@forEach
            pen.clear()
            if (inside(linkBox, i, l, t, r, b) && pen(i, pen)) { hover(pen); wire(i, z, fringe, tolerance, false) }
        }
        commit(HOVER, true)
    }

    fun hover(x: Float, y: Float, reach: Float = 0f) {
        val card = if (x.isNaN() || endAt(x, y) != null) -1 else cardIndex(x, y)
        val pin = if (card >= 0 && level == 2) pinKey(card, x, y) else null
        val link = if (card < 0 && !x.isNaN() && reach > 0f) visibleLinkAt(x, y, reach) else -1
        if (card == hovered && pin == hoveredPin && link == hoveredLink) return
        hovered = card; hoveredPin = pin; hoveredLink = link; dirty[HOVER] = true; version.intValue++
    }

    fun visibleLinkAt(x: Float, y: Float, tolerance: Float): Int = linkIndexAt(x, y, tolerance) { alphas[it] >= .2f }

    protected open fun pinKey(i: Int, x: Float, y: Float): String? = null

    private fun wire(i: Int, z: Float, fringe: Float, tolerance: Float, keep: Boolean = true) {
        val first = start[i]; val end = start[i + 1]
        if (end - first < 4 || pen.alpha <= 0f) return
        if (keep) { tints[i] = pen.rgb; alphas[i] = pen.alpha; flows[i] = pen.flowing; leads[i] = pen.lead }
        val unit = if (scaled) z else 1f
        stroker.clear()
        when {
            pen.straight -> { stroker.point(points[first] * z - wl, points[first + 1] * z - wt); stroker.point(points[end - 2] * z - wl, points[end - 1] * z - wt) }
            curved[i] -> {
                stroker.point(points[first] * z - wl, points[first + 1] * z - wt)
                stroker.cubic(points[first + 2] * z - wl, points[first + 3] * z - wt, points[first + 4] * z - wl, points[first + 5] * z - wt, points[first + 6] * z - wl, points[first + 7] * z - wt, tolerance)
            }
            else -> stroker.corners(points, first, end, z, wl, wt, pen.corner, tolerance)
        }
        if (pen.glow ushr 24 != 0) stroker.stroke(solid, (pen.width + pen.glowWidth) * unit / 2, fringe, pen.glow, pen.alpha * .35f, round = pen.round)
        val mesh = if (pen.on <= 0f) solid else if (pen.round) dash(pen.on + pen.width, max(pen.off - pen.width, 1f), pen.flowing) else dash(pen.on, pen.off, pen.flowing)
        main = mesh; mainFrom = mesh.indexCount
        stroker.stroke(mesh, pen.width * unit / 2, fringe, pen.rgb, pen.alpha, round = pen.round && mesh === solid, dashed = mesh !== solid)
        if (pen.head <= 0f) return
        val length = pen.head * unit / z
        if (links[i].reversed) arrow(points[first], points[first + 1], atan2(points[first + 1] - points[first + 3], points[first] - points[first + 2]), length, z, fringe, pen.rgb, pen.alpha, pen.spread)
        else arrow(points[end - 2], points[end - 1], atan2(points[end - 1] - points[end - 3], points[end - 2] - points[end - 4]), length, z, fringe, pen.rgb, pen.alpha, pen.spread)
    }

    protected fun restyle(changed: (Int) -> Boolean) {
        for (i in links.indices) {
            if (!seen[i] || !changed(i)) continue
            dirty[if (dragged[i]) DRAGGED else WIRES] = true
            if (dirty[WIRES] && (draggedCount == 0 || dirty[DRAGGED])) return
        }
    }

    protected fun clear(textured: Boolean) {
        solid.clear()
        if (textured) dashed.values.forEach { it.clear() }
    }

    protected fun dash(on: Float, off: Float, flowing: Boolean = false): SurfaceGraphMesh =
        (if (flowing) stream else dashed).getOrPut(SurfaceGraphDash(on, off)) { SurfaceGraphMesh(true) }

    protected fun commit(kind: Int, textured: Boolean) {
        solid.close()
        if (textured) { dashed.values.forEach { it.close() }; stream.values.forEach { it.close() } }
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

    private fun DrawScope.mesh(kind: Int, camera: Viewport) {
        val layer = meshes[kind]?.takeIf { filled[kind] } ?: return
        space(camera) { drawLayer(layer) }
    }

    private fun DrawScope.flow(camera: Viewport, shift: Float) {
        if (streams.none { meshes -> meshes.values.any { it.batchCount > 0 } }) return
        space(camera) { for (meshes in streams) for ((dash, mesh) in meshes) if (mesh.batchCount > 0) drawSurfaceGraphMesh(mesh, dashShader(dash, shift)) }
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

    private fun layer(i: Int, slot: Int, deadline: TimeSource.Monotonic.ValueTimeMark, urgent: Boolean = false): GraphicsLayer? {
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

    protected inline fun bannerLayer(i: Int, key: Any, record: (GraphicsLayer) -> Unit): GraphicsLayer {
        banners[i]?.let { if (bannerKeys[i] == key) return it }
        val layer = banners[i] ?: graphics.createGraphicsLayer().also { banners[i] = it }
        record(layer); bannerKeys[i] = key
        return layer
    }

    protected fun headerLayer(i: Int, ahead: Boolean = false, deadline: TimeSource.Monotonic.ValueTimeMark? = null): GraphicsLayer? {
        if (level != 2 && !ahead) return null
        val key = headerKey(i) ?: return null
        val layer = headers[i]
        if (layer != null && headerKeys[i] == key) return layer
        if (deadline?.hasPassedNow() == true) { pending = true; return layer }
        val frame = frames[i]
        val next = layer ?: graphics.createGraphicsLayer().also { headers[i] = it }
        next.place(frame.x, frame.y, frame.width, BlueprintEngine.FrameTop) { x, y -> header(i, key, x, y) }
        headerKeys[i] = key
        return next
    }

    private fun DrawScope.paint(camera: Viewport, deadline: TimeSource.Monotonic.ValueTimeMark) {
        background(camera)
        mesh(GRID, camera); mesh(UNDER, camera)
        if (parts.isNotEmpty()) space(camera) { for (part in parts) drawLayer(part.layer) }
        mesh(DRAGGED, camera); mesh(HOVER, camera)
        flow(camera, shift / density * zoom.toFloat())
        val l = (-camera.x / camera.zoom).toFloat(); val t = (-camera.y / camera.zoom).toFloat()
        val r = ((viewport.width - camera.x) / camera.zoom).toFloat(); val b = ((viewport.height - camera.y) / camera.zoom).toFloat()
        if (level == 2) world(camera) {
            for (i in frames.indices) {
                if (!inside(frameBox, i, l, t, r, b) || frameBox[i * 4 + 1] + BlueprintEngine.FrameTop < t) continue
                headerLayer(i, false, deadline)?.let { drawLayer(it) }
            }
        }
        mesh(CARDS, camera)
        if (level > 0) world(camera) { drawCards(level - 1, l, t, r, b, deadline) }
        mesh(OVER, camera); mesh(LIT, camera); mesh(DRAGGED_LIT, camera)
        if (pulses.isNotEmpty()) world(camera) { drawPulses() }
        if (labels.isNotEmpty()) drawLabels(camera, l, t, r, b)
        if (level < 2) drawBanners(camera)
        overlay(camera)
        ends.clear()
        if (leading && labelEnds(this, camera, deadline, ends)) pending = true
    }

    internal fun inset(left: Float, top: Float, right: Float, bottom: Float) { inset[0] = left; inset[1] = top; inset[2] = right; inset[3] = bottom }

    internal fun aim(view: Viewport?, level: Int, idle: (((Long) -> Boolean) -> Unit)?) {
        aim = view; aimSlot = level - 1; idling = idling && view != null
        if (view == null || idling || idle == null) return
        idling = true
        idle { nanos -> (aim?.let { val deadline = TimeSource.Monotonic.markNow() + nanos.nanoseconds; aimSlot >= 0 && fill(it, deadline) || leading && labelEnds(null, it, deadline, aimed.apply { clear() }) } ?: false).also { idling = it } }
    }

    private fun fill(aim: Viewport, deadline: TimeSource.Monotonic.ValueTimeMark): Boolean {
        val z = aim.zoom
        for (pass in 0..2) {
            val e = if (pass == 2) 0f else 1f
            val l = ((inset[0] * e - aim.x) / z).toFloat(); val t = ((inset[1] * e - aim.y) / z).toFloat()
            val r = ((viewport.width - inset[2] * e - aim.x) / z).toFloat(); val b = ((viewport.height - inset[3] * e - aim.y) / z).toFloat()
            for (i in 0 until cardBox.size / 4) {
                if (pass == 0 && halo[i] ushr 24 == 0 || !shown(i) || !inside(cardBox, i, l, t, r, b) || current(i, aimSlot)) continue
                if ((deadline - minOf(cost, 4.milliseconds)).hasPassedNow()) return true
                val started = TimeSource.Monotonic.markNow()
                record(i, aimSlot, null)
                cost = (cost * 3 + started.elapsedNow()) / 4
            }
        }
        return false
    }

    fun endAt(x: Float, y: Float): String? {
        val camera = camera ?: return null
        val sx = (camera.x + x * camera.zoom).toFloat(); val sy = (camera.y + y * camera.zoom).toFloat()
        return ends.lastOrNull { sx >= it[2] && sx <= it[4] && sy >= it[3] && sy <= it[5] }?.let { end(it[0].toInt(), it[1] > 0f) }
    }

    private fun labelEnds(scope: DrawScope?, camera: Viewport, deadline: TimeSource.Monotonic.ValueTimeMark, into: ArrayList<FloatArray>): Boolean {
        val z = camera.zoom.toFloat(); val ox = camera.x.toFloat(); val oy = camera.y.toFloat()
        val l = inset[0] + 12f; val t = inset[1] + 12f; val r = viewport.width - inset[2] - 12f; val b = viewport.height - inset[3] - 12f
        if (r - l < 160f || b - t < 120f) return false
        val mx = (l + r) / 2; val my = (t + b) / 2
        var made = false; var late = false
        for (i in links.indices) {
            if (into.size >= 8) break
            if (!leads[i] || start[i + 1] - start[i] < 4) continue
            val p = start[i]; val q = start[i + 1] - 2
            val ax = ox + points[p] * z; val ay = oy + points[p + 1] * z; val bx = ox + points[q] * z; val by = oy + points[q + 1] * z
            val to = ax in l..r && ay in t..b
            if (to == (bx in l..r && by in t..b)) continue
            val color = Color(tints[i] or 0xFF000000.toInt())
            val title = endTitle(i, to) ?: continue
            if (made && deadline.hasPassedNow() && title to color !in chips) { late = true; continue }
            made = made || title to color !in chips
            val layer = cached(chips, title, color, ::chip) ?: continue
            val dx = (if (to) bx else ax) - mx; val dy = (if (to) by else ay) - my
            val k = min(if (dx == 0f) Float.MAX_VALUE else ((if (dx > 0) r else l) - mx) / dx, if (dy == 0f) Float.MAX_VALUE else ((if (dy > 0) b else t) - my) / dy)
            val w = layer.size.width.toFloat(); val h = layer.size.height.toFloat()
            val x = (mx + dx * k - w / 2).coerceIn(l, max(l, r - w)); val y = (my + dy * k - h / 2).coerceIn(t, max(t, b - h))
            if (into.any { x < it[4] + 4 && x + w + 4 > it[2] && y < it[5] + 4 && y + h + 4 > it[3] }) continue
            into += floatArrayOf(i.toFloat(), if (to) 1f else 0f, x, y, x + w, y + h)
            scope?.run {
                val n = sqrt(dx * dx + dy * dy); val ux = dx / n; val uy = dy / n
                val e = min(w / 2 / max(abs(ux), 1e-3f), h / 2 / max(abs(uy), 1e-3f)); val ex = x + w / 2 + ux * e; val ey = y + h / 2 + uy * e
                drawPath(tail.apply { reset(); moveTo(ex + ux * 7f, ey + uy * 7f); lineTo(ex - uy * 5f - ux * 2f, ey + ux * 5f - uy * 2f); lineTo(ex + uy * 5f - ux * 2f, ey - ux * 5f - uy * 2f); close() }, color)
                translate(x, y) { drawLayer(layer) }
            }
        }
        return late
    }

    private fun cached(cache: HashMap<Pair<String, Color>, GraphicsLayer>, label: String, color: Color, make: (String, Color) -> GraphicsLayer?) = cache[label to color] ?: make(label, color)?.also {
        if (cache.size >= 256) { cache.values.forEach(graphics::releaseGraphicsLayer); cache.clear() }
        cache[label to color] = it
    }

    private fun DrawScope.drawBanners(camera: Viewport) {
        val z = camera.zoom.toFloat()
        for (i in frames.indices) {
            val frame = frames[i]
            if (frame.parent != null) continue
            val x = (camera.x + frame.x * camera.zoom).toFloat(); val y = (camera.y + frame.y * camera.zoom).toFloat()
            val width = (frame.width * camera.zoom).toFloat(); val height = (frame.height * camera.zoom).toFloat()
            if (x >= viewport.width || y - Above * z >= viewport.height || x + width <= 0f || y + height <= 0f) continue
            val layer = banner(i, width, camera.zoom) ?: continue
            val top = y + bannerLift(layer.size.height.toFloat(), z)
            clipRect(floor(x), floor(top), ceil(x + width), ceil(y + height)) { translate(x, top) { drawLayer(layer) } }
        }
    }

    private fun DrawScope.drawCards(slot: Int, l: Float, t: Float, r: Float, b: Float, deadline: TimeSource.Monotonic.ValueTimeMark) {
        for (pass in 0..1) {
            if (pass == 0 && !anyDim) continue
            var open = false
            for (i in 0 until cardBox.size / 4) {
                if (dim[i] != (pass == 0) || !shown(i) || !inside(cardBox, i, l, t, r, b)) continue
                if (pass == 0 && !open) { drawContext.canvas.saveLayer(Rect(l, t, r, b), dimPaint); open = true }
                val layer = layer(i, slot, deadline, halo[i] ushr 24 != 0) ?: layers[i * 2 + 1 - slot]
                if (layer != null) drawLayer(layer) else placeholder(i, slot)
            }
            if (open) drawContext.canvas.restore()
        }
    }

    private fun DrawScope.drawLabels(camera: Viewport, l: Float, t: Float, r: Float, b: Float) {
        traced()
        val z = camera.zoom.toFloat(); val k = if (scaled) z.coerceAtLeast(1f) else 1f
        for (i in links.indices) {
            val label = labels[links[i].id]
            if (label.isNullOrBlank() || alphas[i] <= 0f || !flows[i] && alphas[i] < .9f || !inside(linkBox, i, l, t, r, b)) continue
            val color = Color(tints[i] or 0xFF000000.toInt()).copy(alpha = .96f)
            val layer = cached(pills, label, color, ::pill) ?: continue
            at(i, lengths[i] / 2) { x, y ->
                withTransform({ translate(camera.x.toFloat() + x * z, camera.y.toFloat() + y * z); scale(k, k, Offset.Zero) }) {
                    translate(-layer.size.width / 2f, -layer.size.height / 2f) { drawLayer(layer) }
                }
            }
        }
    }

    private fun DrawScope.drawPulses() {
        if (pulses.isEmpty()) return
        traced()
        for (pulse in pulses) {
            val progress = pulse.born.elapsedNow().inWholeMilliseconds.toFloat() / pulse.travelMillis
            if (progress < 0f || progress > 1f) continue
            val i = linkIndex[pulse.link] ?: continue
            if (start[i + 1] - start[i] < 4) continue
            val fade = if (progress > .85f) (1f - progress) / .15f else 1f
            at(i, lengths[i] * progress) { x, y ->
                drawCircle(pulse.color.copy(alpha = .25f * fade), 9f, Offset(x, y))
                drawCircle(pulse.color.copy(alpha = .9f * fade), 4f, Offset(x, y))
                drawCircle(Color.White.copy(alpha = .85f * fade), 1.6f, Offset(x, y))
            }
        }
    }

    private inline fun at(i: Int, distance: Float, use: (Float, Float) -> Unit) {
        val starts = traced()
        var remaining = distance
        var k = starts[i]
        while (k + 3 < starts[i + 1]) {
            val d = hypot(trace[k + 2] - trace[k], trace[k + 3] - trace[k + 1])
            if (remaining <= d) {
                val u = if (d == 0f) 0f else remaining / d
                use(trace[k] + (trace[k + 2] - trace[k]) * u, trace[k + 1] + (trace[k + 3] - trace[k + 1]) * u)
                return
            }
            remaining -= d; k += 2
        }
        if (starts[i + 1] > starts[i]) use(trace[starts[i + 1] - 2], trace[starts[i + 1] - 1])
    }

    private fun slot(zoom: Double, ahead: Boolean) = if (!ahead) level - 1 else when (level) {
        0 -> if (meshZoom > 0 && zoom >= meshZoom * .6) 0 else -1
        1 -> if (zoom >= farZoom * .7) 1 else -1
        else -> if (zoom < farZoom * 1.25) 0 else -1
    }

    private fun work(deadline: TimeSource.Monotonic.ValueTimeMark?): Boolean {
        val view = camera
        if (deadline == null && view != null && (dirty.any { it } || stale || returningCount > 0 || (view.zoom / zoom).toFloat() != 1f)) return true
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
                if (!shown(i) || !inside(cardBox, i, l - mx, t - my, r + mx, b + my) || current(i, slot)) continue
                if (deadline == null || deadline.hasPassedNow()) return true
                record(i, slot, deadline)
                if (pass == 0) visible = true
            }
        }
        val ahead = slot(z, true) == 1
        if (level == 2 || ahead) for (i in frames.indices) {
            if (!inside(frameBox, i, l, t, r, b)) continue
            val key = headerKey(i) ?: continue
            if (headers[i] != null && headerKeys[i] == key) continue
            if (deadline == null || deadline.hasPassedNow()) return true
            headerLayer(i, ahead)
        }
        if (live > 600 && aim == null) {
            val mx = 2 * (r - l); val my = 2 * (b - t)
            for (i in 0 until count) if (!inside(cardBox, i, l - mx, t - my, r + mx, b + my)) for (k in i * 2..i * 2 + 1) layers[k]?.let { graphics.releaseGraphicsLayer(it); layers[k] = null; live-- }
        }
        return visible
    }

    private class Part(val mesh: SurfaceGraphMesh, val batch: Int, val dash: SurfaceGraphDash?, val layer: GraphicsLayer) { var stale = true }

    protected companion object {
        const val GRID = 0; const val UNDER = 1; const val WIRES = 2; const val CARDS = 3; const val OVER = 4; const val LIT = 5; const val HOVER = 6
        const val DRAGGED = 7; const val DRAGGED_LIT = 8; const val KINDS = 9
        val WARMUPS = floatArrayOf(.4f, 1f, 2.5f, .1f, .35f)
        private val GLOWS = floatArrayOf(12f, .08f, 6f, .18f, 2f, .38f)
        private const val Above = (BlueprintEngine.GroupGap + BlueprintEngine.FrameSide).toFloat()
        private val DashColors = listOf(.5f, 1f, 1f, 0f, 0f, .5f).map { Color.White.copy(alpha = it) }
    }
}
