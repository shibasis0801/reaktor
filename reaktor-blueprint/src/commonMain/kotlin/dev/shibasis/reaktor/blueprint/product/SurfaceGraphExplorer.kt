package dev.shibasis.reaktor.blueprint.product

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import dev.shibasis.composeflow.compose.interaction.*
import dev.shibasis.composeflow.model.*
import dev.shibasis.reaktor.blueprint.BlueprintEngine
import dev.shibasis.reaktor.blueprint.BlueprintLayout
import dev.shibasis.reaktor.blueprint.Bounds
import dev.shibasis.reaktor.blueprint.Card
import dev.shibasis.reaktor.blueprint.Frame
import dev.shibasis.reaktor.blueprint.Link
import dev.shibasis.reaktor.blueprint.LinkKind
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
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
class SurfaceGraphExplorerState : GraphCanvasState(0.03..3.0) {
    internal var cards: List<Card> = emptyList()
    internal var frames: List<Frame> = emptyList()
    private var placed: List<Card>? = null
    var generation by mutableIntStateOf(0)
        private set
    var showing: List<SurfaceGraphNode>? = null
        private set
    private var detailLevel: Int? = null
    init { insetTop = 48.0 }
    internal fun detailLevel(zoom: Double): Int {
        val next = when(detailLevel) {
            0 -> if(zoom>=.45) 2 else if(zoom>=.2) 1 else 0
            1 -> if(zoom<.16) 0 else if(zoom>=.45) 2 else 1
            2 -> if(zoom<.16) 0 else if(zoom<.39) 1 else 2
            else -> if(zoom<.18) 0 else if(zoom<.42) 1 else 2
        }
        detailLevel=next
        return next
    }
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
        if (flow.canvasSize == IntSize.Zero) return
        if (placed !== geometry) { placed = geometry; generation++ }
        fitOnce(key ?: generation) { fit() }
        showing = shown
    }
    internal fun resize(size: IntSize) {
        val previous = flow.canvasSize
        val view = flow.viewport
        val overview = frames.isNotEmpty() && frames.all { view.x + it.x * view.zoom >= -2 && view.y + it.y * view.zoom >= -2 && view.x + (it.x + it.width) * view.zoom <= previous.width + 2 && view.y + (it.y + it.height) * view.zoom <= previous.height + 2 }
        flow.setCanvasSize(size)
        if (previous != IntSize.Zero && previous != size && placed != null) {
            if (overview) fit() else flow.setViewport(view.copy(x = view.x + (size.width - previous.width) / 2.0, y = view.y + (size.height - previous.height) / 2.0))
        }
    }
}

@Composable
fun SurfaceGraphExplorer(nodes: List<SurfaceGraphNode>, links: List<SurfaceGraphLink>, selectedId: String?, appearance: SurfaceGraphAppearance,
    state: SurfaceGraphExplorerState, onSelect: (String) -> Unit, modifier: Modifier = Modifier, layout: BlueprintLayout<*>? = null, resourceLinks: Boolean = true, chapterLinks: Boolean = true, areaCorridors: Boolean = true, highlighted: Set<String> = emptySet(), onClear: () -> Unit = {}, key: Any? = null, groups: List<SurfaceGraphGroup> = emptyList()) {
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
    val interaction = rememberFlowViewportInteractionState()
    val density = LocalDensity.current.density.toDouble()
    val config = remember(density, state) { FlowViewportGestureConfig(minZoom = state.zoomRange.start * density, maxZoom = state.zoomRange.endInclusive * density) }
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
    DisposableEffect(painter) { onDispose { painter.release() } }
    LaunchedEffect(painter) { painter.run() }
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
    var pointing by remember { mutableStateOf(false) }
    val hit = remember(painter, state) { { point: Offset ->
        val world = state.flow.screenToFlowPosition(point.x.toDouble(), point.y.toDouble())
        painter.model.value?.cards?.asReversed()?.firstOrNull { world.x >= it.x && world.x <= it.x + it.width && world.y >= it.y && world.y <= it.y + it.height }?.let { it to world }
    } }
    val tap = remember(painter, state) { { point: Offset ->
        val found = hit(point)
        val shown = painter.model.value
        if (shown != null && found != null) {
            val (card, world) = found
            val node = shown.byId.getValue(card.id)
            val half = BlueprintEngine.RowHeight / 2
            val pin = if (state.detailLevel(state.viewport().zoom) == 2) card.pins.firstOrNull { world.y - card.y - it.y in -half..<half } else null
            select(pin?.id ?: node.id)
        } else clear()
        state.record(); found != null
    } }
    Canvas(modifier.clipToBounds().onSizeChanged { size -> state.resize(size); painter.model.value?.let { state.place(it.cards, it.nodes, key) } }
        .pointerHoverIcon(if (pointing) PointerIcon.Hand else PointerIcon.Default)
        .pointerInput(hit) { awaitPointerEventScope { while (true) {
            val event = awaitPointerEvent(); val change = event.changes.first()
            if (change.type == PointerType.Mouse) pointing = event.type != PointerEventType.Exit && !event.buttons.areAnyPressed && hit(change.position) != null
        } } }
        .flowWheelAndTrackpadViewportGestures(state.flow, interaction, config)
        .flowPointerViewportGestures(state.flow, interaction, config, onPaneTap = tap)) {
        painter.version.intValue
        val scale = density.toFloat()
        withTransform({ scale(scale, scale, Offset.Zero) }) {
            val camera = state.viewport()
            painter.draw(this, camera, Size(size.width / scale, size.height / scale), scale, state.detailLevel(camera.zoom), selection, highlight, resourceLinks, chapterLinks, areaCorridors)
        }
    }
}
