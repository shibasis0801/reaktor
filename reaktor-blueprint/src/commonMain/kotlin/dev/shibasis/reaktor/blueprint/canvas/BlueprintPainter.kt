package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.reaktor.blueprint.Blueprint
import dev.shibasis.reaktor.blueprint.BlueprintCardLook
import dev.shibasis.reaktor.blueprint.BlueprintEngine
import dev.shibasis.reaktor.blueprint.BlueprintFrameLook
import dev.shibasis.reaktor.blueprint.BlueprintLayout
import dev.shibasis.reaktor.blueprint.BlueprintMark
import dev.shibasis.reaktor.blueprint.BlueprintPinLook
import dev.shibasis.reaktor.blueprint.Card
import dev.shibasis.reaktor.blueprint.Frame
import dev.shibasis.reaktor.blueprint.Link
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphEngine
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.time.TimeSource

internal class BlueprintType(private val ui: FontFamily, private val mono: FontFamily, private val base: TextStyle) {
    fun style(size: TextUnit, color: Color, mono: Boolean = false, weight: FontWeight = FontWeight.Normal) =
        base.merge(TextStyle(color = color, fontSize = size, fontWeight = weight, fontFamily = if (mono) this.mono else ui))

    fun label(color: Color) = base.merge(TextStyle(color = color, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace))
}

internal fun blueprintHeaderScale(zoom: Double) = 2.0.pow(round(log2((1.0 / zoom.coerceIn(0.35, 1.0)).coerceAtMost(1.8)) * 32) / 32).toFloat()

private data class FarKey(val title: String, val tone: Color, val selected: Boolean, val outline: Color?)
private data class HeaderKey(val look: BlueprintFrameLook, val scale: Float)
private class Measured(val width: Float, val height: Float, val text: TextLayoutResult?)

internal class BlueprintPainter(graphics: GraphicsContext, private val measurer: TextMeasurer, private val type: BlueprintType) : SurfaceGraphEngine(graphics, 2, scaled = true) {
    private val icons = HashMap<ImageVector, BlueprintIcon>()
    private val pills = HashMap<Pair<String, Color>, GraphicsLayer>()
    private val tint = Blueprint.Container
    private val accent = MachineSignal.Editor.Accent
    private val titleStyle = type.style(12.5.sp, Color.White, mono = true, weight = FontWeight.SemiBold)
    private val subtitleStyles = arrayOf(type.style(10.5.sp, MachineSignal.Editor.Muted), type.style(10.5.sp, MachineSignal.Editor.Muted, mono = true))
    private val chipStyle = type.style(10.sp, Color.White, mono = true)
    private val foldedStyle = type.style(9.5.sp, MachineSignal.Editor.Muted)
    private val headerStyles = arrayOf(type.style(13.sp, MachineSignal.Editor.Text, weight = FontWeight.SemiBold),
        type.style(11.sp, MachineSignal.Editor.Muted), type.style(11.sp, MachineSignal.Editor.Muted, mono = true))

    private var layout: BlueprintLayout<*>? = null
    private var cards = emptyArray<Card>()
    private var keys = arrayOfNulls<Any>(0)
    private var looks: List<BlueprintCardLook?> = emptyList()
    private var glows: List<Color?> = emptyList()
    private var frames: List<Frame> = emptyList()
    private var frameBox = FloatArray(0)
    private var frameLooks: List<BlueprintFrameLook> = emptyList()
    private var headers = arrayOfNulls<GraphicsLayer>(0)
    private var headerKey = arrayOfNulls<HeaderKey>(0)
    private var links: List<Link> = emptyList()
    private var linkIndex: Map<String, Int> = emptyMap()
    private var start = IntArray(1)
    private var points = FloatArray(0)
    private var lengths = FloatArray(0)
    private var linkBox = FloatArray(0)
    private var styles: List<LinkStyle> = emptyList()
    private var meshStyles = arrayOfNulls<LinkStyle>(0)

    private var selected: String? = null
    private var highlight: Set<String> = emptySet()
    private var labels: Map<String, String> = emptyMap()
    private var shift = 0f
    private var pulses: List<CanvasPulse> = emptyList()

    override val warmups = WARMUPS.size
    override val warmable get() = cards.isNotEmpty()

    fun sync(layout: BlueprintLayout<*>, looks: List<BlueprintCardLook?>, glows: List<Color?>, frameLooks: List<BlueprintFrameLook>, styles: List<LinkStyle>,
             selected: String?, highlight: Set<String>, labels: Map<String, String>) {
        if (layout !== this.layout) scene(layout)
        if (styles !== this.styles) {
            this.styles = styles
            if (links.indices.any { meshStyles[it] != null && meshStyles[it] != styles[it] }) dirty[WIRES] = true
        }
        this.frameLooks = frameLooks; this.looks = looks; this.glows = glows; this.selected = selected; this.highlight = highlight; this.labels = labels
    }

    private fun scene(layout: BlueprintLayout<*>) {
        this.layout = layout
        val next = layout.cards.values.toTypedArray()
        val previous = HashMap<String, Int>(cards.size * 2).also { map -> cards.forEachIndexed { i, card -> map[card.id] = i } }
        val index = IntArray(next.size) { i -> previous[next[i].id]?.takeIf { cards[it] == next[i] } ?: -1 }
        remap(index)
        keys = arrayOfNulls<Any>(next.size * 2).also { kept -> index.forEachIndexed { i, j -> if (j >= 0) { kept[i * 2] = keys[j * 2]; kept[i * 2 + 1] = keys[j * 2 + 1] } } }
        cards = next
        cardBox = FloatArray(next.size * 4).also { box -> next.forEachIndexed { i, c -> box.put(i, c.x, c.y, c.width, c.height) } }
        headers.forEach { it?.let(graphics::releaseGraphicsLayer) }
        frames = layout.frames
        frameBox = FloatArray(frames.size * 4).also { box -> frames.forEachIndexed { i, f -> box.put(i, f.x, f.y, f.width, f.height) } }
        headers = arrayOfNulls(frames.size); headerKey = arrayOfNulls(frames.size)
        links = layout.links
        linkIndex = HashMap<String, Int>(links.size * 2).also { map -> links.forEachIndexed { i, link -> map[link.id] = i } }
        start = IntArray(links.size + 1).also { s -> links.forEachIndexed { i, link -> s[i + 1] = s[i] + link.points.size * 2 } }
        points = FloatArray(start[links.size])
        lengths = FloatArray(links.size)
        linkBox = FloatArray(links.size * 4)
        links.forEachIndexed { i, link ->
            var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY; var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
            link.points.forEachIndexed { k, (x, y) ->
                val px = x.toFloat(); val py = y.toFloat()
                points[start[i] + k * 2] = px; points[start[i] + k * 2 + 1] = py
                if (k > 0) lengths[i] += hypot(px - points[start[i] + k * 2 - 2], py - points[start[i] + k * 2 - 1])
                l = min(l, px); t = min(t, py); r = max(r, px); b = max(b, py)
            }
            linkBox[i * 4] = l; linkBox[i * 4 + 1] = t; linkBox[i * 4 + 2] = r; linkBox[i * 4 + 3] = b
        }
        meshStyles = arrayOfNulls(links.size)
        dirty.fill(true)
    }

    private fun FloatArray.put(i: Int, x: Double, y: Double, width: Double, height: Double) {
        this[i * 4] = x.toFloat(); this[i * 4 + 1] = y.toFloat(); this[i * 4 + 2] = (x + width).toFloat(); this[i * 4 + 3] = (y + height).toFloat()
    }

    fun draw(scope: DrawScope, camera: Viewport, viewport: Size, density: Float, shift: Float, pulses: List<CanvasPulse>) {
        this.shift = shift; this.pulses = pulses
        with(scope) { frame(camera, viewport, density) }
    }

    override fun DrawScope.paint(camera: Viewport, deadline: TimeSource.Monotonic.ValueTimeMark) {
        drawRect(MachineSignal.Editor.Canvas, size = viewport)
        val l = (-camera.x / camera.zoom).toFloat(); val t = (-camera.y / camera.zoom).toFloat()
        val r = ((viewport.width - camera.x) / camera.zoom).toFloat(); val b = ((viewport.height - camera.y) / camera.zoom).toFloat()
        val detail = camera.zoom >= Blueprint.FarZoom
        mesh(GRID, camera); mesh(WIRES, camera); flow(camera, shift / density * zoom.toFloat())
        world(camera) {
            if (!detail) drawCards(false, l, t, r, b, deadline)
            drawBodies(l, t, r, b)
            drawHeaders(l, t, r, b, deadline)
            if (detail) drawCards(true, l, t, r, b, deadline) else drawGlows(l, t, r, b)
            drawLabels(l, t, r, b)
            drawPulses(pulses)
        }
    }

    override fun release() {
        super.release()
        headers.forEach { it?.let(graphics::releaseGraphicsLayer) }; headers.fill(null)
        pills.values.forEach(graphics::releaseGraphicsLayer); pills.clear()
    }

    fun cardAt(x: Float, y: Float): String? {
        for (i in cards.indices.reversed()) if (looks.getOrNull(i) != null && x >= cardBox[i * 4] && x <= cardBox[i * 4 + 2] && y >= cardBox[i * 4 + 1] && y <= cardBox[i * 4 + 3]) return cards[i].id
        return null
    }

    fun headerAt(x: Float, y: Float) = frames.indices.any { i -> x >= frameBox[i * 4] && x <= frameBox[i * 4 + 2] && y >= frameBox[i * 4 + 1] && y <= frameBox[i * 4 + 1] + BlueprintEngine.FrameTop }

    fun linkAt(x: Float, y: Float, tolerance: Float): Link? {
        var best = -1; var distance = tolerance
        for (i in links.indices) {
            if (x < linkBox[i * 4] - tolerance || x > linkBox[i * 4 + 2] + tolerance || y < linkBox[i * 4 + 1] - tolerance || y > linkBox[i * 4 + 3] + tolerance) continue
            var k = start[i]
            while (k + 3 < start[i + 1]) {
                val d = segment(x, y, points[k], points[k + 1], points[k + 2], points[k + 3])
                if (d < distance) { distance = d; best = i }
                k += 2
            }
        }
        return if (best < 0) null else links[best]
    }

    private fun segment(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay; val length = dx * dx + dy * dy
        val u = if (length == 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / length).coerceIn(0f, 1f)
        return hypot(ax + u * dx - x, ay + u * dy - y)
    }

    override fun tessellate(wired: Boolean) {
        val z = zoom.toFloat()
        if (dirty[GRID]) grid(z)
        if (dirty[WIRES]) { if (wired) wires(z, 1f / density, .2f / density) else pending = true }
    }

    private fun grid(z: Float) {
        val minor = (Blueprint.Grid.step * z).toFloat(); val major = minor * Blueprint.Grid.every
        commit(GRID, minor * density >= 7f || major * density >= 7f) {
            if (minor * density >= 7f) lines(minor, Blueprint.Grid.minor, 1f / density)
            if (major * density >= 7f) lines(major, Blueprint.Grid.major, 1.4f / density)
        }
    }

    private fun DrawScope.lines(spacing: Float, color: Color, width: Float) {
        val w = wr - wl; val h = wb - wt
        var x = ceil(wl / spacing) * spacing - wl
        while (x <= w) { drawRect(color, Offset(x - width / 2, 0f), Size(width, h)); x += spacing }
        var y = ceil(wt / spacing) * spacing - wt
        while (y <= h) { drawRect(color, Offset(0f, y - width / 2), Size(w, width)); y += spacing }
    }

    private fun DrawScope.drawBodies(l: Float, t: Float, r: Float, b: Float) {
        for (i in frames.indices) {
            if (!inside(frameBox, i, l, t, r, b)) continue
            val look = frameLooks[i]
            val at = Offset(frameBox[i * 4], frameBox[i * 4 + 1]); val size = Size(frameBox[i * 4 + 2] - at.x, frameBox[i * 4 + 3] - at.y)
            drawRoundRect(tint.copy(alpha = if (frames[i].muted || look.muted) .03f else .06f), at, size, CornerRadius(12f))
            drawRoundRect(look.lit?.copy(alpha = .75f) ?: tint.copy(alpha = .28f), at, size, CornerRadius(12f), style = Stroke(if (look.lit != null) 1.5f else 1f))
        }
    }

    private fun wires(z: Float, fringe: Float, tolerance: Float) {
        clear(true)
        meshStyles.fill(null)
        val l = wl / z; val t = wt / z; val r = wr / z; val b = wb / z
        val radius = 7f / density
        for (i in links.indices) {
            if (!inside(linkBox, i, l, t, r, b)) continue
            val style = styles[i]; meshStyles[i] = style
            val first = start[i]; val end = start[i + 1]
            if (end - first < 4 || style.alpha <= 0f) continue
            stroker.clear(); stroker.corners(points, first, end, z, wl, wt, radius, tolerance)
            style.glow?.let { stroker.stroke(solid, (style.width + style.glowWidth) * z / 2, fringe, it.toArgb(), style.alpha * .35f, round = true) }
            val rgb = style.color.toArgb()
            val mesh = style.dash?.let { (on, off) -> dash(on + style.width, max(off - style.width, 1f), style.flowing) } ?: solid
            stroker.stroke(mesh, style.width * z / 2, fringe, rgb, style.alpha, round = mesh === solid, dashed = mesh !== solid)
            if (links[i].reversed) arrow(points[first], points[first + 1], atan2(points[first + 1] - points[end - 1], points[first] - points[end - 2]), 10f / density, z, fringe, rgb, style.alpha)
            else arrow(points[end - 2], points[end - 1], atan2(points[end - 1] - points[end - 3], points[end - 2] - points[end - 4]), 10f / density, z, fringe, rgb, style.alpha)
        }
        commit(WIRES, true)
    }

    override fun DrawScope.warm(step: Int): Boolean {
        val i = cards.indices.filter { looks.getOrNull(it) != null }.maxByOrNull { cards[it].pins.size + looks[it]!!.marks.size } ?: return true
        if (!current(i, 1)) record(i, 1, null)
        val layer = layers[i * 2 + 1] ?: return true
        prime(layer, WARMUPS[step])
        if (step > 0) return true
        if (frames.isNotEmpty()) header(0, blueprintHeaderScale(zoom), null)?.let { prime(it, WARMUPS[step], .5f) }
        sample()
        drawContext.canvas.saveLayer(Rect(0f, 0f, cardBox[i * 4 + 2] - cardBox[i * 4], cardBox[i * 4 + 3] - cardBox[i * 4 + 1]), dimPaint)
        translate(-cardBox[i * 4], -cardBox[i * 4 + 1]) { drawLayer(layer); glow(i, accent, 1f) }
        drawContext.canvas.restore()
        return true
    }

    private fun DrawScope.drawCards(detail: Boolean, l: Float, t: Float, r: Float, b: Float, deadline: TimeSource.Monotonic.ValueTimeMark) {
        val dimming = highlight.isNotEmpty()
        for (pass in 0..1) {
            if (pass == 0 && !dimming) continue
            var open = false
            for (i in cards.indices) {
                val look = looks.getOrNull(i) ?: continue
                if ((dimming && cards[i].id !in highlight) != (pass == 0) || !inside(cardBox, i, l - REACH, t - REACH, r + REACH, b + REACH)) continue
                if (pass == 0 && !open) { drawContext.canvas.saveLayer(Rect(l - REACH, t - REACH, r + REACH, b + REACH), dimPaint); open = true }
                if (detail) {
                    halo(i)?.let { glow(i, it, 1f) }
                    layer(i, 1, deadline)?.let { drawLayer(it) } ?: placeholder(i, look, true)
                } else layer(i, 0, deadline)?.let { drawLayer(it) } ?: placeholder(i, look, false)
            }
            if (open) drawContext.canvas.restore()
        }
    }

    private fun DrawScope.drawGlows(l: Float, t: Float, r: Float, b: Float) {
        for (i in cards.indices) {
            if (looks.getOrNull(i) == null || !inside(cardBox, i, l - REACH, t - REACH, r + REACH, b + REACH)) continue
            val color = halo(i) ?: continue
            glow(i, color, if (highlight.isNotEmpty() && cards[i].id !in highlight) .2f else 1f)
        }
    }

    private fun halo(i: Int): Color? = if (cards[i].id == selected) accent else glows.getOrNull(i)

    private fun DrawScope.glow(i: Int, color: Color, alpha: Float) {
        val x = cardBox[i * 4]; val y = cardBox[i * 4 + 1]; val w = cardBox[i * 4 + 2] - x; val h = cardBox[i * 4 + 3] - y
        for (k in 0..2) {
            val spread = SPREADS[k] / density
            drawRoundRect(color.copy(alpha = GLOWS[k] * alpha), Offset(x - spread, y - spread), Size(w + spread * 2, h + spread * 2), CornerRadius(8f + spread), style = Stroke(spread))
        }
    }

    private fun DrawScope.placeholder(i: Int, look: BlueprintCardLook, detail: Boolean) {
        val x = cardBox[i * 4]; val y = cardBox[i * 4 + 1]; val w = cardBox[i * 4 + 2] - x; val h = cardBox[i * 4 + 3] - y
        if (!detail) { drawRoundRect(lerp(look.tone, Color.Black, .62f), Offset(x, y), Size(w, h), CornerRadius(8f)); return }
        drawRoundRect(Blueprint.Body, Offset(x, y), Size(w, h), CornerRadius(8f))
        drawPath(headerPath(x, y, w), headerBrush(look.tone, x, w))
    }

    private fun farKey(i: Int, look: BlueprintCardLook): FarKey {
        val chosen = look.selected || cards[i].id == selected
        return FarKey(look.title, look.tone, chosen, if (chosen) null else glows.getOrNull(i) ?: look.outline)
    }

    override fun current(i: Int, slot: Int): Boolean {
        val look = looks.getOrNull(i) ?: return true
        return layers[i * 2 + slot] != null && keys[i * 2 + slot] == (if (slot == 1) look else farKey(i, look))
    }

    override fun record(i: Int, slot: Int, deadline: TimeSource.Monotonic.ValueTimeMark?) {
        val look = looks.getOrNull(i) ?: return
        val card = cards[i]
        if (slot == 1) {
            tile(i, 1, card.x, card.y, card.width, card.height, MARGIN) { x, y -> drawDetail(card, look, x, y) }
            keys[i * 2 + 1] = look
        } else {
            val key = farKey(i, look)
            tile(i, 0, card.x, card.y, card.width, card.height, MARGIN) { x, y -> drawFar(card, key, x, y) }
            keys[i * 2] = key
        }
    }

    override fun slot(zoom: Double, ahead: Boolean): Int {
        val detail = zoom >= Blueprint.FarZoom
        return if (!ahead) (if (detail) 1 else 0) else when {
            detail && zoom < Blueprint.FarZoom * 1.35 -> 0
            !detail && zoom > Blueprint.FarZoom * .7 -> 1
            else -> -1
        }
    }

    override fun headers(deadline: TimeSource.Monotonic.ValueTimeMark?, zoom: Double, l: Float, t: Float, r: Float, b: Float): Boolean {
        val scale = blueprintHeaderScale(zoom)
        for (i in frames.indices) {
            if (!inside(frameBox, i, l, t, r, b) || headers[i] != null && headerKey[i] == HeaderKey(frameLooks[i], scale)) continue
            if (deadline == null || deadline.hasPassedNow()) return true
            header(i, scale, null)
        }
        return false
    }

    private fun DrawScope.drawHeaders(l: Float, t: Float, r: Float, b: Float, deadline: TimeSource.Monotonic.ValueTimeMark) {
        val scale = blueprintHeaderScale(zoom)
        for (i in frames.indices) {
            if (!inside(frameBox, i, l, t, r, b) || frameBox[i * 4 + 1] + BlueprintEngine.FrameTop < t) continue
            header(i, scale, deadline)?.let { drawLayer(it) }
        }
    }

    private fun header(i: Int, scale: Float, deadline: TimeSource.Monotonic.ValueTimeMark?): GraphicsLayer? {
        val key = HeaderKey(frameLooks[i], scale)
        val layer = headers[i]
        if (layer != null && headerKey[i] == key) return layer
        if (deadline?.hasPassedNow() == true) { pending = true; return layer }
        val frame = frames[i]
        val next = layer ?: graphics.createGraphicsLayer().also { headers[i] = it }
        next.place(frame.x, frame.y, frame.width, BlueprintEngine.FrameTop) { x, y -> drawHeader(frame, key.look, scale, x, y) }
        headerKey[i] = key
        return next
    }

    private fun DrawScope.drawLabels(l: Float, t: Float, r: Float, b: Float) {
        if (labels.isEmpty()) return
        for (i in links.indices) {
            val label = labels[links[i].id]
            if (label.isNullOrBlank() || !inside(linkBox, i, l, t, r, b)) continue
            val style = styles[i]
            if (!style.flowing && style.alpha < .9f) continue
            val color = style.color.copy(alpha = .96f)
            val layer = pills.getOrPut(label to color) { pill(label, color) }
            at(i, lengths[i] / 2) { x, y -> translate(x - layer.size.width / 2f, y - layer.size.height / 2f) { drawLayer(layer) } }
        }
    }

    private fun pill(label: String, color: Color): GraphicsLayer {
        val text = measurer.measure(label, type.label(color), maxLines = 1)
        val w = text.size.width + 14f; val h = text.size.height + 4f
        return graphics.createGraphicsLayer().also { layer ->
            layer.record(unit, LayoutDirection.Ltr, IntSize(ceil(w).toInt(), ceil(h).toInt())) {
                drawRoundRect(PillSurface, Offset.Zero, Size(w, h), CornerRadius(h / 2))
                drawRoundRect(PillBorder, Offset(.5f, .5f), Size(w - 1f, h - 1f), CornerRadius(h / 2 - .5f), style = Stroke(1f))
                paint(text, 7f, 2f)
            }
        }
    }

    private inline fun at(i: Int, distance: Float, use: (Float, Float) -> Unit) {
        var remaining = distance
        var k = start[i]
        while (k + 3 < start[i + 1]) {
            val d = hypot(points[k + 2] - points[k], points[k + 3] - points[k + 1])
            if (remaining <= d) {
                val u = if (d == 0f) 0f else remaining / d
                use(points[k] + (points[k + 2] - points[k]) * u, points[k + 1] + (points[k + 3] - points[k + 1]) * u)
                return
            }
            remaining -= d; k += 2
        }
        if (start[i + 1] > start[i]) use(points[start[i + 1] - 2], points[start[i + 1] - 1])
    }

    private fun DrawScope.drawPulses(pulses: List<CanvasPulse>) {
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

    private fun hypot(dx: Float, dy: Float) = sqrt(dx * dx + dy * dy)

    private fun icon(vector: ImageVector) = icons.getOrPut(vector) { BlueprintIcon(vector) }

    private fun measure(value: String, style: TextStyle, width: Float): TextLayoutResult =
        measurer.measure(value, style, TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = max(width, 0f).toInt()))

    private fun headerPath(x: Float, y: Float, w: Float) = Path().apply {
        addRoundRect(RoundRect(x, y, x + w, y + 26f, topLeftCornerRadius = CornerRadius(8f), topRightCornerRadius = CornerRadius(8f)))
    }

    private fun headerBrush(tone: Color, x: Float, w: Float) = Brush.horizontalGradient(listOf(lerp(tone, Color.Black, .3f), lerp(tone, Color.Black, .78f)), x, x + w)

    private fun measureMark(mark: BlueprintMark): Measured = when (mark.kind) {
        BlueprintMark.Kind.Dot -> Measured(7f, 7f, null)
        BlueprintMark.Kind.Tag -> measurer.measure(mark.text.uppercase(), type.style(8.5.sp, MachineSignal.Editor.Canvas, weight = FontWeight.Bold), maxLines = 1)
            .let { Measured(it.size.width + 8f, it.size.height + 2f, it) }
        BlueprintMark.Kind.Badge -> measurer.measure(mark.text, type.style(MachineSignal.Editor.meta, mark.color), maxLines = 1)
            .let { Measured(it.size.width + 10f, max(16f, it.size.height.toFloat()), it) }
        BlueprintMark.Kind.Pill -> measurer.measure(mark.text, type.style(MachineSignal.Type.dataMicro, Color.White, mono = true), maxLines = 1)
            .let { Measured(it.size.width + 10f, it.size.height + 2f, it) }
    }

    private fun DrawScope.drawMark(mark: BlueprintMark, measured: Measured, x: Float, y: Float) {
        val size = Size(measured.width, measured.height)
        when (mark.kind) {
            BlueprintMark.Kind.Dot, BlueprintMark.Kind.Tag -> drawRoundRect(mark.color, Offset(x, y), size, CornerRadius(3f))
            BlueprintMark.Kind.Badge -> drawRoundRect(mark.color.copy(alpha = .18f), Offset(x, y), size, CornerRadius(3f))
            BlueprintMark.Kind.Pill -> drawRoundRect(mark.color.copy(alpha = .28f), Offset(x, y), size, CornerRadius(8f))
        }
        measured.text?.let { paint(it, x + (measured.width - it.size.width) / 2, y + (measured.height - it.size.height) / 2) }
    }

    private fun DrawScope.drawDetail(card: Card, look: BlueprintCardLook, x: Float, y: Float) {
        val w = card.width.toFloat(); val h = card.height.toFloat()
        drawRoundRect(Blueprint.Body, Offset(x, y), Size(w, h), CornerRadius(8f))
        drawPath(headerPath(x, y, w), headerBrush(look.tone, x, w))
        icon(look.icon).draw(this, x + 8f, y + 6f, 14f, Color.White.copy(alpha = .92f))
        val marks = look.marks.map(::measureMark)
        val titleWidth = w - 36f - marks.sumOf { it.width.toDouble() + 6 }.toFloat()
        val title = measure(look.title, titleStyle, titleWidth)
        paint(title, x + 28f, y + (26f - title.size.height) / 2)
        var at = x + 28f + max(titleWidth, 0f) + 6f
        look.marks.forEachIndexed { k, mark -> drawMark(mark, marks[k], at, y + (26f - marks[k].height) / 2); at += marks[k].width + 6f }
        paint(measure(look.subtitle, subtitleStyles[if (look.subtitleMono) 1 else 0], w - 18f), x + 10f, y + 29f)
        val oneSided = card.pins.all { it.provides } || card.pins.none { it.provides }
        card.pins.forEachIndexed { k, pin ->
            val pinLook = look.pins.getOrNull(k) ?: return@forEachIndexed
            val width = if (oneSided) w else w / 2
            drawPin(pin.provides, pinLook, x + if (pin.provides && !oneSided) w / 2 else 0f, y + pin.y.toFloat(), width)
        }
        look.folded?.let { folded -> measure(folded, foldedStyle, w).let { paint(it, x + w - 8f - it.size.width, y + 30f) } }
        val border = when { look.selected -> 2f; look.outline != null -> 1.6f; else -> 1f }
        val edge = when { look.selected -> accent; look.outline != null -> look.outline; else -> look.tone.copy(alpha = .3f) }
        drawRoundRect(edge, Offset(x + border / 2, y + border / 2), Size(w - border, h - border), CornerRadius(8f - border / 2), style = Stroke(border))
    }

    private fun DrawScope.drawPin(provides: Boolean, pin: BlueprintPinLook, x: Float, middle: Float, width: Float) {
        val text = measure(pin.text, if (pin.chip) chipStyle else type.style(10.5.sp, pin.textColor, mono = true), width - 26f - if (pin.chip) 8f else 0f)
        val box = text.size.width + if (pin.chip) 8f else 0f
        val dot = Offset(if (provides) x + width - 10f else x + 10f, middle)
        val left = if (provides) x + width - 20f - box else x + 20f
        if (pin.filled) drawCircle(pin.color, 4f, dot)
        drawCircle(pin.color, 3.3f, dot, style = Stroke(1.4f))
        val top = middle - text.size.height / 2f
        if (pin.chip) drawRoundRect(pin.color.copy(alpha = .2f), Offset(left, top), Size(box, text.size.height.toFloat()), CornerRadius(3f))
        paint(text, left + if (pin.chip) 4f else 0f, top)
    }

    private fun DrawScope.drawFar(card: Card, key: FarKey, x: Float, y: Float) {
        val w = card.width.toFloat(); val h = card.height.toFloat()
        drawRoundRect(Brush.horizontalGradient(listOf(lerp(key.tone, Color.Black, .45f), lerp(key.tone, Color.Black, .8f)), x, x + w), Offset(x, y), Size(w, h), CornerRadius(8f))
        val border = if (key.selected) 2f else if (key.outline != null) 1.6f else 1f
        drawRoundRect(if (key.selected) accent else key.outline ?: key.tone.copy(alpha = .3f), Offset(x, y), Size(w, h), CornerRadius(8f), style = Stroke(border))
        val size = Blueprint.fitted(key.title, card.width - 18, 13.0, Blueprint.FarZoom)
        paint(measure(key.title, type.style(size, MachineSignal.Editor.Text, mono = true, weight = FontWeight.SemiBold), w - 18f), x + 10f, y + 6f)
    }

    private fun DrawScope.drawHeader(frame: Frame, look: BlueprintFrameLook, scale: Float, x: Float, y: Float) {
        val top = BlueprintEngine.FrameTop.toFloat()
        val w = frame.width.toFloat()
        drawRect(Brush.verticalGradient(listOf(tint.copy(alpha = .2f), tint.copy(alpha = .07f)), y, y + top), Offset(x, y), Size(w, top))
        val right = x + w - 12f
        var at = x + 12f
        icon(look.icon).draw(this, at, y + (top - 14f * scale) / 2, 14f * scale, tint)
        at += 14f * scale + 8f
        listOf(frame.label, look.count, frame.detail.joinToString(" · ")).forEachIndexed { k, value ->
            val text = measure(value, headerStyles[k], (right - at) / scale)
            withTransform({ translate(at, y + (top - text.size.height * scale) / 2); scale(scale, scale, Offset.Zero) }) { paint(text, 0f, 0f) }
            at += text.size.width * scale + 8f
        }
        for (mark in look.marks) {
            if (at >= right) break
            val measured = measureMark(mark)
            drawMark(mark, measured, at, y + (top - measured.height) / 2)
            at += measured.width + 8f
        }
    }

    private companion object {
        const val GRID = 0; const val WIRES = 1
        const val MARGIN = 2.0
        const val REACH = 20f
        val SPREADS = floatArrayOf(2f, 6f, 12f)
        val GLOWS = floatArrayOf(.38f, .18f, .08f)
        val PillSurface = Color(0xD90A0D14)
        val PillBorder = Color(0xFF232B3E)
    }
}
