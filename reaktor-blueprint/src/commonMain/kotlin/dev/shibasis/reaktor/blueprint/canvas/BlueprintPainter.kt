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

internal fun blueprintHeaderScale(zoom: Double) = 2.0.pow(round(log2((1.0 / zoom.coerceIn(0.35, 1.0)).coerceAtMost(1.8)) * 32) / 32).toFloat()

private data class PaintKey(val look: BlueprintCardLook, val tier: PaintDetail.Tier, val title: Int, val band: Int, val previous: PaintKey? = null, val blend: Int = 32)
private data class RegionText(val text: TextLayoutResult, val x: Float, val y: Float, val title: Boolean = false)
private data class RegionBadge(val color: Color, val x: Float, val y: Float, val width: Float, val height: Float)
private data class RegionMeasure(val unit: Float, val texts: List<RegionText>, val badges: List<RegionBadge>, val bar: Float?, val smallest: Float, val complete: Boolean)
private data class PinMeasure(val text: TextLayoutResult, val marks: List<Pair<BlueprintMark, Measured>>, val markWidth: Float,
                              val glyphSize: Float, val glyphWidth: Float, val box: Float, val left: Float, val textLeft: Float,
                              val smallest: Double, val height: Float, val complete: Boolean)
private data class CardMeasure(val look: BlueprintCardLook, val card: Card, val band: Int, val titles: List<TextLayoutResult>, val contents: List<PaintDetail.Content>, val rows: PaintDetail.Content?, val region: RegionMeasure?, val titleLeft: Float, val titleRoom: Float, val bodyHeight: Float, val unit: Float, val pins: List<PinMeasure?>)
private data class CardPaint(val key: PaintKey, val detail: PaintDetail, val title: TextLayoutResult?, val smallest: Float, val measured: CardMeasure,
                             val previous: CardPaint? = null, val blend: Float = 1f) {
    val pins get() = detail.pins && (previous == null || blend > 0f || previous.detail.pins)
    val visible get() = if (blend == 0f && !(measured.look.agent != null && detail.tier == PaintDetail.Tier.Title && previous?.detail?.tier == PaintDetail.Tier.Summary)) previous ?: this else this
}
private data class FarKey(val title: String, val tone: Color, val selected: Boolean, val outline: Color?, val image: androidx.compose.ui.graphics.ImageBitmap?, val muted: Boolean, val band: Int = -1)
private data class RegionKey(val look: BlueprintCardLook, val band: Int, val chosen: Boolean, val glow: Color?)
internal data class PaintStats(val retainedPixelBytes: Long, val releasedViews: Long)

private class Read(val text: Float, val title: BlueprintTitle)
private data class HeaderKey(val look: BlueprintFrameLook, val scale: Float)
private data class BannerKey(val look: BlueprintFrameLook, val room: Int)
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
    private val proseFar = Blueprint.ProseBands.map { prose(it.size) }
    private val eyebrowStyle = type.style(Blueprint.ProseMeta.sp, colors.textMuted, weight = FontWeight.SemiBold).copy(letterSpacing = .6.sp)
    private val footerStyle = type.style(Blueprint.ProseMeta.sp, colors.textMuted)

    private fun prose(size: Double) = type.style(size.sp, colors.textStrong, weight = FontWeight.SemiBold).copy(lineHeight = (size * ProseLeading).sp)

    private var layout: BlueprintLayout<*>? = null
    private var cards = emptyArray<Card>()
    private var cardSlots = emptyMap<String, Int>()
    private var keys = arrayOfNulls<Any>(0)
    private var reads = arrayOfNulls<Read>(0)
    private var paints = arrayOfNulls<CardPaint>(0)
    private var markerKeys = arrayOfNulls<PaintKey>(0)
    private var paintScale = 1.0
    private var projecting = false
    private var mapLevels: dev.shibasis.reaktor.blueprint.BlueprintMapLevels<*>? = null
    private var boundsLevels: dev.shibasis.reaktor.blueprint.BlueprintMapLevels<*>? = null
    private var boundsLooks: Map<String, BlueprintCardLook?> = emptyMap()
    private var boundsFrames: Map<String, BlueprintFrameLook> = emptyMap()
    private var boundsBundles = true
    private var mapZoom = Double.NaN
    private var mapExpanded = emptySet<String>()
    private var mapBundles = true
    private var mapPaint: PaintMap<*>? = null
    private var cardOpacity = emptyMap<String, Float>()
    private var frameOpacity = emptyMap<String, Float>()
    private var summaryFrames = emptyMap<String, String>()
    private var summaryMembers = emptyMap<String, List<Pair<Card, BlueprintCardLook>>>()
    private var summaryWires = emptyMap<String, List<Pair<Link, LinkStyle>>>()
    private var looks: List<BlueprintCardLook?> = emptyList()
    private var pictures = false
    private var regions = false
    private val naturals = HashMap<Pair<String, BlueprintFrameLook>, Float>()
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
    override val farZoom = Blueprint.FarZoom
    override val meshZoom = .2
    override fun bannerLift(height: Float, z: Float, clear: Boolean) = if (pictures || clear && regions) BlueprintEngine.FrameTop.toFloat() * z - height - 2f else super.bannerLift(height, z, clear)
    override val cardRadius = MachineSignal.Radius.node.value

    fun sync(layout: BlueprintLayout<*>, looks: List<BlueprintCardLook?>, glows: List<Color?>, frameLooks: List<BlueprintFrameLook>, styles: List<LinkStyle>,
             selected: String?, highlight: Set<String>, labels: Map<String, String>, grabbed: Set<String> = emptySet(), aside: Set<String> = emptySet(), opening: BlueprintOpening<*>? = null,
             levels: dev.shibasis.reaktor.blueprint.BlueprintMapLevels<*>? = null,
             current: dev.shibasis.reaktor.blueprint.BlueprintMapLevel<*>? = null,
             fullLooks: Map<String, BlueprintCardLook?> = emptyMap(), fullFrames: Map<String, BlueprintFrameLook> = emptyMap(),
             fullStyles: Map<String, LinkStyle> = emptyMap(), zoom: Double = current?.zoom ?: 1.0) {
        if (!projecting) {
            boundsLevels = levels
            boundsLooks = fullLooks.ifEmpty { layout.cards.keys.mapIndexed { i, id -> id to looks.getOrNull(i) }.toMap() }
            boundsFrames = fullFrames.ifEmpty { layout.frames.mapIndexedNotNull { i, frame -> frameLooks.getOrNull(i)?.let { frame.key to it } }.toMap() }
            boundsBundles = current?.bundles ?: true
        }
        val step = current?.let { if (levels != null && it.zoom != zoom) levels.at(zoom, it.expanded, bundles = it.bundles) else it }
        val sourceFull = current?.frames?.values?.all { it.expanded && it.blend == 1.0 } == true
        val paintedFull = step?.frames?.values?.all { it.expanded && it.blend == 1.0 } == true
        if (levels != null && step != null && (!paintedFull || !sourceFull)) {
            if (mapLevels !== levels || mapZoom != zoom || mapExpanded != step.expanded || mapBundles != step.bundles) {
                mapLevels = levels; mapZoom = zoom; mapExpanded = step.expanded; mapBundles = step.bundles
                mapPaint = project(levels, zoom, step.expanded, step.bundles)
            }
            val paint = mapPaint!!
            val summaryOf = step.frames.values.mapNotNull { level -> level.summary?.takeIf { it.id in paint.layout.cards }?.let { it.id to level } }.toMap()
            summaryFrames = summaryOf.mapValues { it.value.frame.key }
            val owners = HashMap<String, String>()
            val weights = HashMap<String, Float>()
            for ((id, group) in summaryOf.entries.sortedBy { it.value.members.size }) {
                val alpha = paint.cards[id] ?: continue
                for (member in group.members) if (alpha > (weights[member] ?: -1f)) {
                    owners[member] = id
                    weights[member] = alpha
                }
            }
            for ((id, alpha) in paint.cards) {
                val member = levels.full.cards[id] ?: continue
                val look = fullLooks[id] ?: continue
                val readable = member.width * zoom >= MachineSignal.Editor.controlHeight.value && member.height * zoom >= 14.0 ||
                    measureCard(member, look, zoom, 1.0, paints.getOrNull(cardSlots[id] ?: -1)).detail.tier != PaintDetail.Tier.Summary
                if (id !in owners || readable && alpha >= (weights[id] ?: 0f)) owners[id] = id
            }
            val visualCards = paint.cards.mapValues { (id, alpha) ->
                if (id !in levels.full.cards) alpha else if (owners[id] == id) 1f else 0f
            }
            val members = summaryOf.mapValues { (summary, group) -> group.members.mapNotNull { id ->
                if (owners[id] != summary) return@mapNotNull null
                val member = levels.full.cards[id] ?: return@mapNotNull null
                fullLooks[id]?.let { member to it }
            } }
            if (summaryMembers != members) { summaryMembers = members; changed = true }
            val wires = summaryOf.mapValues { (_, group) -> levels.full.links.filter { it.from in group.members && it.to in group.members }
                .map { it to (fullStyles[it.id] ?: Blueprint.link(tint, false, false)) } }
            if (summaryWires != wires) { summaryWires = wires; changed = true }
            val projectedLooks = paint.layout.cards.values.map { card ->
                val group = summaryOf[card.id]
                if (group == null) fullLooks[card.id] else {
                    val frame = fullFrames[group.frame.key]
                    val supplied = fullLooks[card.id]
                    val marks = supplied?.marks?.takeIf { it.isNotEmpty() } ?: frame?.marks?.takeIf { it.isNotEmpty() } ?: group.members.flatMap { fullLooks[it]?.marks.orEmpty() }.distinct()
                    val agents = group.members.mapNotNull { fullLooks[it]?.agent }.distinctBy { it.label }
                    val agent = supplied?.agent ?: agents.firstOrNull()?.let { it.copy(otherAgents = it.otherAgents + agents.size - 1) }
                    (supplied ?: BlueprintCardLook(tint, frame?.icon ?: dev.shibasis.reaktor.blueprint.product.SurfaceGraphSymbol,
                        group.frame.label, frame?.count ?: "${group.members.size} nodes")).copy(face = BlueprintFace.Region, marks = marks, agent = agent)
                }
            }
            val projectedStyles = paint.layout.links.mapIndexed { index, link ->
                (fullStyles[link.id] ?: Blueprint.link(tint, false, false)).let { it.copy(alpha = it.alpha * paint.links[index]) }
            }
            if (cardOpacity != visualCards || frameOpacity != paint.frames) {
                cardOpacity = visualCards; frameOpacity = paint.frames
                version.intValue++
                dirty[UNDER] = true; dirty[CARDS] = true; dirty[OVER] = true; changed = true
            }
            val projectedLabels = labels + paint.layout.links.filter { it.members.size > 1 || it.from in summaryOf || it.to in summaryOf }.associate { link ->
                link.id to if (link.from in summaryOf || link.to in summaryOf)
                    dev.shibasis.reaktor.blueprint.BlueprintFarProjection.label(labels[link.id], link.members.size)
                else listOfNotNull(labels[link.id], "${link.members.size} links").joinToString(" · ")
            }
            val glowOf = layout.cards.keys.mapIndexed { i, id -> id to glows.getOrNull(i) }.toMap()
            val projectedHighlight = highlight + summaryOf.filterValues { group -> group.members.any(highlight::contains) }.keys
            val projectedSelected = selected?.let { id ->
                id.takeIf(paint.layout.cards::containsKey) ?: summaryOf.entries.firstOrNull { (summary, group) ->
                    id in group.members && (paint.cards[summary] ?: 0f) > 0f
                }?.key
            }
            projecting = true
            try { sync(paint.layout, projectedLooks, paint.layout.cards.keys.map(glowOf::get),
                paint.layout.frames.map { fullFrames[it.key] ?: BlueprintFrameLook(dev.shibasis.reaktor.blueprint.product.SurfaceGraphSymbol, "${it.nodes.size} nodes") },
                projectedStyles, projectedSelected, projectedHighlight, projectedLabels, grabbed, aside) } finally { projecting = false }
            return
        }
        if (!projecting) {
            cardOpacity = emptyMap(); frameOpacity = emptyMap(); summaryFrames = emptyMap(); summaryMembers = emptyMap(); summaryWires = emptyMap(); mapLevels = null; mapPaint = null
        }
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
        if (frameLooks !== this.frameLooks && (frameLooks.size != this.frameLooks.size || frameLooks.indices.any { frameLooks[it].lit != this.frameLooks[it].lit || frameLooks[it].muted != this.frameLooks[it].muted })) dirty[UNDER] = true
        val marked = fresh || glows !== this.glows || selected != this.selected || highlight != this.highlight || aside != this.aside
        this.aside = aside
        pictures = looks.any { it?.image != null }
        regions = looks.any { it?.face == BlueprintFace.Region }
        this.frameLooks = frameLooks; this.looks = looks; this.glows = glows; this.selected = selected; this.highlight = highlight; this.labels = labels
        if (marked) mark()
    }

    @Suppress("UNCHECKED_CAST")
    private fun project(levels: dev.shibasis.reaktor.blueprint.BlueprintMapLevels<*>, zoom: Double, expanded: Set<String>, bundles: Boolean): PaintMap<*> {
        val source = levels as dev.shibasis.reaktor.blueprint.BlueprintMapLevels<Any?>
        return paintMap(source, source.at(zoom, expanded, bundles = bundles))
    }

    override fun visibleLinkAt(x: Float, y: Float, tolerance: Float): Int =
        linkIndexAt(x, y, tolerance) { (mapPaint?.links?.get(it) ?: 1f) >= .2f }

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
        markerKeys = arrayOfNulls(next.size)
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

    fun draw(scope: DrawScope, camera: Viewport, viewport: Size, density: Float, level: Int, shift: Float, pulses: List<CanvasPulse>, labelExclusions: List<Rect> = emptyList()) {
        partialSummaryAlphaScopes = 0
        summaryPaintComplete = false
        if (observeSummaryPaints) summaryPaintFrame++
        paintScale = camera.zoom
        val z = camera.zoom
        val l = ((-camera.x - viewport.width) / z).toFloat()
        val t = ((-camera.y - viewport.height) / z).toFloat()
        val r = ((viewport.width * 2 - camera.x) / z).toFloat()
        val b = ((viewport.height * 2 - camera.y) / z).toFloat()
        for (i in cards.indices) {
            if (!inside(cardBox, i, l, t, r, b)) {
                if (paints[i] != null) {
                    paints[i] = null
                    for (slot in 0..1) { keys[i * 2 + slot] = null; reads[i * 2 + slot] = null }
                }
                continue
            }
            val look = looks.getOrNull(i)?.takeIf { shown(i) } ?: continue
            val next = measured(i, look, camera.zoom, 1.0)
            if (paints[i]?.key != next.key) {
                for (slot in 0..1) {
                    val at = i * 2 + slot
                    layers[at]?.let { graphics.releaseGraphicsLayer(it); layers[at] = null; live-- }
                    keys[at] = null
                    reads[at] = null
                }
                dirty[CARDS] = true; dirty[OVER] = true; changed = true
            }
            paints[i] = next
            for (slot in 0..1) layers[i * 2 + slot]?.alpha = cardOpacity[cards[i].id] ?: 1f
        }
        val next = if (pictures || paints.any { it?.detail?.tier != PaintDetail.Tier.Summary && it != null }) maxOf(1, level) else level
        if (next != this.level) { dirty[CARDS] = true; dirty[OVER] = true }
        this.level = next; this.shift = shift; this.pulses = pulses
        with(scope) {
            paintingMap = true
            try { frame(camera, viewport, density) } finally { paintingMap = false }
            drawMapLabels(camera, labelExclusions)
        }
        var markersChanged = false
        for (i in paints.indices) {
            val key = paints[i]?.key?.takeIf { current(i, 0) || current(i, 1) }
            if (markerKeys[i] != key) { markerKeys[i] = key; markersChanged = true }
        }
        if (markersChanged) version.intValue++
        summaryPaintComplete = observeSummaryPaints
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
            val tier = if (read == null) BlueprintTier.Overview else paints[i]?.visible?.detail?.publicTier ?: BlueprintTier.Overview
            tiers[tier] = (tiers[tier] ?: 0) + 1
        }
        return BlueprintReading(level, (smallest * z).toDouble(), titles, tiers)
    }

    fun agentBounds(id: String, zoom: Double): BlueprintBadgeBounds? {
        version.intValue
        val i = cardSlots[id] ?: return null
        val look = looks.getOrNull(i)?.takeIf { it.agent != null } ?: return null
        val next = committed(i)?.visible ?: return null
        val card = cards[i]
        val inset = MachineSignal.Space.s3.value.toDouble()
        return when (next.detail.tier) {
            PaintDetail.Tier.Summary -> {
                val unit = min(1.0 / paintScale, min(card.width, card.height) / (MachineSignal.AgentBadge.size.value * 2))
                val side = MachineSignal.AgentBadge.size.value * unit
                BlueprintBadgeBounds(card.x + (card.width - side) / 2, card.y + (card.height - side) / 2, side, side, unit, true)
            }
            PaintDetail.Tier.Title -> {
                val unit = next.measured.unit.toDouble()
                val side = MachineSignal.AgentBadge.size.value * unit
                BlueprintBadgeBounds(card.x + inset, card.y + (card.height - side) / 2, side, side, unit, true)
            }
            PaintDetail.Tier.Rows -> BlueprintBadgeBounds(card.x + inset, card.y + next.measured.bodyHeight + MachineSignal.AgentBadge.gap.value,
                card.width - inset * 2, card.height - next.measured.bodyHeight - MachineSignal.AgentBadge.gap.value * 2, 1.0, false)
        }
    }

    fun paintedBounds(requested: dev.shibasis.reaktor.blueprint.Bounds, zoom: Double): List<dev.shibasis.reaktor.blueprint.Bounds> {
        val levels = boundsLevels ?: return bannerBounds(requested, zoom)
        if (!zoom.isFinite() || zoom <= 0) return emptyList()
        @Suppress("UNCHECKED_CAST")
        val source = levels as dev.shibasis.reaktor.blueprint.BlueprintMapLevels<Any?>
        val step = source.at(zoom, bundles = boundsBundles)
        val candidate = paintMap(source, step)
        val summaries = step.frames.values.mapNotNull { it.summary?.id }.toSet()
        val pictures = candidate.layout.cards.keys.any { boundsLooks[it]?.image != null }
        val regions = candidate.layout.cards.keys.any { it in summaries || boundsLooks[it]?.face == BlueprintFace.Region }
        return bannerBounds(requested, zoom, candidate.layout.frames, candidate.layout.cards.values,
            { i, width, _ ->
                val frame = candidate.layout.frames[i]
                val look = boundsFrames[frame.key] ?: BlueprintFrameLook(dev.shibasis.reaktor.blueprint.product.SurfaceGraphSymbol, "${frame.nodes.size} nodes")
                bannerSpace(frame.label, look, width)?.let { space ->
                    measureBanner(frame.label, look, space)?.let { measured ->
                        val height = max(30f, max(measured.label.size.height.toFloat(),
                            max(measured.count?.size?.height?.toFloat() ?: 0f, space.marks.maxOfOrNull { it.height } ?: 0f)) + MachineSignal.Editor.controlGap.value * 2)
                        IntSize(ceil(measured.width).toInt(), ceil(height).toInt())
                    }
                }
            },
            { height, z, clear ->
                if (pictures || clear && regions) BlueprintEngine.FrameTop.toFloat() * z - height - 2f else super.bannerLift(height, z, clear)
            })
    }

    fun cardAt(x: Float, y: Float): String? = cardIndex(x, y).takeIf { it >= 0 }?.let { cards[it].id }

    fun pinAt(x: Float, y: Float): Pair<Card, Pin>? = cardIndex(x, y).takeIf { it >= 0 && committed(it)?.pins == true }?.let { i -> pin(i, x, y)?.let { cards[i] to it } }

    private fun committed(i: Int): CardPaint? = paints.getOrNull(i)?.takeIf { current(i, 0) || current(i, 1) }

    fun rows(id: String, zoom: Double): Boolean {
        version.intValue
        val i = cardSlots[id] ?: return false
        return committed(i)?.pins == true
    }

    fun headerScale(id: String, zoom: Double): Float? {
        version.intValue
        val i = cardSlots[id] ?: return null
        val look = looks.getOrNull(i) ?: return null
        val next = committed(i)?.visible ?: return null
        if (next.detail.tier == PaintDetail.Tier.Summary) return null
        return if (next.detail.tier == PaintDetail.Tier.Title || look.face == BlueprintFace.Region) next.measured.unit else 1f
    }

    fun readableZoom(x: Double, y: Double, width: Double, height: Double): Double? {
        val i = cards.indexOfFirst { it.x == x && it.y == y && it.width == width && it.height == height }
        val full = boundsLevels?.full?.cards?.values?.filter { it.x == x && it.y == y && it.width == width && it.height == height }
        if (full != null && full.size > 1) return null
        val card = full?.singleOrNull() ?: cards.getOrNull(i) ?: return null
        val look = if (full?.singleOrNull() != null) boundsLooks[card.id] else looks.getOrNull(i)
        if (look == null) return null
        fun at(zoom: Double) = if (cards.getOrNull(i) == card && looks.getOrNull(i) == look) measured(i, look, zoom, 1.0)
            else measureCard(card, look, zoom, 1.0)
        val readable = if (look.face == BlueprintFace.Region) (32 downTo -8).firstNotNullOfOrNull { band ->
            val zoom = 2.0.pow(-band / 4.0)
            zoom.takeIf { at(zoom).pins }
        }?.times(1.120001) ?: return null else {
            val rows = at(1.0).measured.rows?.takeIf { it.complete && it.smallest.isFinite() && it.smallest > 0 } ?: return null
            11.0 / rows.smallest * 1.120001
        }
        val exposure = boundsLevels?.at(readable, bundles = boundsBundles)?.frames?.values
            ?.filter { it.summary != null && card.id in it.members }?.maxOfOrNull { it.enterZoom } ?: 0.0
        return max(readable, exposure)
    }

    override fun pinKey(i: Int, x: Float, y: Float) = pin(i, x, y)?.key

    private fun pin(i: Int, x: Float, y: Float): Pin? {
        val card = cards[i]; val look = looks.getOrNull(i) ?: return null
        if (paints.getOrNull(i)?.detail?.pins != true) return null
        val oneSided = card.pins.all { it.provides } || card.pins.none { it.provides }
        val half = BlueprintEngine.RowHeight / 2
        return card.pins.indices.firstOrNull { k -> val pin = card.pins[k]
            look.pins.getOrNull(k) != null && y - card.y - pin.y in -half..<half && (oneSided || (x - card.x >= card.width / 2) == pin.provides)
        }?.let(card.pins::get)
    }

    fun visualOpacity(id: String): Float {
        version.intValue
        return cardOpacity[id] ?: 1f
    }

    override fun shown(i: Int) = looks.getOrNull(i) != null && (cardOpacity[cards[i].id] ?: 1f) > 0f

    override fun countLabelWidth(i: Int): Float? {
        val link = links[i]
        if (link.from !in summaryFrames && link.to !in summaryFrames) return null
        return (dev.shibasis.reaktor.blueprint.BlueprintFarProjection.corridor(link.members.size, styles[i].width, mapZoom).width * mapZoom).toFloat()
    }

    override fun pen(i: Int, pen: SurfaceGraphPen): Boolean {
        val style = styles[i]; meshStyles[i] = style
        pen.rgb = style.color.toArgb(); pen.alpha = style.alpha; pen.width = style.width; pen.flowing = style.flowing; pen.head = 10f; pen.lead = style.alpha >= .99f; pen.lifted = style.lifted
        val link = links[i]
        if (link.from in summaryFrames || link.to in summaryFrames) {
            val ink = dev.shibasis.reaktor.blueprint.BlueprintFarProjection.corridor(link.members.size, style.width, mapZoom)
            pen.width = ink.width; pen.head = ink.arrow
            if (style.alpha >= .2f) pen.alpha = max(.55f, style.alpha)
        }
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
        val lit = look.lit
        val alpha = frameOpacity[frames[i].key] ?: 1f
        stroker.roundRect(x0, y0, x1, y1, MachineSignal.Radius.panel.value * z, tolerance)
        stroker.fill(solid, fringe, colors.surface.copy(alpha = (if (frames[i].muted || look.muted) .2f else .5f) * alpha).toArgb(), min(x1 - x0, y1 - y0))
        stroker.outline(solid, (if (lit != null) 1.5f else 1f) * z / 2, fringe, (lit ?: colors.line).toArgb(), (if (lit != null) .75f else .24f) * alpha)
    }

    override fun face(i: Int, x0: Float, y0: Float, x1: Float, y1: Float, z: Float, fringe: Float, tolerance: Float) {
        val look = looks.getOrNull(i) ?: return
        val alpha = (if (dim[i]) .2f else 1f) * (cardOpacity[cards[i].id] ?: 1f)
        val chosen = look.selected || cards[i].id == selected
        val edge = if (chosen) accent else glows.getOrNull(i) ?: look.outline
        val block = look.face == BlueprintFace.Prose
        stroker.roundRect(x0, y0, x1, y1, MachineSignal.Radius.node.value * z, tolerance)
        if (cards[i].id !in summaryFrames) stroker.fill(solid, fringe, surfaceGraphArgb(lerp(colors.surface, look.tone, if (block) .62f else .06f).toArgb(), alpha), min(x1 - x0, y1 - y0))
        stroker.outline(solid, if (chosen) 1f else if (edge != null) .8f else .5f, fringe, (edge ?: if (block) look.tone else colors.line).toArgb(), (edge?.alpha ?: if (block) .9f else .45f) * alpha)
    }

    override fun cardId(i: Int) = cards[i].id

    override fun endTitle(i: Int, to: Boolean) = cardOf(if (to) links[i].to else links[i].from).takeIf { it >= 0 }?.let { looks.getOrNull(it)?.title }

    override fun headerKey(i: Int): Any? = null

    private var paintingMap = false
    private val mapLabels = ArrayList<Rect>()
    private val memberLabels = ArrayList<Rect>()
    private var observeSummaryPaints = false
    private var partialSummaryAlphaScopes = 0
    private var summaryPaintFrame = 0L
    private var summaryPaintComplete = false
    private val summaryPaints = ArrayList<SummaryPaint>()
    private val memberPaints = ArrayList<SummaryPaint>()
    private class SummaryPaint(val id: String, val layout: TextLayoutResult, val x: Float, val y: Float, val opacity: Float)

    private class BannerSpace(val room: Int, val width: Float, val markWidth: Float, val marks: List<Measured>)
    private class BannerMeasure(val width: Float, val label: TextLayoutResult, val count: TextLayoutResult?)

    private fun bannerSpace(title: String, look: BlueprintFrameLook, width: Float): BannerSpace? {
        if (naturals.size > 512) naturals.clear()
        val natural = naturals.getOrPut(title to look) { 43f + measure(title, headerStyles[0], 1e5f).size.width }
        val room = if (min(width, BannerCap) >= natural) -1 else min(width, BannerCap).toInt() / 8 * 8
        if (room in 0 until 48) return null
        return BannerSpace(room, if (room < 0) natural else room.toFloat(), 0f, emptyList())
    }

    private fun measureBanner(title: String, look: BlueprintFrameLook, given: BannerSpace): BannerMeasure? {
        val space = given.width
        val label = name(title, headerStyles[0], space - 43f)
        return BannerMeasure(min(space, 43f + label.size.width), label, null)
    }

    override fun banner(i: Int, width: Float, zoom: Double): GraphicsLayer? {
        if (paintingMap) return null
        val look = frameLooks.getOrNull(i) ?: return null
        val space = bannerSpace(frames[i].label, look, width) ?: return null
        val measured = measureBanner(frames[i].label, look, space) ?: return null
        return bannerLayer(i, BannerKey(look, space.room)) { layer ->
            val w = measured.width; val label = measured.label; val count = measured.count; val marks = space.marks
            val height = max(30f, max(label.size.height.toFloat(), max(count?.size?.height?.toFloat() ?: 0f, marks.maxOfOrNull { it.height } ?: 0f)) + MachineSignal.Editor.controlGap.value * 2)
            layer.record(unit, LayoutDirection.Ltr, IntSize(ceil(w).toInt(), ceil(height).toInt())) {
                drawPath(Path().apply { addRoundRect(RoundRect(0f, 0f, w, height, bottomRightCornerRadius = CornerRadius(MachineSignal.Radius.panel.value))) }, colors.surface.copy(alpha = .92f))
                drawRect(look.lit ?: tint, Offset.Zero, Size(3f, height))
                icon(look.icon).draw(this, 11f, (height - 14f) / 2, 14f, tint)
                paint(label, 31f, (height - label.size.height) / 2)

            }
        }
    }

    private fun DrawScope.drawMapLabels(camera: Viewport, labelExclusions: List<Rect>) {
        summaryPaints.clear()
        memberPaints.clear()
        mapLabels.clear()
        memberLabels.clear()
        val gap = MachineSignal.Space.s2.value
        val z = camera.zoom
        val namedFrames = HashMap<String, Pair<String, String>>()
        val cardRooms = cards.indices.filter { shown(it) }.map { i ->
            val card = cards[i]
            Rect((camera.x + card.x * z).toFloat(), (camera.y + card.y * z).toFloat(),
                (camera.x + (card.x + card.width) * z).toFloat(), (camera.y + (card.y + card.height) * z).toFloat())
        }.filter { it.right > gap && it.bottom > gap && it.left < viewport.width - gap && it.top < viewport.height - gap }
        val frameLabels = HashMap<String, Int>()
        for (i in frames.indices.sortedByDescending { frames[it].width * frames[it].height }) {
            val frame = frames[i]
            val ownsCard = frame.nodes.any { id -> cardSlots[id]?.let(::shown) == true }
            val ownsSummary = summaryFrames.any { (id, key) -> key == frame.key && cardSlots[id]?.let(::shown) == true }
            if (!ownsCard && !ownsSummary) continue
            val left = (camera.x + frame.x * z).toFloat()
            val top = (camera.y + frame.y * z).toFloat()
            val right = (camera.x + (frame.x + frame.width) * z).toFloat()
            val bottom = (camera.y + (frame.y + frame.height) * z).toFloat()
            if (right <= gap || bottom <= gap || left >= viewport.width - gap || top >= viewport.height - gap) continue
            val room = min(viewport.width - gap * 2, max((frame.width * z).toFloat(), BannerCap))
            val layer = banner(i, room, z) ?: continue
            val width = layer.size.width.toFloat()
            val height = layer.size.height.toFloat()
            val x = left.coerceIn(gap, max(gap, min(viewport.width - gap, right) - width))
            var y = (top + bannerLift(height, z.toFloat(), false)).coerceAtLeast(gap)
            var box = Rect(x, y, x + width, y + height)
            val occupiedLabels = mapLabels + labelExclusions
            while (occupiedLabels.any { it.overlaps(box) }) {
                y = occupiedLabels.filter { it.overlaps(box) }.maxOf { it.bottom } + gap
                box = Rect(x, y, x + width, y + height)
            }
            var lifted = false
            if (cardRooms.any { it.overlaps(box) }) {
                var above = Rect(x, top - gap - height, x + width, top - gap)
                while (above.top >= gap && (occupiedLabels.any { it.overlaps(above) } || cardRooms.any { it.overlaps(above) })) {
                    val ceiling = (occupiedLabels + cardRooms).filter { it.overlaps(above) }.minOf { it.top } - gap
                    above = Rect(x, ceiling - height, x + width, ceiling)
                }
                if (above.top >= gap && above.right <= viewport.width - gap) {
                    box = above
                    lifted = true
                }
            }
            if (box.right > viewport.width - gap || box.bottom > min(viewport.height - gap, bottom) || cardRooms.any { it.overlaps(box) }) continue
            mapLabels += box
            namedFrames[frame.key] = frame.label to frameLooks[i].count
            if (lifted) drawLine(colors.line, Offset(left.coerceIn(box.left, box.right), box.bottom), Offset(left, top))
            frameLabels[frame.key] = mapLabels.lastIndex
            translate(box.left, box.top) { replayLayer(layer, REPLAY_MAP_LABELS) }
            if (observeSummaryPaints) summaryFrames.entries.firstOrNull { it.value == frame.key }?.key?.let { id ->
                val measured = bannerSpace(frame.label, frameLooks[i], room)?.let { measureBanner(frame.label, frameLooks[i], it) }
                measured?.label?.let { text -> summaryPaints += SummaryPaint(id, text, box.left + 31f,
                    box.top + (height - text.size.height) / 2, 1f) }
            }
        }
        for (i in cards.indices.sortedByDescending { cards[it].width * cards[it].height }) {
            if (!shown(i)) continue
            val summary = mapLevels?.let { cards[i].id !in it.full.cards } == true
            if (summary) {
                val look = looks[i] ?: continue
                for ((member, memberLook) in summaryMembers[cards[i].id].orEmpty()) {
                    val x = (camera.x + member.x * z).toFloat()
                    val y = (camera.y + member.y * z).toFloat()
                    val width = (member.width * z).toFloat().coerceAtLeast(2f)
                    val height = (member.height * z).toFloat().coerceAtLeast(2f)
                    val radius = CornerRadius(min(MachineSignal.Radius.node.value * z.toFloat(), min(width, height) / 3))
                    drawRoundRect(lerp(colors.surface, memberLook.tone, .10f), Offset(x, y), Size(width, height), radius)
                    drawRoundRect(memberLook.tone, Offset(x, y), Size(width, height), radius,
                        style = Stroke(MachineSignal.Stroke.hairline.value))
                    val inset = min(gap / 2, width / 10)
                    val room = width - inset * 2
                    val available = height - inset * 2
                    if (room >= 28f && available >= 14f && x + width > gap && y + height > gap &&
                        x < viewport.width - gap && y < viewport.height - gap) {
                        val title = measurer.measure(memberLook.title, type.style(11.sp, colors.textStrong),
                            overflow = TextOverflow.Ellipsis, maxLines = if (available >= 28f) 2 else 1,
                            constraints = Constraints(maxWidth = room.toInt(), maxHeight = available.toInt()))
                        val tx = x + (width - title.size.width) / 2
                        val ty = y + (height - title.size.height) / 2
                        val box = Rect(tx, ty, tx + title.size.width, ty + title.size.height)
                        if (box.left >= gap && box.top >= gap && box.right <= viewport.width - gap &&
                            box.bottom <= viewport.height - gap && labelExclusions.none { it.overlaps(box) } &&
                            mapLabels.none { it.overlaps(box) } && memberLabels.none { it.overlaps(box) }) {
                            paint(title, tx, ty)
                            memberLabels += box
                            if (observeSummaryPaints) memberPaints += SummaryPaint(member.id, title, tx, ty, 1f)
                        }
                    }
                }
                val alpha = cardOpacity[cards[i].id] ?: 1f
                if (alpha < 1f) {
                    drawContext.canvas.saveLayer(Rect(0f, 0f, viewport.width.toFloat(), viewport.height.toFloat()),
                        androidx.compose.ui.graphics.Paint().apply { this.alpha = alpha })
                    if (observeSummaryPaints && alpha > 0f) partialSummaryAlphaScopes++
                }
                try {
                    val card = cards[i]
                    val left = (camera.x + card.x * z).toFloat()
                    val top = (camera.y + card.y * z).toFloat()
                    val right = (camera.x + (card.x + card.width) * z).toFloat()
                    val bottom = (camera.y + (card.y + card.height) * z).toFloat()
                    val room = min(BannerCap, min(right, viewport.width - gap) - max(left, gap) - gap * 2)
                    for ((wire, style) in summaryWires[card.id].orEmpty()) {
                        val trace = wire.trace
                        val inkAlpha = if (style.alpha >= .2f) max(.55f, style.alpha) else style.alpha
                        for (k in 1 until trace.size) drawLine(style.color.copy(alpha = inkAlpha.coerceIn(0f, 1f)),
                            Offset((camera.x + trace[k - 1].first * z).toFloat(), (camera.y + trace[k - 1].second * z).toFloat()),
                            Offset((camera.x + trace[k].first * z).toFloat(), (camera.y + trace[k].second * z).toFloat()),
                            max(MachineSignal.Stroke.hairline.value, style.width * z.toFloat()))
                    }
                    var detailed = false
                    if (room > 0 && bottom > gap && top < viewport.height - gap) {
                        val caption = summaryFrames[card.id]?.let(namedFrames::get)
                        val named = caption?.first == look.title
                        val own = if (named) summaryFrames[card.id]?.let(frameLabels::get) else null
                        val x = max(left, gap) + gap
                        var y = max(top, gap) + gap
                        // Keep a useful whole-word name in clear space; tiny fragments use the external caption.
                        val lines = summaryTexts(look, room, !named).filter {
                            val shown = it.text.layoutInput.text.text
                            val prefix = shown.removeSuffix("…")
                            !it.title || shown == look.title || shown.endsWith("…") && prefix.isNotBlank() &&
                                look.title.startsWith(prefix) && look.title.getOrNull(prefix.length)?.isWhitespace() == true
                        }
                        val floor = min(bottom, viewport.height - gap) - gap
                        val reserved = ArrayList<Rect>()
                        own?.let { reserved += mapLabels[it] }
                        agentBounds(card.id, z)?.let {
                            reserved += Rect((camera.x + it.x * z).toFloat(), (camera.y + it.y * z).toFloat(),
                                (camera.x + (it.x + it.width) * z).toFloat(), (camera.y + (it.y + it.height) * z).toFloat())
                        }
                        if (paints.getOrNull(i)?.pins == true) for (pin in card.pins) {
                            val middle = (camera.y + (card.y + pin.y) * z).toFloat()
                            val radius = MachineSignal.Type.body2.value * max(1f, z.toFloat())
                            reserved += Rect(left, middle - radius, right, middle + radius)
                        }
                        val rawCards = cards.indices.filter { shown(it) && cards[it].id in mapLevels!!.full.cards }.map { k ->
                            val raw = cards[k]
                            Rect((camera.x + raw.x * z).toFloat(), (camera.y + raw.y * z).toFloat(),
                                (camera.x + (raw.x + raw.width) * z).toFloat(), (camera.y + (raw.y + raw.height) * z).toFloat())
                        }
                        val occupied = mapLabels.filterIndexed { index, _ -> index != own } + memberLabels + reserved + labelExclusions + rawCards
                        var visible = lines.takeWhile { y + it.y + it.text.size.height + gap <= floor }
                        if (visible.isNotEmpty()) {
                            fun bounds(): Rect {
                                val height = visible.last().let { it.y + it.text.size.height }
                                val width = visible.maxOf { it.x + it.text.size.width }
                                return Rect(x - gap, y - gap, x + width + gap, y + height + gap)
                            }
                            var box = bounds()
                            while (visible.isNotEmpty() && occupied.any { it.overlaps(box) }) {
                                y = occupied.filter { it.overlaps(box) }.maxOf { it.bottom } + gap * 2
                                visible = lines.takeWhile { y + it.y + it.text.size.height + gap <= floor }
                                if (visible.isNotEmpty()) box = bounds()
                            }
                            if (visible.isNotEmpty() && box.bottom < floor && (named || visible.any { it.title })) {
                                if (own == null) mapLabels += box else {
                                    val caption = mapLabels[own]
                                    mapLabels[own] = Rect(min(caption.left, box.left), min(caption.top, box.top), max(caption.right, box.right), max(caption.bottom, box.bottom))
                                }
                                drawRoundRect(colors.surface, box.topLeft, box.size, CornerRadius(MachineSignal.Radius.node.value))
                                visible.forEach {
                                    paint(it.text, x + it.x, y + it.y)
                                    if (observeSummaryPaints) summaryPaints += SummaryPaint(card.id, it.text, x + it.x, y + it.y, alpha)
                                }
                                val glyph = MachineSignal.Editor.controlIconSize.value
                                icon(look.icon).draw(this, x, y, glyph, look.tone)
                                detailed = true
                            }
                        }
                    }
                    if (!detailed && right > gap && bottom > gap && left < viewport.width - gap && top < viewport.height - gap) {
                        val room = min(BannerCap, viewport.width - gap * 4)
                        val caption = summaryFrames[card.id]?.let(namedFrames::get)
                        val named = caption?.first == look.title
                        val lines = summaryTexts(look, room, !named)
                        val complete = named || lines.any { it.title }
                        val width = (lines.maxOfOrNull { it.x + it.text.size.width } ?: 0f) + gap * 2
                        val textHeight = lines.lastOrNull()?.let { it.y + it.text.size.height } ?: 0f
                        val height = textHeight + gap * 2
                        val x = max(left, gap).coerceAtMost(max(gap, viewport.width - gap - width))
                        val occupied = mapLabels + memberLabels + labelExclusions + cardRooms + linkLabelBounds
                        val anchored = if (named) null else links.asSequence().withIndex()
                            .filter { it.value.from == card.id || it.value.to == card.id }
                            .flatMap { (index, link) ->
                                dev.shibasis.reaktor.blueprint.BlueprintFarProjection.boundaryLabels(link, camera,
                                    viewport.width.toDouble(), viewport.height.toDouble(), width / 2.0, height / 2.0,
                                    countLabelWidth(index)?.toDouble() ?: 0.0).asSequence()
                            }.filter { it.owner == card.id && !it.clipped }
                            .mapNotNull { endpoint -> endpoint.anchor?.let { (x, y) ->
                                Rect((x - width / 2).toFloat(), (y - height / 2).toFloat(),
                                    (x + width / 2).toFloat(), (y + height / 2).toFloat()) to endpoint
                            } }.firstOrNull { (box, _) -> box.left >= gap && box.top >= gap &&
                                box.right <= viewport.width - gap && box.bottom <= viewport.height - gap && occupied.none { it.overlaps(box) } }
                        var box = anchored?.first ?: Rect(x, max(top, gap), x + width, max(top, gap) + height)
                        if (anchored == null) while (occupied.any { it.overlaps(box) }) {
                            val y = occupied.filter { it.overlaps(box) }.maxOf { it.bottom } + gap
                            box = Rect(x, y, x + width, y + height)
                        }
                        if (complete && lines.isNotEmpty() && box.right <= viewport.width - gap && box.bottom <= viewport.height - gap) {
                            val boundary = anchored?.second?.screen?.let { Offset(it.first.toFloat(), it.second.toFloat()) }
                            val origin = boundary ?: Offset(left.coerceIn(box.left, box.right), min(bottom, box.top))
                            drawLine(colors.line, origin, Offset(origin.x.coerceIn(box.left, box.right), origin.y.coerceIn(box.top, box.bottom)))
                            drawRoundRect(colors.surface, box.topLeft, box.size, CornerRadius(MachineSignal.Radius.node.value))
                            mapLabels += box
                            lines.forEach {
                                val tx = box.left + gap + it.x
                                val ty = box.top + gap + it.y
                                paint(it.text, tx, ty)
                                if (observeSummaryPaints) summaryPaints += SummaryPaint(card.id, it.text, tx, ty, alpha)
                            }
                            val glyph = MachineSignal.Editor.controlIconSize.value
                            icon(look.icon).draw(this, box.left + gap, box.top + gap, glyph, look.tone)
                            detailed = true
                        }
                    }
                    if (!detailed) {
                        val side = min(MachineSignal.AgentBadge.iconSize.value, min(right - left, bottom - top) / 3).coerceAtLeast(0f)
                        icon(look.icon).draw(this, (left + right - side) / 2, (top + bottom - side) / 2, side, look.tone)
                    }
                } finally { if (alpha < 1f) drawContext.canvas.restore() }
                continue
            }
            if (paints.getOrNull(i)?.visible?.detail?.tier != PaintDetail.Tier.Summary) continue
            val painted = committed(i)
            if (painted != null && (painted.detail.tier != PaintDetail.Tier.Summary ||
                    painted.previous?.detail?.tier?.let { it != PaintDetail.Tier.Summary } == true)) continue
            val look = looks[i] ?: continue
            val card = cards[i]
            if (level == 0) {
                val side = min(MachineSignal.AgentBadge.size.value * z.toFloat(), min(card.width, card.height).toFloat() * z.toFloat() / 3)
                icon(look.icon).draw(this, (camera.x + (card.x + card.width / 2) * z).toFloat() - side / 2,
                    (camera.y + (card.y + card.height / 2) * z).toFloat() - side / 2, side, look.tone)
            }
            if (card.width * z < MachineSignal.Editor.controlHeight.value && !look.selected && card.id != selected) continue
            val left = max(gap, (camera.x + card.x * z).toFloat())
            val right = min(viewport.width - gap, (camera.x + (card.x + card.width) * z).toFloat())
            val top = max(gap, (camera.y + card.y * z).toFloat())
            val bottom = min(viewport.height - gap, (camera.y + (card.y + card.height) * z).toFloat())
            val insideRoom = min(BannerCap - gap * 2, right - left - gap * 2)
            if (insideRoom >= MachineSignal.Type.label.value && bottom - top >= MachineSignal.Type.label.value) {
                val title = name(look.title, type.style(MachineSignal.Type.label, colors.textStrong), insideRoom)
                val x = (left + right - title.size.width) / 2
                val bodyBottom = min(bottom, (camera.y + (card.y + (paints.getOrNull(i)?.measured?.bodyHeight ?: card.height.toFloat())) * z).toFloat())
                var y = (top + bodyBottom - title.size.height) / 2
                var box = Rect(x - gap, y, x + title.size.width + gap, y + title.size.height)
                val agent = agentBounds(card.id, z)?.let { Rect((camera.x + it.x * z).toFloat(), (camera.y + it.y * z).toFloat(),
                    (camera.x + (it.x + it.width) * z).toFloat(), (camera.y + (it.y + it.height) * z).toFloat()) }
                if (agent?.overlaps(box) == true) {
                    val above = agent.top - gap - title.size.height
                    y = if (above >= top + gap) above else agent.bottom + gap
                    box = Rect(x - gap, y, x + title.size.width + gap, y + title.size.height)
                }
                if (box.top >= top && box.bottom <= bottom && mapLabels.none { it.overlaps(box) } &&
                    memberLabels.none { it.overlaps(box) } && labelExclusions.none { it.overlaps(box) } && agent?.overlaps(box) != true) {
                    mapLabels += box
                    drawRoundRect(colors.surface, box.topLeft, box.size, CornerRadius(MachineSignal.Radius.node.value))
                    paint(title, x, y)
                    continue
                }
            }
            val center = (camera.x + (card.x + card.width / 2) * z).toFloat()
            val room = min(BannerCap, min(center - gap * 2, viewport.width - gap * 2 - center) * 2)
            if (room < 1f) continue
            val title = name(look.title, type.style(MachineSignal.Type.label, colors.textStrong), room)
            val x = center - title.size.width / 2
            var y = (camera.y + (card.y + card.height) * z).toFloat() + gap
            var box = Rect(x - gap, y, x + title.size.width + gap, y + title.size.height)
            while (labelExclusions.any { it.overlaps(box) }) {
                y = labelExclusions.filter { it.overlaps(box) }.maxOf { it.bottom } + gap
                box = Rect(x - gap, y, x + title.size.width + gap, y + title.size.height)
            }
            if (box.left < gap || box.top < gap || box.right > viewport.width - gap || box.bottom > viewport.height - gap || mapLabels.any { it.overlaps(box) } || memberLabels.any { it.overlaps(box) }) continue
            val points = listOf(box.topLeft, box.topRight, box.bottomLeft, box.bottomRight, box.center)
            if (points.any { point -> cardIndex(((point.x - camera.x) / z).toFloat(), ((point.y - camera.y) / z).toFloat()).let { it >= 0 && it != i && shown(it) } }) continue
            mapLabels += box
            drawRoundRect(colors.surface, box.topLeft, box.size, CornerRadius(MachineSignal.Radius.node.value))
            paint(title, x, y)
        }
    }

    private fun summaryTexts(look: BlueprintCardLook, room: Float, title: Boolean): List<RegionText> {
        val gap = MachineSignal.Editor.controlGap.value
        val glyph = MachineSignal.Editor.controlIconSize.value + gap
        val result = ArrayList<RegionText>()
        var y = 0f
        fun line(value: String, heading: Boolean = false) {
            if (value.isBlank()) return
            val x = if (result.isEmpty()) glyph else 0f
            val style = type.style(if (heading) MachineSignal.Type.label else MachineSignal.Type.body2,
                if (heading) colors.textStrong else colors.textMuted,
                weight = if (heading) FontWeight.SemiBold else FontWeight.Normal)
            if (room <= x) return
            val text = name(value, style, room - x)
            result += RegionText(text, x, y, heading)
            y += max(text.size.height.toFloat(), if (result.size == 1) MachineSignal.Editor.controlIconSize.value else 0f) + gap
        }
        if (title) line(look.title, true)
        return result
    }

    private fun budgetMarks(values: List<BlueprintMark>): List<BlueprintMark> =
        values.distinct().sortedBy { if (it.kind == BlueprintMark.Kind.Badge) 0 else 1 }.take(2)

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
        drawSummary(cards[i], look, cardBox[i * 4], cardBox[i * 4 + 1])
    }

    private fun farKey(i: Int, look: BlueprintCardLook): FarKey {
        val chosen = look.selected || cards[i].id == selected
        return FarKey(look.title, look.tone, chosen, if (chosen) null else glows.getOrNull(i) ?: look.outline, look.image, look.muted,
            if (look.face == BlueprintFace.Prose) proseBand() else -1)
    }

    private fun regionKey(i: Int, look: BlueprintCardLook) =
        RegionKey(look, round(log2(paintScale.coerceIn(.004, 4.0)) * 4).toInt(), look.selected || cards[i].id == selected, glows.getOrNull(i))

    private fun proseBand() = Blueprint.ProseBands.indexOfFirst { zoom >= it.from }.let { if (it < 0) Blueprint.ProseBands.lastIndex else it }

    override fun current(i: Int, slot: Int): Boolean =
        layers[i * 2 + slot] != null && keys[i * 2 + slot] == paints.getOrNull(i)?.key

    override fun record(i: Int, slot: Int, deadline: TimeSource.Monotonic.ValueTimeMark?) {
        val look = looks.getOrNull(i) ?: return
        val card = cards[i]
        val paint = paints.getOrNull(i) ?: return
        tile(i, slot, card.x, card.y, card.width, card.height, MARGIN) { x, y ->
            fun DrawScope.face(value: CardPaint, showTitle: Boolean = true) {
                when (value.detail.tier) {
                    PaintDetail.Tier.Summary -> drawSummary(card, look, x, y)
                    PaintDetail.Tier.Title -> drawWholeTitle(card, look, value.title, value.measured, x, y, showTitle)
                    PaintDetail.Tier.Rows -> when (look.face) {
                        BlueprintFace.Prose -> drawProse(card, look, x, y, value.measured.bodyHeight, showTitle)
                        BlueprintFace.Region -> drawRegion(card, look, regionKey(i, look), value.measured.region!!, x, y, showTitle, value.measured.pins)
                        else -> drawDetail(card, look, x, y, value.measured.bodyHeight, showTitle)
                    }
                }
            }
            fun DrawScope.weight(value: CardPaint, alpha: Float, showTitle: Boolean = true) {
                if (alpha <= 0f) return
                drawContext.canvas.saveLayer(Rect(x - MARGIN.toFloat(), y - MARGIN.toFloat(), x + card.width.toFloat() + MARGIN.toFloat(), y + card.height.toFloat() + MARGIN.toFloat()),
                    androidx.compose.ui.graphics.Paint().apply { this.alpha = alpha })
                try { face(value, showTitle) } finally { drawContext.canvas.restore() }
            }
            if (mapLevels?.full?.cards?.containsKey(card.id) == false) {
                drawSummary(card, look, x, y, showIcon = false)
                if (paint.pins) drawRows(card, look, x, y, paint.measured.pins)
            } else if (paint.previous == null) face(paint) else {
                drawSummary(card, look, x, y, showIcon = false)
                val previous = paint.previous
                val fromTitle = cardTitle(card, look, previous, x, y)
                val title = cardTitle(card, look, paint, x, y)
                weight(previous, 1f - paint.blend, false)
                weight(paint, paint.blend, false)
                val shown = title ?: fromTitle
                var titleBounds: Rect? = null
                if (shown != null) {
                    val from = fromTitle?.second ?: shown.second
                    val to = title?.second ?: shown.second
                    fun blend(a: Float, b: Float) = a + (b - a) * paint.blend
                    val bounds = rowSafeBounds(card, look, paint, Rect(blend(from.left, to.left), blend(from.top, to.top),
                        blend(from.right, to.right), blend(from.bottom, to.bottom)), y)
                    titleBounds = bounds
                    drawTitle(shown.first, bounds)
                }
                val fromFact = cardFact(card, look, previous, x, y)
                val fact = cardFact(card, look, paint, x, y)
                val shownFact = fact ?: fromFact
                if (shownFact != null) {
                    val from = fromFact?.second ?: shownFact.second
                    val to = fact?.second ?: shownFact.second
                    fun blend(a: Float, b: Float) = a + (b - a) * paint.blend
                    val bounds = rowSafeBounds(card, look, paint, Rect(blend(from.left, to.left), blend(from.top, to.top),
                        blend(from.right, to.right), blend(from.bottom, to.bottom)), y, fact = true)
                    if (titleBounds?.inflate(MachineSignal.Space.s1.value)?.overlaps(bounds) != true)
                        drawTitle(shownFact.first, bounds, backing = false)
                }
            }
        }
        layers[i * 2 + slot]?.alpha = cardOpacity[card.id] ?: 1f
        keys[i * 2 + slot] = paint.key
        reads[i * 2 + slot] = Read(paint.smallest, if (paint.visible.detail.tier == PaintDetail.Tier.Summary) BlueprintTitle.Hidden else BlueprintTitle.Whole)
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
        val lines = listOf(agentBadgeState(agent), agentBadgeTuple(agent)).filter(String::isNotBlank)
        val text = lines.sumOf { measure(it, style, room, 64).size.height.toDouble() }.toFloat() +
            MachineSignal.AgentBadge.gap.value * (lines.size - 1).coerceAtLeast(0)
        return max(MachineSignal.AgentBadge.size.value, text) + MachineSignal.AgentBadge.gap.value * 2
    }

    private fun measured(i: Int, look: BlueprintCardLook, zoom: Double, density: Double): CardPaint {
        val next = measureCard(i, look, zoom, density)
        if (next.detail.tier == PaintDetail.Tier.Summary) return next
        val measured = next.measured
        val edge = if (look.face == BlueprintFace.Region) density * 2.0.pow(-measured.band / RegionBands)
            else density / measured.unit
        val thresholds = listOf(edge) + measured.contents.map { 11.0 * density / it.smallest } +
            listOfNotNull(measured.rows?.takeIf { it.complete }?.let { 11.0 * density / it.smallest })
        for (threshold in thresholds.distinct().filter { it.isFinite() && it > 0 && it <= zoom && zoom < it * 1.12 }.sortedDescending()) {
            val previous = measureCard(i, look, threshold * (1.0 - .0000001), density)
            if (previous.key == next.key) continue
            val blend = round(PaintDetail.blend(zoom, threshold) * 32).toInt().coerceIn(0, 32)
            if (blend == 32) return next
            return next.copy(key = next.key.copy(previous = previous.key, blend = blend), previous = previous,
                blend = blend / 32f, smallest = min(next.smallest, previous.smallest))
        }
        return next
    }

    private fun regionBand(scale: Double) = ceil(log2(1.0 / scale.coerceIn(.004, 4.0)) * RegionBands).toInt()

    private fun measureCard(i: Int, look: BlueprintCardLook, zoom: Double, density: Double): CardPaint =
        measureCard(cards[i], look, zoom, density, paints.getOrNull(i))

    private fun measureCard(card: Card, look: BlueprintCardLook, zoom: Double, density: Double, previous: CardPaint? = null): CardPaint {
        val band = if (look.face == BlueprintFace.Region) regionBand(zoom / density) else ceil(log2((density / zoom).coerceAtLeast(.25)) * 4).toInt()
        val measured = listOfNotNull(previous?.measured, previous?.previous?.measured).firstOrNull {
            it.look == look && it.band == band && it.card.width == card.width && it.card.height == card.height && it.card.pins == card.pins
        } ?: run {
            val unit = 2.0.pow(band / if (look.face == BlueprintFace.Region) RegionBands else 4.0).toFloat()
            val inset = if (look.face == BlueprintFace.Region) MachineSignal.Space.s2.value * unit else 10f
            val badge = if (look.agent == null) 0f else (MachineSignal.AgentBadge.size.value + MachineSignal.AgentBadge.gap.value) * unit
            val glyph = MachineSignal.Editor.controlIconSize.value * unit + MachineSignal.Space.s2.value
            val actions = actionWidth(look, unit)
            val room = (card.width.toFloat() - inset * 2 - badge - glyph - actions).coerceAtLeast(0f)
            val height = (card.height.toFloat() - inset * 2).coerceAtLeast(0f)
            val sizes = if (look.face == BlueprintFace.Code) listOf(16f, 14f, 12.5f, 11f) else listOf(20f, 17f, 14f, 12.5f, 11f)
            val titles = sizes.map { size ->
                val font = size * unit
                val lines = floor(height / (font * ProseLeading)).toInt().coerceIn(1, if (look.agent == null) 64 else 2)
                measure(look.title, type.style(font.sp, colors.textStrong, mono = look.face == BlueprintFace.Code, weight = FontWeight.SemiBold)
                    .copy(lineHeight = (font * ProseLeading).sp), room, lines)
            }
            val contents = titles.mapIndexed { k, text -> PaintDetail.Content(text.size.width + inset * 2.0 + badge + glyph + actions,
                maxOf(text.size.height.toDouble(), if (look.agent == null) 0.0 else MachineSignal.AgentBadge.size.value * unit.toDouble(),
                    if (look.actions.isEmpty()) 0.0 else MachineSignal.Editor.controlHeight.value * unit.toDouble()) + inset * 2.0,
                min(sizes[k], if (look.actions.isEmpty()) Float.POSITIVE_INFINITY else MachineSignal.Editor.controlIconSize.value) * unit.toDouble(),
                !text.hasVisualOverflow && look.title.split(Regex("\\s+")).all { word ->
                    measurer.measure(word, text.layoutInput.style, maxLines = 1).size.width <= room
                }) }
            val agentHeight = look.agent?.let { agentHeight(it, card.width.toFloat()) } ?: 0f
            val bodyHeight = (card.height.toFloat() - agentHeight).coerceAtLeast(0f)
            val body = if (agentHeight == 0f) card else card.copy(height = bodyHeight.toDouble())
            val region = if (look.face == BlueprintFace.Region) regionMeasure(body, look, unit) else null
            val oneSided = card.pins.all { it.provides } || card.pins.none { it.provides }
            val pins = if (region == null) emptyList() else card.pins.mapIndexed { k, pin ->
                look.pins.getOrNull(k)?.let { measureRegionPin(pin.provides, it, card.width.toFloat() / if (oneSided) 1 else 2, unit.coerceAtLeast(1f)) }
            }
            val pinContent = pins.takeIf { it.isNotEmpty() }?.let { values ->
                PaintDetail.Content(card.width, bodyHeight.toDouble(), values.filterNotNull().minOfOrNull { it.smallest } ?: Double.POSITIVE_INFINITY,
                    values.withIndex().all { (k, row) ->
                        val pin = card.pins[k]
                        row != null && row.complete && pin.y >= row.height / 2 && pin.y + row.height / 2 <= bodyHeight &&
                            (0 until k).none { j -> card.pins[j].provides == pin.provides && values[j]?.let { other ->
                                kotlin.math.abs(card.pins[j].y - pin.y) < (other.height + row.height) / 2
                            } == true }
                    })
            }
            val content = region?.let {
                val heading = PaintDetail.Content(card.width, card.height, it.smallest.toDouble(), it.complete)
                if (mapLevels?.full?.cards?.containsKey(card.id) == false && pinContent != null) pinContent
                else pinContent?.let { ink -> heading.copy(smallest = min(heading.smallest, ink.smallest), complete = heading.complete && ink.complete) } ?: heading
            } ?: rowContent(body, look, 1.0)
            val rows = content?.let { if (look.agent == null) it else it.copy(height = card.height, smallest = min(it.smallest, MachineSignal.Type.body2.value.toDouble()), complete = it.complete && bodyHeight > 0) }
            CardMeasure(look, card, band, titles, contents, rows, region, inset + badge + glyph, room, bodyHeight, unit, pins)
        }
        fun PaintDetail.Content.onScreen() = copy(width = width * zoom, height = height * zoom, smallest = smallest * zoom)
        val rows = measured.rows?.onScreen()
        val detail = PaintDetail.choose(card.width * zoom, card.height * zoom, density, measured.contents.map { it.onScreen() }, rows)
        val title = measured.titles.getOrNull(detail.title)
        val smallest = when (detail.tier) {
            PaintDetail.Tier.Summary -> Float.POSITIVE_INFINITY
            PaintDetail.Tier.Title -> title?.layoutInput?.style?.fontSize?.value ?: Float.POSITIVE_INFINITY
            PaintDetail.Tier.Rows -> measured.rows?.smallest?.toFloat() ?: Float.POSITIVE_INFINITY
        }
        return CardPaint(PaintKey(look, detail.tier, detail.title, if (detail.tier == PaintDetail.Tier.Title || look.face == BlueprintFace.Region) band else 0), detail, title, smallest, measured)
    }

    private fun codePinWidth(card: Card, pin: dev.shibasis.reaktor.blueprint.Pin): Float =
        card.width.toFloat() / if (card.pins.any {
            it.provides != pin.provides && kotlin.math.abs(it.y - pin.y) < BlueprintEngine.RowHeight
        }) 2 else 1

    private fun rowContent(card: Card, look: BlueprintCardLook, zoom: Double): PaintDetail.Content? {
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
            BlueprintFace.Region -> return null
        }
        return PaintDetail.Content(card.width * zoom, card.height * zoom, smallest * zoom, fits && texts.none { it.hasVisualOverflow })
    }

    private fun DrawScope.drawSummary(card: Card, look: BlueprintCardLook, x: Float, y: Float, showIcon: Boolean = true) {
        val w = card.width.toFloat(); val h = card.height.toFloat()
        if (card.id in summaryFrames) {
            drawBorder(look.selected, look.outline, x, y, w, h)
            return
        }
        if (look.face == BlueprintFace.Code) drawRoundRect(lerp(colors.surface, look.tone, .06f), Offset(x, y), Size(w, h), CornerRadius(MachineSignal.Radius.node.value))
        else proseBody(look.tone, x, y, w, h, if (look.face == BlueprintFace.Region) RegionTint else CardTint)
        drawScreenshot(look.image, x, y, w, h, look.muted)
        val size = min(32f, min(w, h) / 3).coerceAtLeast(0f)
        if (showIcon) icon(look.icon).draw(this, x + (w - size) / 2, y + (h - size) / 2, size, look.tone)
        look.progress?.let { progress ->
            val inset = min(12f, w / 4)
            drawRoundRect(look.tone, Offset(x + inset, y + h - 8f), Size((w - inset * 2) * progress.coerceIn(0f, 1f), 3f), CornerRadius(1.5f))
        }
        drawBorder(look.selected, look.outline, x, y, w, h)
    }

    private fun wholeTitleBounds(card: Card, look: BlueprintCardLook, title: TextLayoutResult, measured: CardMeasure, x: Float, y: Float): Rect {
        val h = card.height.toFloat()
        val titleHeight = if (look.image == null) h else min(h, max(BlueprintEngine.HeaderHeight.toFloat(), title.size.height + MachineSignal.Space.s2.value * 2))
        val tx = x + measured.titleLeft + (measured.titleRoom - title.size.width) / 2
        val ty = y + (titleHeight - title.size.height) / 2
        return Rect(tx, ty, tx + title.size.width, ty + title.size.height)
    }

    private fun cardTitle(card: Card, look: BlueprintCardLook, value: CardPaint, x: Float, y: Float): Pair<TextLayoutResult, Rect>? {
        if (value.detail.tier == PaintDetail.Tier.Summary) return null
        if (value.detail.tier == PaintDetail.Tier.Title) return value.title?.let { it to wholeTitleBounds(card, look, it, value.measured, x, y) }
        val width = card.width.toFloat()
        val title = when (look.face) {
            BlueprintFace.Code -> {
                val marks = budgetMarks(look.marks).sumOf { measureMark(it).width.toDouble() + 6 }.toFloat()
                val text = measure(look.title, titleStyle, width - 36f - marks - actionWidth(look))
                RegionText(text, 28f, (26f - text.size.height) / 2)
            }
            BlueprintFace.Prose -> {
                val room = width - ProseInset - 12f - actionWidth(look)
                val text = proseTitle(card, look, room, value.measured.bodyHeight)
                    ?: words(look.title, proseTitles.last(), room, ProseLines.last())
                        ?.takeIf { it.size.height + 34f <= proseTitleBottom(card, look, value.measured.bodyHeight) }
                    ?: return null
                RegionText(text, ProseInset, 34f)
            }
            BlueprintFace.Region -> value.measured.region?.texts?.firstOrNull { it.title } ?: return null
        }
        return title.text to Rect(x + title.x, y + title.y, x + title.x + title.text.size.width, y + title.y + title.text.size.height)
    }

    private fun rowSafeBounds(card: Card, look: BlueprintCardLook, value: CardPaint, bounds: Rect, y: Float, fact: Boolean = false): Rect {
        if (look.face != BlueprintFace.Code || value.detail.tier != PaintDetail.Tier.Rows || value.blend <= 0f) return bounds
        val first = card.pins.minOfOrNull { y + it.y.toFloat() - BlueprintEngine.RowHeight.toFloat() / 2 } ?: return bounds
        val gap = MachineSignal.Space.s1.value
        val factHeight = if (fact) 0f else listOfNotNull(value, value.previous).maxOfOrNull {
            val unit = if (it.detail.tier == PaintDetail.Tier.Title) it.measured.unit.coerceAtLeast(1f) else 1f
            val style = subtitleStyles[if (look.subtitleMono) 1 else 0]
            measure("Ag", style.copy(fontSize = style.fontSize * unit, lineHeight = style.lineHeight * unit),
                card.width.toFloat() - 18f).size.height.toFloat()
        } ?: 0f
        val bottom = first - gap - if (factHeight > 0f) factHeight + gap else 0f
        return if (bounds.bottom > bottom) bounds.translate(0f, bottom - bounds.bottom) else bounds
    }

    private fun DrawScope.drawTitle(title: TextLayoutResult, bounds: Rect, backing: Boolean = true) {
        val scale = min(bounds.width / title.size.width.coerceAtLeast(1), bounds.height / title.size.height.coerceAtLeast(1))
        val width = title.size.width * scale
        val height = title.size.height * scale
        val left = bounds.left + (bounds.width - width) / 2
        val top = bounds.top + (bounds.height - height) / 2
        if (backing) drawRoundRect(colors.surface.copy(alpha = .96f), Offset(left - MachineSignal.Space.s1.value, top),
            Size(width + MachineSignal.Space.s2.value, height), CornerRadius(MachineSignal.Radius.node.value))
        withTransform({
            translate(left, top)
            scale(scale, scale, Offset.Zero)
        }) { paint(title, 0f, 0f) }
    }

    private fun DrawScope.drawWholeTitle(card: Card, look: BlueprintCardLook, title: TextLayoutResult?, measured: CardMeasure, x: Float, y: Float, showTitle: Boolean = true) {
        drawSummary(card, look, x, y, showIcon = false)
        title ?: return
        val w = card.width.toFloat(); val h = card.height.toFloat()
        if (showTitle) {
            drawTitle(title, wholeTitleBounds(card, look, title, measured, x, y))
            wholeTitleFact(card, look, title, measured)?.let { paint(it.text, x + it.x, y + it.y) }
        }
        val glyph = MachineSignal.Editor.controlIconSize.value * measured.unit
        icon(look.icon).draw(this, x + MachineSignal.Space.s2.value, y + MachineSignal.Space.s2.value, glyph, look.tone)
        var right = x + w - MachineSignal.Space.s2.value - actionWidth(look, measured.unit)
        for (mark in budgetMarks(look.marks).asReversed()) {
            val markSize = measureMark(mark)
            right -= markSize.width
            drawMark(mark, markSize, right, y + h - markSize.height - MachineSignal.Space.s2.value)
            right -= MachineSignal.Editor.controlGap.value
        }
    }

    private fun wholeTitleFact(card: Card, look: BlueprintCardLook, title: TextLayoutResult, measured: CardMeasure): RegionText? {
        if (look.face != BlueprintFace.Code || look.subtitle.isBlank() || look.image != null) return null
        val gap = MachineSignal.Space.s2.value * measured.unit
        val top = wholeTitleBounds(card, look, title, measured, 0f, 0f).bottom + gap
        val marks = budgetMarks(look.marks).maxOfOrNull { measureMark(it).height } ?: 0f
        val height = measured.bodyHeight - gap - marks - top
        val base = subtitleStyles[if (look.subtitleMono) 1 else 0]
        val style = base.copy(fontSize = base.fontSize * measured.unit, lineHeight = base.lineHeight * measured.unit)
        val font = style.fontSize.value
        val lines = floor(height / style.lineHeight.value).toInt().coerceAtMost(64)
        if (lines < 1 || measured.titleRoom <= 0f) return null
        val text = measure(look.subtitle, style, measured.titleRoom, lines)
        if (!PaintDetail.Content(text.size.width.toDouble(), text.size.height.toDouble(), font.toDouble(), !text.hasVisualOverflow)
                .fits(measured.titleRoom.toDouble(), height.toDouble(), 1.0 / zoom)) return null
        return RegionText(text, measured.titleLeft + (measured.titleRoom - text.size.width) / 2, top)
    }

    private fun cardFact(card: Card, look: BlueprintCardLook, value: CardPaint, x: Float, y: Float): Pair<TextLayoutResult, Rect>? {
        if (look.face != BlueprintFace.Code || look.subtitle.isBlank() || look.image != null || value.detail.tier == PaintDetail.Tier.Summary) return null
        val fact = if (value.detail.tier == PaintDetail.Tier.Title) {
            wholeTitleFact(card, look, value.title ?: return null, value.measured) ?: return null
        } else {
            val w = card.width.toFloat()
            val folded = look.folded?.let { measure(it, foldedStyle, w / 2) }
            val text = measure(look.subtitle, subtitleStyles[if (look.subtitleMono) 1 else 0], w - 18f - (folded?.let { it.size.width + 8f } ?: 0f))
            RegionText(text, 10f, 29f)
        }
        return fact.text to Rect(x + fact.x, y + fact.y, x + fact.x + fact.text.size.width, y + fact.y + fact.text.size.height)
    }

    private fun icon(vector: ImageVector) = icons.getOrPut(vector) { BlueprintIcon(vector) }

    private fun measure(value: String, style: TextStyle, width: Float, lines: Int = 1): TextLayoutResult =
        measurer.measure(value, style, TextOverflow.Ellipsis, maxLines = lines, constraints = Constraints(maxWidth = width.coerceIn(0f, 32760f).toInt()))

    private fun lead(title: String): String {
        val words = title.split(' ')
        val meaningful = words.indexOfFirst { it.trimEnd { c -> c in Joiners }.lowercase() !in Minor }.takeIf { it >= 0 } ?: words.lastIndex
        return words.take(meaningful + 1).joinToString(" ").trimEnd { it in Joiners }
    }

    private fun name(value: String, style: TextStyle, width: Float): TextLayoutResult {
        words(value, style, width)?.let { return it }
        val clipped = measurer.measure(value, style, TextOverflow.Clip, maxLines = 1,
            constraints = Constraints(maxWidth = width.coerceIn(0f, 32760f).toInt()))
        var shown = value.take(clipped.getLineEnd(0)).trimEnd()
        while (shown.isNotEmpty()) {
            measure("$shown…", style, width).takeUnless { it.hasVisualOverflow }?.let { return it }
            shown = shown.dropLast(1).trimEnd()
        }
        return measure("…", style, width)
    }

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

    private fun titled(value: String, shown: TextLayoutResult?) = when {
        shown == null -> BlueprintTitle.Hidden
        shown.layoutInput.text.text == value -> BlueprintTitle.Whole
        else -> BlueprintTitle.Words
    }

    private fun headerPath(x: Float, y: Float, w: Float) = Path().apply {
        addRoundRect(RoundRect(x, y, x + w, y + 26f, topLeftCornerRadius = CornerRadius(MachineSignal.Radius.node.value), topRightCornerRadius = CornerRadius(MachineSignal.Radius.node.value)))
    }

    private fun headerBrush(tone: Color, x: Float, w: Float) = Brush.horizontalGradient(listOf(lerp(colors.canvas.copy(alpha = .95f), tone, .04f), colors.canvas.copy(alpha = .95f)), x, x + w)

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

    private fun DrawScope.drawDetail(card: Card, look: BlueprintCardLook, x: Float, y: Float, bodyHeight: Float = card.height.toFloat(), showTitle: Boolean = true) {
        val w = card.width.toFloat(); val h = card.height.toFloat()
        drawRoundRect(if (look.image != null) colors.surface else colors.canvas.copy(alpha = .95f), Offset(x, y), Size(w, h), CornerRadius(MachineSignal.Radius.node.value))
        if (look.image == null) drawPath(headerPath(x, y, w), headerBrush(look.tone, x, w))
        if (look.choice == null) icon(look.icon).draw(this, x + 8f, y + 6f, 14f, look.tone)
        val marks = budgetMarks(look.marks).map(::measureMark)
        val titleWidth = w - 36f - marks.sumOf { it.width.toDouble() + 6 }.toFloat() - actionWidth(look)
        val title = measure(look.title, titleStyle, titleWidth)
        if (showTitle) paint(title, x + 28f, y + (26f - title.size.height) / 2)
        var at = x + 28f + max(titleWidth, 0f) + 6f
        budgetMarks(look.marks).forEachIndexed { k, mark -> drawMark(mark, marks[k], at, y + (26f - marks[k].height) / 2); at += marks[k].width + 6f }
        val folded = look.folded?.let { measure(it, foldedStyle, w / 2) }
        if (showTitle || look.image != null) paint(measure(look.subtitle, subtitleStyles[if (look.subtitleMono) 1 else 0], w - 18f - (folded?.let { it.size.width + 8f } ?: 0f)), x + 10f, y + 29f)
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

    private fun DrawScope.drawRows(card: Card, look: BlueprintCardLook, x: Float, y: Float, measured: List<PinMeasure?> = emptyList()) {
        val oneSided = card.pins.all { it.provides } || card.pins.none { it.provides }
        card.pins.forEachIndexed { k, pin -> look.pins.getOrNull(k)?.let { row ->
            val width = if (look.face == BlueprintFace.Code) codePinWidth(card, pin) else card.width.toFloat() / if (oneSided) 1 else 2
            drawPin(pin.provides, row, x + if (pin.provides) card.width.toFloat() - width else 0f, y + pin.y.toFloat(), width, measured.getOrNull(k))
        } }
    }

    private fun DrawScope.drawBorder(selected: Boolean, outline: Color?, x: Float, y: Float, w: Float, h: Float) {
        val border = when { selected -> 2f; outline != null -> 1.6f; else -> 1f }
        val edge = when { selected -> accent; outline != null -> outline; else -> colors.line.copy(alpha = .45f) }
        drawRoundRect(edge, Offset(x + border / 2, y + border / 2), Size(w - border, h - border), CornerRadius(MachineSignal.Radius.node.value - border / 2), style = Stroke(border))
    }

    private fun DrawScope.proseBody(tone: Color, x: Float, y: Float, w: Float, h: Float, tint: Float) {
        val outline = Path().apply { addRoundRect(RoundRect(x, y, x + w, y + h, CornerRadius(MachineSignal.Radius.node.value))) }
        drawPath(outline, lerp(colors.surface, tone, tint))
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

    private fun DrawScope.drawProse(card: Card, look: BlueprintCardLook, x: Float, y: Float, bodyHeight: Float = card.height.toFloat(), showTitle: Boolean = true): Read {
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
        if (showTitle) title?.let { paint(it, left, y + 34f) }
        if (look.subtitle.isNotEmpty()) words(look.subtitle, footerStyle, right - left)?.let { paint(it, left, y + bodyHeight - 28f) }
        drawRows(card, look, x, y)
        drawProgress(look, x, y, w, bodyHeight)
        drawBorder(look.selected, look.outline, x, y, w, h)
        val size = title?.let { it.layoutInput.style.fontSize.value } ?: Float.POSITIVE_INFINITY
        return Read(min(Blueprint.ProseMeta.toFloat(), size), titled(look.title, title))
    }

    private fun DrawScope.drawProseFar(card: Card, key: FarKey, x: Float, y: Float): Read {
        val w = card.width.toFloat(); val h = card.height.toFloat()
        val band = Blueprint.ProseBands[key.band]
        proseBody(key.tone, x, y, w, h, if (band.block) BlockTint else CardTint)
        if (band.size <= 0.0) { drawBorder(key.selected, key.outline, x, y, w, h); return Read(Float.POSITIVE_INFINITY, BlueprintTitle.Hidden) }
        val lines = ((h - 28f) / (band.size * ProseLeading)).toInt().coerceAtLeast(1)
        val room = w - ProseInset - 12f
        val title = words(key.title, proseFar[key.band], room, lines)
        title?.let { paint(it, x + ProseInset, y + 14f) }
        drawBorder(key.selected, key.outline, x, y, w, h)
        return Read(band.size.toFloat(), titled(key.title, title))
    }

    private fun regionMeasure(card: Card, look: BlueprintCardLook, unit: Float): RegionMeasure {
        val texts = mutableListOf<RegionText>()
        val badges = mutableListOf<RegionBadge>()
        var smallest = Float.POSITIVE_INFINITY
        fun result(complete: Boolean, bar: Float? = null) = RegionMeasure(unit, texts, badges, bar, smallest, complete)
        fun add(text: TextLayoutResult, x: Float, y: Float, title: Boolean = false) {
            texts += RegionText(text, x, y, title)
            if (text.layoutInput.text.isNotEmpty()) smallest = min(smallest, text.layoutInput.style.fontSize.value)
        }
        val left = (Stripe + RegionInset) * unit
        val right = card.width.toFloat() - RegionInset * unit - actionWidth(look, unit)
        val room = right - left
        val bottom = card.height.toFloat() - RegionInset * unit
        if (room < RegionMinimum * unit) return result(false)
        var top = RegionInset * unit
        var at = right
        var head = 0f
        val meta = (RegionMeta * unit).sp
        for (mark in budgetMarks(look.marks).asReversed()) {
            val text = measurer.measure(mark.text, type.style(meta, mark.color), maxLines = 1)
            val width = text.size.width + 10f * unit
            if (text.hasVisualOverflow || at - width < left + room / 2) return result(false)
            at -= width
            badges += RegionBadge(mark.color, at, top, width, text.size.height + 4f * unit)
            add(text, at + 5f * unit, top + 2f * unit)
            head = max(head, text.size.height + 4f * unit)
            at -= 6f * unit
        }
        look.eyebrow?.let { value ->
            val text = measure(value.uppercase(), type.style(meta, colors.textMuted, weight = FontWeight.SemiBold).copy(letterSpacing = (.6f * unit).sp), at - left)
            if (text.hasVisualOverflow) return result(false)
            add(text, left, top)
            head = max(head, text.size.height.toFloat())
        }
        if (head > 0f) top += head + 8f * unit
        val footer = (if (look.subtitle.isNotEmpty()) RegionSubtitle * 1.4f * unit else 0f) + (if (look.progress != null) 12f * unit else 0f)
        val title = RegionTitles.firstNotNullOfOrNull { size ->
            val lines = ((bottom - top - footer) / (size * RegionLeading * unit)).toInt().coerceIn(1, 3)
            measure(look.title, regionTitle(size * unit), room, lines).takeUnless { it.hasVisualOverflow || top + it.size.height > bottom - footer }
        } ?: return result(false)
        add(title, left, top, title = true)
        top += title.size.height + 6f * unit
        if (look.subtitle.isNotEmpty()) {
            val subtitle = measure(look.subtitle, type.style((RegionSubtitle * unit).sp, colors.textMuted), room)
            if (subtitle.hasVisualOverflow || top + subtitle.size.height > bottom) return result(false)
            add(subtitle, left, top)
            top += subtitle.size.height + 6f * unit
        }
        val bar = look.progress?.let { max(top, bottom - 4f * unit) }
        if (bar != null && bar + 4f * unit > card.height) return result(false)
        return result(true, bar)
    }

    private fun DrawScope.drawRegion(card: Card, look: BlueprintCardLook, key: RegionKey, measured: RegionMeasure, x: Float, y: Float, showTitle: Boolean = true, pins: List<PinMeasure?> = emptyList()): Read {
        val w = card.width.toFloat(); val h = card.height.toFloat()
        val unit = measured.unit
        val radius = MachineSignal.Radius.panel.value * unit
        val outline = Path().apply { addRoundRect(RoundRect(x, y, x + w, y + h, CornerRadius(radius))) }
        drawPath(outline, lerp(colors.surface, look.tone, RegionTint))
        clipPath(outline) { drawRect(look.tone, Offset(x, y), Size(Stripe * unit, h)) }
        val border = (if (key.chosen) 2.4f else if (key.glow != null || look.outline != null) 1.8f else 1.2f) * unit
        val edge = when { key.chosen -> accent; key.glow != null -> key.glow; else -> look.outline ?: look.tone.copy(alpha = .55f) }
        drawRoundRect(edge, Offset(x + border / 2, y + border / 2), Size(w - border, h - border), CornerRadius(radius - border / 2), style = Stroke(border))
        drawScreenshot(look.image, x, y, w, h, look.muted)
        icon(look.icon).draw(this, x + MachineSignal.Space.s1.value * unit, y + MachineSignal.Space.s2.value * unit, MachineSignal.Editor.smallIconSize.value * unit, look.tone)
        measured.badges.forEach { badge ->
            drawRoundRect(badge.color.copy(alpha = .18f), Offset(x + badge.x, y + badge.y), Size(badge.width, badge.height), CornerRadius(3f * unit))
        }
        measured.texts.forEach { text -> if (showTitle || !text.title) paint(text.text, x + text.x, y + text.y) }
        measured.bar?.let { top ->
            val left = x + (Stripe + RegionInset) * unit
            val room = w - (Stripe + RegionInset * 2) * unit
            drawRoundRect(colors.line.copy(alpha = .5f), Offset(left, y + top), Size(room, 4f * unit), CornerRadius(2f * unit))
            drawRoundRect(look.tone, Offset(left, y + top), Size(room * (look.progress ?: 0f).coerceIn(0f, 1f), 4f * unit), CornerRadius(2f * unit))
        }
        drawRows(card, look, x, y, pins)
        return Read(measured.smallest, BlueprintTitle.Whole)
    }

    private fun regionTitle(size: Float) = type.style(size.sp, colors.textStrong, weight = FontWeight.SemiBold).copy(lineHeight = (size * RegionLeading).sp)

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

    private fun measureRegionPin(provides: Boolean, pin: BlueprintPinLook, width: Float, unit: Float): PinMeasure {
        val gap = BlueprintEngine.RowHeight.toFloat() / 8
        val marks = pin.marks.map { it to measureMark(it) }
        val markWidth = marks.sumOf { it.second.width.toDouble() }.toFloat() + gap * marks.size
        val glyphSize = MachineSignal.Editor.smallIconSize.value
        val glyphWidth = if (pin.icon == null) 0f else glyphSize + MachineSignal.Editor.controlGap.value
        val room = width - 26f - markWidth - glyphWidth - if (pin.chip) 8f else 0f
        val style = type.style((MachineSignal.Type.data.value * unit).sp, if (pin.chip) colors.textStrong else pin.textColor, mono = true)
        val text = measure(pin.text, style, room.coerceAtLeast(BlueprintEngine.RowHeight.toFloat()))
        val box = text.size.width + if (pin.chip) 8f else 0f
        val left = if (provides) width - 20f - box - markWidth else 20f + glyphWidth
        val textLeft = left + if (pin.chip) 4f else 0f
        val smallest = min(style.fontSize.value.toDouble(), marks.mapNotNull { it.second.text?.layoutInput?.style?.fontSize?.value?.toDouble() }.minOrNull() ?: Double.POSITIVE_INFINITY)
        val height = maxOf(text.size.height.toFloat(), if (pin.icon == null) 0f else glyphSize,
            marks.maxOfOrNull { it.second.height } ?: 0f, if (pin.selected) BlueprintEngine.RowHeight.toFloat() - 2 else 8f)
        val complete = pin.text.isNotBlank() && !text.hasVisualOverflow && text.size.width <= room &&
            width >= 20f && left - glyphWidth >= 0f && left + box + markWidth <= width &&
            marks.all { (_, measured) -> measured.text?.hasVisualOverflow != true }
        return PinMeasure(text, marks, markWidth, glyphSize, glyphWidth, box, left, textLeft, smallest, height, complete)
    }

    private fun DrawScope.drawPin(provides: Boolean, pin: BlueprintPinLook, x: Float, middle: Float, width: Float, given: PinMeasure? = null) {
        if (pin.selected) {
            drawRoundRect(accent.copy(alpha = .16f), Offset(x + 3f, middle - BlueprintEngine.RowHeight.toFloat() / 2 + 1f), Size(width - 6f, BlueprintEngine.RowHeight.toFloat() - 2f), CornerRadius(4f))
            drawCircle(accent.copy(alpha = .35f), 7f, Offset(if (provides) x + width - 10f else x + 10f, middle))
        }
        val gap = BlueprintEngine.RowHeight.toFloat() / 8
        val marks = given?.marks ?: pin.marks.map { it to measureMark(it) }
        val markWidth = given?.markWidth ?: (marks.sumOf { it.second.width.toDouble() }.toFloat() + gap * marks.size)
        val glyphSize = given?.glyphSize ?: MachineSignal.Editor.smallIconSize.value
        val glyphWidth = given?.glyphWidth ?: if (pin.icon == null) 0f else glyphSize + MachineSignal.Editor.controlGap.value
        val text = given?.text ?: measure(pin.text, if (pin.chip) chipStyle else type.style(MachineSignal.Type.data, pin.textColor, mono = true), (width - pinTextMargin - markWidth - glyphWidth - if (pin.chip) 8f else 0f).coerceAtLeast(BlueprintEngine.RowHeight.toFloat()))
        val box = given?.box ?: (text.size.width + if (pin.chip) 8f else 0f)
        val dot = Offset(if (provides) x + width - 10f else x + 10f, middle)
        val left = if (given != null) x + given.left else if (provides) x + width - 20f - box - markWidth else x + 20f + glyphWidth
        pin.icon?.let { icon(it).draw(this, left - glyphWidth, middle - glyphSize / 2, glyphSize, pin.color) }
        if (pin.choice == null) {
            if (pin.filled) drawCircle(pin.color, 4f, dot)
            drawCircle(pin.color, 3.3f, dot, style = Stroke(1.4f))
        }
        val top = middle - text.size.height / 2f
        if (pin.chip) drawRoundRect(pin.color.copy(alpha = .2f), Offset(left, top), Size(box, text.size.height.toFloat()), CornerRadius(3f))
        paint(text, if (given != null) x + given.textLeft else left + if (pin.chip) 4f else 0f, top)
        var markLeft = left + box + gap
        marks.forEach { (mark, measured) ->
            drawMark(mark, measured, markLeft, middle - measured.height / 2)
            markLeft += measured.width + gap
        }
    }

    private fun DrawScope.drawFar(card: Card, key: FarKey, x: Float, y: Float) {
        val w = card.width.toFloat(); val h = card.height.toFloat()
        drawRoundRect(lerp(colors.surface, key.tone, .06f), Offset(x, y), Size(w, h), CornerRadius(MachineSignal.Radius.node.value))
        val border = if (key.selected) 2f else if (key.outline != null) 1.6f else 1f
        drawRoundRect(if (key.selected) accent else key.outline ?: colors.line.copy(alpha = .45f), Offset(x, y), Size(w, h), CornerRadius(MachineSignal.Radius.node.value), style = Stroke(border))
        drawScreenshot(key.image, x, y, w, h, key.muted)
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
        val text = measure(frame.label, headerStyles[0], min(BannerCap, (right - at) / scale))
        withTransform({ translate(at, y + (top - text.size.height * scale) / 2); scale(scale, scale, Offset.Zero) }) { paint(text, 0f, 0f) }
    }

    private companion object {
        const val MARGIN = 2.0
        const val Stripe = 5f
        const val ProseInset = 17f
        const val ProseLeading = 1.25
        const val CardTint = .05f
        const val BlockTint = .3f
        const val BannerCap = 320f
        const val RegionTint = .1f
        const val RegionBands = 32.0
        const val RegionInset = 14f
        const val RegionMinimum = 40f
        const val RegionMeta = 12f
        const val RegionSubtitle = 13f
        const val RegionLeading = 1.2f
        val RegionTitles = listOf(22f, 18f, 15f, 13f)
        val ProseLines = intArrayOf(3, 4, 5)
        const val Joiners = ",;:·–-"
        val Minor = setOf("a", "an", "the", "of", "to", "in", "on", "at", "by", "for", "and", "or", "with")
    }
}
