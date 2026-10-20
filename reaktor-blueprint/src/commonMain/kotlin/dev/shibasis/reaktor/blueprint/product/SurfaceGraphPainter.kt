package dev.shibasis.reaktor.blueprint.product

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.reaktor.blueprint.BlueprintLayout
import dev.shibasis.reaktor.blueprint.Card
import dev.shibasis.reaktor.blueprint.Frame
import dev.shibasis.reaktor.blueprint.Link
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal class SurfaceGraphSelection(val id: String?, val inGraph: Boolean, val owners: Set<String>, val cards: Set<String>, val keys: Set<String> = setOfNotNull(id))
internal class SurfaceGraphHighlight(val ids: Set<String>, val groups: Set<String>)

internal class SurfaceGraphCorridor(val ax: Float, val ay: Float, val cx: Float, val cy: Float, val bx: Float, val by: Float, val count: Int) {
    val width = min(2 + log2(count + 1.0) * 1.6, 12.0).toFloat()
    val left = min(ax, min(cx, bx)); val top = min(ay, min(cy, by)); val right = max(ax, max(cx, bx)); val bottom = max(ay, max(cy, by))
}

internal fun surfaceGraphCorridors(frames: List<Frame>, links: List<Link>, sources: List<SurfaceGraphLink>, nodes: List<SurfaceGraphNode>): List<SurfaceGraphCorridor> {
    val roots = frames.filter { it.parent == null }.associateBy { it.key }
    val parents = frames.associate { it.key to it.parent }
    val owner = buildMap<String, String> { nodes.forEach { node ->
        var scope = node.scope
        while (true) scope = parents[scope] ?: break
        put(node.id, scope); put(node.owner, scope)
        node.rows.forEach { row -> put(row.id, scope); row.aliases.forEach { put(it, scope) } }
    } }
    return links.indices.filter { links[it].cross }.groupBy { listOfNotNull(owner[sources[it].from], owner[sources[it].to]).sorted() }
        .mapNotNull { (pair, picked) ->
            if (pair.size != 2 || pair[0] == pair[1]) return@mapNotNull null
            val a = roots[pair[0]] ?: return@mapNotNull null; val b = roots[pair[1]] ?: return@mapNotNull null
            fun edge(frame: Frame, towards: Frame): Pair<Double, Double> {
                val cx = frame.x + frame.width / 2; val cy = frame.y + frame.height / 2
                val dx = towards.x + towards.width / 2 - cx; val dy = towards.y + towards.height / 2 - cy
                val scale = minOf(if (dx == 0.0) Double.POSITIVE_INFINITY else frame.width / 2 / abs(dx), if (dy == 0.0) Double.POSITIVE_INFINITY else frame.height / 2 / abs(dy), 1.0)
                return cx + dx * scale to cy + dy * scale
            }
            val start = edge(a, b); val end = edge(b, a)
            val control = (start.first + end.first) / 2 - (end.second - start.second) * .12 to (start.second + end.second) / 2 + (end.first - start.first) * .12
            SurfaceGraphCorridor(start.first.toFloat(), start.second.toFloat(), control.first.toFloat(), control.second.toFloat(), end.first.toFloat(), end.second.toFloat(), picked.sumOf { links[it].members.size })
        }.sortedByDescending { it.count }
}

private class SurfaceGraphRoutes(routes: List<Link>, sources: List<SurfaceGraphLink>, fallback: Color, owners: Map<String, String?>) {
    val count = routes.size
    val from = Array(count) { sources[it].from }
    val to = Array(count) { sources[it].to }
    val fromOwner = Array(count) { owners[from[it]] }
    val toOwner = Array(count) { owners[to[it]] }
    val start = IntArray(count + 1)
    val curve = BooleanArray(count) { routes[it].curve && routes[it].points.size >= 4 }
    val bounds = FloatArray(count * 4)
    val family = IntArray(count) { when (routes[it].family) { "chapter" -> CHAPTER; "prerequisite" -> PREREQUISITE; "optional" -> OPTIONAL; "related" -> RELATED; "draft" -> DRAFT; else -> OTHER } }
    val rgb = IntArray(count) { (sources[it].color ?: fallback).toArgb() }
    val cross = BooleanArray(count) { routes[it].cross }
    val active = BooleanArray(count)
    val points: FloatArray

    init {
        for (i in 0 until count) start[i + 1] = start[i] + routes[i].points.size * 2
        points = FloatArray(start[count])
        for (i in 0 until count) {
            var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY; var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
            routes[i].points.forEachIndexed { k, (x, y) ->
                val px = x.toFloat(); val py = y.toFloat()
                points[start[i] + k * 2] = px; points[start[i] + k * 2 + 1] = py
                l = min(l, px); t = min(t, py); r = max(r, px); b = max(b, py)
            }
            bounds[i * 4] = l; bounds[i * 4 + 1] = t; bounds[i * 4 + 2] = r; bounds[i * 4 + 3] = b
        }
    }

    companion object { const val CHAPTER = 0; const val PREREQUISITE = 1; const val OPTIONAL = 2; const val RELATED = 3; const val DRAFT = 4; const val OTHER = 5 }
}

private class SurfaceGraphCard(val node: SurfaceGraphNode, val x: Double, val y: Double, val width: Float, val height: Float, val pins: Array<Color?>, val shade: Color) {
    val selected = BooleanArray(2)
    val marks = arrayOfNulls<String>(2)
    var partial = false
    fun same(other: SurfaceGraphCard) = x == other.x && y == other.y && width == other.width && height == other.height && pins.contentEquals(other.pins) && node == other.node
}

internal class SurfaceGraphModel(val nodes: List<SurfaceGraphNode>, val links: List<SurfaceGraphLink>, val groups: List<SurfaceGraphGroup>, val layout: BlueprintLayout<*>,
    val byId: Map<String, SurfaceGraphNode>, val cards: List<Card>)

private class SurfaceGraphInput(val nodes: List<SurfaceGraphNode>, val links: List<SurfaceGraphLink>, val groups: List<SurfaceGraphGroup>, val layout: BlueprintLayout<*>) {
    fun same(model: SurfaceGraphModel?) = model != null && model.nodes === nodes && model.links === links && model.groups === groups && model.layout === layout
    fun same(other: SurfaceGraphInput?) = other != null && other.nodes === nodes && other.links === links && other.groups === groups && other.layout === layout
}

private class SurfaceGraphBanner(val label: TextLayoutResult, val tally: TextLayoutResult, val natural: Float)

private class SurfaceGraphPrepared(val model: SurfaceGraphModel, val corridors: List<SurfaceGraphCorridor>, val routes: SurfaceGraphRoutes,
    val cards: Array<SurfaceGraphCard>, val cardBox: FloatArray, val cardFill: IntArray, val cardEdge: IntArray, val rowCards: Map<String, List<Int>>, val banners: Array<SurfaceGraphBanner?>, val frameLooks: List<SurfaceGraphGroup>)

internal class SurfaceGraphPainter(graphics: GraphicsContext, private val measurer: TextMeasurer, private val appearance: SurfaceGraphAppearance, styles: List<TextStyle>) : SurfaceGraphEngine(graphics, 5, scaled = false) {
    val model = mutableStateOf<SurfaceGraphModel?>(null)
    private val dark = appearance.canvas.luminance() < .2f
    private val ink = if (dark) Color(0xFF0E1311) else Color(0xFF203D35)
    private val selectedArgb = appearance.selected.toArgb()
    private val gridRgb = appearance.text.toArgb()
    private val corridorRgb = (if (dark) Color(0xFF7E968A) else Color(0xFF6F8F7D)).toArgb()
    private val grid = if (dark) floatArrayOf(16f, .035f, 128f, .08f) else floatArrayOf(16f, .045f, 128f, .09f)
    private val glows = floatArrayOf(12f, .08f, 6f, .18f, 2f, .38f)
    private val titleStyle = styles[0].copy(letterSpacing = (-.065).sp)
    private val subtitleStyle = styles[1]
    private val smallStyle = styles[1].copy(fontSize = 9.5.sp)
    private val rowStyle = styles[2]
    private val doneStyle = styles[2].copy(color = styles[2].color.copy(alpha = .6f))
    private val farStyle = styles[3]
    private val domainStyle = styles[4]
    private val bannerStyle = styles[5]
    private val tallyStyle = styles[1].copy(fontSize = 11.sp)
    private val domainTallyStyle = styles[1].copy(fontSize = 11.sp, lineHeight = 15.95.sp)
    private val corridorStyle = styles[2].copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    private val frameTitleStyles = Array(4) { styles[5].copy(color = styles[5].color.copy(alpha = if (it % 2 == 0) 1f else .3f), fontSize = (if (it < 2) 13.0 else 16.9).sp, fontWeight = FontWeight.Bold) }
    private val frameDetailStyles = Array(4) { styles[1].copy(color = styles[1].color.copy(alpha = if (it % 2 == 0) 1f else .3f), fontSize = if (it < 2) styles[1].fontSize else 14.3.sp) }
    private val dashes = arrayOf(dash(6f, 4f), dash(2f, 4f), dash(5f, 4f))

    private var wanted: SurfaceGraphInput? = null
    private var slice = TimeSource.Monotonic.markNow()
    private var corridors: List<SurfaceGraphCorridor> = emptyList()
    private var routes = SurfaceGraphRoutes(emptyList(), emptyList(), appearance.selected, emptyMap())
    private var cards = emptyArray<SurfaceGraphCard>()
    private var cardFill = IntArray(0)
    private var cardEdge = IntArray(0)
    private var selectedCard = BooleanArray(0)
    private var dimCard = BooleanArray(0)
    private var markCard = arrayOfNulls<String>(0)
    private var rowCards: Map<String, List<Int>> = emptyMap()
    private var anyDim = false
    private var frames: List<Frame> = emptyList()
    private var frameLooks: List<SurfaceGraphGroup> = emptyList()
    private var frameBox = FloatArray(0)
    private var frameOpacity = FloatArray(0)
    private var frameLayers = arrayOfNulls<GraphicsLayer>(0)
    private var frameRecorded = FloatArray(0)
    private var banners = arrayOfNulls<GraphicsLayer>(0)
    private var bannerKeys = IntArray(0)
    private var bannerText = arrayOfNulls<SurfaceGraphBanner>(0)
    private var labels = arrayOfNulls<GraphicsLayer>(0)
    private var labelSizes = FloatArray(0)

    private var selection: SurfaceGraphSelection? = null
    private var activeFor: SurfaceGraphSelection? = null
    private var highlight: SurfaceGraphHighlight? = null
    private var look = -1
    private var flags = -1
    private var budget: TimeSource.Monotonic.ValueTimeMark? = null
    private var partial = false

    override val warmups = WARMUPS.size + 7
    override val warmable get() = cards.isNotEmpty() && appearance.canvas.alpha == 1f

    fun submit(nodes: List<SurfaceGraphNode>, links: List<SurfaceGraphLink>, groups: List<SurfaceGraphGroup>, layout: BlueprintLayout<*>) {
        val input = SurfaceGraphInput(nodes, links, groups, layout)
        if (input.same(wanted) || wanted == null && input.same(model.value)) return
        wanted = if (input.same(model.value)) null else input
        signal.trySend(Unit)
    }

    private suspend fun pause(input: SurfaceGraphInput): Boolean {
        if (slice.elapsedNow() >= SLICE) { withFrameNanos { }; slice = TimeSource.Monotonic.markNow() }
        return wanted === input
    }

    private suspend fun prepare(input: SurfaceGraphInput): SurfaceGraphPrepared? {
        slice = TimeSource.Monotonic.markNow()
        val nodes = input.nodes; val links = input.links; val layout = input.layout
        val byId = HashMap<String, SurfaceGraphNode>(nodes.size * 2)
        val owners = HashMap<String, String?>(); val colors = HashMap<String, Color?>()
        for (k in nodes.indices) {
            val node = nodes[k]
            byId[node.id] = node
            owners[node.id] = node.owner; owners[node.owner] = node.owner; colors[node.id] = node.color; colors[node.owner] = node.color
            for (row in node.rows) { owners[row.id] = node.owner; colors[row.id] = node.color; for (alias in row.aliases) { owners[alias] = node.owner; colors[alias] = node.color } }
            if (k % 32 == 31 && !pause(input)) return null
        }
        val rects = layout.cards.values.filter { it.id in byId }
        if (!pause(input)) return null
        val linkById = HashMap<String, SurfaceGraphLink>(links.size * 2); for (link in links) linkById[link.id] = link
        val routed = ArrayList<Link>(layout.links.size); val sources = ArrayList<SurfaceGraphLink>(layout.links.size)
        for (link in layout.links) link.members.firstNotNullOfOrNull { linkById[it] }?.let { routed += link; sources += it }
        val corridors = surfaceGraphCorridors(layout.frames, routed, sources, nodes)
        if (!pause(input)) return null
        val incoming = HashMap<String, Color?>(); val outgoing = HashSet<String>()
        for (link in links) { incoming[link.to] = colors[link.from]; outgoing += link.from }
        val previous = HashMap<String, SurfaceGraphCard>(cards.size * 2); for (card in cards) previous[card.node.id] = card
        val next = arrayOfNulls<SurfaceGraphCard>(rects.size)
        for (i in rects.indices) {
            if (i % 16 == 0 && !pause(input)) return null
            val rect = rects[i]; val node = byId.getValue(rect.id); val tone = node.color ?: appearance.selected
            val pins = arrayOfNulls<Color>(node.rows.size * 2)
            node.rows.forEachIndexed { k, row ->
                val aliases = row.aliases.ifEmpty { listOf(row.id) }
                if (row.input != null || aliases.any { it in incoming }) pins[k * 2] = (row.input ?: aliases.firstNotNullOfOrNull { incoming[it] } ?: tone).let { if (dark) it else mix(it, ink, .35f) }
                if (row.output != null || aliases.any { it in outgoing }) pins[k * 2 + 1] = (row.output ?: tone).let { if (dark) it else mix(it, ink, .35f) }
            }
            val fresh = SurfaceGraphCard(node, rect.x, rect.y, rect.width.toFloat(), rect.height.toFloat(), pins, mix(tone, ink, .6f))
            next[i] = previous[node.id]?.takeIf { it.same(fresh) } ?: fresh
        }
        val ready = next.requireNoNulls()
        val cardBox = FloatArray(ready.size * 4).also { box -> ready.forEachIndexed { i, card -> box[i * 4] = card.x.toFloat(); box[i * 4 + 1] = card.y.toFloat(); box[i * 4 + 2] = (card.x + card.width).toFloat(); box[i * 4 + 3] = (card.y + card.height).toFloat() } }
        val cardFill = IntArray(ready.size) { mix(appearance.canvas, ready[it].node.color ?: appearance.selected, .74f).toArgb() }
        val cardEdge = IntArray(ready.size) { (ready[it].node.activity ?: (ready[it].node.color ?: appearance.selected).copy(alpha = if (dark) .35f else .45f)).toArgb() }
        val rowCards = HashMap<String, MutableList<Int>>()
        for (i in ready.indices) {
            if (i % 32 == 0 && !pause(input)) return null
            for (pin in rects[i].pins) { rowCards.getOrPut(pin.id) { mutableListOf() }.add(i); for (alias in pin.aliases) rowCards.getOrPut(alias) { mutableListOf() }.add(i) }
        }
        val groups = input.groups.associateBy { it.key }
        val looks = layout.frames.map { groups[it.key] ?: SurfaceGraphGroup(it.key, it.label, muted = it.muted) }
        val banners = arrayOfNulls<SurfaceGraphBanner>(layout.frames.size * 2)
        for ((i, frame) in layout.frames.withIndex()) {
            if (frame.parent != null) continue
            banners[i * 2] = banner(looks[i], false); banners[i * 2 + 1] = banner(looks[i], true)
            if (!pause(input)) return null
        }
        if (!pause(input)) return null
        val routes = SurfaceGraphRoutes(routed, sources, appearance.selected, owners)
        return SurfaceGraphPrepared(SurfaceGraphModel(nodes, links, input.groups, layout, byId, rects), corridors, routes, ready, cardBox, cardFill, cardEdge, rowCards, banners, looks)
    }

    private fun show(prepared: SurfaceGraphPrepared) {
        val next = prepared.cards
        val previous = HashMap<SurfaceGraphCard, Int>(cards.size * 2); cards.forEachIndexed { i, card -> previous[card] = i }
        remap(IntArray(next.size) { previous[next[it]] ?: -1 })
        corridors = prepared.corridors; routes = prepared.routes
        cards = next; cardBox = prepared.cardBox; cardFill = prepared.cardFill; cardEdge = prepared.cardEdge; rowCards = prepared.rowCards
        selectedCard = BooleanArray(next.size); dimCard = BooleanArray(next.size); markCard = arrayOfNulls(next.size)
        frames = prepared.model.layout.frames; frameLooks = prepared.frameLooks
        frameBox = FloatArray(frames.size * 4).also { box -> frames.forEachIndexed { i, f -> box[i * 4] = f.x.toFloat(); box[i * 4 + 1] = f.y.toFloat(); box[i * 4 + 2] = (f.x + f.width).toFloat(); box[i * 4 + 3] = (f.y + f.height).toFloat() } }
        frameOpacity = FloatArray(frames.size) { 1f }
        frameLayers.forEach { it?.let(graphics::releaseGraphicsLayer) }; frameLayers = arrayOfNulls(frames.size); frameRecorded = FloatArray(frames.size)
        banners.forEach { it?.let(graphics::releaseGraphicsLayer) }; banners = arrayOfNulls(frames.size); bannerKeys = IntArray(frames.size) { Int.MIN_VALUE }; bannerText = prepared.banners
        labels.forEach { it?.let(graphics::releaseGraphicsLayer) }; labels = arrayOfNulls(min(10, corridors.size)); labelSizes = FloatArray(labels.size * 2)
        selection = null; activeFor = null; highlight = null
        dirty.fill(true)
        model.value = prepared.model
        version.intValue++
    }

    fun draw(scope: DrawScope, camera: Viewport, viewport: Size, density: Float, look: Int, selection: SurfaceGraphSelection, highlight: SurfaceGraphHighlight, resourceLinks: Boolean, chapterLinks: Boolean, areaCorridors: Boolean) = with(scope) {
        if (selection !== this@SurfaceGraphPainter.selection) applySelection(selection)
        if (highlight !== this@SurfaceGraphPainter.highlight) applyHighlight(highlight)
        val flags = (if (resourceLinks) 1 else 0) or (if (chapterLinks) 2 else 0) or (if (areaCorridors) 4 else 0)
        if (flags != this@SurfaceGraphPainter.flags) { this@SurfaceGraphPainter.flags = flags; dirty[WIRES] = true }
        if (look != this@SurfaceGraphPainter.look) { this@SurfaceGraphPainter.look = look; dirty[CARDS] = true; dirty[OVER] = true; changed = true }
        frame(camera, viewport, density)
    }

    override fun DrawScope.paint(camera: Viewport, deadline: TimeSource.Monotonic.ValueTimeMark) {
        drawRect(appearance.canvas, size = viewport)
        mesh(UNDER, camera)
        val l = (-camera.x / camera.zoom).toFloat(); val t = (-camera.y / camera.zoom).toFloat()
        val r = ((viewport.width - camera.x) / camera.zoom).toFloat(); val b = ((viewport.height - camera.y) / camera.zoom).toFloat()
        if (look == 2) world(camera) { for (i in frames.indices) if (inside(frameBox, i, l, t, r, b)) frameLayer(i, deadline)?.let { drawLayer(it) } }
        mesh(WIRES, camera)
        mesh(CARDS, camera)
        if (look > 0) world(camera) { drawCards(look - 1, l, t, r, b, deadline) }
        mesh(OVER, camera)
        mesh(LIT, camera)
        if (camera.zoom < .18 && flags and 4 != 0) drawLabels(camera)
        if (look < 2) drawBanners(camera, viewport)
    }

    override suspend fun load(): Boolean {
        val next = wanted ?: return false
        prepare(next)?.let { if (wanted === next) { wanted = null; show(it) } }
        return true
    }

    override fun DrawScope.warm(step: Int): Boolean {
        if (step < WARMUPS.size) {
            val i = cards.indices.maxBy { cards[it].node.rows.size + cards[it].node.badges.size }
            val slot = if (step < 3) 1 else 0
            if (!current(i, slot)) { record(i, slot, null); return false }
            prime(layers[i * 2 + slot]!!, WARMUPS[step])
            return true
        }
        when (step - WARMUPS.size) {
            0 -> if (frames.isNotEmpty()) frameLayer(0)?.let { prime(it, WARMUPS[0], .5f) }
            1 -> sample()
            2 -> frames.indices.firstOrNull { bannerText[it * 2] != null }?.let { i -> for (domain in 0..1) bannerText[i * 2 + domain]?.let { drawBanner(frameLooks[i], it, it.natural, domain == 1) } }
            3 -> if (labels.isNotEmpty()) drawLayer(labels[0] ?: labelLayer(0, corridors[0]))
            4 -> { drawContext.canvas.saveLayer(Rect(0f, 0f, 8f, 8f), dimPaint); drawRect(appearance.card, size = Size(8f, 8f)); drawRect(appearance.line, Offset(2f, 2f), Size(4f, 4f)); drawContext.canvas.restore() }
            5 -> clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, 8f, 8f, CornerRadius(3f))) }) { drawRect(appearance.card, size = Size(8f, 8f)) }
            else -> drawLine(appearance.line, Offset(0f, 4.3f), Offset(8f, 4.3f), 1f)
        }
        return true
    }

    override fun release() {
        super.release()
        (frameLayers + banners + labels).forEach { it?.let(graphics::releaseGraphicsLayer) }
        frameLayers.fill(null); banners.fill(null); labels.fill(null)
    }

    private fun applySelection(selection: SurfaceGraphSelection) {
        this.selection = selection
        for (i in cards.indices) { selectedCard[i] = cards[i].node.id in selection.cards; markCard[i] = null }
        selection.id?.let { id -> rowCards[id]?.forEach { markCard[it] = id } }
        dirty[WIRES] = true; dirty[CARDS] = true; dirty[OVER] = true; changed = true
    }

    private fun applyHighlight(highlight: SurfaceGraphHighlight) {
        this.highlight = highlight
        val any = highlight.ids.isNotEmpty()
        anyDim = false
        for (i in cards.indices) { val node = cards[i].node; dimCard[i] = any && node.id !in highlight.ids && node.owner !in highlight.ids; anyDim = anyDim || dimCard[i] }
        for (i in frames.indices) frameOpacity[i] = if (any && frames[i].key !in highlight.groups) .3f else 1f
        dirty[UNDER] = true; dirty[CARDS] = true; dirty[OVER] = true; changed = true
    }

    override fun tessellate(wired: Boolean) {
        val selection = selection ?: return
        val z = zoom.toFloat(); val fringe = 1f / density; val tolerance = .2f / density
        val l = wl / z; val t = wt / z; val r = wr / z; val b = wb / z
        if (dirty[UNDER]) {
            clear(false)
            if (appearance.grid) for (k in 0..1) {
                val spacing = grid[k * 2] * z
                val alpha = grid[k * 2 + 1] * ((spacing - 5) / 4).coerceIn(0f, 1f)
                if (alpha == 0f) continue
                var x = ceil(wl / spacing) * spacing - wl
                while (x <= wr - wl) { line(x, 0f, x, wb - wt, fringe, alpha); x += spacing }
                var y = ceil(wt / spacing) * spacing - wt
                while (y <= wb - wt) { line(0f, y, wr - wl, y, fringe, alpha); y += spacing }
            }
            for (i in frames.indices) {
                if (!inside(frameBox, i, l, t, r, b)) continue
                val look = frameLooks[i]; val nested = frames[i].parent != null; val opacity = frameOpacity[i]; val rgb = (look.color ?: appearance.selected).toArgb()
                val x0 = frameBox[i * 4] * z - wl; val y0 = frameBox[i * 4 + 1] * z - wt; val x1 = frameBox[i * 4 + 2] * z - wl; val y1 = frameBox[i * 4 + 3] * z - wt
                stroker.roundRect(x0, y0, x1, y1, (if (nested) 10f else 16f) * z, tolerance)
                stroker.fill(solid, fringe, surfaceGraphArgb(rgb, (if (nested) .11f else if (look.muted) .03f else if (dark) .07f else .06f) * opacity), min(x1 - x0, y1 - y0))
                stroker.outline(solid, .5f, fringe, rgb, (if (nested) .42f else if (dark) .32f else .28f) * opacity)
            }
            commit(UNDER, false)
        }
        if (dirty[WIRES]) { if (wired) wires(selection, z, fringe, tolerance) else pending = true }
        if (dirty[CARDS]) {
            clear(false)
            val radius = (if (look == 0) 6f else 10f) * z
            for (i in cards.indices) {
                if (!inside(cardBox, i, l, t, r, b)) continue
                val dim = if (dimCard[i]) .2f else 1f; val selected = selectedCard[i]
                val x0 = cardBox[i * 4] * z - wl; val y0 = cardBox[i * 4 + 1] * z - wt; val x1 = cardBox[i * 4 + 2] * z - wl; val y1 = cardBox[i * 4 + 3] * z - wt
                if (selected) for (k in 0..2) {
                    val inset = glows[k * 2]
                    stroker.roundRect(x0 - inset, y0 - inset, x1 + inset, y1 + inset, radius, tolerance)
                    stroker.fill(solid, fringe, surfaceGraphArgb(selectedArgb, glows[k * 2 + 1] * dim), min(x1 - x0, y1 - y0) + 2 * inset)
                }
                if (look != 0) continue
                stroker.roundRect(x0, y0, x1, y1, radius, tolerance)
                stroker.fill(solid, fringe, surfaceGraphArgb(if (selected) selectedArgb else cardFill[i], dim), min(x1 - x0, y1 - y0))
                val edge = if (selected) selectedArgb else cardEdge[i]
                stroker.outline(solid, (if (selected) 2f else if (cards[i].node.activity != null) 1.6f else z) / 2, fringe, edge, (edge ushr 24) / 255f * dim)
            }
            commit(CARDS, false)
        }
        if (dirty[OVER]) {
            clear(false)
            if (look != 0) for (i in cards.indices) {
                val selected = selectedCard[i]
                if (!selected && cards[i].node.activity == null || !inside(cardBox, i, l, t, r, b)) continue
                val x0 = cardBox[i * 4] * z - wl; val y0 = cardBox[i * 4 + 1] * z - wt; val x1 = cardBox[i * 4 + 2] * z - wl; val y1 = cardBox[i * 4 + 3] * z - wt
                val edge = if (selected) selectedArgb else cardEdge[i]
                stroker.roundRect(x0, y0, x1, y1, 10f * z, tolerance)
                stroker.outline(solid, if (selected) 1f else .8f, fringe, edge, (edge ushr 24) / 255f * if (dimCard[i]) .2f else 1f)
            }
            commit(OVER, false)
        }
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, fringe: Float, alpha: Float) {
        stroker.clear(); stroker.point(x0, y0); stroker.point(x1, y1)
        stroker.stroke(solid, fringe / 2, fringe, gridRgb, alpha)
    }

    private fun wires(selection: SurfaceGraphSelection, z: Float, fringe: Float, tolerance: Float) {
        val routes = routes
        if (activeFor !== selection) {
            activeFor = selection
            for (i in 0 until routes.count) routes.active[i] = routes.from[i] in selection.keys || routes.to[i] in selection.keys ||
                (selection.inGraph && (routes.fromOwner[i] in selection.owners || routes.toOwner[i] in selection.owners))
        }
        val lift = selection.id != null && !selection.inGraph
        clear(true)
        if (zoom < .18 && flags and 4 != 0) corridors.forEach { c ->
            if (c.right * z < wl || c.left * z > wr || c.bottom * z < wt || c.top * z > wb) return@forEach
            stroker.clear(); stroker.point(c.ax * z - wl, c.ay * z - wt)
            stroker.quad(c.cx * z - wl, c.cy * z - wt, c.bx * z - wl, c.by * z - wt, tolerance)
            stroker.stroke(solid, .5f + c.width * .18f, fringe, corridorRgb, .6f, round = true)
        }
        for (i in 0 until routes.count) if (!lift || !routes.active[i]) route(i, selection, z, fringe, tolerance)
        commit(WIRES, true)
        clear(true)
        if (lift) for (i in 0 until routes.count) if (routes.active[i]) route(i, selection, z, fringe, tolerance)
        commit(LIT, true)
    }

    private fun route(i: Int, selection: SurfaceGraphSelection, z: Float, fringe: Float, tolerance: Float) {
        val routes = routes
        val overview = zoom < .18; val distant = zoom < .42
        if (routes.bounds[i * 4 + 2] * z < wl || routes.bounds[i * 4] * z > wr || routes.bounds[i * 4 + 3] * z < wt || routes.bounds[i * 4 + 1] * z > wb) return
        val on = routes.active[i]; val kind = routes.family[i]; val chapter = kind == SurfaceGraphRoutes.CHAPTER
        if (!on && (overview && routes.cross[i] && flags and 4 != 0 || chapter && flags and 2 == 0 || !chapter && flags and 1 == 0)) return
        if (chapter && !on && (distant || routes.cross[i])) return
        val first = routes.start[i]; val end = routes.start[i + 1]
        if (end - first < 4) return
        val points = routes.points
        stroker.clear()
        if (overview && !on) { stroker.point(points[first] * z - wl, points[first + 1] * z - wt); stroker.point(points[end - 2] * z - wl, points[end - 1] * z - wt) }
        else if (routes.curve[i]) {
            stroker.point(points[first] * z - wl, points[first + 1] * z - wt)
            stroker.cubic(points[first + 2] * z - wl, points[first + 3] * z - wt, points[first + 4] * z - wl, points[first + 5] * z - wt, points[first + 6] * z - wl, points[first + 7] * z - wt, tolerance)
        } else stroker.corners(points, first, end, z, wl, wt, 7f, tolerance)
        val alpha = if (on) 1f else if (selection.id != null) .05f else if (chapter) .24f else if (routes.cross[i]) .3f else if (overview) .45f else .7f
        val rgb = routes.rgb[i]
        if (on) stroker.stroke(solid, 2.6f * 2.8f / 2, fringe, rgb, alpha * .35f)
        val dash = when (kind) { SurfaceGraphRoutes.OPTIONAL -> 0; SurfaceGraphRoutes.RELATED -> 1; SurfaceGraphRoutes.DRAFT -> 2; else -> -1 }
        stroker.stroke(if (dash < 0) solid else dashes[dash], (if (on) 2.6f else if (kind == SurfaceGraphRoutes.PREREQUISITE) 1.8f else 1.4f) / 2, fringe, rgb, alpha, dashed = dash >= 0)
        if (kind == SurfaceGraphRoutes.PREREQUISITE && (on || !overview)) {
            val ex = points[end - 2]; val ey = points[end - 1]
            arrow(ex, ey, atan2(ey - points[end - 3], ex - points[end - 4]), 8 / z, z, fringe, rgb, alpha, .5f)
        }
    }

    private fun DrawScope.drawCards(slot: Int, l: Float, t: Float, r: Float, b: Float, deadline: TimeSource.Monotonic.ValueTimeMark) {
        var open = false
        for (pass in 0..1) {
            if (pass == 1 && !anyDim) break
            for (i in cards.indices) {
                if (dimCard[i] != (pass == 1) || !inside(cardBox, i, l, t, r, b)) continue
                if (pass == 1 && !open) { drawContext.canvas.saveLayer(Rect(l, t, r, b), dimPaint); open = true }
                val layer = layer(i, slot, deadline, selectedCard[i]) ?: layers[i * 2 + 1 - slot]
                if (layer != null) drawLayer(layer)
                else drawRoundRect(if (slot == 0) cards[i].shade else appearance.card, Offset(cardBox[i * 4], cardBox[i * 4 + 1]), Size(cards[i].width, cards[i].height), CornerRadius(10f))
            }
        }
        if (open) drawContext.canvas.restore()
    }

    override fun current(i: Int, slot: Int): Boolean {
        val card = cards[i]
        return layers[i * 2 + slot] != null && card.selected[slot] == selectedCard[i] && (slot == 0 || card.marks[slot] == markCard[i] && !card.partial)
    }

    override fun record(i: Int, slot: Int, deadline: TimeSource.Monotonic.ValueTimeMark?) {
        val card = cards[i]
        val selected = selectedCard[i]; val mark = markCard[i]
        budget = deadline; partial = false
        tile(i, slot, card.x, card.y, card.width.toDouble(), card.height.toDouble()) { x, y -> drawCard(card, slot, selected, mark, x, y) }
        budget = null
        card.selected[slot] = selected; card.marks[slot] = mark
        if (slot == 1) card.partial = partial
        if (partial) pending = true
    }

    override fun slot(zoom: Double, ahead: Boolean) = if (!ahead) look - 1 else when {
        look == 0 && zoom >= .12 -> 0; look == 1 && zoom >= .32 -> 1; look == 2 && zoom < .55 -> 0; else -> -1
    }

    override fun headers(deadline: TimeSource.Monotonic.ValueTimeMark?, zoom: Double, l: Float, t: Float, r: Float, b: Float): Boolean {
        if (look != 2 && slot(zoom, true) != 1) return false
        for (i in frames.indices) {
            if (!inside(frameBox, i, l, t, r, b) || frameLayers[i] != null && frameRecorded[i] == frameOpacity[i]) continue
            if (deadline == null || deadline.hasPassedNow()) return true
            frameLayer(i)
        }
        return false
    }

    private fun frameLayer(i: Int, deadline: TimeSource.Monotonic.ValueTimeMark? = null): GraphicsLayer? {
        val opacity = frameOpacity[i]
        frameLayers[i]?.let { if (frameRecorded[i] == opacity) return it }
        if (deadline?.hasPassedNow() == true) { pending = true; return frameLayers[i] }
        val frame = frames[i]; val look = frameLooks[i]
        val layer = frameLayers[i] ?: graphics.createGraphicsLayer().also { frameLayers[i] = it }
        layer.place(frame.x, frame.y, frame.width, 40.0) { x, y -> drawFrameTitle(frame, look, opacity, x, y) }
        frameRecorded[i] = opacity
        return layer
    }

    private fun DrawScope.drawBanners(camera: Viewport, viewport: Size) {
        val domain = camera.zoom < .18
        for (i in frames.indices) {
            val frame = frames[i]
            if (frame.parent != null) continue
            val x = (camera.x + frame.x * camera.zoom).toFloat(); val y = (camera.y + frame.y * camera.zoom).toFloat()
            val width = (frame.width * camera.zoom).toFloat(); val height = (frame.height * camera.zoom).toFloat()
            if (x >= viewport.width || y >= viewport.height || x + width <= 0f || y + height <= 0f) continue
            val layer = bannerLayer(i, width, domain)
            clipRect(floor(x), floor(y), ceil(x + width), ceil(y + height)) { translate(x, y) { drawLayer(layer) } }
        }
    }

    private fun banner(look: SurfaceGraphGroup, domain: Boolean): SurfaceGraphBanner {
        val label = measurer.measure(look.title, if (domain) domainStyle else bannerStyle, maxLines = 1)
        val tally = measurer.measure(look.subtitle, if (domain) domainTallyStyle else tallyStyle, maxLines = 1)
        return SurfaceGraphBanner(label, tally, if (domain) max(label.size.width, tally.size.width) + 22f else label.size.width + tally.size.width + 28f)
    }

    private fun bannerLayer(i: Int, width: Float, domain: Boolean): GraphicsLayer {
        val banner = bannerText[i * 2 + if (domain) 1 else 0]!!
        val full = width >= banner.natural
        val key = if (full) (if (domain) -1 else -2) else (width / 4).toInt() * 2 + if (domain) 1 else 0
        banners[i]?.let { if (bannerKeys[i] == key) return it }
        val layer = banners[i] ?: graphics.createGraphicsLayer().also { banners[i] = it }
        val effective = if (full) banner.natural else max((width / 4).toInt() * 4f, 1f)
        layer.record(unit, LayoutDirection.Ltr, IntSize(ceil(effective).toInt() + 1, 56)) { drawBanner(frameLooks[i], banner, effective, domain) }
        bannerKeys[i] = key
        return layer
    }

    private fun DrawScope.drawLabels(camera: Viewport) {
        for (i in labels.indices) {
            val c = corridors[i]
            val layer = labels[i] ?: labelLayer(i, c)
            val x = (camera.x + (.25f * c.ax + .5f * c.cx + .25f * c.bx) * camera.zoom).toFloat(); val y = (camera.y + (.25f * c.ay + .5f * c.cy + .25f * c.by) * camera.zoom).toFloat()
            translate(x - labelSizes[i * 2] / 2, y - labelSizes[i * 2 + 1] / 2) { drawLayer(layer) }
        }
    }

    private fun labelLayer(i: Int, c: SurfaceGraphCorridor): GraphicsLayer {
        val label = measurer.measure("×${c.count}", corridorStyle)
        val box = Size(label.size.width + 12f, label.size.height + 2f)
        labelSizes[i * 2] = box.width; labelSizes[i * 2 + 1] = box.height
        return graphics.createGraphicsLayer().also { layer ->
            labels[i] = layer
            layer.record(unit, LayoutDirection.Ltr, IntSize(ceil(box.width).toInt() + 1, ceil(box.height).toInt() + 1)) {
                drawRoundRect(appearance.card.copy(alpha = .94f), Offset.Zero, box, CornerRadius(99f))
                drawRoundRect(Color(corridorRgb).copy(alpha = .55f), Offset.Zero, box, CornerRadius(99f), style = Stroke(1f))
                drawText(label, topLeft = Offset(6f, 1f))
            }
        }
    }

    private fun DrawScope.text(value: String, style: TextStyle, x: Float, y: Float, width: Float, lines: Int = 1) {
        if (width < 1) return
        paint(measurer.measure(value, style, maxLines = lines, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = width.toInt().coerceAtLeast(1))), x, y)
    }

    private fun DrawScope.mark(mark: SurfaceGraphMark, x: Float, y: Float, color: Color) = when (mark) {
        SurfaceGraphMark.Done -> drawPath(Path().apply { moveTo(x, y + 4.2f); lineTo(x + 2.8f, y + 7f); lineTo(x + 8f, y + .8f) }, color, style = Stroke(1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        SurfaceGraphMark.Session -> drawPath(Path().apply { moveTo(x + 1f, y); lineTo(x + 7.5f, y + 3.75f); lineTo(x + 1f, y + 7.5f); close() }, color)
        SurfaceGraphMark.Note -> drawPath(Path().apply { moveTo(x, y + 8f); lineTo(x + .7f, y + 5.4f); lineTo(x + 5.6f, y + .5f); lineTo(x + 7.5f, y + 2.4f); lineTo(x + 2.6f, y + 7.3f); close() }, color)
    }

    private fun badgeStyle(badge: SurfaceGraphBadge, row: Boolean) = rowStyle.copy(color = badge.foreground, fontSize = (if (row) if (badge.rounded) 8.5 else 8.0 else 9.0).sp,
        fontWeight = if (badge.rounded) FontWeight.Bold else FontWeight.SemiBold, letterSpacing = if (badge.rounded) .255.sp else 0.sp)

    private fun labels(badges: List<SurfaceGraphBadge>, row: Boolean): List<TextLayoutResult> = if (badges.isEmpty()) emptyList() else badges.map { measurer.measure(it.text, badgeStyle(it, row), maxLines = 1) }

    private fun space(labels: List<TextLayoutResult>) = if (labels.isEmpty()) 0f else labels.sumOf { it.size.width + 13 } - 3f

    private fun DrawScope.badges(badges: List<SurfaceGraphBadge>, labels: List<TextLayoutResult>, x: Float, y: Float, row: Boolean) {
        var at = x
        badges.forEachIndexed { i, badge ->
            val label = labels[i]
            val height = if (row) if (badge.rounded) 14f else 13f else 15f
            drawRoundRect(badge.background, Offset(at, y), Size(label.size.width + 10f, height), CornerRadius(if (badge.rounded) 99f else 6f))
            paint(label, at + 5, y + (height - label.size.height) / 2)
            at += label.size.width + 13
        }
    }

    private fun DrawScope.drawCard(card: SurfaceGraphCard, slot: Int, selected: Boolean, mark: String?, x: Float, y: Float) {
        val n = card.node; val w = card.width; val h = card.height; val tone = n.color ?: appearance.selected
        val radius = CornerRadius(10f); val corner = Offset(x, y); val size = Size(w, h)
        if (slot == 0) {
            drawRoundRect(Brush.horizontalGradient(listOf(mix(tone, ink, .5f), mix(tone, ink, .7f)), x, x + w), corner, size, radius)
            text(n.farTitle, farStyle, x + 19, y + 15, w - 38, 3)
        } else {
            drawRoundRect(appearance.card, corner, size, radius)
            drawPath(Path().apply { addRoundRect(RoundRect(x, y, x + w, y + 26f, radius, radius)) },
                Brush.horizontalGradient(listOf(mix(tone, ink, if (n.symbol == "mission") .35f else if (dark) .4f else .55f), mix(tone, ink, if (n.symbol == "mission") .7f else if (dark) .8f else .85f)), x, x + w))
            withTransform({ translate(x + 8.5f, y + 6.5f); scale(13f / 16, 13f / 16, Offset.Zero) }) {
                val white = Color.White.copy(alpha = .92f)
                when (n.symbol) {
                    "course" -> { drawRoundRect(white, Offset(1.5f, 2.5f), Size(13f, 11f), CornerRadius(2.5f), style = Stroke(1.6f)); drawPath(Path().apply { moveTo(6.5f, 5.6f); lineTo(6.5f, 10.4f); lineTo(10.6f, 8f); close() }, white) }
                    "mission" -> { drawCircle(white, 6f, Offset(8f, 8f), style = Stroke(1.6f)); drawCircle(white, 2.4f, Offset(8f, 8f)) }
                    else -> drawPath(Path().apply { moveTo(8f, 3.6f); cubicTo(6.4f, 2.6f, 4.3f, 2.3f, 1.8f, 2.6f); lineTo(1.8f, 12.6f); cubicTo(4.3f, 12.3f, 6.4f, 12.6f, 8f, 13.6f); cubicTo(9.6f, 12.6f, 11.7f, 12.3f, 14.2f, 12.6f); lineTo(14.2f, 2.6f); cubicTo(11.7f, 2.3f, 9.6f, 2.6f, 8f, 3.6f); moveTo(8f, 3.6f); lineTo(8f, 13.6f) }, white, style = Stroke(1.5f, join = StrokeJoin.Round))
                }
            }
            val titleBadges = labels(n.badges, false)
            val badgeSpace = space(titleBadges)
            text(n.title, titleStyle, x + 28, y + 4, w - 36 - if (n.badges.isEmpty()) 0f else badgeSpace + 6)
            badges(n.badges, titleBadges, max(x + 34, x + w - 8 - badgeSpace), y + 5.5f, false)
            val counts = n.counts.map { measurer.measure(it.text, smallStyle, maxLines = 1) }
            val countsWidth = if (counts.isEmpty()) 0f else counts.sumOf { it.size.width + 9 } + n.counts.count { it.mark != null } * 10f + 3f
            text(n.subtitle, subtitleStyle, x + 10, y + 28, w - (if (n.folded > 0) 90 else 20) - countsWidth)
            var at = x + w - (if (n.folded > 0) 90 else 8) - countsWidth
            n.counts.forEachIndexed { i, count ->
                count.mark?.let { mark(it, at, y + 31.5f, smallStyle.color); at += 10f }
                paint(counts[i], at, y + 29); at += counts[i].size.width + 9
            }
            if (n.folded > 0) text("${if (n.rows.isNotEmpty()) "+" else ""}${n.folded} ${n.foldedLabel}", smallStyle, x + w - 82, y + 29, 74f)
            if (n.progress > 0f) {
                val color = n.activity ?: if (dark) Color(0xFF4BD08B) else Color(0xFF2E8B62)
                drawRect(color.copy(alpha = .18f), Offset(x, y + 26), Size(w, 2f)); drawRect(color, Offset(x, y + 26), Size(w * n.progress.coerceIn(0f, 1f), 2f))
            }
            for (i in n.rows.indices) {
                if (budget?.hasPassedNow() == true) { partial = true; break }
                val row = n.rows[i]; val rowY = y + 46 + i * 18
                if (mark != null && (row.id == mark || mark in row.aliases)) drawRect(tone.copy(alpha = .12f), Offset(x, rowY), Size(w, 18f))
                row.background?.let { drawRect(it, Offset(x, rowY), Size(w, 18f)) }
                row.marker?.let { drawRect(it, Offset(x, rowY), Size(3f, 18f)) }
                val marks = labels(row.badges, true)
                val marksWidth = space(marks)
                if (row.completed) mark(SurfaceGraphMark.Done, x + 20, rowY + 5f, doneStyle.color)
                text(row.title, if (row.completed) doneStyle else rowStyle, x + if (row.completed) 31 else 20, rowY, w - (if (row.completed) 51 else 40) - if (row.badges.isEmpty()) 0f else marksWidth + 4)
                badges(row.badges, marks, x + w - 20 - marksWidth, rowY + 2, true)
                card.pins[i * 2]?.let { drawCircle(it, 4f, Offset(x + 10, rowY + 9)) }
                card.pins[i * 2 + 1]?.let { drawCircle(it, 4f, Offset(x + w - 10, rowY + 9)) }
            }
        }
        if (!selected && n.activity == null) drawRoundRect(tone.copy(alpha = if (dark) .35f else .45f), corner, size, radius, style = Stroke(1f))
    }

    private fun DrawScope.drawFrameTitle(frame: Frame, look: SurfaceGraphGroup, opacity: Float, x: Float, y: Float) {
        val tone = look.color ?: appearance.selected
        val w = frame.width.toFloat()
        val nested = frame.parent != null
        val k = (if (nested) 0 else 2) + if (opacity < 1f) 1 else 0
        drawRect(Brush.verticalGradient(listOf(tone.copy(alpha = (if (dark) .22f else .2f) * opacity), tone.copy(alpha = (if (dark) .08f else .07f) * opacity)), y, y + 40), Offset(x, y), Size(w, 40f))
        if (nested) {
            text(look.title, frameTitleStyles[k], x + 10, y + 4, w - 20)
            text(look.subtitle, frameDetailStyles[k], x + 10, y + 21, w - 20)
            return
        }
        drawCircle(tone.copy(alpha = opacity), 4.5f, Offset(x + 16.5f, y + 20))
        val title = measurer.measure(look.title, frameTitleStyles[k], maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = (w - 40).toInt().coerceAtLeast(1)))
        paint(title, x + 28, y + (40 - title.size.height) / 2)
        text(look.subtitle, frameDetailStyles[k], x + 36 + title.size.width, y + 10, w - 48 - title.size.width)
    }

    private fun DrawScope.drawBanner(look: SurfaceGraphGroup, banner: SurfaceGraphBanner, width: Float, domain: Boolean) {
        val full = width >= banner.natural
        val label = if (full) banner.label else measurer.measure(look.title, if (domain) domainStyle else bannerStyle, overflow = if (width < 50) TextOverflow.Clip else TextOverflow.Ellipsis, maxLines = 1,
            constraints = Constraints(maxWidth = (width - 22).toInt().coerceAtLeast(1)))
        val tally = banner.tally.size.width
        val bannerWidth = min(width, if (domain) max(label.size.width, tally) + 22f else label.size.width + tally + 28f)
        val bannerHeight = if (domain) 55f else 30f
        drawPath(Path().apply { addRoundRect(RoundRect(0f, 0f, bannerWidth, bannerHeight, bottomRightCornerRadius = CornerRadius(10f))) }, appearance.canvas.copy(alpha = .88f))
        drawRect(look.color ?: appearance.selected, Offset.Zero, Size(3f, bannerHeight))
        drawText(label, topLeft = Offset(11f, if (domain) 7f else 5f))
        val x = if (domain) 11f else label.size.width + 18f; val y = if (domain) 32f else 5f
        if (full) paint(banner.tally, x, y) else text(look.subtitle, if (domain) domainTallyStyle else tallyStyle, x, y, if (domain) bannerWidth - 22 else bannerWidth - label.size.width - 28)
    }

    private companion object { const val UNDER = 0; const val WIRES = 1; const val CARDS = 2; const val OVER = 3; const val LIT = 4; val SLICE = 2.milliseconds }
}
