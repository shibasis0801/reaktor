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
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.ui.unit.IntOffset
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
import dev.shibasis.reaktor.blueprint.Pin
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphEngine
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphPen
import dev.shibasis.reaktor.blueprint.product.surfaceGraphArgb
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.time.TimeSource

internal class BlueprintType(private val ui: FontFamily, private val mono: FontFamily, private val base: TextStyle, private val labelFont: FontFamily = FontFamily.Monospace) {
    fun style(size: TextUnit, color: Color, mono: Boolean = false, weight: FontWeight = FontWeight.Normal) =
        base.merge(TextStyle(color = color, fontSize = size, fontWeight = weight, fontFamily = if (mono) this.mono else ui))

    fun label(color: Color) = base.merge(TextStyle(color = color, fontSize = 8.5.sp, fontFamily = labelFont))
}

internal fun blueprintHeaderScale(zoom: Double) = 2.0.pow(round(log2((1.0 / zoom.coerceIn(0.35, 1.0)).coerceAtMost(1.8)) * 32) / 32).toFloat()

private data class FarKey(val title: String, val tone: Color, val selected: Boolean, val outline: Color?)
private data class HeaderKey(val look: BlueprintFrameLook, val scale: Float)
private data class BannerKey(val look: BlueprintFrameLook, val room: Int)
private class Measured(val width: Float, val height: Float, val text: TextLayoutResult?)

internal class BlueprintPainter(graphics: GraphicsContext, private val measurer: TextMeasurer, private val type: BlueprintType, private val colors: dev.shibasis.reaktor.ui.machinesignal.MachineSignalColors) : SurfaceGraphEngine(graphics, scaled = true) {
    private val icons = HashMap<ImageVector, BlueprintIcon>()
    private val tint = colors.source
    private val accent = colors.controlAccent
    private val accentArgb = accent.toArgb()
    private val titleStyle = type.style(12.5.sp, colors.textStrong, mono = true, weight = FontWeight.SemiBold)
    private val subtitleStyles = arrayOf(type.style(10.5.sp, colors.textMuted), type.style(10.5.sp, colors.textMuted, mono = true))
    private val chipStyle = type.style(10.sp, colors.textStrong, mono = true)
    private val foldedStyle = type.style(9.5.sp, colors.textMuted)
    private val headerStyles = arrayOf(type.style(13.sp, colors.textStrong, weight = FontWeight.SemiBold),
        type.style(11.sp, colors.textMuted), type.style(11.sp, colors.textMuted, mono = true))

    private var layout: BlueprintLayout<*>? = null
    private var cards = emptyArray<Card>()
    private var keys = arrayOfNulls<Any>(0)
    private var looks: List<BlueprintCardLook?> = emptyList()
    private val naturals = HashMap<Pair<String, BlueprintFrameLook>, Float>()
    private var glows: List<Color?> = emptyList()
    private var frameLooks: List<BlueprintFrameLook> = emptyList()
    private var styles: List<LinkStyle> = emptyList()
    private var meshStyles = arrayOfNulls<LinkStyle>(0)
    private var rerouted = IntArray(64)
    private var selected: String? = null
    private var highlight: Set<String> = emptySet()
    private var grabbed: Set<String> = emptySet()

    override val warmups = WARMUPS.size
    override val warmable get() = cards.isNotEmpty()
    override val canvas = colors.canvas
    override val grid = CanvasGrid(minor = colors.textMuted.copy(alpha = .025f), major = colors.textMuted.copy(alpha = .055f))
    override val farZoom = Blueprint.FarZoom
    override val meshZoom = .2
    override val cardRadius = MachineSignal.Radius.node.value

    fun sync(layout: BlueprintLayout<*>, looks: List<BlueprintCardLook?>, glows: List<Color?>, frameLooks: List<BlueprintFrameLook>, styles: List<LinkStyle>,
             selected: String?, highlight: Set<String>, labels: Map<String, String>, grabbed: Set<String> = emptySet()) {
        val fresh = layout !== this.layout
        if (fresh) scene(layout)
        if (grabbed !== this.grabbed) { this.grabbed = grabbed; if (grabbed.isNotEmpty()) ready(grabbed) }
        if (styles !== this.styles) { this.styles = styles; restyle { meshStyles[it] != styles[it] } }
        if (frameLooks !== this.frameLooks && (frameLooks.size != this.frameLooks.size || frameLooks.indices.any { frameLooks[it].lit != this.frameLooks[it].lit || frameLooks[it].muted != this.frameLooks[it].muted })) dirty[UNDER] = true
        val marked = fresh || glows !== this.glows || selected != this.selected || highlight != this.highlight
        this.frameLooks = frameLooks; this.looks = looks; this.glows = glows; this.selected = selected; this.highlight = highlight; this.labels = labels
        if (marked) mark()
    }

    private fun mark() {
        var touched = false; var any = false
        for (i in cards.indices) {
            val glow = if (cards[i].id == selected) accentArgb else glows.getOrNull(i)?.toArgb() ?: 0
            val faded = highlight.isNotEmpty() && cards[i].id !in highlight
            if (halo[i] != glow || dim[i] != faded) { halo[i] = glow; dim[i] = faded; touched = true }
            any = any || faded
        }
        anyDim = any
        if (touched) { dirty[CARDS] = true; changed = true }
    }

    private fun scene(layout: BlueprintLayout<*>) {
        val last = this.layout
        this.layout = layout
        if (last != null && carried(layout)) return
        val next = layout.cards.values.toTypedArray()
        val previous = HashMap<String, Int>(cards.size * 2).also { map -> cards.forEachIndexed { i, card -> map[card.id] = i } }
        val index = IntArray(next.size) { i -> previous[next[i].id]?.takeIf { cards[it] == next[i] } ?: -1 }
        remap(index)
        keys = arrayOfNulls<Any>(next.size * 2).also { kept -> index.forEachIndexed { i, j -> if (j >= 0) { kept[i * 2] = keys[j * 2]; kept[i * 2 + 1] = keys[j * 2 + 1] } } }
        cards = next
        index(layout.frames, layout.links, FloatArray(next.size * 4).also { box -> next.forEachIndexed { i, c -> box.put(i, c.x, c.y, c.width, c.height) } })
        meshStyles = arrayOfNulls(layout.links.size)
    }

    private fun carried(layout: BlueprintLayout<*>): Boolean {
        if (layout.cards.size != cards.size || layout.links.size != links.size) return false
        val next = layout.cards.values.toTypedArray()
        for (i in next.indices) {
            val card = next[i]; val old = cards[i]
            if (card !== old && (card.id != old.id || card.width != old.width || card.height != old.height || card.folded != old.folded || card.pins != old.pins)) return false
        }
        var count = 0
        layout.links.forEachIndexed { i, link ->
            val old = links[i]
            if (link === old) return@forEachIndexed
            if (link.id != old.id || link.points.size != old.points.size) return false
            if (count == rerouted.size) rerouted = rerouted.copyOf(count * 2)
            rerouted[count++] = i
        }
        if (!reroute(layout.frames, layout.links, rerouted, count)) return false
        var moved = false
        for (i in next.indices) {
            val card = next[i]; val old = cards[i]
            if (card === old) continue
            moved = true
            cardBox.put(i, card.x, card.y, card.width, card.height)
            for (slot in 0..1) layers[i * 2 + slot]?.let { if (!slid(it, old, card)) keys[i * 2 + slot] = null }
        }
        cards = next
        if (moved) { dirty[CARDS] = true; dirty[OVER] = true; changed = true }
        return true
    }

    private fun slid(layer: GraphicsLayer, old: Card, card: Card): Boolean {
        val ox = floor(old.x - MARGIN); val oy = floor(old.y - MARGIN); val x = floor(card.x - MARGIN); val y = floor(card.y - MARGIN)
        if (old.x - ox != card.x - x || old.y - oy != card.y - y) return false
        layer.topLeft = IntOffset(x.toInt(), y.toInt())
        return true
    }

    fun draw(scope: DrawScope, camera: Viewport, viewport: Size, density: Float, level: Int, shift: Float, pulses: List<CanvasPulse>) {
        this.level = level; this.shift = shift; this.pulses = pulses
        with(scope) { frame(camera, viewport, density) }
    }

    fun cardAt(x: Float, y: Float): String? = cardIndex(x, y).takeIf { it >= 0 }?.let { cards[it].id }

    fun pinAt(x: Float, y: Float): Pair<Card, Pin>? = cardIndex(x, y).takeIf { it >= 0 && level == 2 }?.let { i -> pin(i, x, y)?.let { cards[i] to it } }

    override fun pinKey(i: Int, x: Float, y: Float) = pin(i, x, y)?.key

    private fun pin(i: Int, x: Float, y: Float): Pin? {
        val card = cards[i]; val look = looks.getOrNull(i) ?: return null
        val oneSided = card.pins.all { it.provides } || card.pins.none { it.provides }
        val half = BlueprintEngine.RowHeight / 2
        return card.pins.indices.firstOrNull { k -> val pin = card.pins[k]
            look.pins.getOrNull(k) != null && y - card.y - pin.y in -half..<half && (oneSided || (x - card.x >= card.width / 2) == pin.provides)
        }?.let(card.pins::get)
    }

    override fun shown(i: Int) = looks.getOrNull(i) != null

    override fun pen(i: Int, pen: SurfaceGraphPen): Boolean {
        val style = styles[i]; meshStyles[i] = style
        pen.rgb = style.color.toArgb(); pen.alpha = style.alpha; pen.width = style.width; pen.flowing = style.flowing; pen.head = 10f; pen.lead = style.alpha >= .99f; pen.lifted = style.lifted
        style.glow?.let { pen.glow = it.toArgb(); pen.glowWidth = style.glowWidth }
        style.dash?.let { (on, off) -> pen.on = on; pen.off = off }
        return true
    }

    override fun body(i: Int, x0: Float, y0: Float, x1: Float, y1: Float, z: Float, fringe: Float, tolerance: Float) {
        val look = frameLooks.getOrNull(i) ?: return
        val lit = look.lit
        stroker.roundRect(x0, y0, x1, y1, MachineSignal.Radius.panel.value * z, tolerance)
        stroker.fill(solid, fringe, colors.surface.copy(alpha = if (frames[i].muted || look.muted) .2f else .5f).toArgb(), min(x1 - x0, y1 - y0))
        stroker.outline(solid, (if (lit != null) 1.5f else 1f) * z / 2, fringe, (lit ?: colors.line).toArgb(), if (lit != null) .75f else .24f)
    }

    override fun face(i: Int, x0: Float, y0: Float, x1: Float, y1: Float, z: Float, fringe: Float, tolerance: Float) {
        val look = looks.getOrNull(i) ?: return
        val alpha = if (dim[i]) .2f else 1f
        val chosen = look.selected || cards[i].id == selected
        val edge = if (chosen) accent else glows.getOrNull(i) ?: look.outline
        stroker.roundRect(x0, y0, x1, y1, MachineSignal.Radius.node.value * z, tolerance)
        stroker.fill(solid, fringe, surfaceGraphArgb(lerp(colors.surface, look.tone, .06f).toArgb(), alpha), min(x1 - x0, y1 - y0))
        stroker.outline(solid, if (chosen) 1f else if (edge != null) .8f else .5f, fringe, (edge ?: colors.line).toArgb(), (edge?.alpha ?: .45f) * alpha)
    }

    override fun cardId(i: Int) = cards[i].id

    override fun endTitle(i: Int, to: Boolean) = cardOf(if (to) links[i].to else links[i].from).takeIf { it >= 0 }?.let { looks.getOrNull(it)?.title }

    override fun headerKey(i: Int): Any? = frameLooks.getOrNull(i)?.let { HeaderKey(it, blueprintHeaderScale(zoom)) }

    override fun banner(i: Int, width: Float, zoom: Double): GraphicsLayer? {
        val look = frameLooks.getOrNull(i) ?: return null
        val title = frames[i].label
        if (naturals.size > 512) naturals.clear()
        val natural = naturals.getOrPut(title to look) { 43f + measure(title, headerStyles[0], 1e5f).size.width + (look.count.takeIf { it.isNotEmpty() }?.let { measure(it, headerStyles[1], 1e5f).size.width + 8f } ?: 0f) }
        val room = if (min(width, 360f) >= natural) -1 else min(width, 360f).toInt() / 8 * 8
        if (room in 0 until 48) return null
        return bannerLayer(i, BannerKey(look, room)) { layer ->
            val space = if (room < 0) natural else room.toFloat()
            val label = measure(title, headerStyles[0], space - 43f)
            val count = look.count.takeIf { it.isNotEmpty() }?.let { measure(it, headerStyles[1], space - 51f - label.size.width) }
            val w = min(space, 43f + label.size.width + (count?.let { it.size.width + 8f } ?: 0f))
            layer.record(unit, LayoutDirection.Ltr, IntSize(ceil(w).toInt(), 30)) {
                drawPath(Path().apply { addRoundRect(RoundRect(0f, 0f, w, 30f, bottomRightCornerRadius = CornerRadius(MachineSignal.Radius.panel.value))) }, colors.surface.copy(alpha = .92f))
                drawRect(look.lit ?: tint, Offset.Zero, Size(3f, 30f))
                icon(look.icon).draw(this, 11f, 8f, 14f, tint)
                paint(label, 31f, (30f - label.size.height) / 2)
                count?.let { paint(it, 39f + label.size.width, (30f - it.size.height) / 2) }
            }
        }
    }

    override fun DrawScope.header(i: Int, key: Any, x: Float, y: Float) { (key as HeaderKey).let { drawHeader(frames[i], it.look, it.scale, x, y) } }

    override fun pill(label: String, color: Color): GraphicsLayer {
        val text = measurer.measure(label, type.label(color), maxLines = 1)
        val w = text.size.width + 14f; val h = text.size.height + 4f
        return graphics.createGraphicsLayer().also { layer ->
            layer.record(unit, LayoutDirection.Ltr, IntSize(ceil(w).toInt(), ceil(h).toInt())) {
                drawRoundRect(colors.surface.copy(alpha = .85f), Offset.Zero, Size(w, h), CornerRadius(h / 2))
                drawRoundRect(colors.line, Offset(.5f, .5f), Size(w - 1f, h - 1f), CornerRadius(h / 2 - .5f), style = Stroke(1f))
                paint(text, 7f, 2f)
            }
        }
    }

    override fun chip(label: String, color: Color): GraphicsLayer {
        val text = measurer.measure(label, type.style(11.sp, if (color.luminance() > .55f) colors.canvas else Color.White, weight = FontWeight.SemiBold), TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = 180))
        val w = text.size.width + 18f; val h = text.size.height + 8f
        return graphics.createGraphicsLayer().also { layer ->
            layer.record(unit, LayoutDirection.Ltr, IntSize(ceil(w).toInt(), ceil(h + 2f).toInt())) {
                drawRoundRect(Color.Black.copy(alpha = .3f), Offset(0f, 1.5f), Size(w, h), CornerRadius(h / 2))
                drawRoundRect(color, Offset.Zero, Size(w, h), CornerRadius(h / 2))
                paint(text, 9f, 4f)
            }
        }
    }

    override fun DrawScope.warm(step: Int): Boolean {
        val i = cards.indices.filter { looks.getOrNull(it) != null }.maxByOrNull { cards[it].pins.size + looks[it]!!.marks.size } ?: return true
        if (!current(i, 1)) record(i, 1, null)
        val layer = layers[i * 2 + 1] ?: return true
        prime(layer, WARMUPS[step])
        if (step > 0) return true
        if (frames.isNotEmpty()) headerLayer(0, ahead = true)?.let { prime(it, WARMUPS[step], .5f) }
        sample()
        drawContext.canvas.saveLayer(Rect(0f, 0f, cardBox[i * 4 + 2] - cardBox[i * 4], cardBox[i * 4 + 3] - cardBox[i * 4 + 1]), dimPaint)
        translate(-cardBox[i * 4], -cardBox[i * 4 + 1]) { drawLayer(layer) }
        drawContext.canvas.restore()
        return true
    }

    override fun DrawScope.placeholder(i: Int, slot: Int) {
        val look = looks.getOrNull(i) ?: return
        val x = cardBox[i * 4]; val y = cardBox[i * 4 + 1]; val w = cardBox[i * 4 + 2] - x; val h = cardBox[i * 4 + 3] - y
        if (slot == 0) { drawRoundRect(lerp(colors.surface, look.tone, .06f), Offset(x, y), Size(w, h), CornerRadius(MachineSignal.Radius.node.value)); return }
        drawRoundRect(colors.canvas.copy(alpha = .95f), Offset(x, y), Size(w, h), CornerRadius(MachineSignal.Radius.node.value))
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

    private fun icon(vector: ImageVector) = icons.getOrPut(vector) { BlueprintIcon(vector) }

    private fun measure(value: String, style: TextStyle, width: Float): TextLayoutResult =
        measurer.measure(value, style, TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = max(width, 0f).toInt()))

    private fun headerPath(x: Float, y: Float, w: Float) = Path().apply {
        addRoundRect(RoundRect(x, y, x + w, y + 26f, topLeftCornerRadius = CornerRadius(MachineSignal.Radius.node.value), topRightCornerRadius = CornerRadius(MachineSignal.Radius.node.value)))
    }

    private fun headerBrush(tone: Color, x: Float, w: Float) = Brush.horizontalGradient(listOf(lerp(colors.canvas.copy(alpha = .95f), tone, .04f), colors.canvas.copy(alpha = .95f)), x, x + w)

    private fun measureMark(mark: BlueprintMark): Measured = when (mark.kind) {
        BlueprintMark.Kind.Dot -> Measured(7f, 7f, null)
        BlueprintMark.Kind.Tag -> measurer.measure(mark.text.uppercase(), type.style(8.5.sp, colors.canvas, weight = FontWeight.Bold), maxLines = 1)
            .let { Measured(it.size.width + 8f, it.size.height + 2f, it) }
        BlueprintMark.Kind.Badge -> measurer.measure(mark.text, type.style(MachineSignal.Editor.meta, mark.color), maxLines = 1)
            .let { Measured(it.size.width + 10f, max(16f, it.size.height.toFloat()), it) }
        BlueprintMark.Kind.Pill -> measurer.measure(mark.text, type.style(MachineSignal.Type.dataMicro, colors.textStrong, mono = true), maxLines = 1)
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
        drawRoundRect(colors.canvas.copy(alpha = .95f), Offset(x, y), Size(w, h), CornerRadius(MachineSignal.Radius.node.value))
        drawPath(headerPath(x, y, w), headerBrush(look.tone, x, w))
        icon(look.icon).draw(this, x + 8f, y + 6f, 14f, look.tone)
        val marks = look.marks.map(::measureMark)
        val titleWidth = w - 36f - marks.sumOf { it.width.toDouble() + 6 }.toFloat()
        val title = measure(look.title, titleStyle, titleWidth)
        paint(title, x + 28f, y + (26f - title.size.height) / 2)
        var at = x + 28f + max(titleWidth, 0f) + 6f
        look.marks.forEachIndexed { k, mark -> drawMark(mark, marks[k], at, y + (26f - marks[k].height) / 2); at += marks[k].width + 6f }
        val folded = look.folded?.let { measure(it, foldedStyle, w / 2) }
        paint(measure(look.subtitle, subtitleStyles[if (look.subtitleMono) 1 else 0], w - 18f - (folded?.let { it.size.width + 8f } ?: 0f)), x + 10f, y + 29f)
        val oneSided = card.pins.all { it.provides } || card.pins.none { it.provides }
        card.pins.forEachIndexed { k, pin ->
            val pinLook = look.pins.getOrNull(k) ?: return@forEachIndexed
            val width = if (oneSided) w else w / 2
            drawPin(pin.provides, pinLook, x + if (pin.provides && !oneSided) w / 2 else 0f, y + pin.y.toFloat(), width)
        }
        folded?.let { paint(it, x + w - 8f - it.size.width, y + 30f) }
        val border = when { look.selected -> 2f; look.outline != null -> 1.6f; else -> 1f }
        val edge = when { look.selected -> accent; look.outline != null -> look.outline; else -> colors.line.copy(alpha = .45f) }
        drawRoundRect(edge, Offset(x + border / 2, y + border / 2), Size(w - border, h - border), CornerRadius(MachineSignal.Radius.node.value - border / 2), style = Stroke(border))
    }

    private fun DrawScope.drawPin(provides: Boolean, pin: BlueprintPinLook, x: Float, middle: Float, width: Float) {
        if (pin.selected) {
            drawRoundRect(accent.copy(alpha = .16f), Offset(x + 3f, middle - BlueprintEngine.RowHeight.toFloat() / 2 + 1f), Size(width - 6f, BlueprintEngine.RowHeight.toFloat() - 2f), CornerRadius(4f))
            drawCircle(accent.copy(alpha = .35f), 7f, Offset(if (provides) x + width - 10f else x + 10f, middle))
        }
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
        drawRoundRect(lerp(colors.surface, key.tone, .06f), Offset(x, y), Size(w, h), CornerRadius(MachineSignal.Radius.node.value))
        val border = if (key.selected) 2f else if (key.outline != null) 1.6f else 1f
        drawRoundRect(if (key.selected) accent else key.outline ?: colors.line.copy(alpha = .45f), Offset(x, y), Size(w, h), CornerRadius(MachineSignal.Radius.node.value), style = Stroke(border))
        val size = Blueprint.fitted(key.title, card.width - 18, 13.0, Blueprint.FarZoom)
        paint(measure(key.title, type.style(size, colors.textStrong, mono = true, weight = FontWeight.SemiBold), w - 18f), x + 10f, y + 6f)
    }

    private fun DrawScope.drawHeader(frame: Frame, look: BlueprintFrameLook, scale: Float, x: Float, y: Float) {
        val top = BlueprintEngine.FrameTop.toFloat()
        val w = frame.width.toFloat()
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
        const val MARGIN = 2.0
    }
}
