package dev.shibasis.reaktor.blueprint.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import dev.shibasis.reaktor.blueprint.BlueprintEngine
import dev.shibasis.reaktor.blueprint.BlueprintLayout
import dev.shibasis.reaktor.blueprint.Card
import dev.shibasis.reaktor.blueprint.Frame
import dev.shibasis.reaktor.blueprint.Link
import dev.shibasis.reaktor.blueprint.LinkKind
import dev.shibasis.reaktor.blueprint.Pin
import dev.shibasis.reaktor.blueprint.canvas.CanvasEditing
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.WireShape
import dev.shibasis.reaktor.surface.Chord
import dev.shibasis.reaktor.surface.Command
import dev.shibasis.reaktor.surface.CommandId
import dev.shibasis.reaktor.surface.CommandSet
import dev.shibasis.reaktor.surface.KeyName
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random

data class Selection(val nodes: Set<String> = emptySet(), val wires: Set<String> = emptySet(), val notes: Set<String> = emptySet()) {
    val empty: Boolean get() = nodes.isEmpty() && wires.isEmpty() && notes.isEmpty()
    val size: Int get() = nodes.size + wires.size + notes.size
    operator fun plus(other: Selection) = Selection(nodes + other.nodes, wires + other.wires, notes + other.notes)
    operator fun minus(other: Selection) = Selection(nodes - other.nodes, wires - other.wires, notes - other.notes)
}

data class PinEnd(val node: String, val key: String, val provides: Boolean)

data class Template(val label: String, val kind: NodeKind, val type: String, val ports: List<PortShape>, val scope: String? = null) {
    fun offers(pin: PortShape?) = pin == null || ports.any { it.provides != pin.provides && it.type == pin.type }
}

data class EditorInk(
    val accent: Color = Color(0xFF4C8DFF),
    val added: Color = Color(0xFF3FB97A),
    val changed: Color = Color(0xFFE0A33A),
    val removed: Color = Color(0xFFE5534B),
    val refused: Color = Color(0xFFE5534B),
    val surface: Color = Color(0xF21C1F26),
    val text: Color = Color(0xFFE8EAF0),
)

sealed interface Target {
    data class OnCard(val id: String) : Target
    data class OnPin(val pin: PinEnd) : Target
    data class OnWire(val id: String) : Target
    data class OnNote(val id: String) : Target
    data object Empty : Target
}

sealed interface Request {
    val at: Spot
    val window: Offset
    data class Menu(override val at: Spot, override val window: Offset, val target: Target) : Request
    data class Palette(override val at: Spot, override val window: Offset, val from: PinEnd? = null) : Request
    data class Rename(override val at: Spot, override val window: Offset, val subject: String, val note: Boolean) : Request
}

sealed interface Gesture {
    data class Move(val from: Spot, val before: Placement, val anchor: Spot, val cards: Set<String>, val notes: Set<String>, val moved: Boolean = false, val only: String? = null) : Gesture
    data class Wiring(val pin: PinEnd, val at: Spot, val window: Offset, val target: PinEnd? = null, val verdict: Verdict? = null, val carried: List<WireShape> = emptyList(), val picked: PinEnd? = null, val from: Spot = at) : Gesture
    data class Marquee(val from: Spot, val to: Spot, val start: Selection, val mode: Int) : Gesture
    data class Resize(val note: String, val from: Spot, val before: Placement) : Gesture
    data class Bend(val wire: String, val index: Int, val before: Placement) : Gesture
}

enum class Edge { Top, Middle, Bottom, Left, Centre, Right }

private data class Step(val shape: GraphShape, val placement: Placement, val title: String)

private data class Clip(val nodes: List<NodeShape>, val wires: List<WireShape>, val spots: Map<String, Spot>)

@Stable
class GraphEditor(
    val base: GraphShape,
    placement: Placement,
    val id: String = fresh("draft"),
    title: String = "Untitled change",
    revision: Int = 0,
    shape: GraphShape = base,
    val ink: EditorInk = EditorInk(),
    val cardWidth: Double = BlueprintEngine.CardWidth,
    val grid: Double = 16.0,
    val single: Boolean = true,
    val pinZone: Double? = null,
    val family: (WireShape) -> String = { "" },
    val catalogue: ((GraphShape) -> List<Template>)? = null,
    val pins: (NodeShape) -> List<Pin> = ::editorPins,
    val terms: GraphTerms = GraphTerms,
) : CanvasEditing {
    var title by mutableStateOf(title)
    var shape by mutableStateOf(shape)
        private set
    var placement by mutableStateOf(placement)
        private set
    var selection by mutableStateOf(Selection())
        private set
    var showChanges by mutableStateOf(true)
    var revision by mutableIntStateOf(revision)
        private set
    var gesture by mutableStateOf<Gesture?>(null)
        private set
    var request by mutableStateOf<Request?>(null)
    var notice by mutableStateOf<String?>(null)
    var onSelect: (String?) -> Unit = {}
    var onOpen: (String) -> Unit = {}
    var onSave: (GraphDraft) -> Unit = {}
    internal var pointer = Spot(0.0, 0.0)
        private set
    private var pointerWindow = Offset.Zero
    private var keptShape by mutableStateOf(shape)
    private var keptPlacement by mutableStateOf(placement)
    private var keptTitle by mutableStateOf(title)
    private val undos = mutableStateListOf<Step>()
    private val redos = mutableStateListOf<Step>()
    private val known = base.problems(single).mapTo(hashSetOf()) { it.text }

    val diff: GraphDiff by derivedStateOf { GraphDiff(base, this.shape, terms) }
    val problems: List<Problem> by derivedStateOf { this.shape.problems(single).filter { it.text !in known } }
    val dirty: Boolean by derivedStateOf { this.shape != keptShape || this.placement != keptPlacement || this.title != keptTitle }
    private var built: Built? = null
    private var hold: Hold? = null
    val layout: BlueprintLayout<String> by derivedStateOf {
        val shown = if (showChanges) diff else null
        val last = built?.takeIf { it.shape === this.shape && it.diff === shown }
        (if (last != null) moved(last, this.placement) else scene(this.shape, this.placement, shown))
            .also { built = Built(this.shape, shown, this.placement, it, last?.links ?: Links(it.links)) }
    }
    val settled: BlueprintLayout<String> by derivedStateOf {
        hold?.takeIf { gesture is Gesture.Move && it.shape === shape && it.changes == showChanges }?.layout ?: layout
    }
    val canUndo: Boolean get() = undos.isNotEmpty()
    val canRedo: Boolean get() = redos.isNotEmpty()
    val valid: Boolean get() = problems.isEmpty()

    val templates: List<Template> by lazy {
        catalogue?.let { return@lazy it(base) }
        val seen = (base.nodes + this.shape.nodes).filter { it.kind != NodeKind.Route }
            .distinctBy { it.kind to it.type }
            .map { node -> Template(node.type.substringAfterLast('.'), node.kind, node.type, node.ports.filterNot(PortShape::internal).map { it.reset() }, node.scope) }
        val blank = listOf(NodeKind.Service, NodeKind.Screen, NodeKind.Interactor, NodeKind.Actor, NodeKind.Controller, NodeKind.Node)
            .map { Template("New ${it.name.lowercase()}", it, it.name, emptyList()) }
        (blank + seen.sortedBy { it.label.lowercase() })
    }

    override val commands: CommandSet = EditorCommands

    fun ghost(id: String): NodeShape? = if (showChanges && diff.nodes[id] == Change.Removed) base.node(id) else null

    fun change(id: String): Change? = if (showChanges) diff.nodes[id] ?: diff.wires[id] else null

    fun node(id: String): NodeShape? = shape.node(id)

    fun select(next: Selection) {
        selection = next
        onSelect(next.nodes.singleOrNull()?.takeIf { next.size == 1 })
    }

    fun selectAll() = select(Selection(shape.nodes.filter { it.kind != NodeKind.Route }.mapTo(hashSetOf()) { it.id }))

    fun undo() {
        val step = undos.removeLastOrNull() ?: return
        redos += Step(shape, placement, title)
        restore(step)
    }

    fun redo() {
        val step = redos.removeLastOrNull() ?: return
        undos += Step(shape, placement, title)
        restore(step)
    }

    private fun restore(step: Step) {
        shape = step.shape; placement = step.placement; title = step.title
        select(Selection(selection.nodes.filterTo(hashSetOf()) { shape.node(it) != null }, selection.wires.filterTo(hashSetOf()) { id -> shape.wires.any { it.id == id } },
            selection.notes.filterTo(hashSetOf()) { id -> placement.notes.any { it.id == id } }))
    }

    fun edit(change: (GraphShape, Placement) -> Pair<GraphShape, Placement>) {
        val (nextShape, nextPlacement) = change(shape, placement)
        if (nextShape == shape && nextPlacement == placement) return
        record(Step(shape, placement, title))
        shape = nextShape; placement = nextPlacement
    }

    private fun record(step: Step) {
        undos += step
        if (undos.size > HistoryLimit) undos.removeAt(0)
        redos.clear()
    }

    fun rename(id: String, text: String) {
        val value = text.trim().ifEmpty { return }
        if (placement.notes.any { it.id == id }) edit { s, p -> s to p.copy(notes = p.notes.map { if (it.id == id) it.copy(text = value) else it }) }
        else update(id) { it.copy(label = value) }
    }

    fun update(id: String, change: (NodeShape) -> NodeShape) {
        val node = shape.node(id) ?: return
        val next = change(node)
        val dropped = shape.with(next).let { after -> shape.wires.count { wire -> (wire.provider == id || wire.consumer == id) && after.wires.none { it.id == wire.id } } }
        edit { s, p -> s.with(next) to p }
        if (dropped > 0) notice = "Removed $dropped wire${if (dropped == 1) "" else "s"} that no longer fit"
    }

    fun recolour(note: String, tint: Long) = edit { s, p -> s to p.copy(notes = p.notes.map { if (it.id == note) it.copy(tint = tint) else it }) }

    fun add(template: Template, at: Spot, from: PinEnd? = null): String {
        val id = fresh("node")
        val start = snapped(Spot(at.x - cardWidth / 2, at.y - BlueprintEngine.HeaderHeight / 2))
        val node = NodeShape(id, scopeAt(at, from), unique(template.label), template.type, template.kind, ports = template.ports.map { it.reset() })
        edit { s, p ->
            var next = s.with(node)
            from?.let { pin ->
                val port = next.node(pin.node)?.port(pin.key, pin.provides)
                val match = node.ports.firstOrNull { it.provides != pin.provides && it.type == port?.type }
                if (match != null) next = (if (pin.provides) next.wire(pin.node, pin.key, id, match.key, single) else next.wire(id, match.key, pin.node, pin.key, single))?.let { next.connect(it, single) } ?: next
            }
            next to p.copy(cards = p.cards + (id to start))
        }
        select(Selection(setOf(id)))
        return id
    }

    fun delete(target: Selection = selection) {
        if (target.empty) return
        val routes = target.nodes.mapNotNull { shape.node(it)?.attachedTo }.filter { route -> shape.node(route)?.kind == NodeKind.Route && shape.nodes.none { it.attachedTo == route && it.id !in target.nodes } }
        edit { s, p -> s.without(target.nodes + routes).disconnect(target.wires) to p.copy(notes = p.notes.filter { it.id !in target.notes }, bends = p.bends - target.wires) }
        select(Selection())
    }

    fun breakLinks(pin: PinEnd) {
        val ids = links(pin).mapTo(hashSetOf()) { it.id }
        if (ids.isEmpty()) return
        edit { s, p -> s.disconnect(ids) to p.copy(bends = p.bends - ids) }
        notice = "Broke ${ids.size} link${if (ids.size == 1) "" else "s"}"
    }

    fun connect(a: PinEnd, b: PinEnd): Verdict {
        val (out, into) = if (a.provides) a to b else b to a
        val verdict = shape.verdict(out.node, out.key, into.node, into.key, single)
        if (!verdict.ok) { notice = verdict.message; return verdict }
        val wire = shape.wire(out.node, out.key, into.node, into.key, single) ?: return verdict
        edit { s, p -> s.connect(wire, single) to p }
        return verdict
    }

    fun verdict(a: PinEnd, b: PinEnd): Verdict {
        if (a.provides == b.provides) return Verdict(false, if (a.provides) "Both are outputs" else "Both are inputs")
        val (out, into) = if (a.provides) a to b else b to a
        return shape.verdict(out.node, out.key, into.node, into.key, single)
    }

    fun links(pin: PinEnd): List<WireShape> = shape.wires.filter { if (pin.provides) it.provider == pin.node && it.providerPort == pin.key else it.consumer == pin.node && it.consumerPort == pin.key }

    fun comment(around: Selection = selection) {
        val cards = around.nodes.mapNotNull(layout.cards::get)
        val id = fresh("note")
        val note = if (cards.isEmpty()) Note(id, "Comment", snap(pointer.x), snap(pointer.y), 400.0, 200.0)
        else {
            val left = cards.minOf { it.x } - BlueprintEngine.FrameSide; val top = cards.minOf { it.y } - BlueprintEngine.FrameTop
            Note(id, "Comment", left, top, cards.maxOf { it.x + it.width } + BlueprintEngine.FrameSide - left, cards.maxOf { it.y + it.height } + BlueprintEngine.FrameSide - top)
        }
        edit { s, p -> s to p.copy(notes = p.notes + note) }
        select(Selection(notes = setOf(id)))
        request = Request.Rename(Spot(note.x, note.y), pointerWindow, id, note = true)
    }

    fun align(edge: Edge) {
        val cards = selection.nodes.mapNotNull(layout.cards::get)
        if (cards.size < 2) return
        val top = cards.minOf { it.y }; val bottom = cards.maxOf { it.y + it.height }
        val left = cards.minOf { it.x }; val right = cards.maxOf { it.x + it.width }
        edit { s, p -> s to p.copy(cards = p.cards + cards.associate { card ->
            card.id to when (edge) {
                Edge.Top -> Spot(card.x, top)
                Edge.Bottom -> Spot(card.x, bottom - card.height)
                Edge.Middle -> Spot(card.x, (top + bottom) / 2 - card.height / 2)
                Edge.Left -> Spot(left, card.y)
                Edge.Right -> Spot(right - card.width, card.y)
                Edge.Centre -> Spot((left + right) / 2 - card.width / 2, card.y)
            }
        }) }
    }

    fun distribute(horizontal: Boolean) {
        val cards = selection.nodes.mapNotNull(layout.cards::get).sortedBy { if (horizontal) it.x else it.y }
        if (cards.size < 3) return
        val span = if (horizontal) cards.last().x - cards.first().x else cards.last().y - cards.first().y
        val step = span / (cards.size - 1)
        edit { s, p -> s to p.copy(cards = p.cards + cards.mapIndexed { i, card ->
            card.id to if (horizontal) Spot(cards.first().x + step * i, card.y) else Spot(card.x, cards.first().y + step * i)
        }) }
    }

    fun straighten() {
        val chosen = shape.wires.filter { it.id in selection.wires || it.consumer in selection.nodes && it.provider !in selection.nodes || it.consumer in selection.nodes && selection.nodes.size == 1 }
        val moves = chosen.mapNotNull { wire ->
            val from = layout.cards[wire.provider]?.let { card -> card.pins.firstOrNull { it.provides && it.key == wire.providerPort }?.let { card.y + it.y } } ?: return@mapNotNull null
            val to = layout.cards[wire.consumer] ?: return@mapNotNull null
            val pin = to.pins.firstOrNull { !it.provides && it.key == wire.consumerPort } ?: return@mapNotNull null
            wire.consumer to Spot(to.x, from - pin.y)
        }.distinctBy { it.first }
        if (moves.isEmpty()) return
        edit { s, p -> s to p.copy(cards = p.cards + moves) }
    }

    fun copy(target: Selection = selection): Int {
        val nodes = shape.nodes.filter { it.id in target.nodes }
        if (nodes.isEmpty()) return 0
        val ids = nodes.mapTo(hashSetOf()) { it.id }
        clipboard = Clip(nodes, shape.wires.filter { it.provider in ids && it.consumer in ids }, ids.mapNotNull { id -> layout.cards[id]?.let { id to Spot(it.x, it.y) } }.toMap())
        notice = "Copied ${nodes.size} node${if (nodes.size == 1) "" else "s"}"
        return nodes.size
    }

    fun cut() { if (copy() > 0) delete(Selection(selection.nodes)) }

    fun paste(at: Spot = pointer) {
        val clip = clipboard ?: return
        val left = clip.spots.values.minOfOrNull { it.x } ?: 0.0
        val top = clip.spots.values.minOfOrNull { it.y } ?: 0.0
        val renamed = clip.nodes.associate { it.id to fresh("node") }
        val scope = scopeAt(at, null)
        val nodes = clip.nodes.map { node -> node.copy(id = renamed.getValue(node.id), scope = if (shape.scopes.any { it.id == node.scope }) node.scope else scope, attachedTo = null, definitionId = "", ports = node.ports.map { it.reset() }) }
        val wires = clip.wires.map { it.copy(provider = renamed.getValue(it.provider), consumer = renamed.getValue(it.consumer), definitionId = "") }
        val spots = clip.nodes.associate { node -> renamed.getValue(node.id) to (clip.spots[node.id] ?: Spot(left, top)).let { snapped(Spot(it.x - left + at.x, it.y - top + at.y)) } }
        edit { s, p -> nodes.fold(s) { acc, node -> acc.copy(nodes = acc.nodes + node) }.let { it.copy(wires = it.wires + wires) }.recounted() to p.copy(cards = p.cards + spots) }
        select(Selection(renamed.values.toSet()))
    }

    fun duplicate() {
        if (copy() == 0) return
        val cards = selection.nodes.mapNotNull(layout.cards::get)
        paste(Spot(cards.minOf { it.x } + grid * 2, cards.minOf { it.y } + grid * 2))
    }

    fun draft(now: Long): GraphDraft = GraphDraft(id, title, revision + 1, now, base, shape,
        placement.copy(cards = placement.cards.filterKeys { id -> shape.node(id) != null || base.node(id) != null }))

    fun saved(draft: GraphDraft) {
        revision = draft.revision
        keptShape = draft.shape; keptPlacement = placement; keptTitle = draft.title
    }

    fun save(now: Long) {
        if (!valid) { notice = problems.first().text; return }
        onSave(draft(now))
    }

    fun revert() {
        record(Step(shape, placement, title))
        shape = keptShape; placement = keptPlacement; title = keptTitle
        select(Selection())
    }

    fun revert(entry: Difference) {
        val subject = entry.subject
        when {
            diff.nodes[subject] == Change.Added -> delete(Selection(setOf(subject)))
            diff.nodes[subject] == Change.Changed -> base.node(subject)?.let { node -> edit { s, p -> s.with(node) to p } }
            diff.nodes[subject] == Change.Removed -> base.node(subject)?.let { node -> edit { s, p ->
                val back = s.copy(nodes = s.nodes + node)
                val ids = back.nodes.mapTo(hashSetOf()) { it.id }
                val taken = back.wires.mapTo(hashSetOf()) { it.consumer to it.consumerPort }
                back.copy(wires = back.wires + base.wires.filter { (it.provider == node.id || it.consumer == node.id) && it.provider in ids && it.consumer in ids && (it.consumer to it.consumerPort) !in taken }).recounted() to p
            } }
            diff.wires[subject] == Change.Added -> edit { s, p -> s.disconnect(setOf(subject)) to p }
            diff.wires[subject] == Change.Removed -> {
                base.wires.firstOrNull { it.id == subject }?.let { wire ->
                    val verdict = shape.verdict(wire.provider, wire.providerPort, wire.consumer, wire.consumerPort, single)
                    if (verdict.ok) edit { s, p -> s.connect(wire, single) to p } else notice = verdict.message
                }
                base.routes.firstOrNull { it.id == subject }?.let { route -> if (shape.node(route.from) != null && shape.node(route.to) != null) edit { s, p -> s.copy(routes = s.routes + route) to p } }
            }
            else -> {
                val before = base.scopes.firstOrNull { it.id == subject }
                val now = shape.scopes.firstOrNull { it.id == subject }
                edit { s, p -> s.copy(scopes = when {
                    before == null -> if (s.nodes.any { it.scope == subject }) s.scopes else s.scopes - now!!
                    now == null -> s.scopes + before
                    else -> s.scopes.map { if (it.id == subject) before else it }
                }) to p }
            }
        }
    }

    fun reveal(id: String) {
        when {
            layout.cards.containsKey(id) -> select(Selection(setOf(id)))
            shape.wires.any { it.id == id } -> select(Selection(wires = setOf(id)))
            placement.notes.any { it.id == id } -> select(Selection(notes = setOf(id)))
        }
    }

    override fun invoke(command: CommandId) {
        when (command.value) {
            "edit-delete", "edit-delete-back" -> delete()
            "edit-undo" -> undo()
            "edit-redo", "edit-redo-y" -> redo()
            "edit-copy" -> copy()
            "edit-cut" -> cut()
            "edit-paste" -> paste()
            "edit-duplicate" -> duplicate()
            "edit-select-all" -> selectAll()
            "edit-comment" -> comment()
            "edit-straighten" -> straighten()
            "edit-align-top" -> align(Edge.Top)
            "edit-align-middle" -> align(Edge.Middle)
            "edit-align-bottom" -> align(Edge.Bottom)
            "edit-align-left" -> align(Edge.Left)
            "edit-align-centre" -> align(Edge.Centre)
            "edit-align-right" -> align(Edge.Right)
            "edit-save" -> save(nowMillis())
            "edit-palette" -> request = Request.Palette(pointer, pointerWindow)
            "edit-rename" -> (selection.nodes.singleOrNull() ?: selection.notes.singleOrNull())?.let { subject ->
                val box = layout.cards[subject] ?: layout.frames.firstOrNull { it.key == subject }
                request = Request.Rename(box?.let { Spot(it.x, it.y) } ?: pointer, pointerWindow, subject, note = subject !in layout.cards)
            }
            "edit-escape" -> { if (request != null) request = null else if (gesture != null) gesture = null else select(Selection()) }
        }
    }

    override fun point(x: Float, y: Float, window: Offset) {
        pointer = Spot(x.toDouble(), y.toDouble()); pointerWindow = window
    }

    override fun press(x: Float, y: Float, window: Offset, reach: Float, keys: PointerKeyboardModifiers, clicks: Int, touch: Boolean): Boolean {
        request = null
        point(x, y, window)
        val at = pointer
        val toggle = keys.isMetaPressed || keys.isCtrlPressed
        val adding = keys.isShiftPressed
        knotAt(at, reach)?.let { (wire, index) ->
            if (clicks >= 2) edit { s, p -> s to p.copy(bends = p.bends.bent(wire) { it.filterIndexed { k, _ -> k != index } }) }
            else gesture = Gesture.Bend(wire, index, placement)
            return true
        }
        pinAt(at)?.takeIf { ghost(it.node) == null }?.let { pin ->
            when {
                keys.isAltPressed -> breakLinks(pin)
                toggle && links(pin).isNotEmpty() -> gesture = links(pin).let { carried ->
                    val far = carried.first().let { if (pin.provides) PinEnd(it.consumer, it.consumerPort, false) else PinEnd(it.provider, it.providerPort, true) }
                    Gesture.Wiring(if (carried.size == 1) far else pin, at, window, carried = carried, picked = pin)
                }
                else -> gesture = Gesture.Wiring(pin, at, window)
            }
            return true
        }
        cardAt(at)?.let { card ->
            if (clicks >= 2 && !toggle && !adding) { onOpen(card.id); return true }
            val next = when {
                toggle -> if (card.id in selection.nodes) selection - Selection(setOf(card.id)) else selection + Selection(setOf(card.id))
                adding -> selection + Selection(setOf(card.id))
                card.id in selection.nodes -> selection
                else -> Selection(setOf(card.id))
            }
            select(next)
            if (ghost(card.id) == null && card.id in next.nodes)
                move(Gesture.Move(at, placement, Spot(card.x, card.y), next.nodes.filterTo(hashSetOf()) { ghost(it) == null }, next.notes,
                    only = card.id.takeIf { !toggle && !adding && next.size > 1 }))
            return true
        }
        noteAt(at)?.let { (note, corner) ->
            if (corner) { gesture = Gesture.Resize(note.id, at, placement); select(Selection(notes = setOf(note.id))); return true }
            if (clicks >= 2) { request = Request.Rename(Spot(note.x, note.y), window, note.id, note = true); return true }
            val inside = inside(note)
            val next = if (toggle || adding) selection + Selection(notes = setOf(note.id)) else Selection(notes = setOf(note.id))
            select(next)
            move(Gesture.Move(at, placement, Spot(note.x, note.y), inside.first + selection.nodes.filter { toggle || adding }, inside.second + next.notes))
            return true
        }
        wireAt(at, reach)?.let { wire ->
            if (clicks >= 2) { bend(wire, at); return true }
            select(if (toggle || adding) selection + Selection(wires = setOf(wire)) else Selection(wires = setOf(wire)))
            return true
        }
        if (touch) return false
        val start = if (toggle || adding) selection else Selection()
        if (!toggle && !adding) select(Selection())
        gesture = Gesture.Marquee(at, at, start, if (toggle) -1 else if (adding) 1 else 0)
        return true
    }

    private fun move(gesture: Gesture.Move) {
        hold = Hold(shape, showChanges, layout)
        this.gesture = gesture
    }

    override fun drag(x: Float, y: Float, window: Offset) {
        point(x, y, window)
        val at = pointer
        when (val g = gesture) {
            is Gesture.Move -> {
                val dx = snap(g.anchor.x + at.x - g.from.x) - g.anchor.x
                val dy = snap(g.anchor.y + at.y - g.from.y) - g.anchor.y
                if (!g.moved && hypot(at.x - g.from.x, at.y - g.from.y) < 3) return
                placement = g.before.copy(
                    cards = g.before.cards + g.cards.associateWith { id -> (g.before.cards[id] ?: layout.cards[id]?.let { Spot(it.x, it.y) } ?: g.anchor).let { Spot(it.x + dx, it.y + dy) } },
                    notes = g.before.notes.map { if (it.id in g.notes) it.copy(x = it.x + dx, y = it.y + dy) else it },
                    bends = g.before.bends.mapValues { (wire, spots) -> if (shape.wires.any { it.id == wire && it.provider in g.cards && it.consumer in g.cards }) spots.map { Spot(it.x + dx, it.y + dy) } else spots },
                )
                gesture = g.copy(moved = true)
            }
            is Gesture.Wiring -> {
                val target = pinAt(at)?.takeIf { it.node != g.pin.node || it.key != g.pin.key || it.provides != g.pin.provides }
                gesture = g.copy(at = at, window = window, target = target, verdict = target?.let { carried(g, it) })
            }
            is Gesture.Marquee -> {
                gesture = g.copy(to = at)
                val hit = Selection(marquee(g.from, at))
                select(when (g.mode) { 1 -> g.start + hit; -1 -> g.start - hit; else -> hit })
            }
            is Gesture.Resize -> placement = g.before.copy(notes = g.before.notes.map { note ->
                if (note.id != g.note) note else note.copy(width = max(MinNote, snap(note.width + at.x - g.from.x)), height = max(MinNote / 2, snap(note.height + at.y - g.from.y)))
            })
            is Gesture.Bend -> placement = g.before.copy(bends = g.before.bends.bent(g.wire) { spots -> spots.mapIndexed { k, spot -> if (k == g.index) Spot(snap(at.x), snap(at.y)) else spot } })
            null -> {}
        }
    }

    override fun release(x: Float, y: Float, window: Offset) {
        point(x, y, window)
        val g = gesture
        gesture = null
        when (g) {
            is Gesture.Move -> if (g.moved) record(Step(shape, g.before, title)) else g.only?.let { select(Selection(setOf(it))) }
            is Gesture.Wiring -> {
                val target = g.target
                when {
                    target != null && g.carried.isNotEmpty() -> rewire(g, target)
                    target != null -> connect(g.pin, target)
                    g.carried.isEmpty() && cardAt(pointer) == null && g.verdict == null && moved(g) -> request = Request.Palette(pointer, window, g.pin)
                    g.carried.isNotEmpty() && cardAt(pointer) == null -> edit { s, p -> s.disconnect(g.carried.mapTo(hashSetOf()) { it.id }) to p }
                    g.carried.isEmpty() && !moved(g) -> select(Selection(setOf(g.pin.node)))
                }
            }
            is Gesture.Resize -> if (placement != g.before) record(Step(shape, g.before, title))
            is Gesture.Bend -> if (placement != g.before) record(Step(shape, g.before, title))
            is Gesture.Marquee, null -> {}
        }
    }

    override fun menu(x: Float, y: Float, window: Offset) {
        point(x, y, window)
        val at = pointer
        val target = pinAt(at)?.takeIf { ghost(it.node) == null }?.let { Target.OnPin(it) } ?: cardAt(at)?.let { Target.OnCard(it.id) } ?: wireAt(at, 6f)?.let { Target.OnWire(it) }
            ?: noteAt(at)?.let { Target.OnNote(it.first.id) } ?: Target.Empty
        when (target) {
            is Target.OnCard -> if (target.id !in selection.nodes) select(Selection(setOf(target.id)))
            is Target.OnPin -> if (target.pin.node !in selection.nodes) select(Selection(setOf(target.pin.node)))
            is Target.OnWire -> if (target.id !in selection.wires) select(Selection(wires = setOf(target.id)))
            is Target.OnNote -> if (target.id !in selection.notes) select(Selection(notes = setOf(target.id)))
            else -> {}
        }
        request = if (target == Target.Empty) Request.Palette(at, window) else Request.Menu(at, window, target)
    }

    override fun overlay(scope: DrawScope, zoom: Double) = drawOverlay(scope, zoom)

    override fun cursor(x: Float, y: Float, reach: Float): Boolean {
        val at = Spot(x.toDouble(), y.toDouble())
        return pinAt(at) != null || cardAt(at) != null || noteAt(at) != null || wireAt(at, reach) != null
    }

    private fun carried(g: Gesture.Wiring, target: PinEnd): Verdict =
        if (g.carried.isEmpty()) verdict(g.pin, target)
        else if (target.provides != g.picked?.provides) Verdict(false, if (g.picked?.provides == true) "Drop on an output" else "Drop on an input")
        else g.carried.map { wire -> if (target.provides) shape.copy(wires = shape.wires - wire).verdict(target.node, target.key, wire.consumer, wire.consumerPort, single)
            else shape.copy(wires = shape.wires - wire).verdict(wire.provider, wire.providerPort, target.node, target.key, single) }.firstOrNull { !it.ok } ?: Verdict(true, "Move ${g.carried.size} link${if (g.carried.size == 1) "" else "s"}")

    private fun moved(g: Gesture.Wiring) = hypot(pointer.x - g.from.x, pointer.y - g.from.y) > ClickSlop

    private fun rewire(g: Gesture.Wiring, target: PinEnd) {
        val verdict = carried(g, target)
        if (!verdict.ok) { notice = verdict.message; return }
        edit { s, p ->
            g.carried.fold(s.disconnect(g.carried.mapTo(hashSetOf()) { it.id })) { acc, wire ->
                (if (target.provides) acc.wire(target.node, target.key, wire.consumer, wire.consumerPort, single) else acc.wire(wire.provider, wire.providerPort, target.node, target.key, single))?.let { acc.connect(it, single) } ?: acc
            } to p
        }
    }

    fun bend(wire: String, at: Spot) {
        val link = layout.links.firstOrNull { it.id == wire } ?: return
        val existing = placement.bends[wire].orEmpty()
        val ends = listOf(link.trace.first()) + existing.map { it.x to it.y } + listOf(link.trace.last())
        val index = ends.zipWithNext().withIndex().minByOrNull { (_, pair) -> segment(at, pair.first, pair.second) }?.index ?: existing.size
        val spot = Spot(snap(at.x), snap(at.y))
        edit { s, p -> s to p.copy(bends = p.bends + (wire to existing.toMutableList().apply { add(index.coerceIn(0, size), spot) })) }
    }

    private fun scopeAt(at: Spot, from: PinEnd?): String {
        val note = placement.notes.filter { at.x in it.x..(it.x + it.width) && at.y in it.y..(it.y + it.height) }.minByOrNull { it.width * it.height }
        val scopes = shape.scopes.mapTo(hashSetOf()) { it.id }
        note?.id?.takeIf { it in scopes }?.let { return it }
        note?.let { inside(it).first.mapNotNull { id -> shape.node(id)?.scope }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key }?.let { return it }
        from?.let { shape.node(it.node)?.scope }?.let { return it }
        layout.cards.values.minByOrNull { hypot(it.x + it.width / 2 - at.x, it.y + it.height / 2 - at.y) }?.let { shape.node(it.id)?.scope }?.let { return it }
        return shape.scopes.firstOrNull { it.parent == null }?.id ?: shape.scopes.first().id
    }

    private fun unique(label: String): String {
        val taken = shape.nodes.mapTo(hashSetOf()) { it.label }
        if (label !in taken) return label
        return generateSequence(2) { it + 1 }.map { "$label $it" }.first { it !in taken }
    }

    private fun inside(note: Note): Pair<Set<String>, Set<String>> {
        val cards = layout.cards.values.filter { ghost(it.id) == null && it.x >= note.x && it.y >= note.y && it.x + it.width <= note.x + note.width && it.y + it.height <= note.y + note.height }.mapTo(hashSetOf()) { it.id }
        val notes = placement.notes.filter { it.id == note.id || it.x >= note.x && it.y >= note.y && it.x + it.width <= note.x + note.width && it.y + it.height <= note.y + note.height }.mapTo(hashSetOf()) { it.id }
        return cards to notes
    }

    private fun marquee(a: Spot, b: Spot): Set<String> {
        val left = min(a.x, b.x); val right = max(a.x, b.x); val top = min(a.y, b.y); val bottom = max(a.y, b.y)
        return layout.cards.values.filter { ghost(it.id) == null && it.x < right && it.x + it.width > left && it.y < bottom && it.y + it.height > top }.mapTo(hashSetOf()) { it.id }
    }

    fun cardAt(at: Spot): Card? = layout.cards.values.lastOrNull { at.x >= it.x && at.x <= it.x + it.width && at.y >= it.y && at.y <= it.y + it.height }

    fun pinAt(at: Spot): PinEnd? {
        val card = layout.cards.values.lastOrNull { at.x >= it.x - PinReach && at.x <= it.x + it.width + PinReach && at.y >= it.y && at.y <= it.y + it.height } ?: return null
        val sided = pinZone == null && (card.pins.all { it.provides } || card.pins.none { it.provides })
        val reach = pinZone ?: (card.width * .4)
        val pin = card.pins.firstOrNull { pin ->
            abs(card.y + pin.y - at.y) <= BlueprintEngine.RowHeight / 2 && (sided || if (pin.provides) at.x >= card.x + card.width - reach else at.x <= card.x + reach)
        } ?: return null
        return PinEnd(card.id, pin.key, pin.provides)
    }

    fun anchor(pin: PinEnd): Pair<Double, Double>? = layout.cards[pin.node]?.let { card ->
        card.pins.firstOrNull { it.key == pin.key && it.provides == pin.provides }?.let { (if (pin.provides) card.x + card.width else card.x) to card.y + it.y }
    }

    private fun noteAt(at: Spot): Pair<Note, Boolean>? = placement.notes.asReversed().firstNotNullOfOrNull { note ->
        val corner = abs(at.x - note.x - note.width) <= Corner && abs(at.y - note.y - note.height) <= Corner
        val header = at.x >= note.x && at.x <= note.x + note.width && at.y >= note.y && at.y <= note.y + BlueprintEngine.FrameTop
        if (corner || header) note to corner else null
    }

    private fun wireAt(at: Spot, reach: Float): String? = layout.links.asReversed().firstOrNull { link ->
        val trace = link.trace
        (1 until trace.size).any { k -> segment(at, trace[k - 1], trace[k]) <= reach + 2 }
    }?.id?.takeIf { id -> diff.wires[id] != Change.Removed || shape.wires.any { it.id == id } }

    private fun knotAt(at: Spot, reach: Float): Pair<String, Int>? = placement.bends.firstNotNullOfOrNull { (wire, spots) ->
        spots.indexOfFirst { hypot(it.x - at.x, it.y - at.y) <= Knot + reach }.takeIf { it >= 0 }?.let { wire to it }
    }

    private fun snap(value: Double) = (value / grid).roundToLong() * grid

    private fun snapped(spot: Spot) = Spot(snap(spot.x), snap(spot.y))

    private fun scene(shape: GraphShape, placement: Placement, diff: GraphDiff?): BlueprintLayout<String> {
        val byId = shape.nodes.associateBy { it.id }
        val attached = shape.nodes.filter { it.attachedTo != null && it.kind != NodeKind.Route }.associate { it.attachedTo!! to it.id }
        fun visible(id: String) = byId[id]?.let { if (it.kind == NodeKind.Route) attached[id] else id }
        val cards = LinkedHashMap<String, Card>(shape.nodes.size * 2)
        var spare = placement.cards.values.maxOfOrNull { it.x }?.plus(cardWidth + BlueprintEngine.LayerGap) ?: 0.0
        var spareY = 0.0
        val shown = shape.nodes.filter { it.kind != NodeKind.Route } + diff?.removedNodes.orEmpty().filter { it.kind != NodeKind.Route }
        shown.forEach { node ->
            val pins = pins(node)
            val height = BlueprintEngine.cardHeight(rows(pins))
            val spot = placement.cards[node.id] ?: Spot(spare, spareY).also { spareY += height + BlueprintEngine.CardGap; if (spareY > 2400) { spareY = 0.0; spare += cardWidth + BlueprintEngine.LayerGap } }
            cards[node.id] = Card(node.id, spot.x, spot.y, cardWidth, height, pins, 0)
        }
        val links = ArrayList<Link>(shape.wires.size + shape.routes.size)
        fun wireLink(wire: WireShape) {
            if (wire.providerPort in Internal || wire.consumerPort in Internal) return
            val from = visible(wire.provider) ?: wire.provider.takeIf { it in cards } ?: return
            val to = visible(wire.consumer) ?: wire.consumer.takeIf { it in cards } ?: return
            val a = cards[from] ?: return; val b = cards[to] ?: return
            val id = "$from.${wire.providerPort}>$to.${wire.consumerPort}"
            val (points, curve) = path(BlueprintEngine.anchor(cards, a.id, wire.providerPort, true), BlueprintEngine.anchor(cards, b.id, wire.consumerPort, false), placement.bends[id].orEmpty())
            links += Link(id, from, to, wire.providerPort, wire.consumerPort, LinkKind.Wire, listOf(id), points, family = family(wire), curve = curve)
        }
        shape.wires.forEach(::wireLink)
        diff?.removedWires?.forEach(::wireLink)
        val routeIds = HashSet<String>()
        val old = diff?.base
        val gone = diff?.let { d -> d.base.routes.filter { d.wires[it.id] == Change.Removed } }.orEmpty()
        fun former(id: String) = old?.nodes?.firstOrNull { it.attachedTo == id && it.kind != NodeKind.Route }?.id
        (shape.routes + gone).forEach { route ->
            val from = visible(route.from) ?: former(route.from) ?: return@forEach
            val to = visible(route.to) ?: former(route.to) ?: return@forEach
            if (from == to || from !in cards || to !in cards || !routeIds.add("$from>$to")) return@forEach
            val (points, curve) = path(BlueprintEngine.anchor(cards, from, null, true), BlueprintEngine.anchor(cards, to, null, false), emptyList())
            links += Link(route.id, from, to, null, null, LinkKind.Route, listOf(route.id), points, curve = curve)
        }
        return assembled(cards, placement.notes, links)
    }

    private fun assembled(cards: Map<String, Card>, notes: List<Note>, links: List<Link>): BlueprintLayout<String> {
        val frames = notes.map { note ->
            Frame(note.id, note.text, note.x, note.y, note.width, note.height,
                cards.values.filter { it.x + it.width / 2 in note.x..(note.x + note.width) && it.y + it.height / 2 in note.y..(note.y + note.height) }.mapTo(LinkedHashSet()) { it.id },
                emptyList(), false, note.parent)
        }
        val boxes = cards.values + frames
        return BlueprintLayout(id, cards, frames, links, boxes.maxOfOrNull { it.x + it.width } ?: 0.0, boxes.maxOfOrNull { it.y + it.height } ?: 0.0)
    }

    private fun moved(last: Built, placement: Placement): BlueprintLayout<String> {
        val old = last.layout
        if (placement == last.placement) return old
        val changed = old.cards.values.filterTo(hashSetOf()) { card -> placement.cards[card.id]?.let { it.x != card.x || it.y != card.y } == true }.mapTo(hashSetOf()) { it.id }
        val bent = (placement.bends.keys + last.placement.bends.keys).filterTo(hashSetOf()) { placement.bends[it] != last.placement.bends[it] }
        if (changed.isEmpty() && bent.isEmpty() && placement.notes == last.placement.notes) return old
        val cards = LinkedHashMap(old.cards)
        changed.forEach { id -> placement.cards[id]?.let { spot -> cards[id] = cards.getValue(id).copy(x = spot.x, y = spot.y) } }
        val touched = changed.flatMapTo(hashSetOf()) { last.links.touching[it].orEmpty() }.apply { bent.mapNotNullTo(this, last.links.slots::get) }
        val links = if (touched.isEmpty()) old.links else ArrayList(old.links).apply {
            for (k in touched) this[k] = this[k].let { link ->
                path(BlueprintEngine.anchor(cards, link.from, link.fromPort, true), BlueprintEngine.anchor(cards, link.to, link.toPort, false), placement.bends[link.id].orEmpty())
                    .let { (points, curve) -> link.copy(points = points, curve = curve) }
            }
        }
        return assembled(cards, placement.notes, links)
    }

    internal fun link(id: String): Link? {
        val shown = layout
        return built?.takeIf { it.layout === shown }?.links?.slots?.get(id)?.let(shown.links::getOrNull)?.takeIf { it.id == id } ?: shown.links.firstOrNull { it.id == id }
    }

    private fun path(from: Pair<Double, Double>, to: Pair<Double, Double>, bends: List<Spot>): Pair<List<Pair<Double, Double>>, Boolean> =
        if (bends.isEmpty()) BlueprintEngine.bezier(from, to) to true
        else (listOf(from) + bends.map { it.x to it.y } + listOf(to)).zipWithNext().flatMapIndexed { k, (a, b) -> BlueprintEngine.curve(a, b).let { if (k == 0) it else it.drop(1) } } to false

    private class Built(val shape: GraphShape, val diff: GraphDiff?, val placement: Placement, val layout: BlueprintLayout<String>, val links: Links)

    private class Links(links: List<Link>) {
        val slots: Map<String, Int> by lazy { HashMap<String, Int>(links.size * 2).also { map -> links.forEachIndexed { i, link -> map[link.id] = i } } }
        val touching: Map<String, List<Int>> by lazy {
            HashMap<String, MutableList<Int>>().also { map -> links.forEachIndexed { i, link -> map.getOrPut(link.from) { ArrayList(4) } += i; if (link.to != link.from) map.getOrPut(link.to) { ArrayList(4) } += i } }
        }
    }

    private class Hold(val shape: GraphShape, val changes: Boolean, val layout: BlueprintLayout<String>)

    companion object {
        private var clipboard: Clip? = null
        private val Internal = setOf("routeBinding", "navBinding")
        private const val HistoryLimit = 200
        private const val PinReach = 10.0
        private const val ClickSlop = 4.0
        private const val Corner = 14.0
        private const val Knot = 6.0
        private const val MinNote = 160.0

        fun placement(layout: BlueprintLayout<*>): Placement = Placement(
            cards = layout.cards.values.associate { it.id to Spot(it.x, it.y) },
            notes = layout.frames.map { Note(it.key, it.label, it.x, it.y, it.width, it.height, parent = it.parent) },
        )

        fun open(draft: GraphDraft, ink: EditorInk = EditorInk(), cardWidth: Double = BlueprintEngine.CardWidth, pins: (NodeShape) -> List<Pin> = ::editorPins) =
            GraphEditor(draft.base, draft.placement, draft.id, draft.title, draft.revision, draft.shape, ink, cardWidth, pins = pins)

        fun open(draft: GraphDraft, ink: EditorInk, cardWidth: Double, single: Boolean, pinZone: Double?, family: (WireShape) -> String,
                 catalogue: ((GraphShape) -> List<Template>)?, pins: (NodeShape) -> List<Pin>, terms: GraphTerms) =
            GraphEditor(draft.base, draft.placement, draft.id, draft.title, draft.revision, draft.shape, ink, cardWidth, single = single, pinZone = pinZone,
                family = family, catalogue = catalogue, pins = pins, terms = terms)
    }
}

fun editorPins(node: NodeShape): List<Pin> {
    val shown = node.ports.filterNot { it.internal }
    return shown.filter { !it.provides }.mapIndexed { i, port -> Pin(port.key, port.type, false, BlueprintEngine.pinY(i), port.wiring) } +
        shown.filter { it.provides }.mapIndexed { i, port -> Pin(port.key, port.type, true, BlueprintEngine.pinY(i), port.wiring) }
}

private fun rows(pins: List<Pin>) = pins.maxOfOrNull { ((it.y - BlueprintEngine.pinY(0)) / BlueprintEngine.RowHeight).roundToLong().toInt() + 1 }?.coerceAtLeast(0) ?: 0

private fun PortShape.reset() = copy(wiring = if (provides) PortWiring.Unused else PortWiring.Unmatched, connections = 0, definitionId = "")

private fun Map<String, List<Spot>>.bent(wire: String, change: (List<Spot>) -> List<Spot>): Map<String, List<Spot>> =
    change(this[wire].orEmpty()).let { if (it.isEmpty()) this - wire else this + (wire to it) }

private fun segment(at: Spot, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val dx = b.first - a.first; val dy = b.second - a.second
    val t = (((at.x - a.first) * dx + (at.y - a.second) * dy) / (dx * dx + dy * dy).coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
    return hypot(at.x - a.first - t * dx, at.y - a.second - t * dy)
}

internal fun fresh(prefix: String) = "$prefix-" + Random.nextLong().toULong().toString(16)

internal fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

private val EditorCommands = CommandSet(listOf(
    Command(CommandId("edit-delete"), "Delete", Chord.Of(KeyName.Delete)),
    Command(CommandId("edit-delete-back"), "Delete", Chord.Of(KeyName.Backspace)),
    Command(CommandId("edit-undo"), "Undo", Chord.Of(KeyName.Z, primary = true)),
    Command(CommandId("edit-redo"), "Redo", Chord.Of(KeyName.Z, primary = true, shift = true)),
    Command(CommandId("edit-redo-y"), "Redo", Chord.Of(KeyName.Y, primary = true)),
    Command(CommandId("edit-copy"), "Copy", Chord.Of(KeyName.C, primary = true)),
    Command(CommandId("edit-cut"), "Cut", Chord.Of(KeyName.X, primary = true)),
    Command(CommandId("edit-paste"), "Paste", Chord.Of(KeyName.V, primary = true)),
    Command(CommandId("edit-duplicate"), "Duplicate", Chord.Of(KeyName.D, primary = true)),
    Command(CommandId("edit-select-all"), "Select all", Chord.Of(KeyName.A, primary = true)),
    Command(CommandId("edit-comment"), "Comment the selection", Chord.Of(KeyName.C)),
    Command(CommandId("edit-straighten"), "Straighten connections", Chord.Of(KeyName.Q)),
    Command(CommandId("edit-align-top"), "Align top", Chord.Of(KeyName.W, shift = true)),
    Command(CommandId("edit-align-middle"), "Align middle", Chord.Of(KeyName.W, shift = true, alt = true)),
    Command(CommandId("edit-align-bottom"), "Align bottom", Chord.Of(KeyName.S, shift = true)),
    Command(CommandId("edit-align-left"), "Align left", Chord.Of(KeyName.A, shift = true)),
    Command(CommandId("edit-align-centre"), "Align centre", Chord.Of(KeyName.S, shift = true, alt = true)),
    Command(CommandId("edit-align-right"), "Align right", Chord.Of(KeyName.D, shift = true)),
    Command(CommandId("edit-save"), "Save", Chord.Of(KeyName.S, primary = true)),
    Command(CommandId("edit-palette"), "Add a node", Chord.Of(KeyName.Space)),
    Command(CommandId("edit-rename"), "Rename", Chord.Of(KeyName.F2)),
    Command(CommandId("edit-escape"), "Cancel", Chord.Of(KeyName.Escape)),
))
