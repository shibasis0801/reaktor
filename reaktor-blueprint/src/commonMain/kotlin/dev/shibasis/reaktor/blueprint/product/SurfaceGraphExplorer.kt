package dev.shibasis.reaktor.blueprint.product

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import dev.shibasis.composeflow.model.XYPosition
import dev.shibasis.reaktor.blueprint.*
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.blueprint.canvas.GraphLevels
import dev.shibasis.reaktor.blueprint.editor.GraphEditor
import dev.shibasis.reaktor.blueprint.editor.Selection
import dev.shibasis.reaktor.blueprint.editor.id
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalDensity
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalMode
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSurface
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalVariant
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

internal fun surfaceGraphPinTarget(layout: BlueprintLayout<*>, card: Card, pin: Pin): Pair<Card, Pin>? {
    val source = PinRef(card.id, pin.key, pin.provides)
    val original = layout.cards[source.card] ?: return null
    val port = original.pins.firstOrNull { source.matches(original.id, it) } ?: return null
    return original to port
}

@Stable
class SurfaceGraphExplorerState : GraphCanvasState(0.002..16.0, GraphLevels(.2, .16, .45, .39)) {
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
    state: SurfaceGraphExplorerState, onSelect: (String) -> Unit, modifier: Modifier = Modifier, layout: BlueprintLayout<*>? = null, resourceLinks: Boolean = true, chapterLinks: Boolean = true, areaCorridors: Boolean = true, highlighted: Set<String> = emptySet(), onClear: () -> Unit = {}, key: Any? = null, groups: List<SurfaceGraphGroup> = emptyList(), editor: GraphEditor? = null,
    onOpen: ((String) -> Boolean)? = null) {
    if (layout != null && editor == null) {
        SurfaceGraphDeclaredExplorer(nodes, links, selectedId, appearance, state, onSelect, modifier, layout,
            resourceLinks, chapterLinks, areaCorridors, highlighted, onClear, key, groups, onOpen)
        return
    }
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
    val active = editor ?: remember(nodes, links, appearance, scene, looks) {
        SurfaceGraphSimple(nodes, links, appearance, groups = looks, initialLayout = scene)
    }.editor
    val laid = active.settled
    val presentation = remember(laid, nodes, links, looks) { SurfaceGraphExplorerPresentation(laid, nodes, links, looks) }
    val byId = remember(nodes) { nodes.associateBy { it.id } }
    val cards = remember(laid) { laid.cards.values.toList() }
    val sourceLinks = remember(links) { links.associateBy { it.id } }
    val sourceByWire = remember(active.shape, sourceLinks) { active.shape.wires.associate { wire -> wire.id to (sourceLinks[wire.id] ?: sourceLinks[wire.definitionId]) } }
    fun source(link: Link) = sourceByWire[link.id] ?: link.members.firstNotNullOfOrNull(sourceByWire::get)
    val density = LocalDensity.current.density.toDouble()
    val select by rememberUpdatedState(onSelect)
    val clear by rememberUpdatedState(onClear)
    val opening by rememberUpdatedState(onOpen)
    val chosen by rememberUpdatedState(selectedId)
    val owners = remember(nodes) { buildMap { nodes.forEach { node -> put(node.id, node.id); getOrPut(node.owner) { node.id }; node.rows.forEach { row -> getOrPut(row.id) { node.id }; row.aliases.forEach { getOrPut(it) { node.id } } } } } }
    fun owner(id: String?) = owners[id]
    fun jump(id: String) {
        select(id)
    }
    LaunchedEffect(cards, laid.frames, key, state.flow.canvasSize, density) {
        state.frames = laid.frames
        state.place(cards, nodes, key)
    }
    SideEffect {
        state.density(density)
        active.font = appearance.fontFamily
        if (editor == null) {
            active.onSelect = {}
            val next = Selection(nodes = setOfNotNull(owner(selectedId)))
            if (active.selection != next) active.select(next)
            active.onSelect = { id ->
                if (id != null) {
                    id.takeIf { it in owners }?.let(select)
                } else active.selection.wires.firstOrNull()?.let { wire ->
                    (sourceByWire[wire] ?: laid.links.firstOrNull { it.id == wire }?.let(::source))?.let { link ->
                        jump(if (chosen == link.to || owner(chosen) == owner(link.to)) link.from else link.to)
                    }
                } ?: clear()
            }
        }
    }
    val tone = appearance.selected
    val view: @Composable (Modifier) -> Unit = { shell ->
        BlueprintGraph(active, state,
            cardLook = { shape ->
                val node = byId[shape.id]
                val look = laid.cards[shape.id]?.let { presentation.look(it, appearance, selectedId, highlighted, null) }
                    ?: BlueprintCardLook(tone, SurfaceGraphSymbol, shape.label, shape.type)
                val actions = remember(node?.id, node?.title, node?.opens, onOpen != null) {
                    if (node?.opens == true && onOpen != null) listOf(
                        BlueprintCardAction("open", "Open ${node.title}", SurfaceGraphOpenSymbol) { opening?.invoke(node.id) }) else emptyList()
                }
                look.copy(actions = actions,
                    outline = if (node?.id in highlighted || node?.owner in highlighted) tone else look.outline,
                    pins = look.pins.mapIndexed { index, pin -> pin?.copy(selected = laid.cards[shape.id]?.pins?.getOrNull(index)?.let { it.id == selectedId || selectedId in it.aliases } == true) })
            },
            groupLook = { frame ->
                val group = looks.firstOrNull { it.key == frame.key }
                BlueprintFrameLook(SurfaceGraphSymbol, group?.subtitle?.ifBlank { null } ?: "${frame.nodes.size} sources", lit = group?.color, muted = group?.muted == true)
            },
            linkStyle = { link ->
                val edge = source(link)
                val lit = edge?.let { it.from == selectedId || it.to == selectedId || owner(it.from) == owner(selectedId) || owner(it.to) == owner(selectedId) } == true
                val visible = lit || if (link.family == "chapter") chapterLinks else resourceLinks
                Blueprint.link(if (visible) edge?.color ?: tone else Color.Transparent, highlighting = selectedId != null || highlighted.isNotEmpty(), lit = lit,
                    cross = link.cross, dash = if (edge?.proposed == true || link.family == "optional") 6f to 4f else null)
            },
            linkLabel = { link -> source(link)?.label?.takeIf { it.isNotBlank() } },
            modifier = shell,
            onPinClick = if (editor != null) null else { card, pin ->
                surfaceGraphPinTarget(laid, card, pin)?.let { (original, port) ->
                    select(port.id.takeUnless { chosen == it } ?: original.id)
                }
            },
            onCardOpen = {},
            onPaneClick = if (editor == null) ({ clear() }) else null,
            editable = editor != null,
            bundles = areaCorridors && active.bundles,
            onFrameClick = { frame -> looks.firstOrNull { it.key == frame.key }?.owner?.let(select) },
            onFrameOpen = {},
            denseOverview = true)
    }
    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact,
        mode = if (appearance.canvas.luminance() > .5f) MachineSignalMode.Light else MachineSignalMode.Dark) {
        view(modifier)
    }
}

internal class SurfaceGraphExplorerPresentation<K>(
    val full: BlueprintLayout<K>,
    sourceNodes: List<SurfaceGraphNode>,
    links: List<SurfaceGraphLink>,
    val groups: List<SurfaceGraphGroup>,
) {
    val nodes = sourceNodes.associateBy { it.id }
    private val sources = links.associateBy { it.id }
    private val owners = buildMap {
        sourceNodes.forEach { node ->
            put(node.id, node.id)
            getOrPut(node.owner) { node.id }
            node.rows.forEach { row ->
                getOrPut(row.id) { node.id }
                row.aliases.forEach { getOrPut(it) { node.id } }
            }
        }
    }

    fun owner(id: String?) = owners[id]
    fun source(link: Link) = sources[link.id] ?: link.members.firstNotNullOfOrNull(sources::get)
    fun target(link: Link, selected: String?) = source(link)?.let { edge ->
        if (selected == edge.to || selected != null && owner(selected) == owner(edge.to)) edge.from else edge.to
    }

    fun look(card: Card, appearance: SurfaceGraphAppearance, selected: String?, highlighted: Set<String>, open: ((String) -> Unit)?): BlueprintCardLook? {
        val node = nodes[card.id] ?: return null
        val tone = node.color ?: appearance.selected
        return BlueprintCardLook(tone, SurfaceGraphSymbol, node.title, node.subtitle,
            tag = "surface-card:${node.id}", action = "Select ${node.title}",
            selected = owner(selected) == node.id,
            folded = node.folded.takeIf { it > 0 }?.let { "$it ${node.foldedLabel}" },
            marks = node.badges.map { BlueprintMark(it.text, it.background, BlueprintMark.Kind.Badge) } + node.counts.map { BlueprintMark(it.text, tone) },
            pins = card.pins.map { pin ->
                (node.rows.firstOrNull { it.id == pin.id || pin.id in it.aliases }
                    ?: node.rows.firstOrNull { it.key == pin.key })?.let { row ->
                    BlueprintPinLook(if (pin.provides) row.output ?: tone else row.input ?: tone, row.title, appearance.text, row.completed,
                        selected = selected == pin.id || selected in pin.aliases,
                        marks = row.badges.map { BlueprintMark(it.text, it.background, BlueprintMark.Kind.Badge) })
                }
            },
            face = if (node.rows.isEmpty()) BlueprintFace.Prose else BlueprintFace.Code,
            progress = node.progress.takeIf { it > 0f },
            outline = if (node.id in highlighted || node.owner in highlighted) appearance.selected else null,
            actions = if (node.opens && open != null) listOf(BlueprintCardAction("open", "Open ${node.title}", SurfaceGraphOpenSymbol) { open(node.id) }) else emptyList())
    }
}

@Composable
private fun <K> SurfaceGraphDeclaredExplorer(
    nodes: List<SurfaceGraphNode>, links: List<SurfaceGraphLink>, selectedId: String?, appearance: SurfaceGraphAppearance,
    state: SurfaceGraphExplorerState, onSelect: (String) -> Unit, modifier: Modifier, layout: BlueprintLayout<K>,
    resourceLinks: Boolean, chapterLinks: Boolean, areaCorridors: Boolean, highlighted: Set<String>, onClear: () -> Unit,
    key: Any?, groups: List<SurfaceGraphGroup>, onOpen: ((String) -> Boolean)?,
) {
    val presentation = remember(layout, nodes, links, groups) { SurfaceGraphExplorerPresentation(layout, nodes, links, groups) }
    val cards = remember(layout) { layout.cards.values.toList() }
    val density = LocalDensity.current.density.toDouble()
    val select by rememberUpdatedState(onSelect)
    val clear by rememberUpdatedState(onClear)
    val opening by rememberUpdatedState(onOpen)
    val chosen by rememberUpdatedState(selectedId)
    SideEffect { state.density(density) }
    LaunchedEffect(cards, layout.frames, key, state.flow.canvasSize, density) {
        state.frames = layout.frames
        state.place(cards, nodes, key)
    }
    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact,
        mode = if (appearance.canvas.luminance() > .5f) MachineSignalMode.Light else MachineSignalMode.Dark) {
        BlueprintGraph(layout, state, highlighted.mapNotNullTo(linkedSetOf(), presentation::owner), presentation.owner(selectedId),
            cardLook = { card -> presentation.look(card, appearance, selectedId, highlighted, if (onOpen == null) null else { id -> opening?.invoke(id) }) },
            frameLook = { frame ->
                val group = groups.firstOrNull { it.key == frame.key }
                BlueprintFrameLook(SurfaceGraphSymbol, group?.subtitle?.ifBlank { null } ?: "${frame.nodes.size} sources", lit = group?.color, muted = group?.muted == true)
            },
            linkStyle = { link ->
                val edge = presentation.source(link)
                val lit = selectedId != null && edge?.let { it.from == selectedId || it.to == selectedId || presentation.owner(it.from) == presentation.owner(selectedId) || presentation.owner(it.to) == presentation.owner(selectedId) } == true
                val visible = lit || if (link.family == "chapter") chapterLinks else resourceLinks
                Blueprint.link(if (visible) edge?.color ?: appearance.selected else Color.Transparent,
                    highlighting = selectedId != null || highlighted.isNotEmpty(), lit = lit, cross = link.cross,
                    dash = if (edge?.proposed == true || link.family == "optional") 6f to 4f else null)
            },
            onCardClick = { id ->
                presentation.owner(id)?.let(select)
            },
            onLinkClick = { link -> presentation.target(link, chosen)?.let(select) },
            onPaneClick = { clear() }, modifier = modifier,
            labels = layout.links.mapNotNull { link -> presentation.source(link)?.label?.takeIf(String::isNotBlank)?.let { link.id to it } }.toMap(),
            fitKey = (key ?: layout.key).hashCode(), fitScene = key ?: layout.key, initialFit = { state.fit() },
            onPinClick = { card, pin -> surfaceGraphPinTarget(layout, card, pin)?.let { (original, port) -> select(port.id.takeUnless { chosen == it } ?: original.id) } },
            editable = false,
            onFrameClick = { frame -> groups.firstOrNull { it.key == frame.key }?.owner?.let(select) },
            denseOverview = true)
    }
}

private val SurfaceGraphOpenSymbol = ImageVector.Builder("Open source", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.5f) {
        moveTo(13f, 4f); lineTo(20f, 4f); lineTo(20f, 11f)
        moveTo(20f, 4f); lineTo(10f, 14f)
        moveTo(9f, 5f); lineTo(4f, 5f); lineTo(4f, 20f); lineTo(19f, 20f); lineTo(19f, 15f)
    }
}.build()
