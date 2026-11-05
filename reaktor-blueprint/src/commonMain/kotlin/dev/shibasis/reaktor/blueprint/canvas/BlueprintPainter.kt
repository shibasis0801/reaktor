package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.graphics.drawscope.clipPath
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
import dev.shibasis.reaktor.blueprint.BlueprintFace
import dev.shibasis.reaktor.blueprint.BlueprintFrameLook
import dev.shibasis.reaktor.blueprint.BlueprintLayout
import dev.shibasis.reaktor.blueprint.BlueprintOpening
import dev.shibasis.reaktor.blueprint.BlueprintMark
import dev.shibasis.reaktor.blueprint.BlueprintPinLook
import dev.shibasis.reaktor.blueprint.BlueprintReading
import dev.shibasis.reaktor.blueprint.BlueprintTitle
import dev.shibasis.reaktor.blueprint.BlueprintTier
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
        base.merge(TextStyle(color = color, fontSize = size, lineHeight = (size.value * 1.25f).sp, fontWeight = weight, fontFamily = if (mono) this.mono else ui))

    fun label(color: Color) = base.merge(TextStyle(color = color, fontSize = MachineSignal.Type.label, fontFamily = labelFont))
}

private data class PaintKey(val look: BlueprintCardLook)
private data class CardMeasure(val look: BlueprintCardLook, val card: Card, val rows: PaintContent, val bodyHeight: Float)
/** Below this scale, cards remain in the mesh and hit index without measuring unreadable text. */
internal const val BlueprintDetailZoom = .18
internal const val BlueprintOverviewCards = 512

private data class CardPaint(val key: PaintKey, val smallest: Float, val measured: CardMeasure)
internal data class PaintStats(val retainedPixelBytes: Long, val releasedViews: Long)

private class Read(val text: Float, val title: BlueprintTitle)
private class Measured(val width: Float, val height: Float, val text: TextLayoutResult?)

internal class BlueprintPainter(graphics: GraphicsContext, private val measurer: TextMeasurer, private val type: BlueprintType, private val colors: dev.shibasis.reaktor.ui.machinesignal.MachineSignalColors) : SurfaceGraphEngine(graphics, scaled = true) {
    private val icons = HashMap<ImageVector, BlueprintIcon>()
    private val tint = colors.source
    private val accent = colors.controlAccent
    private val accentArgb = accent.toArgb()
    private val titleStyle = type.style(12.5.sp, colors.textStrong, mono = true, weight = FontWeight.SemiBold)
    private val subtitleStyles = arrayOf(type.style(MachineSignal.Type.body2, colors.textMuted), type.style(MachineSignal.Type.data, colors.textMuted, mono = true))
    private val chipStyle = type.style(MachineSignal.Type.data, colors.textStrong, mono = true)
    private val foldedStyle = type.style(MachineSignal.Type.label, colors.textMuted)
    private val pinTextMargin = 20f + MachineSignal.Space.s0_5.value
    private val headerStyles = arrayOf(type.style(13.sp, colors.textStrong, weight = FontWeight.SemiBold),
        type.style(11.sp, colors.textMuted), type.style(11.sp, colors.textMuted, mono = true))
    private val proseTitles = Blueprint.ProseTitles.map(::prose)
    private val eyebrowStyle = type.style(Blueprint.ProseMeta.sp, colors.textMuted, weight = FontWeight.SemiBold).copy(letterSpacing = .6.sp)
    private val footerStyle = type.style(Blueprint.ProseMeta.sp, colors.textMuted)

    private fun prose(size: Double) = type.style(size.sp, colors.textStrong, weight = FontWeight.SemiBold).copy(lineHeight = (size * ProseLeading).sp)

    private var layout: BlueprintLayout<*>? = null
    private var cards = emptyArray<Card>()
    private var cardSlots = emptyMap<String, Int>()
    private var keys = arrayOfNulls<Any>(0)
    private var reads = arrayOfNulls<Read>(0)
    private var paints = arrayOfNulls<CardPaint>(0)
    private var markerKeys = emptyArray<MutableState<PaintKey?>>()
    private var looks: List<BlueprintCardLook?> = emptyList()
    private var glows: List<Color?> = emptyList()
    private var frameLooks: List<BlueprintFrameLook> = emptyList()
    private var styles: List<LinkStyle> = emptyList()
    private var meshStyles = arrayOfNulls<LinkStyle>(0)
    private var rerouted = IntArray(64)
    private var selected: String? = null
    private var highlight: Set<String> = emptySet()
    private var grabbed: Set<String> = emptySet()
    private var aside: Set<String> = emptySet()
    private var released = false
    private var releasedViews = 0L
    val stats: PaintStats
        get() = PaintStats(retainedPixelBytes, releasedViews)

    override val warmups = WARMUPS.size
    override val warmable get() = cards.isNotEmpty()
    override val canvas = colors.canvas
    override val grid = CanvasGrid(minor = colors.textMuted.copy(alpha = .025f), major = colors.textMuted.copy(alpha = .055f))
    override val cardRadius = MachineSignal.Radius.node.value
    override val worldPictures = true

    fun sync(layout: BlueprintLayout<*>, looks: List<BlueprintCardLook?>, glows: List<Color?>, frameLooks: List<BlueprintFrameLook>, styles: List<LinkStyle>,
             selected: String?, highlight: Set<String>, labels: Map<String, String>, grabbed: Set<String> = emptySet(), aside: Set<String> = emptySet(),
             focusLabels: Boolean = false) {
        this.focusLabels = focusLabels
        val fresh = layout !== this.layout
        if (fresh) scene(layout)
        if (grabbed !== this.grabbed) { this.grabbed = grabbed; if (grabbed.isNotEmpty()) ready(grabbed) }
        if (styles !== this.styles) {
            this.styles = styles
            restyle {
                val changed = meshStyles[it] != styles[it]
                if (changed) dirty[HOVER] = true
                changed
            }
        }
        if (frameLooks !== this.frameLooks && (frameLooks.size != this.frameLooks.size || frameLooks.indices.any { frameLooks[it].lit != this.frameLooks[it].lit || frameLooks[it].muted != this.frameLooks[it].muted || frameLooks[it].selected != this.frameLooks[it].selected })) dirty[UNDER] = true
        val marked = fresh || glows !== this.glows || selected != this.selected || highlight != this.highlight || aside != this.aside
        this.aside = aside
        this.frameLooks = frameLooks; this.looks = looks; this.glows = glows; this.selected = selected; this.highlight = highlight; this.labels = labels
        if (marked) mark()
    }

    override fun visibleLinkAt(x: Float, y: Float, tolerance: Float): Int =
        linkIndexAt(x, y, tolerance) { true }

    fun paintedLinkAt(x: Float, y: Float, reach: Float): Link? =
        visibleLinkAt(x, y, reach).takeIf { it >= 0 }?.let(links::get)

    override fun release() {
        if (released) return
        released = true
        super.release()
        releasedViews++
    }

    private fun mark() {
        var touched = false; var any = false
        for (i in cards.indices) {
            val glow = if (cards[i].id == selected && looks.getOrNull(i)?.image == null) accentArgb else glows.getOrNull(i)?.toArgb() ?: 0
            val faded = cards[i].id in aside || highlight.isNotEmpty() && cards[i].id !in highlight
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
        reads = arrayOfNulls<Read>(next.size * 2).also { kept -> index.forEachIndexed { i, j -> if (j >= 0) { kept[i * 2] = reads[j * 2]; kept[i * 2 + 1] = reads[j * 2 + 1] } } }
        paints = arrayOfNulls(next.size)
        markerKeys = Array(next.size) { mutableStateOf(null) }
        cards = next
        cardSlots = next.indices.associateBy { next[it].id }
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

    fun draw(scope: DrawScope, camera: Viewport, viewport: Size, density: Float, shift: Float, pulses: List<CanvasPulse>, labelExclusions: List<Rect> = emptyList()) {
        val z = camera.zoom
        level = if (cards.size > BlueprintOverviewCards && z / density < BlueprintDetailZoom) 0 else 2
        val l = ((-camera.x - viewport.width) / z).toFloat()
        val t = ((-camera.y - viewport.height) / z).toFloat()
        val r = ((viewport.width * 2 - camera.x) / z).toFloat()
        val b = ((viewport.height * 2 - camera.y) / z).toFloat()
        if (level > 0) for (i in cards.indices) if (inside(cardBox, i, l, t, r, b)) paint(i)
        this.shift = shift; this.pulses = pulses; this.labelExclusions = labelExclusions
        with(scope) { frame(camera, viewport, density) }
        for (i in paints.indices) markerKeys[i].value = paints[i]?.key?.takeIf { current(i, 0) || current(i, 1) }
    }

    private fun paint(i: Int): CardPaint? {
        val look = looks.getOrNull(i)?.takeIf { shown(i) } ?: return null
        val kept = paints[i]
        if (kept != null && kept.measured.card === cards[i] && kept.measured.look == look) return kept
        val next = measureCard(cards[i], look, kept)
        if (kept?.key != next.key) {
            for (slot in 0..1) {
                val at = i * 2 + slot
                layers[at]?.let { graphics.releaseGraphicsLayer(it); layers[at] = null; live-- }
                keys[at] = null
                reads[at] = null
            }
            dirty[CARDS] = true; dirty[OVER] = true; changed = true
        }
        paints[i] = next
        return next
    }

    fun reading(): BlueprintReading {
        val camera = camera ?: return BlueprintReading(level, Double.POSITIVE_INFINITY, emptyMap())
        val z = camera.zoom.toFloat()
        val l = -camera.x.toFloat() / z; val t = -camera.y.toFloat() / z
        val r = l + viewport.width / z; val b = t + viewport.height / z
        var smallest = Float.POSITIVE_INFINITY
        val titles = HashMap<BlueprintTitle, Int>()
        val tiers = HashMap<BlueprintTier, Int>()
        for (i in cards.indices) {
            if (!shown(i) || cardBox[i * 4 + 2] < l || cardBox[i * 4] > r || cardBox[i * 4 + 3] < t || cardBox[i * 4 + 1] > b) continue
            val read = (0..1).firstOrNull { current(i, it) }?.let { reads[i * 2 + it] }
            if (read != null) smallest = min(smallest, read.text)
            val title = read?.title ?: BlueprintTitle.Hidden
            titles[title] = (titles[title] ?: 0) + 1
            val tier = if (read == null) BlueprintTier.Overview else BlueprintTier.Detail
            tiers[tier] = (tiers[tier] ?: 0) + 1
        }
        return BlueprintReading(level, (smallest * z).toDouble(), titles, tiers)
    }

    fun agentBounds(id: String): BlueprintBadgeBounds? {
        val i = cardSlots[id] ?: return null
        markerKeys[i].value
        looks.getOrNull(i)?.takeIf { it.agent != null } ?: return null
        val next = committed(i) ?: return null
        val card = cards[i]
        val inset = MachineSignal.Space.s3.value.toDouble()
        return BlueprintBadgeBounds(card.x + inset, card.y + next.measured.bodyHeight + MachineSignal.AgentBadge.gap.value,
            card.width - inset * 2, card.height - next.measured.bodyHeight - MachineSignal.AgentBadge.gap.value * 2, 1.0, false)
    }

    fun cardAt(x: Float, y: Float): String? = cardIndex(x, y).takeIf { it >= 0 }?.let { cards[it].id }

    fun pinAt(x: Float, y: Float): Pair<Card, Pin>? = cardIndex(x, y).takeIf { it >= 0 && committed(it) != null }?.let { i -> pin(i, x, y)?.let { cards[i] to it } }

    private fun committed(i: Int): CardPaint? = paints.getOrNull(i)?.takeIf { current(i, 0) || current(i, 1) }

    fun drawn(id: String): Boolean {
        val i = cardSlots[id] ?: return false
        markerKeys[i].value
        return committed(i) != null
    }

    fun readableZoom(x: Double, y: Double, width: Double, height: Double): Double? {
        val i = cards.indexOfFirst { it.x == x && it.y == y && it.width == width && it.height == height }
        val look = looks.getOrNull(i) ?: return null
        val rows = worldMeasure(cards[i], look).rows.takeIf { it.complete && it.smallest.isFinite() && it.smallest > 0 } ?: return null
        return 11.0 / rows.smallest * 1.120001
    }

    override fun pinKey(i: Int, x: Float, y: Float) = pin(i, x, y)?.key

    private fun pin(i: Int, x: Float, y: Float): Pin? {
        val card = cards[i]; val look = looks.getOrNull(i) ?: return null
        if (paints.getOrNull(i) == null) return null
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

    override fun hover(pen: SurfaceGraphPen) {
        val alpha = pen.alpha
        super.hover(pen)
        pen.alpha = alpha
    }

    override fun body(i: Int, x0: Float, y0: Float, x1: Float, y1: Float, z: Float, fringe: Float, tolerance: Float) {
        val look = frameLooks.getOrNull(i) ?: return
        val edge = if (look.selected) accent else look.lit
        stroker.roundRect(x0, y0, x1, y1, MachineSignal.Radius.panel.value * z, tolerance)
        stroker.fill(solid, fringe, colors.surface.copy(alpha = if (frames[i].muted || look.muted) .2f else .5f).toArgb(), min(x1 - x0, y1 - y0))
        stroker.outline(solid, (if (edge != null) 1.5f else 1f) * z / 2, fringe, (edge ?: colors.line).toArgb(), if (look.selected) .9f else if (edge != null) .75f else .24f)
    }

    override fun face(i: Int, x0: Float, y0: Float, x1: Float, y1: Float, z: Float, fringe: Float, tolerance: Float) {
        val look = looks.getOrNull(i) ?: return
        stroker.roundRect(x0, y0, x1, y1, cardRadius * z, tolerance)
        stroker.fill(solid, fringe, cardBody(look.tone).toArgb(), min(x1 - x0, y1 - y0))
        stroker.outline(solid, z / 2, fringe, (look.outline ?: look.tone).toArgb(), if (look.selected) 1f else .65f)
    }

    override fun cardId(i: Int) = cards[i].id

    override fun endTitle(i: Int, to: Boolean) = cardOf(if (to) links[i].to else links[i].from).takeIf { it >= 0 }?.let { looks.getOrNull(it)?.title }

    override fun headerKey(i: Int): Any? = frameLooks.getOrNull(i)

    private fun budgetMarks(values: List<BlueprintMark>): List<BlueprintMark> =
        values.distinct().sortedBy { if (it.kind == BlueprintMark.Kind.Badge) 0 else 1 }.take(2)

    override fun DrawScope.header(i: Int, key: Any, x: Float, y: Float) = drawHeader(frames[i], key as BlueprintFrameLook, x, y)

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
        if (frames.isNotEmpty()) headerLayer(0)?.let { prime(it, WARMUPS[step], .5f) }
        sample()
        drawContext.canvas.saveLayer(Rect(0f, 0f, cardBox[i * 4 + 2] - cardBox[i * 4], cardBox[i * 4 + 3] - cardBox[i * 4 + 1]), dimPaint)
        translate(-cardBox[i * 4], -cardBox[i * 4 + 1]) { drawLayer(layer) }
        drawContext.canvas.restore()
        return true
    }

    override fun DrawScope.placeholder(i: Int, slot: Int) {
        val look = looks.getOrNull(i) ?: return
        val w = cards[i].width.toFloat(); val h = cards[i].height.toFloat()
        val x = cardBox[i * 4]; val y = cardBox[i * 4 + 1]
        drawRoundRect(cardBody(look.tone), Offset(x, y), Size(w, h), CornerRadius(MachineSignal.Radius.node.value))
        drawBorder(look.selected, look.outline, x, y, w, h)
    }

    override fun current(i: Int, slot: Int): Boolean =
        layers[i * 2 + slot] != null && keys[i * 2 + slot] == paints.getOrNull(i)?.key

    override fun record(i: Int, slot: Int, deadline: TimeSource.Monotonic.ValueTimeMark?) {
        val paint = paint(i) ?: return
        val look = paint.measured.look
        val card = cards[i]
        tile(i, slot, card.x, card.y, card.width, card.height, MARGIN) { x, y ->
            if (look.face == BlueprintFace.Prose) drawProse(card, look, x, y, paint.measured.bodyHeight) else drawDetail(card, look, x, y, paint.measured.bodyHeight)
        }
        keys[i * 2 + slot] = paint.key
        reads[i * 2 + slot] = Read(paint.smallest, BlueprintTitle.Whole)
    }

    private fun actionWidth(look: BlueprintCardLook, scale: Float = 1f): Float =
        if (look.actions.isEmpty()) 0f else (MachineSignal.Editor.controlHeight.value * look.actions.size + MachineSignal.Editor.controlGap.value) * scale

    fun body(node: dev.shibasis.reaktor.blueprint.BlueprintNode, look: BlueprintCardLook): dev.shibasis.reaktor.blueprint.BlueprintBody {
        fun width(value: String, style: TextStyle) = measurer.measure(value, style, maxLines = 1).size.width.toDouble()
        val marks = look.marks.sumOf { measureMark(it).width.toDouble() + MachineSignal.Editor.controlGap.value }
        val folded = look.folded?.let { width(it, foldedStyle) + MachineSignal.Space.s2.value } ?: 0.0
        var w = maxOf(BlueprintEngine.CardWidth, width(look.title, titleStyle) + BlueprintEngine.RowHeight * 2 + marks + actionWidth(look),
            width(look.subtitle, subtitleStyles[if (look.subtitleMono) 1 else 0]) + BlueprintEngine.RowHeight + folded)
        val oneSided = node.inputs.isEmpty() || node.outputs.isEmpty()
        val widestRow = look.pins.filterNotNull().maxOfOrNull { row ->
            val glyph = if (row.icon == null) 0.0 else (MachineSignal.Editor.smallIconSize.value + MachineSignal.Editor.controlGap.value).toDouble()
            val extra = row.marks.sumOf { measureMark(it).width.toDouble() + BlueprintEngine.RowHeight / 8 }
            width(row.text, if (row.chip) chipStyle else type.style(MachineSignal.Type.data, row.textColor, mono = true)) + pinTextMargin + extra + glyph + if (row.chip) 8 else 0
        } ?: 0.0
        w = maxOf(w, widestRow * if (oneSided) 1 else 2)
        var h = BlueprintEngine.cardHeight(node.copy(body = null, rows = if (look.face == BlueprintFace.Prose) null else node.rows))
        if (look.face == BlueprintFace.Prose) {
            val style = proseTitles.first()
            w = maxOf(BlueprintEngine.CardWidth, widestRow * if (oneSided) 1 else 2, look.title.split(Regex("\\s+")).maxOfOrNull { width(it, style) }?.plus(ProseInset + MachineSignal.Space.s3.value + actionWidth(look)) ?: 0.0,
                width(look.subtitle, footerStyle) + ProseInset + MachineSignal.Space.s3.value + actionWidth(look))
            val room = (w - ProseInset - MachineSignal.Space.s3.value - actionWidth(look)).toFloat()
            val title = measurer.measure(look.title, style, constraints = Constraints(maxWidth = room.toInt()))
            val top = MachineSignal.Space.s6.value + MachineSignal.Space.s0_5.value
            val bottom = if (look.subtitle.isEmpty()) MachineSignal.Space.s2_5.value else top
            h = maxOf(h, title.size.height + top + bottom.toDouble())
        }
        look.image?.let { image -> h = maxOf(h, BlueprintEngine.HeaderHeight + w * image.height / image.width) }
        look.agent?.let { h += agentHeight(it, w.toFloat()) }
        return dev.shibasis.reaktor.blueprint.BlueprintBody(ceil(w), ceil(h - node.pinHeader))
    }

    private fun agentHeight(agent: dev.shibasis.reaktor.blueprint.BlueprintAgent, width: Float): Float {
        val room = width - MachineSignal.Space.s3.value * 2 - MachineSignal.AgentBadge.size.value - MachineSignal.AgentBadge.gap.value
        val style = type.style(MachineSignal.Type.body2, colors.textStrong)
        val states = dev.shibasis.reaktor.blueprint.BlueprintAgent.Activity.entries
            .map { agentBadgeState(agent.copy(activity = it, otherAgents = max(agent.otherAgents, 99))) }.filter(String::isNotBlank)
        val lines = listOfNotNull(states.maxOfOrNull { measure(it, style, room, 64).size.height },
            agentBadgeTuple(agent).takeIf(String::isNotBlank)?.let { measure(it, style, room, 64).size.height })
        val text = lines.sum().toFloat() + MachineSignal.AgentBadge.gap.value * (lines.size - 1).coerceAtLeast(0)
        return max(MachineSignal.AgentBadge.size.value, text) + MachineSignal.AgentBadge.gap.value * 2
    }

    private fun measureCard(card: Card, look: BlueprintCardLook, kept: CardPaint?): CardPaint {
        val measured = kept?.measured?.takeIf { it.look == look && it.card.width == card.width && it.card.height == card.height && it.card.pins == card.pins }
            ?.copy(card = card) ?: worldMeasure(card, look)
        return CardPaint(PaintKey(look), measured.rows.smallest.toFloat(), measured)
    }

    private fun worldMeasure(card: Card, look: BlueprintCardLook): CardMeasure {
        val agentHeight = look.agent?.let { agentHeight(it, card.width.toFloat()) } ?: 0f
        val bodyHeight = (card.height.toFloat() - agentHeight).coerceAtLeast(0f)
        val content = rowContent(if (agentHeight == 0f) card else card.copy(height = bodyHeight.toDouble()), look)
        val rows = if (look.agent == null) content else content.copy(height = card.height,
            smallest = min(content.smallest, MachineSignal.Type.body2.value.toDouble()), complete = content.complete && bodyHeight > 0)
        return CardMeasure(look, card, rows, bodyHeight)
    }

    private fun codePinWidth(card: Card, pin: dev.shibasis.reaktor.blueprint.Pin): Float =
        card.width.toFloat() / if (card.pins.any {
            it.provides != pin.provides && kotlin.math.abs(it.y - pin.y) < BlueprintEngine.RowHeight
        }) 2 else 1

    private fun rowContent(card: Card, look: BlueprintCardLook): PaintContent {
        val w = card.width.toFloat(); val h = card.height.toFloat()
        val texts = ArrayList<TextLayoutResult>()
        var smallest = Float.POSITIVE_INFINITY
        fun text(value: String, style: TextStyle, width: Float, lines: Int = 1): TextLayoutResult {
            val result = measure(value, style, width, lines)
            if (value.isNotEmpty()) { texts += result; smallest = min(smallest, style.fontSize.value) }
            return result
        }
        fun mark(value: BlueprintMark, meta: TextUnit = MachineSignal.Type.label) {
            measureMark(value, meta).text?.let { result -> texts += result; smallest = min(smallest, result.layoutInput.style.fontSize.value) }
        }
        var fits = true
        when (look.face) {
            BlueprintFace.Code -> {
                val marks = budgetMarks(look.marks).map(::measureMark)
                budgetMarks(look.marks).forEach { mark(it) }
                if (text(look.title, titleStyle, w - 36f - marks.sumOf { it.width.toDouble() + 6 }.toFloat() - actionWidth(look)).size.height > 26) fits = false
                val folded = look.folded?.let { text(it, foldedStyle, w / 2) }
                if (text(look.subtitle, subtitleStyles[if (look.subtitleMono) 1 else 0], w - 18f - (folded?.let { it.size.width + 8f } ?: 0f)).size.height + 29 > h) fits = false
                if (folded != null && folded.size.height + 30 > h) fits = false
                card.pins.forEachIndexed { k, pin -> look.pins.getOrNull(k)?.let { row ->
                    val width = codePinWidth(card, pin)
                    val markWidth = row.marks.sumOf { measureMark(it).width.toDouble() + BlueprintEngine.RowHeight / 8 }.toFloat()
                    val glyphWidth = if (row.icon == null) 0f else MachineSignal.Editor.smallIconSize.value + MachineSignal.Editor.controlGap.value
                    if (width < pinTextMargin + markWidth + glyphWidth + if (row.chip) 8f else 0f) fits = false
                    if (text(row.text, if (row.chip) chipStyle else type.style(MachineSignal.Type.data, row.textColor, mono = true), width - pinTextMargin - markWidth - glyphWidth - if (row.chip) 8f else 0f).size.height > BlueprintEngine.RowHeight) fits = false
                    row.marks.forEach { mark(it) }
                    if (pin.y + BlueprintEngine.RowHeight / 2 > h) fits = false
                } }
            }
            BlueprintFace.Prose -> {
                val room = w - ProseInset - 12f - actionWidth(look)
                val marks = budgetMarks(look.marks).map { measureMark(it, Blueprint.ProseMeta.sp) }
                val used = marks.sumOf { it.width.toDouble() + 6 }.toFloat()
                if (used > room - 48f) fits = false
                budgetMarks(look.marks).forEach { mark(it, Blueprint.ProseMeta.sp) }
                look.eyebrow?.let { text(it.uppercase(), eyebrowStyle, room - used) }
                val title = proseTitle(card, look, room, h)
                if (title == null || title.size.height + 34f > h - if (look.subtitle.isNotEmpty()) 34f else 10f) fits = false
                title?.let { texts += it; smallest = min(smallest, it.layoutInput.style.fontSize.value) }
                text(look.subtitle, footerStyle, room)
            }
        }
        return PaintContent(card.width, card.height, smallest.toDouble(), fits && texts.none { it.hasVisualOverflow })
    }

    private fun icon(vector: ImageVector) = icons.getOrPut(vector) { BlueprintIcon(vector) }

    private fun measure(value: String, style: TextStyle, width: Float, lines: Int = 1): TextLayoutResult =
        measurer.measure(value, style, TextOverflow.Ellipsis, maxLines = lines, constraints = Constraints(maxWidth = width.coerceIn(0f, 32760f).toInt()))

    private fun words(value: String, style: TextStyle, width: Float, lines: Int = 1): TextLayoutResult? {
        val whole = measure(value, style, width, lines)
        if (!whole.hasVisualOverflow) return whole
        val clipped = measurer.measure(value, style, TextOverflow.Clip, maxLines = lines, constraints = Constraints(maxWidth = width.coerceIn(0f, 32760f).toInt()))
        val end = clipped.getLineEnd(min(lines, clipped.lineCount) - 1)
        if (end <= 0) return null
        val split = end >= value.length || value[end].isWhitespace() || value[end - 1].isWhitespace()
        var shown = (if (split) value.take(end) else value.take(end).substringBeforeLast(' ', "")).trimEnd { it.isWhitespace() || it in Joiners }
        while (shown.isNotEmpty()) {
            measure("$shown…", style, width, lines).takeUnless { it.hasVisualOverflow }?.let { return it }
            shown = shown.substringBeforeLast(' ', "").trimEnd { it.isWhitespace() || it in Joiners }
        }
        return null
    }

    private fun headerPath(x: Float, y: Float, w: Float) = Path().apply {
        addRoundRect(RoundRect(x, y, x + w, y + 26f, topLeftCornerRadius = CornerRadius(MachineSignal.Radius.node.value), topRightCornerRadius = CornerRadius(MachineSignal.Radius.node.value)))
    }

    private fun cardBody(tone: Color) = lerp(colors.card, tone, CardTint)

    private fun headerBrush(tone: Color, x: Float, w: Float) = Brush.horizontalGradient(listOf(lerp(colors.card, tone, CardTint * 2), cardBody(tone)), x, x + w)

    private fun measureMark(mark: BlueprintMark, meta: TextUnit = MachineSignal.Type.label): Measured = when (mark.kind) {
        BlueprintMark.Kind.Dot -> Measured(7f, 7f, null)
        BlueprintMark.Kind.Tag -> measurer.measure(mark.text.uppercase(), type.style(meta, colors.canvas, weight = FontWeight.Bold), maxLines = 1)
            .let { Measured(it.size.width + 8f, it.size.height + 2f, it) }
        BlueprintMark.Kind.Badge -> measurer.measure(mark.text, type.style(meta, mark.color), maxLines = 1)
            .let { Measured(it.size.width + 10f, max(16f, it.size.height.toFloat()), it) }
        BlueprintMark.Kind.Pill -> measurer.measure(mark.text, type.style(meta, colors.textStrong, mono = true), maxLines = 1)
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

    private fun titleWidth(card: Card, look: BlueprintCardLook, marks: List<Measured>) =
        card.width.toFloat() - 36f - marks.sumOf { it.width.toDouble() + 6 }.toFloat() - actionWidth(look)

    private fun cardTitle(card: Card, look: BlueprintCardLook, x: Float, y: Float): Pair<TextLayoutResult, Rect> {
        val title = measure(look.title, titleStyle, titleWidth(card, look, budgetMarks(look.marks).map(::measureMark)))
        val top = y + (26f - title.size.height) / 2
        return title to Rect(x + 28f, top, x + 28f + title.size.width, top + title.size.height)
    }

    private fun DrawScope.drawDetail(card: Card, look: BlueprintCardLook, x: Float, y: Float, bodyHeight: Float) {
        val w = card.width.toFloat(); val h = card.height.toFloat()
        drawRoundRect(if (look.image != null) colors.card else cardBody(look.tone), Offset(x, y), Size(w, h), CornerRadius(MachineSignal.Radius.node.value))
        if (look.image == null) drawPath(headerPath(x, y, w), headerBrush(look.tone, x, w))
        if (look.choice == null) icon(look.icon).draw(this, x + 8f, y + 6f, 14f, look.tone)
        val marks = budgetMarks(look.marks).map(::measureMark)
        val (title, titleBounds) = cardTitle(card, look, x, y)
        paint(title, titleBounds.left, titleBounds.top)
        var at = x + 28f + max(titleWidth(card, look, marks), 0f) + 6f
        budgetMarks(look.marks).forEachIndexed { k, mark -> drawMark(mark, marks[k], at, y + (26f - marks[k].height) / 2); at += marks[k].width + 6f }
        val folded = look.folded?.let { measure(it, foldedStyle, w / 2) }
        paint(measure(look.subtitle, subtitleStyles[if (look.subtitleMono) 1 else 0], w - 18f - (folded?.let { it.size.width + 8f } ?: 0f)), x + 10f, y + 29f)
        drawScreenshot(look.image, x, y, w, bodyHeight, look.muted)
        look.image?.let { image ->
            look.imageHighlight?.intersect(Rect(0f, 0f, 1f, 1f))?.takeUnless { it.isEmpty }?.let { rect ->
                val bounds = screenshotBounds(image, x, y, w, bodyHeight)
                drawRect(accent.copy(alpha = .22f), Offset(bounds.left + rect.left * bounds.width, bounds.top + rect.top * bounds.height),
                    Size(rect.width * bounds.width, rect.height * bounds.height))
            }
        }
        card.pins.forEachIndexed { k, pin ->
            val pinLook = look.pins.getOrNull(k) ?: return@forEachIndexed
            val width = codePinWidth(card, pin)
            drawPin(pin.provides, pinLook, x + if (pin.provides) w - width else 0f, y + pin.y.toFloat(), width)
        }
        folded?.let { paint(it, x + w - 8f - it.size.width, y + 30f) }
        drawProgress(look, x, y, w, bodyHeight)
        drawBorder(look.selected, look.outline, x, y, w, h)
    }

    private fun DrawScope.drawProgress(look: BlueprintCardLook, x: Float, y: Float, w: Float, h: Float) {
        look.progress?.let { progress ->
            val gap = MachineSignal.Space.s2.value
            val height = MachineSignal.Stroke.hairline.value
            drawRoundRect(look.tone, Offset(x + gap, y + h - gap - height), Size((w - gap * 2) * progress.coerceIn(0f, 1f), height), CornerRadius(height))
        }
    }

    private fun DrawScope.drawRows(card: Card, look: BlueprintCardLook, x: Float, y: Float) {
        val oneSided = card.pins.all { it.provides } || card.pins.none { it.provides }
        card.pins.forEachIndexed { k, pin -> look.pins.getOrNull(k)?.let { row ->
            val width = if (look.face == BlueprintFace.Code) codePinWidth(card, pin) else card.width.toFloat() / if (oneSided) 1 else 2
            drawPin(pin.provides, row, x + if (pin.provides) card.width.toFloat() - width else 0f, y + pin.y.toFloat(), width)
        } }
    }

    private fun DrawScope.drawBorder(selected: Boolean, outline: Color?, x: Float, y: Float, w: Float, h: Float) {
        val border = when { selected -> 2f; outline != null -> 1.6f; else -> 1f }
        val edge = when { selected -> accent; outline != null -> outline; else -> colors.line.copy(alpha = .45f) }
        drawRoundRect(edge, Offset(x + border / 2, y + border / 2), Size(w - border, h - border), CornerRadius(MachineSignal.Radius.node.value - border / 2), style = Stroke(border))
    }

    private fun DrawScope.proseBody(tone: Color, x: Float, y: Float, w: Float, h: Float, tint: Float) {
        val outline = Path().apply { addRoundRect(RoundRect(x, y, x + w, y + h, CornerRadius(MachineSignal.Radius.node.value))) }
        drawPath(outline, lerp(colors.card, tone, tint))
        clipPath(outline) { drawRect(tone, Offset(x, y), Size(Stripe, h)) }
    }

    private fun proseTitleBottom(card: Card, look: BlueprintCardLook, height: Float): Float =
        card.pins.minOfOrNull { it.y - BlueprintEngine.RowHeight / 2 - BlueprintEngine.RowHeight / 3 }?.toFloat()
            ?: (height - if (look.subtitle.isNotEmpty()) 34f else 10f)

    private fun proseTitle(card: Card, look: BlueprintCardLook, width: Float, height: Float): TextLayoutResult? =
        Blueprint.ProseTitles.indices.firstNotNullOfOrNull { k ->
            measure(look.title, proseTitles[k], width, ProseLines[k]).takeUnless {
                it.hasVisualOverflow || it.size.height + 34f > proseTitleBottom(card, look, height)
            }
        }

    private fun DrawScope.drawProse(card: Card, look: BlueprintCardLook, x: Float, y: Float, bodyHeight: Float) {
        val w = card.width.toFloat(); val h = card.height.toFloat()
        proseBody(look.tone, x, y, w, h, CardTint)
        drawScreenshot(look.image, x, y, w, bodyHeight, look.muted)
        icon(look.icon).draw(this, x + MachineSignal.Space.s1.value, y + MachineSignal.Space.s2.value, MachineSignal.Editor.smallIconSize.value, look.tone)
        val left = x + ProseInset; val right = x + w - 12f - actionWidth(look)
        val marks = budgetMarks(look.marks).map { measureMark(it, Blueprint.ProseMeta.sp) }
        var at = right
        for (k in marks.indices.reversed()) {
            if (at - marks[k].width < left + 48f) break
            at -= marks[k].width
            drawMark(budgetMarks(look.marks)[k], marks[k], at, y + 10f + (16f - marks[k].height) / 2)
            at -= 6f
        }
        look.eyebrow?.let { eyebrow -> words(eyebrow.uppercase(), eyebrowStyle, at - left)?.let { paint(it, left, y + 11f) } }
        val fitted = proseTitle(card, look, right - left, bodyHeight)
        val title = fitted ?: words(look.title, proseTitles.last(), right - left, ProseLines.last())
            ?.takeIf { it.size.height + 34f <= proseTitleBottom(card, look, bodyHeight) }
        title?.let { paint(it, left, y + 34f) }
        if (look.subtitle.isNotEmpty()) words(look.subtitle, footerStyle, right - left)?.let { paint(it, left, y + bodyHeight - 28f) }
        drawRows(card, look, x, y)
        drawProgress(look, x, y, w, bodyHeight)
        drawBorder(look.selected, look.outline, x, y, w, h)
    }

    private fun DrawScope.drawScreenshot(image: androidx.compose.ui.graphics.ImageBitmap?, x: Float, y: Float, w: Float, h: Float, muted: Boolean) {
        image ?: return
        if (h <= BlueprintEngine.HeaderHeight) return
        val bounds = screenshotBounds(image, x, y, w, h)
        val path = Path().apply { addRoundRect(RoundRect(x, y, x + w, y + h, CornerRadius(MachineSignal.Radius.node.value))) }
        clipPath(path) {
            drawImage(image, dstOffset = IntOffset(bounds.left.toInt(), bounds.top.toInt()),
                dstSize = IntSize(bounds.width.toInt().coerceAtLeast(1), bounds.height.toInt().coerceAtLeast(1)),
                alpha = if (muted) .25f else 1f, filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium)
        }
    }

    private fun screenshotBounds(image: androidx.compose.ui.graphics.ImageBitmap, x: Float, y: Float, w: Float, h: Float): Rect {
        val top = BlueprintEngine.HeaderHeight.toFloat()
        val scale = min(w / image.width, (h - top).coerceAtLeast(0f) / image.height)
        val width = image.width * scale
        val height = image.height * scale
        return Rect(x + (w - width) / 2, y + top, x + (w + width) / 2, y + top + height)
    }

    private fun DrawScope.drawPin(provides: Boolean, pin: BlueprintPinLook, x: Float, middle: Float, width: Float) {
        if (pin.selected) {
            drawRoundRect(accent.copy(alpha = .16f), Offset(x + 3f, middle - BlueprintEngine.RowHeight.toFloat() / 2 + 1f), Size(width - 6f, BlueprintEngine.RowHeight.toFloat() - 2f), CornerRadius(4f))
            drawCircle(accent.copy(alpha = .35f), 7f, Offset(if (provides) x + width - 10f else x + 10f, middle))
        }
        val gap = BlueprintEngine.RowHeight.toFloat() / 8
        val marks = pin.marks.map { it to measureMark(it) }
        val markWidth = marks.sumOf { it.second.width.toDouble() }.toFloat() + gap * marks.size
        val glyphSize = MachineSignal.Editor.smallIconSize.value
        val glyphWidth = if (pin.icon == null) 0f else glyphSize + MachineSignal.Editor.controlGap.value
        val text = measure(pin.text, if (pin.chip) chipStyle else type.style(MachineSignal.Type.data, pin.textColor, mono = true), (width - pinTextMargin - markWidth - glyphWidth - if (pin.chip) 8f else 0f).coerceAtLeast(BlueprintEngine.RowHeight.toFloat()))
        val box = text.size.width + if (pin.chip) 8f else 0f
        val dot = Offset(if (provides) x + width - 10f else x + 10f, middle)
        val left = if (provides) x + width - 20f - box - markWidth else x + 20f + glyphWidth
        pin.icon?.let { icon(it).draw(this, left - glyphWidth, middle - glyphSize / 2, glyphSize, pin.color) }
        if (pin.choice == null) {
            if (pin.filled) drawCircle(pin.color, 4f, dot)
            drawCircle(pin.color, 3.3f, dot, style = Stroke(1.4f))
        }
        val top = middle - text.size.height / 2f
        if (pin.chip) drawRoundRect(pin.color.copy(alpha = .2f), Offset(left, top), Size(box, text.size.height.toFloat()), CornerRadius(3f))
        paint(text, left + if (pin.chip) 4f else 0f, top)
        var markLeft = left + box + gap
        marks.forEach { (mark, measured) ->
            drawMark(mark, measured, markLeft, middle - measured.height / 2)
            markLeft += measured.width + gap
        }
    }

    private fun DrawScope.drawHeader(frame: Frame, look: BlueprintFrameLook, x: Float, y: Float) {
        val top = BlueprintEngine.FrameTop.toFloat()
        val w = frame.width.toFloat()
        val right = x + w - 12f
        var at = x + 12f
        icon(look.icon).draw(this, at, y + (top - 14f) / 2, 14f, tint)
        at += 14f + 8f
        listOf(frame.label, look.count, frame.detail.joinToString(" · ")).forEachIndexed { k, value ->
            val text = measure(value, headerStyles[k], right - at)
            paint(text, at, y + (top - text.size.height) / 2)
            at += text.size.width + 8f
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
        const val Stripe = 5f
        const val ProseInset = 17f
        const val ProseLeading = 1.25
        const val CardTint = .05f
        val ProseLines = intArrayOf(3, 4, 5)
        const val Joiners = ",;:·–-"
    }
}
