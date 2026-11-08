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
import androidx.compose.ui.graphics.drawscope.clipRect
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
import dev.shibasis.reaktor.blueprint.BlueprintBadgeStyle
import dev.shibasis.reaktor.blueprint.BlueprintCardLook
import dev.shibasis.reaktor.blueprint.BlueprintEngine
import dev.shibasis.reaktor.blueprint.BlueprintFace
import dev.shibasis.reaktor.blueprint.BlueprintFrameLook
import dev.shibasis.reaktor.blueprint.BlueprintLayout
import dev.shibasis.reaktor.blueprint.BlueprintOpening
import dev.shibasis.reaktor.blueprint.BlueprintMark
import dev.shibasis.reaktor.blueprint.BlueprintPinLook
import dev.shibasis.reaktor.blueprint.BlueprintReading
import dev.shibasis.reaktor.blueprint.BlueprintRowLook
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal class BlueprintType(private val ui: FontFamily, private val mono: FontFamily, private val base: TextStyle, private val labelFont: FontFamily = FontFamily.Monospace) {
    fun style(size: TextUnit, color: Color, mono: Boolean = false, weight: FontWeight = FontWeight.Normal) =
        base.merge(TextStyle(color = color, fontSize = size, lineHeight = (size.value * 1.25f).sp, fontWeight = weight, fontFamily = if (mono) this.mono else ui))

    fun label(color: Color) = base.merge(TextStyle(color = color, fontSize = MachineSignal.Type.label, fontFamily = labelFont))
}

internal fun blueprintEndpointLabelBounds(points: List<Offset>, end: Boolean, size: Size, gap: Float, clear: (Rect) -> Boolean): Rect? {
    if (points.size < 2) return null
    var total = 0f
    for (i in 1 until points.size) total += (points[i] - points[i - 1]).getDistance()
    var walked = 0f
    for (i in 1 until points.size) {
        val from = points[if (end) points.lastIndex - i + 1 else i - 1]
        val to = points[if (end) points.lastIndex - i else i]
        val delta = to - from
        val length = delta.getDistance()
        if (length <= 0f) continue
        if (walked > total / 2) break
        val direction = delta / length
        val along = gap + (kotlin.math.abs(direction.x) * size.width + kotlin.math.abs(direction.y) * size.height) / 2
        val across = gap + (kotlin.math.abs(direction.y) * size.width + kotlin.math.abs(direction.x) * size.height) / 2
        for (distance in listOf(along.coerceAtMost(length / 2), length / 2)) {
            if (walked + distance > total / 2) continue
            val centre = from + direction * distance
            for (side in listOf(-1f, 1f)) {
                val at = centre + Offset(-direction.y, direction.x) * (across * side)
                val box = Rect(at.x - size.width / 2, at.y - size.height / 2, at.x + size.width / 2, at.y + size.height / 2)
                if (clear(box)) return box
            }
        }
        walked += length
    }
    return null
}

private data class PaintKey(val look: BlueprintCardLook)

private val PaintNoClick: () -> Unit = {}
private val PaintNoChange: (Boolean) -> Unit = {}
private val PaintTitle: @androidx.compose.runtime.Composable (TextStyle) -> Unit = {}
private val PaintMark: @androidx.compose.runtime.Composable () -> Unit = {}

internal data class BlueprintTitleSlot(val bounds: Rect, val style: TextStyle, val centered: Boolean)
internal data class BlueprintMarkSlot(val mark: BlueprintMark, val bounds: Rect, val style: TextStyle?)
internal data class BlueprintHeaderSlots(val title: BlueprintTitleSlot?, val marks: List<BlueprintMarkSlot>)

private data class PlacedMark(val mark: BlueprintMark, val measured: Measured, val bounds: Rect) {
    fun slot() = BlueprintMarkSlot(mark, bounds, measured.text?.layoutInput?.style)
}
private data class FrameHeader(val title: BlueprintTitleSlot, val labels: List<Pair<TextLayoutResult, Float>>, val marks: List<PlacedMark>)

// Composition can replace handlers without changing anything in the recorded picture.
private fun BlueprintCardLook.paintKey(): PaintKey {
    fun BlueprintPinLook.withoutHandler() = choice?.let { copy(choice = it.copy(change = PaintNoChange)) } ?: this
    return PaintKey(copy(
        choice = choice?.copy(change = PaintNoChange),
        actions = actions.map { it.copy(onInvoke = PaintNoClick) },
        pins = pins.map { it?.withoutHandler() },
        rows = rows.map { row -> row.copy(content = row.content.withoutHandler(),
            onClick = row.onClick?.let { PaintNoClick }, trailingCells = row.trailingCells.map { it.withoutHandler() }) },
        titleContent = titleContent?.let { PaintTitle },
        markContent = if (markContent.isEmpty()) markContent else markContent.mapValues { PaintMark },
    ))
}

private fun BlueprintFrameLook.paintKey() = if (!hasHeaderContent) this else copy(
    titleContent = titleContent?.let { PaintTitle },
    markContent = if (markContent.isEmpty()) markContent else markContent.mapValues { PaintMark },
)
private data class CardMeasure(val look: BlueprintCardLook, val card: Card, val rows: PaintContent, val bodyHeight: Float, val columns: List<Float>)
/** Below this scale, cards remain in the mesh and hit index without measuring unreadable text. */
internal const val BlueprintDetailZoom = .18
internal const val BlueprintMapTitleZoom = .45
internal const val MapTextCache = 2048
internal const val MapMarkerBatch = 6
private val MeasureBudget = 2.milliseconds
private val TitleBurst = 10.milliseconds
internal const val BlueprintLegibleText = 11.0
internal const val BlueprintOverviewCards = 512
private const val CardLabelReadable = 7.0
private const val CorridorZoom = .22
private const val CardLabelWidth = 220
private const val CardLabelLimit = 160

private data class CardPaint(val key: PaintKey, val smallest: Float, val measured: CardMeasure)
internal data class PaintStats(val retainedPixelBytes: Long, val releasedViews: Long)

private class Read(val text: Float, val title: BlueprintTitle)
private class Measured(val width: Float, val height: Float, val text: TextLayoutResult?)

internal class BlueprintPainter(graphics: GraphicsContext, private val measurer: TextMeasurer, private val type: BlueprintType, private val colors: dev.shibasis.reaktor.ui.machinesignal.MachineSignalColors, private val denseOverview: Boolean = false, private val mapLayers: Boolean = false, private val mapDetail: Boolean = false) : SurfaceGraphEngine(graphics, scaled = true) {
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
    override val headerZoom = BlueprintLegibleText / headerStyles[0].fontSize.value
    private val areaStyle = type.style(15.sp, colors.textStrong, weight = FontWeight.SemiBold)
    private val areaTallyStyle = type.style(11.sp, colors.textMuted)
    private val areaLabels = HashMap<Triple<String, String, Int>, Pair<TextLayoutResult, TextLayoutResult?>>()
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
    private var framePaintKeys: List<BlueprintFrameLook> = emptyList()
    private var styles: List<LinkStyle> = emptyList()
    private var endpointLinks = IntArray(0)
    private val endpointObstacles = ArrayList<Rect>()
    private val endpointPlaced = ArrayList<Rect>()
    private val endpointSeen = HashSet<Pair<Offset, String>>()
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
    override val warmable get() = level > 0 && cards.isNotEmpty()
    override val canvas = colors.canvas
    override val grid = CanvasGrid(minor = colors.textMuted.copy(alpha = .025f), major = colors.textMuted.copy(alpha = .055f))
    override val cardRadius = MachineSignal.Radius.node.value
    override val worldPictures = !mapDetail
    override val farZoom get() = if (mapDetail) BlueprintMapTitleZoom else super.farZoom
    override val corridorZoom = if (mapLayers) CorridorZoom else 0.0
    override val corridorRgb = colors.textMuted.toArgb()
    override val corridorsQuiet get() = anyDim || selected != null

    fun sync(layout: BlueprintLayout<*>, looks: List<BlueprintCardLook?>, glows: List<Color?>, frameLooks: List<BlueprintFrameLook>, styles: List<LinkStyle>,
             selected: String?, highlight: Set<String>, labels: Map<String, String>, grabbed: Set<String> = emptySet(), aside: Set<String> = emptySet(),
             focusLabels: Boolean = false) {
        this.focusLabels = focusLabels
        val fresh = layout !== this.layout
        if (fresh) scene(layout)
        if (grabbed !== this.grabbed) { this.grabbed = grabbed; if (grabbed.isNotEmpty()) ready(grabbed) }
        if (styles !== this.styles) {
            this.styles = styles
            endpointLinks = styles.indices.filter { styles[it].startLabel != null || styles[it].endLabel != null }
                .sortedWith(compareByDescending<Int> { styles[it].lifted }.thenByDescending { styles[it].alpha }).toIntArray()
            restyle {
                val changed = meshStyles[it] != styles[it]
                if (changed) dirty[HOVER] = true
                changed
            }
        }
        if (frameLooks !== this.frameLooks) {
            if (frameLooks.size != this.frameLooks.size || frameLooks.indices.any { frameLooks[it].lit != this.frameLooks[it].lit || frameLooks[it].muted != this.frameLooks[it].muted || frameLooks[it].selected != this.frameLooks[it].selected }) dirty[UNDER] = true
            framePaintKeys = frameLooks.map { it.paintKey() }
        }
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
        val scale = z / density
        level = when {
            mapDetail -> if (z < BlueprintDetailZoom) 0 else if (z < BlueprintMapTitleZoom) 1 else 2
            denseOverview && cards.size > BlueprintOverviewCards && scale < BlueprintDetailZoom -> 0
            else -> 2
        }
        val l = ((-camera.x - viewport.width) / z).toFloat()
        val t = ((-camera.y - viewport.height) / z).toFloat()
        val r = ((viewport.width * 2 - camera.x) / z).toFloat()
        val b = ((viewport.height * 2 - camera.y) / z).toFloat()
        if (level == 2 || level == 1 && !mapDetail) {
            val until = TimeSource.Monotonic.markNow() + MeasureBudget
            val vl = (-camera.x / z).toFloat(); val vt = (-camera.y / z).toFloat()
            val vr = ((viewport.width - camera.x) / z).toFloat(); val vb = ((viewport.height - camera.y) / z).toFloat()
            for (i in cards.indices) if (inside(cardBox, i, vl, vt, vr, vb)) paint(i, until)
            for (i in cards.indices) if (inside(cardBox, i, l, t, r, b)) paint(i, until)
        }
        if (mapDetail && level == 1 && !moving && camera == this.camera) {
            val until = TimeSource.Monotonic.markNow() + TitleBurst
            val vl = (-camera.x / z).toFloat(); val vt = (-camera.y / z).toFloat()
            val vr = ((viewport.width - camera.x) / z).toFloat(); val vb = ((viewport.height - camera.y) / z).toFloat()
            var recorded = false
            for (i in cards.indices) {
                if (until.hasPassedNow()) break
                if (shown(i) && inside(cardBox, i, vl, vt, vr, vb) && !current(i, 0)) { record(i, 0, null); recorded = true }
            }
            if (recorded) { dirty[CARDS] = true; changed = true }
        }
        this.shift = shift; this.pulses = pulses; this.labelExclusions = labelExclusions
        with(scope) { frame(camera, viewport, density) }
        for (i in paints.indices) markerKeys[i].value = paints[i]?.key?.takeIf { current(i, 0) || current(i, 1) }
    }

    override fun DrawScope.overlay(camera: Viewport) {
        endpointPlaced.clear(); endpointSeen.clear()
        if (endpointLinks.isEmpty() || camera.zoom < BlueprintDetailZoom) return
        val z = camera.zoom.toFloat()
        val scale = z.coerceAtLeast(1f)
        val cx = camera.x.toFloat(); val cy = camera.y.toFloat()
        val left = -cx / z; val top = -cy / z
        val right = (viewport.width - cx) / z; val bottom = (viewport.height - cy) / z
        endpointObstacles.clear()
        for (i in cards.indices) if (shown(i)) {
            val card = cards[i]
            endpointObstacles += Rect(cx + card.x.toFloat() * z, cy + card.y.toFloat() * z,
                cx + (card.x + card.width).toFloat() * z, cy + (card.y + card.height).toFloat() * z)
        }
        frames.forEach { frame -> endpointObstacles += Rect(cx + frame.x.toFloat() * z, cy + frame.y.toFloat() * z,
            cx + (frame.x + frame.width).toFloat() * z, cy + (frame.y + BlueprintEngine.FrameTop).toFloat() * z) }
        endpointObstacles += labelExclusions
        if (labels.isNotEmpty()) endpointObstacles += linkLabelBounds
        val padding = MachineSignal.Space.s0_5.value
        val gap = MachineSignal.Space.s1.value * scale
        for (i in endpointLinks) {
            val style = styles.getOrNull(i) ?: continue
            if (style.alpha < .25f || !inside(linkBox, i, left, top, right, bottom)) continue
            val route = links.getOrNull(i)?.trace?.map { (x, y) -> Offset(cx + x.toFloat() * z, cy + y.toFloat() * z) } ?: continue
            for (end in listOf(false, true)) {
                val label = (if (end) style.endLabel else style.startLabel)?.takeIf { it.isNotBlank() } ?: continue
                val identity = (if (end) route.lastOrNull() else route.firstOrNull())?.let { it to label } ?: continue
                if (identity in endpointSeen) continue
                val ink = style.color.copy(alpha = (style.color.alpha * style.alpha).coerceIn(0f, 1f))
                val text = measurer.measure(label, type.label(ink), maxLines = 1)
                val size = Size(text.size.width + padding * 2, text.size.height + padding * 2)
                val box = blueprintEndpointLabelBounds(route, end, size * scale, gap) { candidate ->
                    candidate.left >= 0f && candidate.top >= 0f && candidate.right <= viewport.width && candidate.bottom <= viewport.height &&
                        endpointObstacles.none { it.overlaps(candidate) } && endpointPlaced.none { it.overlaps(candidate) }
                } ?: continue
                endpointPlaced += box; endpointSeen += identity
                withTransform({ translate(box.left, box.top); scale(scale, scale, Offset.Zero) }) {
                    drawRoundRect(colors.canvas.copy(alpha = style.alpha.coerceIn(0f, 1f)), Offset.Zero, size,
                        CornerRadius(min(MachineSignal.Radius.panel.value, size.height / 2)))
                    paint(text, padding, padding)
                }
            }
        }
    }

    private fun paint(i: Int, until: TimeSource.Monotonic.ValueTimeMark? = null): CardPaint? {
        val look = looks.getOrNull(i)?.takeIf { shown(i) } ?: return null
        val kept = paints[i]
        if (kept != null && kept.measured.card === cards[i] && kept.measured.look == look) return kept
        if (until?.hasPassedNow() == true) { changed = true; return kept }
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
        var legible = 0
        val legibleText = BlueprintLegibleText * density / z
        val titles = HashMap<BlueprintTitle, Int>()
        val tiers = HashMap<BlueprintTier, Int>()
        for (i in cards.indices) {
            if (!shown(i) || cardBox[i * 4 + 2] < l || cardBox[i * 4] > r || cardBox[i * 4 + 3] < t || cardBox[i * 4 + 1] > b) continue
            // Cached detail can survive a zoom-out; it is not painted in the overview mesh.
            val read = if (level == 0) null else (0..1).firstOrNull { current(i, it) }?.let { reads[i * 2 + it] }
            if (read != null) smallest = min(smallest, read.text)
            if (read != null && read.text >= legibleText) legible++
            val title = read?.title ?: BlueprintTitle.Hidden
            titles[title] = (titles[title] ?: 0) + 1
            val tier = if (read == null) BlueprintTier.Overview else BlueprintTier.Detail
            tiers[tier] = (tiers[tier] ?: 0) + 1
        }
        return BlueprintReading(level, (smallest * z).toDouble(), titles, tiers, legible, groupsInView, namedInView, unnamedInView.toList())
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

    fun rowPinAt(x: Float, y: Float): Pair<Card, Pin>? = cardIndex(x, y).takeIf { it >= 0 && level == 2 }?.let { i -> pin(i, x, y)?.let { cards[i] to it } }

    fun rowAt(x: Float, y: Float): BlueprintRowLook? = cardIndex(x, y).takeIf { it >= 0 && committed(it) != null }?.let { i ->
        val card = cards[i]
        looks.getOrNull(i)?.rows?.getOrNull(floor((y - card.y - card.pinHeader) / BlueprintEngine.RowHeight).toInt())
    }

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
        return BlueprintLegibleText / rows.smallest * 1.120001
    }

    override fun pinKey(i: Int, x: Float, y: Float) = pin(i, x, y)?.key

    private fun pin(i: Int, x: Float, y: Float): Pin? {
        val card = cards[i]; val look = looks.getOrNull(i) ?: return null
        if (paints.getOrNull(i) == null) return null
        val oneSided = card.pins.all { it.provides } || card.pins.none { it.provides }
        val half = BlueprintEngine.RowHeight / 2
        return card.pins.indices.firstOrNull { k -> val pin = card.pins[k]
            look.pins.getOrNull(k) != null && y - card.y - pin.y in -half..<half &&
                if (look.rows.any { it.id == pin.key && (it.onClick != null || card.pins.count { other -> other.key == pin.key } < 2) }) {
                    if (pin.provides) x - card.x >= card.width - BlueprintEngine.RowHeight else x - card.x < BlueprintEngine.RowHeight
                } else (oneSided || (x - card.x >= card.width / 2) == pin.provides)
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
        val faded = dim.getOrElse(i) { false }
        stroker.roundRect(x0, y0, x1, y1, cardRadius * z, tolerance)
        val fill = look.fill ?: lerp(colors.card, look.tone, OverviewTint)
        stroker.fill(solid, fringe, lerp(colors.card, fill, if (faded) FadedTint else 1f).toArgb(), min(x1 - x0, y1 - y0))
        stroker.outline(solid, max(z / 2, .5f), fringe, (look.outline ?: look.tone).toArgb(), if (look.selected) 1f else if (faded) .2f else .8f)
    }

    override fun cardId(i: Int) = cards[i].id

    override fun endTitle(i: Int, to: Boolean) = cardOf(if (to) links[i].to else links[i].from).takeIf { it >= 0 }?.let { c ->
        (if (mapLayers) nestedFrame(c) else -1).takeIf { it >= 0 }?.let { frames[it].label } ?: looks.getOrNull(c)?.title
    }

    override fun headerKey(i: Int): Any? = framePaintKeys.getOrNull(i)

    private fun cardMarks(look: BlueprintCardLook, room: Float, meta: TextUnit = MachineSignal.Type.label): List<Pair<BlueprintMark, Measured>> {
        val ordered = look.marks.distinct().sortedBy { it.kind != BlueprintMark.Kind.Badge }
        if (look.markContent.isEmpty()) return ordered.take(2).map { it to measureMark(it, meta) }
        val controls = ordered.filter { it in look.markContent }
        val marks = controls + ordered.filterNot { it in look.markContent }.take(1)
        var remaining = room
        return marks.mapNotNull { mark ->
            val measured = measureMark(mark, meta)
            if (measured.width > remaining) null else {
                remaining -= measured.width + 6f
                mark to measured
            }
        }
    }

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

    override fun banner(i: Int, width: Float, zoom: Double, compact: Boolean): GraphicsLayer? {
        val frame = frames.getOrNull(i)?.takeIf { it.label.isNotBlank() } ?: return null
        val look = frameLooks.getOrNull(i) ?: return null
        val count = if (compact) "" else look.count
        val whole = areaLabels.getOrPut(Triple(frame.label, count, Int.MAX_VALUE)) { areaText(frame.label, count, Int.MAX_VALUE) }
        val natural = max(whole.first.size.width, whole.second?.size?.width ?: 0) + 22f
        val shown = if (natural <= width) natural else max(48f, floor(width / 8f) * 8f)
        val (label, tally) = if (natural <= width) whole else areaLabels.getOrPut(Triple(frame.label, count, shown.toInt())) {
            areaText(frame.label, count, (shown - 22f).toInt().coerceAtLeast(1))
        }
        val height = if (tally == null) 30f else 50f
        return bannerLayer(i, listOf(framePaintKeys[i], frame.label, shown, compact)) { layer ->
            layer.record(unit, LayoutDirection.Ltr, IntSize(ceil(shown).toInt(), ceil(height).toInt())) {
                drawRoundRect(colors.surface.copy(alpha = .92f), Offset.Zero, Size(shown, height), CornerRadius(MachineSignal.Radius.panel.value))
                drawRect(look.lit ?: accent, Offset.Zero, Size(3f, height))
                clipRect(0f, 0f, floor(shown - 6f), height) {
                    paint(label, 11f, 6f)
                    tally?.let { paint(it, 11f, 29f) }
                }
            }
        }
    }

    override fun bannerLift(height: Float, z: Float, clear: Boolean) = if (clear) -height - 2f else super.bannerLift(height, z, clear)

    private val cardLabelStyle = type.style(11.5.sp, colors.textStrong, weight = FontWeight.SemiBold)
    private val titledStyle = type.style(44.sp, colors.textStrong, weight = FontWeight.SemiBold).copy(lineHeight = 50.6.sp)
    private val cardLabelTexts = HashMap<String, TextLayoutResult>()
    private val cardLabelsPlaced = ArrayList<Rect>()
    private var cardLabelOrder = IntArray(0)
    private var cardLabelOrderOf: Array<Card>? = null
    private val cardLabelFramesNamed = HashSet<Int>()
    private var nestedOf: Array<Card>? = null
    private var nested = IntArray(0)

    private fun nestedFrame(i: Int): Int {
        if (nestedOf !== cards) {
            val owner = IntArray(cards.size) { -1 }
            val index = HashMap<String, Int>(cards.size * 2).also { map -> cards.forEachIndexed { k, card -> map[card.id] = k } }
            frames.indices.filter { frames[it].parent != null && frames[it].label.isNotBlank() }
                .sortedByDescending { frames[it].width * frames[it].height }
                .forEach { f -> frames[f].nodes.forEach { id -> index[id]?.let { owner[it] = f } } }
            nested = owner
            nestedOf = cards
        }
        return nested.getOrElse(i) { -1 }
    }

    override fun DrawScope.cardLabels(camera: Viewport) {
        cardLabelsPlaced.clear()
        cardLabelFramesNamed.clear()
        if (!mapLayers || cards.isEmpty() || titleStyle.fontSize.value * camera.zoom >= CardLabelReadable) return
        val titling = mapDetail && level == 1
        if (cardLabelOrderOf !== cards) {
            fun extent(i: Int) = nestedFrame(i).takeIf { it >= 0 }?.let { frames[it].width * frames[it].height } ?: (cards[i].width * cards[i].height)
            cardLabelOrder = cards.indices.sortedWith(compareByDescending<Int> { extent(it) }.thenBy { it }).toIntArray()
            cardLabelOrderOf = cards
        }
        val left = insets[0]; val top = insets[1]; val right = viewport.width - insets[2]; val bottom = viewport.height - insets[3]
        fun place(i: Int) {
            if (cardLabelsPlaced.size >= CardLabelLimit || !shown(i)) return
            val look = looks.getOrNull(i) ?: return
            val nest = nestedFrame(i)
            if (titling && (nest < 0 && current(i, 0) || nest >= 0 && frames[nest].nodes.firstOrNull()?.let(cardSlots::get)?.let { current(it, 0) } == true)) return
            val frame = nest.takeIf { it >= 0 }?.let { f -> if (!cardLabelFramesNamed.add(f)) return; frames[f] }
            val title = frame?.label ?: look.title
            if (title.isBlank()) return
            val x0 = (camera.x + (frame?.x ?: cardBox[i * 4].toDouble()) * camera.zoom).toFloat(); val y0 = (camera.y + (frame?.y ?: cardBox[i * 4 + 1].toDouble()) * camera.zoom).toFloat()
            val x1 = (camera.x + (frame?.let { it.x + it.width } ?: cardBox[i * 4 + 2].toDouble()) * camera.zoom).toFloat()
            val y1 = (camera.y + (frame?.let { it.y + it.height } ?: cardBox[i * 4 + 3].toDouble()) * camera.zoom).toFloat()
            if (x1 < left || y1 < top || x0 > right || y0 > bottom) return
            val text = cardLabelTexts.getOrPut(title) {
                measurer.measure(title, cardLabelStyle, TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = CardLabelWidth))
            }
            val width = text.size.width + 12f; val height = text.size.height + 6f
            val x = max(x0, left); val y = max(y0, top)
            if (x + width > right || y + height > bottom || y > y1) return
            val box = Rect(x, y, x + width, y + height)
            if (cardLabelsPlaced.any { it.overlaps(box) } || covered(box.left, box.top, box.right, box.bottom)) return
            cardLabelsPlaced += box
            drawRoundRect(colors.surface.copy(alpha = .92f), Offset(x, y), Size(width, height), CornerRadius(4f))
            drawRect(if (look.selected || cards[i].id == selected) accent else look.tone, Offset(x, y), Size(2.5f, height))
            paint(text, x + 7f, y + 3f)
        }
        val chosen = selected?.let { id -> cards.indexOfFirst { it.id == id } } ?: -1
        if (chosen >= 0) place(chosen)
        for (i in cardLabelOrder) if (i != chosen && cards[i].id in highlight) place(i)
        for (i in cardLabelOrder) if (i != chosen && cards[i].id !in highlight) place(i)
    }

    private fun areaText(label: String, count: String, room: Int) = Constraints(maxWidth = room).let { fit ->
        measurer.measure(label, areaStyle, TextOverflow.Ellipsis, maxLines = 1, constraints = fit) to
            count.takeIf { it.isNotBlank() }?.let { measurer.measure(it, areaTallyStyle, TextOverflow.Ellipsis, maxLines = 1, constraints = fit) }
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
        drawRoundRect(cardBody(look), Offset(x, y), Size(w, h), CornerRadius(MachineSignal.Radius.node.value))
        drawBorder(look.selected, look.outline, x, y, w, h)
    }

    override fun current(i: Int, slot: Int): Boolean {
        val at = i * 2 + slot
        if (layers[at] == null) return false
        if (!mapDetail || slot != 0) return keys[at] == paints.getOrNull(i)?.key
        val look = looks.getOrNull(i) ?: return false
        val kept = keys[at] as? BlueprintCardLook ?: return false
        return kept === look || kept.title == look.title && kept.tone == look.tone && kept.fill == look.fill && kept.selected == look.selected && kept.outline == look.outline
    }

    override fun record(i: Int, slot: Int, deadline: TimeSource.Monotonic.ValueTimeMark?) {
        if (mapDetail && slot == 0) {
            val look = looks.getOrNull(i)?.takeIf { shown(i) } ?: return
            val card = cards[i]
            val name = nestedFrame(i).takeIf { it >= 0 && frames[it].nodes.firstOrNull() == card.id }?.let { frames[it].label } ?: look.title
            tile(i, 0, card.x, card.y, card.width, card.height, MARGIN) { x, y -> drawTitled(card, look, name, x, y) }
            keys[i * 2] = look
            reads[i * 2] = Read(titledStyle.fontSize.value, BlueprintTitle.Whole)
            return
        }
        val paint = paint(i) ?: return
        val look = paint.measured.look
        val card = cards[i]
        tile(i, slot, card.x, card.y, card.width, card.height, MARGIN) { x, y ->
            if (look.face == BlueprintFace.Prose) drawProse(card, look, x, y, paint.measured.bodyHeight, paint.measured.columns) else drawDetail(card, look, x, y, paint.measured.bodyHeight, paint.measured.columns)
        }
        keys[i * 2 + slot] = paint.key
        reads[i * 2 + slot] = Read(paint.smallest, BlueprintTitle.Whole)
    }

    private fun actionWidth(look: BlueprintCardLook, scale: Float = 1f): Float =
        if (look.actions.isEmpty()) 0f else (MachineSignal.Editor.controlHeight.value * look.actions.size + MachineSignal.Editor.controlGap.value) * scale

    private fun cellWidth(cell: BlueprintPinLook): Float {
        val glyph = if (cell.icon == null) 0f else MachineSignal.Editor.smallIconSize.value + MachineSignal.Editor.controlGap.value
        val marks = cell.marks.sumOf { measureMark(it).width.toDouble() + BlueprintEngine.RowHeight / 8 }.toFloat()
        val text = measurer.measure(cell.text, if (cell.chip) chipStyle else type.style(MachineSignal.Type.data, cell.textColor, mono = true), maxLines = 1).size.width
        return text + glyph + marks + if (cell.chip) 8f else 0f
    }

    private fun rowColumns(look: BlueprintCardLook): List<Float> {
        val count = look.rows.maxOfOrNull { it.trailingCells.size + 1 } ?: 0
        if (count < 2) return emptyList()
        val widths = MutableList(count) { 0f }
        look.rows.forEach { row ->
            (listOf(row.content) + row.trailingCells).forEachIndexed { index, cell -> widths[index] = max(widths[index], cellWidth(cell)) }
        }
        return widths
    }

    fun body(node: dev.shibasis.reaktor.blueprint.BlueprintNode, look: BlueprintCardLook): dev.shibasis.reaktor.blueprint.BlueprintBody {
        fun width(value: String, style: TextStyle) = measurer.measure(value, style, maxLines = 1).size.width.toDouble()
        val marks = look.marks.sumOf { measureMark(it).width.toDouble() + MachineSignal.Editor.controlGap.value }
        val folded = look.folded?.let { width(it, foldedStyle) + MachineSignal.Space.s2.value } ?: 0.0
        var w = maxOf(BlueprintEngine.CardWidth, width(look.title, titleStyle) + BlueprintEngine.RowHeight * 2 + marks + actionWidth(look),
            width(look.subtitle, subtitleStyles[if (look.subtitleMono) 1 else 0]) + BlueprintEngine.RowHeight + folded)
        val oneSided = node.inputs.isEmpty() || node.outputs.isEmpty()
        fun rowWidth(row: BlueprintPinLook): Double = cellWidth(row).toDouble() + pinTextMargin
        val specs = node.inputs + node.outputs
        val shared = look.rows.mapTo(hashSetOf()) { it.id }
        val widestPin = look.pins.mapIndexedNotNull { index, row -> row?.takeIf { specs.getOrNull(index)?.key !in shared } }.maxOfOrNull(::rowWidth) ?: 0.0
        val columns = rowColumns(look)
        val sharedWidth = if (columns.isEmpty()) look.rows.maxOfOrNull { rowWidth(it.content) } ?: 0.0
            else columns.sum().toDouble() + MachineSignal.Space.s3.value * (columns.size - 1) + BlueprintEngine.RowHeight * 2
        val widestRow = maxOf(widestPin * if (oneSided) 1 else 2, sharedWidth)
        w = maxOf(w, widestRow)
        var h = BlueprintEngine.cardHeight(node.copy(body = null, rows = if (look.face == BlueprintFace.Prose) null else node.rows))
        if (look.rows.isNotEmpty()) h = maxOf(h, node.pinHeader + look.rows.size * BlueprintEngine.RowHeight + node.pinFooter)
        if (look.face == BlueprintFace.Prose) {
            val style = proseTitles.first()
            w = maxOf(BlueprintEngine.CardWidth, widestRow, look.title.split(Regex("\\s+")).maxOfOrNull { width(it, style) }?.plus(ProseInset + MachineSignal.Space.s3.value + actionWidth(look)) ?: 0.0,
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
        val key = look.paintKey()
        val measured = kept?.takeIf { it.key == key }?.measured
            ?.takeIf { it.card.width == card.width && it.card.height == card.height && it.card.pins == card.pins }
            ?.copy(look = look, card = card) ?: worldMeasure(card, look)
        return CardPaint(key, measured.rows.smallest.toFloat(), measured)
    }

    private fun worldMeasure(card: Card, look: BlueprintCardLook): CardMeasure {
        val agentHeight = look.agent?.let { agentHeight(it, card.width.toFloat()) } ?: 0f
        val bodyHeight = (card.height.toFloat() - agentHeight).coerceAtLeast(0f)
        val columns = rowColumns(look)
        val content = rowContent(if (agentHeight == 0f) card else card.copy(height = bodyHeight.toDouble()), look, columns)
        val rows = if (look.agent == null) content else content.copy(height = card.height,
            smallest = min(content.smallest, MachineSignal.Type.body2.value.toDouble()), complete = content.complete && bodyHeight > 0)
        return CardMeasure(look, card, rows, bodyHeight, columns)
    }

    private fun codePinWidth(card: Card, pin: dev.shibasis.reaktor.blueprint.Pin): Float =
        card.width.toFloat() / if (card.pins.any {
            it.provides != pin.provides && kotlin.math.abs(it.y - pin.y) < BlueprintEngine.RowHeight
        }) 2 else 1

    private fun rowContent(card: Card, look: BlueprintCardLook, columns: List<Float>): PaintContent {
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
        fun row(content: BlueprintPinLook, width: Float, middle: Double) {
            val markWidth = content.marks.sumOf { measureMark(it).width.toDouble() + BlueprintEngine.RowHeight / 8 }.toFloat()
            val glyphWidth = if (content.icon == null) 0f else MachineSignal.Editor.smallIconSize.value + MachineSignal.Editor.controlGap.value
            if (width < pinTextMargin + markWidth + glyphWidth + if (content.chip) 8f else 0f) fits = false
            if (text(content.text, if (content.chip) chipStyle else type.style(MachineSignal.Type.data, content.textColor, mono = true), width - pinTextMargin - markWidth - glyphWidth - if (content.chip) 8f else 0f).size.height > BlueprintEngine.RowHeight) fits = false
            content.marks.forEach { mark(it) }
            if (middle + BlueprintEngine.RowHeight / 2 > h) fits = false
        }
        if (columns.isNotEmpty() && columns.sum() + MachineSignal.Space.s3.value * (columns.size - 1) + BlueprintEngine.RowHeight * 2 > w) fits = false
        look.rows.forEachIndexed { index, content ->
            val middle = BlueprintEngine.pinY(index, card.pinHeader)
            if (columns.isEmpty()) row(content.content, w, middle)
            else (listOf(content.content) + content.trailingCells).forEachIndexed { cell, value -> row(value, columns[cell] + pinTextMargin, middle) }
        }
        when (look.face) {
            BlueprintFace.Code -> {
                val marks = codeMarks(look, w)
                marks.forEach { (value, _) -> mark(value) }
                if (text(look.title, titleStyle, codeTitleWidth(look, w, marks)).size.height > 26) fits = false
                val folded = look.folded?.let { text(it, foldedStyle, w / 2) }
                if (text(look.subtitle, subtitleStyles[if (look.subtitleMono) 1 else 0], w - 18f - (folded?.let { it.size.width + 8f } ?: 0f)).size.height + 29 > h) fits = false
                if (folded != null && folded.size.height + 30 > h) fits = false
                card.pins.forEachIndexed { k, pin -> look.pins.getOrNull(k)?.takeIf { look.rows.none { it.id == pin.key } }?.let { content ->
                    row(content, codePinWidth(card, pin), pin.y)
                } }
            }
            BlueprintFace.Prose -> {
                val room = w - ProseInset - 12f - actionWidth(look)
                val marks = cardMarks(look, room - 48f, Blueprint.ProseMeta.sp)
                val used = markWidth(marks, 6f)
                if (used > room - 48f) fits = false
                marks.forEach { (value, _) -> mark(value, Blueprint.ProseMeta.sp) }
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

    private fun cardBody(look: BlueprintCardLook) = look.fill ?: lerp(colors.card, look.tone, CardTint)

    private fun headerBrush(look: BlueprintCardLook, x: Float, w: Float) = Brush.horizontalGradient(listOf(look.fill ?: lerp(colors.card, look.tone, CardTint * 2), cardBody(look)), x, x + w)

    private fun measureMark(mark: BlueprintMark, meta: TextUnit = MachineSignal.Type.label): Measured = when (mark.kind) {
        BlueprintMark.Kind.Dot -> Measured(7f, 7f, null)
        BlueprintMark.Kind.Tag -> measurer.measure(mark.text.uppercase(), type.style(meta, colors.canvas, weight = FontWeight.Bold), maxLines = 1)
            .let { Measured(it.size.width + 8f, it.size.height + 2f, it) }
        BlueprintMark.Kind.Badge -> measurer.measure(mark.text, type.style(meta, mark.color), maxLines = 1)
            .let { Measured(it.size.width + BlueprintBadgeStyle.HorizontalPadding * 2, max(BlueprintBadgeStyle.MinHeight, it.size.height.toFloat()), it) }
        BlueprintMark.Kind.Pill -> measurer.measure(mark.text, type.style(meta, colors.textStrong, mono = true), maxLines = 1)
            .let { Measured(it.size.width + 10f, it.size.height + 2f, it) }
    }

    private fun DrawScope.drawMark(mark: BlueprintMark, measured: Measured, x: Float, y: Float) {
        val size = Size(measured.width, measured.height)
        when (mark.kind) {
            BlueprintMark.Kind.Dot, BlueprintMark.Kind.Tag -> drawRoundRect(mark.color, Offset(x, y), size, CornerRadius(3f))
            BlueprintMark.Kind.Badge -> drawRoundRect(mark.color.copy(alpha = BlueprintBadgeStyle.Tint), Offset(x, y), size, CornerRadius(BlueprintBadgeStyle.Radius))
            BlueprintMark.Kind.Pill -> drawRoundRect(mark.color.copy(alpha = .28f), Offset(x, y), size, CornerRadius(8f))
        }
        measured.text?.let { paint(it, x + (measured.width - it.size.width) / 2, y + (measured.height - it.size.height) / 2) }
    }

    private fun markWidth(marks: List<Pair<BlueprintMark, Measured>>, gap: Float) =
        marks.sumOf { (_, measured) -> measured.width.toDouble() + gap }.toFloat()

    private fun codeMarks(look: BlueprintCardLook, width: Float) =
        cardMarks(look, width - 36f - 48f - actionWidth(look))

    private fun codeTitleWidth(look: BlueprintCardLook, width: Float, marks: List<Pair<BlueprintMark, Measured>>) =
        width - 36f - markWidth(marks, 6f) - actionWidth(look)

    private fun placedMark(mark: BlueprintMark, measured: Measured, left: Float, center: Float) =
        PlacedMark(mark, measured, Rect(Offset(left, center - measured.height / 2), Size(measured.width, measured.height)))

    private fun codeMarkSlots(marks: List<Pair<BlueprintMark, Measured>>, titleWidth: Float): List<PlacedMark> {
        var left = 28f + max(titleWidth, 0f) + 6f
        return marks.map { (mark, measured) ->
            placedMark(mark, measured, left, 13f).also { left += measured.width + 6f }
        }
    }

    private fun DrawScope.drawHeaderMarks(marks: List<PlacedMark>, x: Float, y: Float) {
        marks.forEach { (mark, measured, bounds) -> drawMark(mark, measured, x + bounds.left, y + bounds.top) }
    }

    fun cardHeaderSlots(card: Card, look: BlueprintCardLook, dimmed: Boolean): BlueprintHeaderSlots {
        val width = card.width.toFloat()
        val title: BlueprintTitleSlot?
        val marks: List<PlacedMark>
        if (look.face == BlueprintFace.Prose) {
            val right = width - 12f - actionWidth(look)
            val room = right - ProseInset
            marks = proseMarks(look, right)
            title = look.titleContent?.let {
                val bodyHeight = (card.height.toFloat() - (look.agent?.let { agentHeight(it, width) } ?: 0f)).coerceAtLeast(0f)
                val text = proseTitle(card, look, room, bodyHeight)
                val style = text?.layoutInput?.style ?: proseTitles.last()
                BlueprintTitleSlot(Rect(ProseInset, 34f, right, proseTitleBottom(card, look, bodyHeight)), style, centered = false)
            }
        } else {
            val measured = codeMarks(look, width)
            val room = codeTitleWidth(look, width, measured)
            marks = codeMarkSlots(measured, room)
            title = look.titleContent?.let { BlueprintTitleSlot(Rect(28f, 0f, 28f + room, 26f), titleStyle, centered = true) }
        }
        val shownTitle = if (!dimmed) title else title?.let {
            it.copy(style = it.style.copy(color = it.style.color.copy(alpha = it.style.color.alpha * dimPaint.alpha)))
        }
        return BlueprintHeaderSlots(shownTitle, marks.filter { it.mark in look.markContent }.map(PlacedMark::slot))
    }

    private fun DrawScope.drawDetail(card: Card, look: BlueprintCardLook, x: Float, y: Float, bodyHeight: Float, columns: List<Float>) {
        val w = card.width.toFloat(); val h = card.height.toFloat()
        drawRoundRect(if (look.image != null) look.fill ?: colors.card else cardBody(look), Offset(x, y), Size(w, h), CornerRadius(MachineSignal.Radius.node.value))
        if (look.image == null) drawPath(headerPath(x, y, w), headerBrush(look, x, w))
        if (look.choice == null) icon(look.icon).draw(this, x + 8f, y + 6f, 14f, look.tone)
        val marks = codeMarks(look, w)
        val titleWidth = codeTitleWidth(look, w, marks)
        if (look.titleContent == null) {
            val title = measure(look.title, titleStyle, titleWidth)
            paint(title, x + 28f, y + (26f - title.size.height) / 2)
        }
        drawHeaderMarks(codeMarkSlots(marks, titleWidth), x, y)
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
        drawRows(card, look, x, y, columns)
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

    private fun DrawScope.drawRows(card: Card, look: BlueprintCardLook, x: Float, y: Float, columns: List<Float>) {
        val oneSided = card.pins.all { it.provides } || card.pins.none { it.provides }
        look.rows.forEachIndexed { index, row ->
            val middle = y + BlueprintEngine.pinY(index, card.pinHeader).toFloat()
            if (row.content.selected) drawRoundRect(accent.copy(alpha = .16f), Offset(x + 3f, middle - BlueprintEngine.RowHeight.toFloat() / 2 + 1f), Size(card.width.toFloat() - 6f, BlueprintEngine.RowHeight.toFloat() - 2f), CornerRadius(4f))
            if (columns.isEmpty()) drawRowContent(false, row.content, x, middle, card.width.toFloat())
            else {
                var left = x + BlueprintEngine.RowHeight.toFloat()
                (listOf(row.content) + row.trailingCells).forEachIndexed { cell, content ->
                    drawRowContent(false, content, left - pinTextMargin + MachineSignal.Space.s0_5.value, middle, columns[cell] + pinTextMargin)
                    left += columns[cell] + MachineSignal.Space.s3.value
                }
            }
        }
        val rowIds = look.rows.mapTo(HashSet()) { it.id }
        card.pins.forEachIndexed { k, pin -> look.pins.getOrNull(k)?.let { row ->
            val width = if (look.face == BlueprintFace.Code) codePinWidth(card, pin) else card.width.toFloat() / if (oneSided) 1 else 2
            val left = x + if (pin.provides) card.width.toFloat() - width else 0f
            if (pin.key in rowIds) drawPinAnchor(pin.provides, row, left, y + pin.y.toFloat(), width)
            else drawPin(pin.provides, row, left, y + pin.y.toFloat(), width)
        } }
    }

    private fun DrawScope.drawTitled(card: Card, look: BlueprintCardLook, name: String, x: Float, y: Float) {
        val w = card.width.toFloat(); val h = card.height.toFloat()
        drawRoundRect(Brush.horizontalGradient(listOf(look.fill ?: lerp(colors.card, look.tone, TitledTint), look.fill ?: lerp(colors.card, look.tone, TitledTint * .6f)), x, x + w), Offset(x, y), Size(w, h), CornerRadius(MachineSignal.Radius.node.value))
        drawBorder(look.selected || card.id == selected, look.outline, x, y, w, h)
        val room = h - TitledInset * 2; val line = titledStyle.lineHeight.value; val k = min(1f, room / line)
        val style = if (k < 1f) titledStyle.copy(fontSize = titledStyle.fontSize * k, lineHeight = titledStyle.lineHeight * k) else titledStyle
        val title = measurer.measure(name, style, TextOverflow.Ellipsis, maxLines = (room / (line * k)).toInt().coerceIn(1, 3), constraints = Constraints(maxWidth = (w - TitledInset * 2).toInt().coerceAtLeast(1)))
        paint(title, x + TitledInset, y + (h - title.size.height) / 2)
    }

    private fun DrawScope.drawBorder(selected: Boolean, outline: Color?, x: Float, y: Float, w: Float, h: Float) {
        val border = when { selected -> 2f; outline != null -> 1.6f; else -> 1f }
        val edge = when { selected -> accent; outline != null -> outline; else -> colors.line.copy(alpha = .45f) }
        drawRoundRect(edge, Offset(x + border / 2, y + border / 2), Size(w - border, h - border), CornerRadius(MachineSignal.Radius.node.value - border / 2), style = Stroke(border))
    }

    private fun DrawScope.proseBody(look: BlueprintCardLook, x: Float, y: Float, w: Float, h: Float) {
        val outline = Path().apply { addRoundRect(RoundRect(x, y, x + w, y + h, CornerRadius(MachineSignal.Radius.node.value))) }
        drawPath(outline, cardBody(look))
        clipPath(outline) { drawRect(look.tone, Offset(x, y), Size(Stripe, h)) }
    }

    private fun proseTitleBottom(card: Card, look: BlueprintCardLook, height: Float): Float =
        card.pins.filter { it.y > BlueprintEngine.HeaderHeight }.minOfOrNull { it.y - BlueprintEngine.RowHeight / 2 - BlueprintEngine.RowHeight / 3 }?.toFloat()
            ?: (height - if (look.subtitle.isNotEmpty()) 34f else 10f)

    private fun proseTitle(card: Card, look: BlueprintCardLook, width: Float, height: Float): TextLayoutResult? =
        Blueprint.ProseTitles.indices.firstNotNullOfOrNull { k ->
            measure(look.title, proseTitles[k], width, ProseLines[k]).takeUnless {
                it.hasVisualOverflow || it.size.height + 34f > proseTitleBottom(card, look, height)
            }
        }

    private fun proseMarks(look: BlueprintCardLook, right: Float): List<PlacedMark> {
        val marks = cardMarks(look, right - ProseInset - 48f, Blueprint.ProseMeta.sp)
        var at = right
        return buildList {
            for ((mark, measured) in marks.asReversed()) {
                if (at - measured.width < ProseInset + 48f) break
                at -= measured.width
                add(placedMark(mark, measured, at, 18f))
                at -= 6f
            }
        }
    }

    private fun DrawScope.drawProse(card: Card, look: BlueprintCardLook, x: Float, y: Float, bodyHeight: Float, columns: List<Float>) {
        val w = card.width.toFloat(); val h = card.height.toFloat()
        proseBody(look, x, y, w, h)
        drawScreenshot(look.image, x, y, w, bodyHeight, look.muted)
        icon(look.icon).draw(this, x + MachineSignal.Space.s1.value, y + MachineSignal.Space.s2.value, MachineSignal.Editor.smallIconSize.value, look.tone)
        val left = x + ProseInset
        val right = w - 12f - actionWidth(look)
        val room = right - ProseInset
        val marks = proseMarks(look, right)
        drawHeaderMarks(marks, x, y)
        val eyebrowWidth = (marks.lastOrNull()?.let { it.bounds.left - 6f } ?: right) - ProseInset
        look.eyebrow?.let { eyebrow -> words(eyebrow.uppercase(), eyebrowStyle, eyebrowWidth)?.let { paint(it, left, y + 11f) } }
        if (look.titleContent == null) {
            val fitted = proseTitle(card, look, room, bodyHeight)
            val title = fitted ?: words(look.title, proseTitles.last(), room, ProseLines.last())
                ?.takeIf { it.size.height + 34f <= proseTitleBottom(card, look, bodyHeight) }
            title?.let { paint(it, left, y + 34f) }
        }
        if (look.subtitle.isNotEmpty()) words(look.subtitle, footerStyle, room)?.let { paint(it, left, y + bodyHeight - 28f) }
        drawRows(card, look, x, y, columns)
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
        if (pin.selected) drawRoundRect(accent.copy(alpha = .16f), Offset(x + 3f, middle - BlueprintEngine.RowHeight.toFloat() / 2 + 1f), Size(width - 6f, BlueprintEngine.RowHeight.toFloat() - 2f), CornerRadius(4f))
        drawPinAnchor(provides, pin, x, middle, width)
        drawRowContent(provides, pin, x, middle, width)
    }

    private fun DrawScope.drawPinAnchor(provides: Boolean, pin: BlueprintPinLook, x: Float, middle: Float, width: Float) {
        val dot = Offset(if (provides) x + width - 10f else x + 10f, middle)
        if (pin.selected) drawCircle(accent.copy(alpha = .35f), 7f, dot)
        if (pin.choice == null) {
            if (pin.filled) drawCircle(pin.color, 4f, dot)
            drawCircle(pin.color, 3.3f, dot, style = Stroke(1.4f))
        }
    }

    private fun DrawScope.drawRowContent(provides: Boolean, pin: BlueprintPinLook, x: Float, middle: Float, width: Float) {
        val gap = BlueprintEngine.RowHeight.toFloat() / 8
        val marks = pin.marks.map { it to measureMark(it) }
        val markWidth = marks.sumOf { it.second.width.toDouble() }.toFloat() + gap * marks.size
        val glyphSize = MachineSignal.Editor.smallIconSize.value
        val glyphWidth = if (pin.icon == null) 0f else glyphSize + MachineSignal.Editor.controlGap.value
        val text = measure(pin.text, if (pin.chip) chipStyle else type.style(MachineSignal.Type.data, pin.textColor, mono = true), (width - pinTextMargin - markWidth - glyphWidth - if (pin.chip) 8f else 0f).coerceAtLeast(BlueprintEngine.RowHeight.toFloat()))
        val box = text.size.width + if (pin.chip) 8f else 0f
        val left = if (provides) x + width - 20f - box - markWidth else x + 20f + glyphWidth
        pin.icon?.let { icon(it).draw(this, left - glyphWidth, middle - glyphSize / 2, glyphSize, pin.color) }
        val top = middle - text.size.height / 2f
        if (pin.chip) drawRoundRect(pin.color.copy(alpha = .2f), Offset(left, top), Size(box, text.size.height.toFloat()), CornerRadius(3f))
        paint(text, left + if (pin.chip) 4f else 0f, top)
        var markLeft = left + box + gap
        marks.forEach { (mark, measured) ->
            drawMark(mark, measured, markLeft, middle - measured.height / 2)
            markLeft += measured.width + gap
        }
    }

    private fun frameHeader(frame: Frame, look: BlueprintFrameLook): FrameHeader {
        val height = BlueprintEngine.FrameTop.toFloat()
        val left = 34f
        val right = frame.width.toFloat() - 12f
        val controls = look.marks.filter { it in look.markContent }.distinct().map { it to measureMark(it) }
        val textRight = right - markWidth(controls, 8f)
        val detail = frame.detail.joinToString(" · ")
        val info = listOf(look.count, detail)
        val titleRoom = (textRight - left).coerceAtLeast(0f)
        val titleText = measure(frame.label, headerStyles[0], titleRoom)
        val titleWidth = if (look.titleContent == null) titleText.size.width.toFloat() else {
            val infoRoom = info.mapIndexed { i, value ->
                measure(value, headerStyles[i + 1], titleRoom).size.width + 8f
            }.sum().coerceAtMost(titleRoom / 3f)
            minOf(maxOf(titleText.size.width.toFloat(), BlueprintEngine.CardWidth.toFloat() / 3f), titleRoom - infoRoom)
        }
        val title = BlueprintTitleSlot(Rect(left, 0f, left + titleWidth, height), headerStyles[0], centered = true)
        var at = title.bounds.right + 8f
        val labels = buildList {
            if (look.titleContent == null) add(titleText to left)
            info.forEachIndexed { i, value ->
                val text = measure(value, headerStyles[i + 1], textRight - at)
                add(text to at)
                at += text.size.width + 8f
            }
        }
        val marks = buildList {
            for (mark in look.marks) {
                if (at >= textRight) break
                if (mark in look.markContent) continue
                val measured = measureMark(mark)
                if (controls.isNotEmpty() && at + measured.width > textRight) continue
                add(placedMark(mark, measured, at, height / 2))
                at += measured.width + 8f
            }
            at = minOf(at, textRight + 8f)
            controls.forEach { (mark, measured) ->
                if (at >= left && at + measured.width <= right) add(placedMark(mark, measured, at, height / 2))
                at += measured.width + 8f
            }
        }
        return FrameHeader(title, labels, marks)
    }

    fun frameHeaderSlots(frame: Frame, look: BlueprintFrameLook): BlueprintHeaderSlots {
        val header = frameHeader(frame, look)
        return BlueprintHeaderSlots(header.title.takeIf { look.titleContent != null },
            header.marks.filter { it.mark in look.markContent }.map(PlacedMark::slot))
    }

    private fun DrawScope.drawHeader(frame: Frame, look: BlueprintFrameLook, x: Float, y: Float) {
        val height = BlueprintEngine.FrameTop.toFloat()
        icon(look.icon).draw(this, x + 12f, y + (height - 14f) / 2, 14f, tint)
        val header = frameHeader(frame, look)
        header.labels.forEach { (text, at) -> paint(text, x + at, y + (height - text.size.height) / 2) }
        drawHeaderMarks(header.marks, x, y)
    }

    private companion object {
        const val MARGIN = 2.0
        const val Stripe = 5f
        const val ProseInset = 17f
        const val ProseLeading = 1.25
        const val CardTint = .05f
        const val OverviewTint = .42f
        const val FadedTint = .3f
        const val TitledTint = .38f
        const val TitledInset = 18f
        val ProseLines = intArrayOf(3, 4, 5)
        const val Joiners = ",;:·–-"
    }
}
