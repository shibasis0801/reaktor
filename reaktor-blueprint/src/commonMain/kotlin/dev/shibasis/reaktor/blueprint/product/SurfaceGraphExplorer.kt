package dev.shibasis.reaktor.blueprint.product

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import dev.shibasis.composeflow.model.*
import dev.shibasis.reaktor.blueprint.BlueprintEngine
import dev.shibasis.reaktor.blueprint.BlueprintLayout
import dev.shibasis.reaktor.blueprint.Bounds
import dev.shibasis.reaktor.blueprint.Card
import dev.shibasis.reaktor.blueprint.Frame
import dev.shibasis.reaktor.blueprint.Link
import dev.shibasis.reaktor.blueprint.LinkKind
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.blueprint.canvas.GraphLevels
import dev.shibasis.reaktor.blueprint.canvas.GraphMarkers
import dev.shibasis.reaktor.blueprint.canvas.GraphView
import dev.shibasis.reaktor.blueprint.canvas.at
import dev.shibasis.reaktor.blueprint.editor.GraphEditor
import dev.shibasis.reaktor.blueprint.editor.GraphEditorLayer
import kotlin.math.*

fun expandedSurfaceGraphPositions(nodes: List<SurfaceGraphNode>, width: Double = 220.0, height: Double = 88.0): Map<String, XYPosition> {
    val groups = nodes.groupBy { it.scope }.entries.sortedBy { it.key }
    data class Group(val nodes: List<SurfaceGraphNode>, val columns: Int, val width: Double, val height: Double)
    val boxes = groups.map { (_, group) ->
        val columns = ceil(sqrt(group.size.toDouble())).toInt().coerceAtLeast(1)
        Group(group.sortedBy { it.id }, columns, columns * (width + 24) + 40, ceil(group.size.toDouble() / columns) * (height + 24) + 64)
    }
    val target = sqrt(boxes.sumOf { it.width * it.height }) * 1.3
    val out = linkedMapOf<String, XYPosition>()
    var x = 0.0; var y = 0.0; var rowHeight = 0.0
    boxes.forEach { box ->
        if (x > 0 && x + box.width > target) { x = 0.0; y += rowHeight + 80; rowHeight = 0.0 }
        box.nodes.forEachIndexed { index, node -> out[node.id] = XYPosition(x + 20 + (index % box.columns) * (width + 24), y + 44 + (index / box.columns) * (height + 24)) }
        x += box.width + 80; rowHeight = maxOf(rowHeight, box.height)
    }
    return out
}

@Stable
class SurfaceGraphExplorerState : GraphCanvasState(0.03..3.0, GraphLevels(.2, .16, .45, .39)) {
    internal var cards: List<Card> = emptyList()
    internal var frames: List<Frame> = emptyList()
    private var placed: List<Card>? = null
    var generation by mutableIntStateOf(0)
        private set
    var showing: List<SurfaceGraphNode>? = null
        private set
    init { insetTop = 48.0 }
    fun fit(ids: Set<String> = emptySet()) {
        val boxes: List<Bounds> = if (ids.isEmpty()) frames.ifEmpty { cards } else cards.filter { it.id in ids }
        fit(boxes, maxZoom = if (ids.isEmpty()) 1.0 else 1.4, minZoom = .005)
    }
    fun centre(id: String, row: Int? = null, level: Double? = null) {
        val (x, y, width, height) = target(id, row) ?: return
        if (level == null) centre(x, y, width, height) else look(x + width / 2, y + height / 2, level)
    }
    fun reveal(id: String, row: Int? = null, readable: Boolean = false) {
        val (x, y, width, height) = target(id, row) ?: return
        if (readable && zoom < .45) centre(id, row) else reveal(x, y, width, height)
    }
    private fun target(id: String, row: Int?) = cards.firstOrNull { it.id == id }?.let { card ->
        val pin = row?.let { card.pins.getOrNull(it) }
        if (pin == null) doubleArrayOf(card.x, card.y, card.width, card.height)
        else doubleArrayOf(card.x, card.y + pin.y - BlueprintEngine.RowHeight / 2, card.width, BlueprintEngine.RowHeight)
    }
    internal fun place(geometry: List<Card>, shown: List<SurfaceGraphNode>? = null, key: Any? = null) {
        cards = geometry
        extent = frames.ifEmpty { cards }
        if (flow.canvasSize == IntSize.Zero) return
        if (placed !== geometry) { placed = geometry; generation++ }
        fitOnce(key ?: generation) { fit() }
        showing = shown
    }
    override fun overview() = fit()
}

@Composable
fun SurfaceGraphExplorer(nodes: List<SurfaceGraphNode>, links: List<SurfaceGraphLink>, selectedId: String?, appearance: SurfaceGraphAppearance,
    state: SurfaceGraphExplorerState, onSelect: (String) -> Unit, modifier: Modifier = Modifier, layout: BlueprintLayout<*>? = null, resourceLinks: Boolean = true, chapterLinks: Boolean = true, areaCorridors: Boolean = true, highlighted: Set<String> = emptySet(), onClear: () -> Unit = {}, key: Any? = null, groups: List<SurfaceGraphGroup> = emptyList(), editor: GraphEditor? = null) {
    val fallback = remember(nodes, links, appearance, layout != null) {
        if (layout != null) return@remember null
        val positions = expandedSurfaceGraphPositions(nodes, appearance.width, appearance.height)
        val cards = LinkedHashMap<String, Card>(nodes.size * 2)
        for (n in nodes) { val p = positions.getValue(n.id); cards[n.id] = Card(n.id, p.x, p.y, appearance.width, appearance.height, n.pins(), n.folded) }
        val scopes = nodes.groupBy { it.scope }
        val frames = scopes.map { (scope, children) ->
            val rects = children.map { cards.getValue(it.id) }
            val x = rects.minOf { it.x } - 18; val y = rects.minOf { it.y } - 40
            Frame(scope, scope, x, y, rects.maxOf { it.x + it.width } - x + 18, rects.maxOf { it.y + it.height } - y + 18, children.mapTo(LinkedHashSet()) { it.id }, emptyList(), false)
        }
        val routes = links.mapNotNull { edge -> val f = positions[edge.from]; val t = positions[edge.to]
            if (f == null || t == null) null else Link(edge.id, edge.from, edge.to, null, null, LinkKind.Wire, listOf(edge.id), listOf(f.x + appearance.width to f.y + 23, t.x to t.y + 23), family = "related") }
        BlueprintLayout(Unit, cards, frames, routes, frames.maxOfOrNull { it.x + it.width } ?: 0.0, frames.maxOfOrNull { it.y + it.height } ?: 0.0) to
            scopes.map { (scope, children) -> SurfaceGraphGroup(scope, scope, color = children.first().color) }
    }
    val scene = layout ?: fallback!!.first
    val looks = fallback?.second ?: groups
    val density = LocalDensity.current.density.toDouble()
    val resolver = LocalFontFamilyResolver.current
    val direction = LocalLayoutDirection.current
    val graphics = LocalGraphicsContext.current
    val measurer = remember(resolver, direction) { TextMeasurer(resolver, Density(1f), direction, cacheSize = 2048) }
    val painter = remember(graphics, measurer, appearance) {
        val font = appearance.fontFamily
        SurfaceGraphPainter(graphics, measurer, appearance, listOf(
            TextStyle(color = Color.White, fontFamily = font, letterSpacing = 0.sp, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.85.sp),
            TextStyle(color = appearance.muted, fontFamily = font, letterSpacing = 0.sp, fontSize = 10.5.sp, fontWeight = FontWeight.Normal),
            TextStyle(color = appearance.text, fontFamily = font, letterSpacing = 0.sp, fontSize = 11.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
            TextStyle(color = Color.White, fontFamily = font, letterSpacing = 0.sp, fontSize = 44.sp, fontWeight = FontWeight.SemiBold, lineHeight = 50.6.sp),
            TextStyle(color = appearance.text, fontFamily = appearance.titleFontFamily, letterSpacing = 0.sp, fontSize = 20.sp, fontWeight = FontWeight.Normal, lineHeight = 23.sp),
            TextStyle(color = appearance.text, fontFamily = font, letterSpacing = 0.sp, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        ).map { it.copy(platformStyle = appearance.textPlatformStyle) })
    }
    SideEffect { painter.submit(nodes, links, looks, scene) }
    val model = painter.model.value
    val selection = remember(model, selectedId) {
        val selected = model?.nodes.orEmpty().filter { it.id == selectedId || it.owner == selectedId || it.rows.any { row -> row.id == selectedId || selectedId in row.aliases } }
        val keys = buildSet { selectedId?.let(::add); selected.forEach { node -> node.rows.forEach { row -> if (row.id == selectedId || selectedId in row.aliases) { add(row.id); addAll(row.aliases) } } } }
        SurfaceGraphSelection(selectedId, model != null && selectedId in model.byId, selected.mapTo(mutableSetOf()) { it.owner }, selected.mapTo(mutableSetOf()) { it.id }, keys)
    }
    val highlight = remember(model, highlighted) { SurfaceGraphHighlight(highlighted, buildSet {
        val parents = model?.layout?.frames.orEmpty().associate { it.key to it.parent }
        model?.nodes.orEmpty().filter { it.id in highlighted || it.owner in highlighted }.forEach { node ->
            var key: String? = node.scope
            while (key != null && add(key)) key = parents[key]
        }
    }) }
    SideEffect { state.density(density); if (model != null) { state.frames = model.layout.frames; state.place(model.cards, model.nodes, key) } }
    val select by rememberUpdatedState(onSelect)
    val clear by rememberUpdatedState(onClear)
    fun cardAt(x: Float, y: Float) = painter.model.value?.cards?.asReversed()?.firstOrNull { x >= it.x && x <= it.x + it.width && y >= it.y && y <= it.y + it.height }
    val chosen by rememberUpdatedState(selectedId)
    fun cardOf(id: String) = painter.model.value?.let { shown -> shown.cards.firstOrNull { card -> shown.byId[card.id]?.let { it.id == id || it.owner == id || it.rows.any { row -> row.id == id || id in row.aliases } } == true } }
    fun jump(id: String) { select(id); cardOf(id)?.let { state.reveal(it.x, it.y, it.width, it.height) } }
    fun ownerOf(frame: Frame) = painter.model.value?.groups?.firstOrNull { it.key == frame.key }?.owner
    val view: @Composable (Modifier) -> Unit = { shell ->
    GraphView(painter, state,
        hit = { x, y, reach -> painter.endAt(x, y) != null || painter.frameAt(x, y)?.let { ownerOf(it) != null } ?: (cardAt(x, y) != null || painter.follow(x, y, reach) != null) },
        tap = { x, y, reach ->
            val shown = painter.model.value
            val handled = painter.endAt(x, y)?.let { jump(it); true } ?: painter.frameAt(x, y)?.let { frame -> ownerOf(frame)?.let(select); true } ?: cardAt(x, y)?.let { card ->
                if (shown != null) select(painter.rowAt(x, y)?.takeUnless { chosen == it.id }?.id ?: shown.byId.getValue(card.id).id)
                true
            } ?: painter.follow(x, y, reach)?.let { jump(it); true } ?: false
            state.record()
            handled
        },
        modifier = shell,
        onPane = { clear() },
        onSize = { size -> state.resize(size); painter.model.value?.let { state.place(it.cards, it.nodes, key) } },
        selection = { chosen?.let(::cardOf) },
        step = { by -> painter.model.value?.let { shown ->
            val order = shown.cards.sortedWith(compareBy({ it.y }, { it.x }))
            val at = order.indexOfFirst { card -> shown.byId[card.id]?.let { it.id == chosen || it.owner == chosen } == true }
            if (order.isNotEmpty()) order[(at + if (at < 0 && by < 0) 0 else by).mod(order.size)].let { card -> select(shown.byId.getValue(card.id).id); state.reveal(card.x, card.y, card.width, card.height) }
        } },
        open = { x, y -> painter.frameAt(x, y)?.let { state.fit(it.x, it.y, it.width, it.height, maxZoom = 1.4); true }
            ?: cardAt(x, y)?.let { state.centre(it.x, it.y, it.width, it.height); true } ?: false },
        editing = editor,
        markers = { focus -> model?.let { SurfaceGraphMarkers(state, it, selectedId) { id -> focus(); select(id) } } },
    ) { camera, viewport, scale ->
        painter.draw(this, camera, viewport, scale, state.level(camera.zoom), selection, highlight, resourceLinks, chapterLinks, areaCorridors)
    }
    }
    if (editor == null) view(modifier) else Box(modifier) { view(Modifier.fillMaxSize()); GraphEditorLayer(editor) }
}

@Composable
private fun SurfaceGraphMarkers(state: SurfaceGraphExplorerState, model: SurfaceGraphModel, selectedId: String?, select: (String) -> Unit) {
    GraphMarkers(state, model.cards, { model.cards[it].id }, MarkedZoom) { i, zoom ->
        val card = model.cards[i]
        val node = model.byId.getValue(card.id)
        Box(Modifier.at(card.x, card.y, card.width, card.height).semantics {
            selected = node.id == selectedId || node.owner == selectedId
            text = AnnotatedString(node.title)
            onClick(label = node.title) { select(node.id); true }
        }) {
            if (zoom < RowZoom) return@Box
            if (node.subtitle.isNotEmpty()) Box(Modifier.at(0.0, BlueprintEngine.RowHeight * 1.5, card.width, BlueprintEngine.RowHeight).semantics { text = AnnotatedString(node.subtitle) })
            node.rows.forEachIndexed { k, row ->
                val pin = card.pins.getOrNull(k) ?: return@forEachIndexed
                Box(Modifier.at(0.0, pin.y - BlueprintEngine.RowHeight / 2, card.width, BlueprintEngine.RowHeight).semantics {
                    selected = row.id == selectedId || selectedId in row.aliases
                    text = AnnotatedString(row.title)
                    onClick(label = row.title) { select(row.id); true }
                })
            }
        }
    }
}

private const val MarkedZoom = .2
private const val RowZoom = .45
