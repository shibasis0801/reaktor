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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import dev.shibasis.reaktor.blueprint.BlueprintEngine
import dev.shibasis.reaktor.blueprint.BlueprintLayout
import dev.shibasis.reaktor.blueprint.Bounds
import dev.shibasis.reaktor.blueprint.Card
import dev.shibasis.reaktor.blueprint.Frame
import dev.shibasis.reaktor.blueprint.Link
import dev.shibasis.reaktor.blueprint.LinkKind
import dev.shibasis.reaktor.blueprint.Pin
import dev.shibasis.reaktor.blueprint.canvas.Aim
import dev.shibasis.reaktor.blueprint.canvas.Area
import dev.shibasis.reaktor.blueprint.canvas.CanvasCamera
import dev.shibasis.reaktor.blueprint.canvas.CanvasEditing
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.ScopeShape
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

data class Template(val label: String, val kind: NodeKind, val type: String, val ports: List<PortShape>, val scope: String? = null, val category: String = kind.name, val part: GraphPart? = null) {
    val key: String get() = "$kind:$type:$label"
    fun offers(pin: PortShape?, fits: (String, String) -> Boolean = Exact) = pin == null || ports.any { it.provides != pin.provides && (if (pin.provides) fits(pin.type, it.type) else fits(it.type, pin.type)) }
    fun joins(pin: PortShape?, fits: (String, String) -> Boolean = Exact) = pin?.let { ports.firstOrNull { it.provides != pin.provides && (if (pin.provides) fits(pin.type, it.type) else fits(it.type, pin.type)) } }
}

data class PortRun(val calls: Int, val failures: Int, val p50Nanos: Long?, val last: String?, val failure: String?)

data class RunFailure(val node: String, val port: String, val text: String)

interface GraphRuns {
    val failures: List<RunFailure>
    fun port(node: String, key: String): PortRun?
}

interface SystemClipboard {
    fun write(text: String)
    fun read(then: (String?) -> Unit)
}

data class Fold(val id: String, val title: String, val members: Set<String>)

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
    data class Find(override val at: Spot, override val window: Offset) : Request
    data class Naming(override val at: Spot, override val window: Offset, val graph: Boolean) : Request
    data class Remark(override val at: Spot, override val window: Offset, val subject: String) : Request
    data class Describe(override val at: Spot, override val window: Offset) : Request
    data class Keys(override val at: Spot, override val window: Offset) : Request
}

data class Carry(val template: Template, val window: Offset)

data class Hit(val id: String, val title: String, val detail: String)

sealed interface Gesture {
    data class Move(val from: Spot, val before: Placement, val anchor: Spot, val cards: Set<String>, val notes: Set<String>, val moved: Boolean = false, val only: String? = null) : Gesture
    data class Wiring(val pin: PinEnd, val at: Spot, val window: Offset, val target: PinEnd? = null, val verdict: Verdict? = null, val carried: List<WireShape> = emptyList(), val picked: PinEnd? = null, val from: Spot = at) : Gesture
    data class Marquee(val from: Spot, val to: Spot, val start: Selection, val mode: Int) : Gesture
    data class Resize(val note: String, val from: Spot, val before: Placement) : Gesture
    data class Bend(val wire: String, val index: Int, val before: Placement, val spot: Spot) : Gesture
}

enum class Edge { Top, Middle, Bottom, Left, Centre, Right }

private data class Step(val shape: GraphShape, val placement: Placement, val title: String, val name: String = "")

private data class Clip(val nodes: List<NodeShape>, val wires: List<WireShape>, val spots: Map<String, Spot>)

@Stable
class GraphEditor(
    base: GraphShape,
    placement: Placement,
    id: String = fresh("draft"),
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
    val origin: Placement = placement,
    val conflicts: List<GraphChange> = emptyList(),
) : CanvasEditing {
    val base: GraphShape = base.injected()
    var id by mutableStateOf(id)
        private set
    var title by mutableStateOf(title)
    var shape by mutableStateOf(shape.injected(base))
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
    override var aim by mutableStateOf<Aim?>(null)
        private set
    internal var found by mutableStateOf(emptySet<String>())
    internal var sought = ""
    var runs by mutableStateOf<GraphRuns?>(null)
    var recovered by mutableStateOf<ChangeSet?>(null)
    var kept by mutableStateOf(false)
    var saved by mutableStateOf<List<ChangeSet>>(emptyList())
    var folded by mutableStateOf(emptySet<String>())
        private set
    var focus by mutableStateOf<String?>(null)
        private set
    var system: SystemClipboard? = null
    var onSelect: (String?) -> Unit = {}
    var onOpen: (String) -> Unit = {}
    var onSave: (GraphDraft) -> Unit = {}
    var onTemplate: ((ChangeSet) -> Unit)? = null
    var prefs by mutableStateOf(EditorPrefs())
    var onPrefs: (EditorPrefs) -> Unit = {}
    var watched by mutableStateOf(emptySet<PinEnd>())
    var trial by mutableStateOf<Trial?>(null)
    var versions by mutableStateOf<List<ChangeSet>>(emptyList())
    var against by mutableStateOf<ChangeSet?>(null)
    var others by mutableStateOf<List<Holder>>(emptyList())
    var onTakeOver: () -> Unit = {}
    var onLocked: () -> Unit = {}
    var onDescribe: ((String, Set<String>) -> Unit)? = null
    var carrying by mutableStateOf<Carry?>(null)
    var stamp: (Long) -> String = { savedAt(it).take(16).replace('T', ' ') }
    var camera: CanvasCamera? by mutableStateOf(null)
        private set
    internal var measurer: TextMeasurer? = null
    var font: FontFamily? = null
    internal var pointer = Spot(0.0, 0.0)
        private set
    private var pointerWindow = Offset.Zero
    private var keptShape by mutableStateOf(this.shape)
    private var keptPlacement by mutableStateOf(placement)
    private var keptTitle by mutableStateOf(title)
    private val undos = mutableStateListOf<Step>()
    private val redos = mutableStateListOf<Step>()
    private val gaps = if (single) this.base.unwired().mapTo(hashSetOf()) { it.text } else emptySet()

    private val baseline: GraphShape by derivedStateOf { against?.graph?.let { this.base.replay(it).shape.injected(this.base) } ?: this.base }
    val diff: GraphDiff by derivedStateOf { GraphDiff(baseline, this.shape, terms) }
    val problems: List<Problem> by derivedStateOf { this.shape.introducedProblems(this.base, single, terms::fits) }
    private val loops by lazy { this.base.cycles().map { loop -> loop.mapTo(hashSetOf()) { it.provider } } }
    val warnings: List<Problem> by derivedStateOf {
        val unwired = if (single) this.shape.unwired().filter { it.text !in gaps } else emptyList()
        unwired + this.shape.cycles().filter { loop -> loop.mapTo(hashSetOf()) { it.provider }.let { members -> loops.none { it.containsAll(members) } } }.map { loop ->
            Problem(loop.first().provider, terms.cycle(loop.map { wire -> this.shape.node(wire.provider)?.label ?: wire.provider }), path = loop.map { it.id })
        }
    }
    val converted: List<Pair<String, String>> by derivedStateOf {
        this.shape.wires.mapNotNull { wire -> this.shape.node(wire.provider)?.port(wire.providerPort, true)?.type?.takeIf { it != wire.type }?.let { from -> terms.conversion(from, wire.type)?.let { wire.id to it } } }
    }
    val locked: Boolean get() = others.any { it.editing && it.ahead }
    val dirty: Boolean by derivedStateOf { this.shape != keptShape || this.placement != keptPlacement || this.title != keptTitle }
    private var built: Built? = null
    private var hold: Hold? = null
    private var hiddenBy: Map<String, String> = emptyMap()
    val layout: BlueprintLayout<String> by derivedStateOf {
        val shown = if (showChanges) diff else null
        val view = folded to focus
        val last = built?.takeIf { it.shape === this.shape && it.diff === shown && it.view == view && (folded.isEmpty() || it.placement.notes == this.placement.notes) }
        (if (last != null) moved(last, this.placement) else scene(this.shape, this.placement, shown))
            .also { built = Built(this.shape, shown, this.placement, it, last?.links ?: Links(it.links), view) }
    }
    val applied: Boolean get() = revision > 0 && conflicts.isEmpty() && (if (against == null) diff.empty else GraphDiff(base, shape, terms).empty) && !dirty
    val history: List<String> get() = undos.asReversed().map { it.name }
    val future: List<String> get() = redos.asReversed().map { it.name }
    val folds: Map<String, Fold> get() = layout.let { hiddenBy.entries.groupBy({ it.value }, { it.key }).mapValues { (id, members) -> Fold(id, placement.notes.firstOrNull { it.id == id }?.text ?: id, members.toSet()) } }
    val crumbs: List<Note> get() = generateSequence(placement.notes.firstOrNull { it.id == focus }) { note -> note.parent?.let { parent -> placement.notes.firstOrNull { it.id == parent } } }.toList().asReversed()
    val settled: BlueprintLayout<String> by derivedStateOf {
        hold?.takeIf { gesture is Gesture.Move && it.shape === shape && it.changes == showChanges }?.layout ?: layout
    }
    val canUndo: Boolean get() = undos.isNotEmpty()
    val canRedo: Boolean get() = redos.isNotEmpty()
    val valid: Boolean get() = problems.isEmpty()

    val templates: List<Template> by lazy {
        catalogue?.let { return@lazy it(this.base) }
        val seen = (this.base.nodes + this.shape.nodes).filter { it.kind != NodeKind.Route }
            .distinctBy { it.kind to it.type }
            .map { node -> Template(node.type.substringAfterLast('.'), node.kind, node.type, node.ports.filterNot(PortShape::internal).map { it.reset() }, node.scope) }
        val blank = listOf(NodeKind.Service, NodeKind.Screen, NodeKind.Interactor, NodeKind.Actor, NodeKind.Controller, NodeKind.Node)
            .map { Template("New ${it.name.lowercase()}", it, it.name, emptyList()) }
        (blank + seen.sortedBy { it.label.lowercase() })
    }

    val choices: List<Template> get() = saved.mapNotNull { set -> set.graph?.let { Template(set.title, NodeKind.Node, set.id, it.signature(), category = SavedCategory, part = it) } }.let { parts ->
        val favourites = prefs.favourites
        (parts + templates).let { all -> if (favourites.isEmpty()) all else all.sortedBy { favourites.indexOf(it.key).let { at -> if (at < 0) Int.MAX_VALUE else at } } }
    }

    override val commands: CommandSet by derivedStateOf { EditorCommands.remapped(prefs.keys) }

    override fun keyed(set: CommandSet): CommandSet = set.remapped(prefs.keys)

    override fun attach(camera: CanvasCamera?) { this.camera = camera }

    fun ghost(id: String): NodeShape? = if (showChanges && diff.nodes[id] == Change.Removed) diff.base.node(id) else null

    fun change(id: String): Change? = if (showChanges) diff.nodes[id] ?: diff.wires[id] else null

    fun node(id: String): NodeShape? = shape.node(id)

    fun select(next: Selection) {
        selection = next
        onSelect(next.nodes.singleOrNull()?.takeIf { next.size == 1 })
    }

    fun selectAll() = select(Selection(shape.nodes.filter { it.kind != NodeKind.Route }.mapTo(hashSetOf()) { it.id }))

    fun undo() {
        if (refused()) return
        val step = undos.removeLastOrNull() ?: return
        redos += Step(shape, placement, title, step.name)
        restore(step)
    }

    fun redo() {
        if (refused()) return
        val step = redos.removeLastOrNull() ?: return
        undos += Step(shape, placement, title, step.name)
        restore(step)
    }

    fun undo(steps: Int) = repeat(steps) { undo() }

    fun redo(steps: Int) = repeat(steps) { redo() }

    private fun restore(step: Step) {
        shape = step.shape; placement = step.placement; title = step.title
        select(Selection(selection.nodes.filterTo(hashSetOf()) { shape.node(it) != null }, selection.wires.filterTo(hashSetOf()) { id -> shape.wires.any { it.id == id } },
            selection.notes.filterTo(hashSetOf()) { id -> placement.notes.any { it.id == id } }))
    }

    fun edit(name: String? = null, change: (GraphShape, Placement) -> Pair<GraphShape, Placement>) {
        if (refused()) return
        val (nextShape, nextPlacement) = change(shape, placement)
        if (nextShape == shape && nextPlacement == placement) return
        record(Step(shape, placement, title, name ?: named(shape, nextShape, placement, nextPlacement)))
        shape = nextShape; placement = nextPlacement
    }

    private fun named(before: GraphShape, after: GraphShape, was: Placement, now: Placement): String {
        val changes = if (before == after) emptyList() else before.changesTo(after, terms)
        return changes.firstOrNull()?.let { if (changes.size == 1) it.summary else "${it.summary} and ${changes.size - 1} more" } ?: when {
            was.notes.size < now.notes.size -> "Add a comment"
            was.notes.size > now.notes.size -> "Delete a comment"
            was.notes != now.notes -> if (was.notes.map { it.copy(x = 0.0, y = 0.0) } == now.notes.map { it.copy(x = 0.0, y = 0.0) }) "Move a comment" else "Edit a comment"
            was.bends != now.bends -> "Reroute a wire"
            else -> now.cards.count { (id, at) -> was.cards[id] != at }.let { "Move $it card${if (it == 1) "" else "s"}" }
        }
    }

    private fun refused(): Boolean = locked.also { shut ->
        if (!shut) return@also
        notice = others.firstOrNull { it.editing && it.ahead }?.let { "${it.name} is editing this draft · take over to change it" }
        onLocked()
    }

    fun takeOver() { others = others.map { it.copy(ahead = false) }; onTakeOver() }

    private fun record(step: Step) {
        undos += step
        if (undos.size > HistoryLimit) undos.removeAt(0)
        redos.clear()
    }

    fun rename(id: String, text: String) {
        val value = text.trim().ifEmpty { return }
        if (placement.notes.any { it.id == id }) edit { s, p ->
            s.copy(scopes = s.scopes.map { if (it.id == id) it.copy(label = value) else it }) to p.copy(notes = p.notes.map { if (it.id == id) it.copy(text = value) else it })
        }
        else update(id) { it.copy(label = value) }
    }

    fun rename(node: String, port: PortShape, key: String) {
        val value = key.trim().ifEmpty { return }
        val owner = shape.node(node) ?: return
        if (value == port.key) return
        if (owner.port(value, port.provides) != null) { notice = "${owner.label} already has ${if (port.provides) "an output" else "an input"} named $value"; return }
        fun carried(wire: WireShape) = if (port.provides) wire.takeIf { it.provider == node && it.providerPort == port.key }?.copy(providerPort = value)
            else wire.takeIf { it.consumer == node && it.consumerPort == port.key }?.copy(consumerPort = value)
        edit { s, p ->
            val renamed = s.wires.mapNotNull { wire -> carried(wire)?.let { wire.id to it } }.toMap()
            s.copy(wires = s.wires.map { renamed[it.id] ?: it }).with(owner.copy(ports = owner.ports.map { if (it == port) it.copy(key = value) else it })) to
                p.copy(bends = p.bends.mapKeys { (id, _) -> renamed[id]?.id ?: id })
        }
    }

    fun update(id: String, change: (NodeShape) -> NodeShape) {
        val node = shape.node(id) ?: return
        val next = change(node)
        val dropped = shape.with(next).let { after -> shape.wires.count { wire -> (wire.provider == id || wire.consumer == id) && after.wires.none { it.id == wire.id } } }
        edit { s, p -> s.with(next) to p }
        if (dropped > 0) notice = "Removed $dropped wire${if (dropped == 1) "" else "s"} that no longer fit"
    }

    fun setting(id: String, key: String, value: String?) = update(id) { it.copy(attributes = if (value == null) it.attributes - key else it.attributes + (key to value)) }

    fun recolour(note: String, tint: Long) = edit { s, p -> s to p.copy(notes = p.notes.map { if (it.id == note) it.copy(tint = tint) else it }) }

    fun add(template: Template, at: Spot, from: PinEnd? = null): String {
        recent.remove(template.key); recent.add(0, template.key); if (recent.size > Recents) recent.removeAt(recent.lastIndex)
        template.part?.let { part ->
            val clip = clipOf(part) ?: return ""
            val set = saved.firstOrNull { it.id == template.type }
            val use = fresh("use")
            val join = from?.let { pin -> template.joins(shape.node(pin.node)?.port(pin.key, pin.provides), terms::fits)?.let { pin to it } }
            return paste(at, clip, { node -> set?.let { node.linked(it, use, node.id) } ?: node }, join).values.firstOrNull().orEmpty()
        }
        val id = fresh("node")
        val scope = scopeAt(at, from)
        val node = NodeShape(id, scope, unique(template.label), template.type, template.kind, ports = template.ports.map { it.reset() })
        val height = BlueprintEngine.cardHeight(rows(pins(node)))
        val start = free(snapped(Spot(at.x - cardWidth / 2, at.y - BlueprintEngine.HeaderHeight / 2)), height)
        edit { s, p ->
            var next = s.with(node)
            from?.let { pin ->
                val match = template.joins(next.node(pin.node)?.port(pin.key, pin.provides), terms::fits)
                if (match != null) next = (if (pin.provides) next.wire(pin.node, pin.key, id, match.key, single, terms::fits) else next.wire(id, match.key, pin.node, pin.key, single, terms::fits))?.let { next.connect(it, single) } ?: next
            }
            next to p.copy(cards = p.cards + (id to start), notes = p.notes.map { if (it.id == scope) it.holding(start, cardWidth, height) else it })
        }
        select(Selection(setOf(id)))
        return id
    }

    private fun free(start: Spot, height: Double): Spot {
        val boxes = layout.cards.values
        val gap = BlueprintEngine.CardGap
        fun clear(at: Spot) = boxes.none { at.x < it.x + it.width + gap && at.x + cardWidth + gap > it.x && at.y < it.y + it.height + gap && at.y + height + gap > it.y }
        if (clear(start)) return start
        val step = grid * 2
        return (1..Reach).asSequence().flatMap { ring -> sequenceOf(Spot(start.x, start.y + ring * step), Spot(start.x + ring * step, start.y), Spot(start.x, start.y - ring * step), Spot(start.x - ring * step, start.y)) }
            .firstOrNull(::clear) ?: start
    }

    fun tidy(target: Selection = selection) {
        val cards = target.nodes.mapNotNull(layout.cards::get)
        if (cards.size < 2) return
        val ids = cards.mapTo(hashSetOf()) { it.id }
        val feeds = shape.wires.filter { it.provider in ids && it.consumer in ids && it.provider != it.consumer }.groupBy({ it.consumer }, { it.provider })
        val layer = HashMap<String, Int>()
        fun depth(id: String, seen: Set<String> = emptySet()): Int = layer[id] ?: (feeds[id].orEmpty().filter { it !in seen }.maxOfOrNull { depth(it, seen + id) + 1 } ?: 0).also { layer[id] = it }
        val left = cards.minOf { it.x }; val top = cards.minOf { it.y }
        val moves = cards.groupBy { depth(it.id) }.entries.sortedBy { it.key }.flatMap { (column, members) ->
            var y = top
            members.sortedBy { it.y }.map { card -> (card.id to snapped(Spot(left + column * (cardWidth + BlueprintEngine.LayerGap), y))).also { y += card.height + BlueprintEngine.CardGap } }
        }
        edit("Tidy up ${cards.size} cards") { s, p -> s to p.copy(cards = p.cards + moves) }
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
        val verdict = shape.verdict(out.node, out.key, into.node, into.key, single, terms::fits, terms::conversion)
        if (!verdict.ok) { adapter(out, into)?.let { bridge(out, into, it); return verdict(a, b) }; notice = verdict.message; return verdict }
        val wire = shape.wire(out.node, out.key, into.node, into.key, single, terms::fits) ?: return verdict
        edit { s, p -> s.connect(wire, single) to p }
        return verdict
    }

    fun verdict(a: PinEnd, b: PinEnd): Verdict {
        if (a.provides == b.provides) return Verdict(false, if (a.provides) "Both are outputs" else "Both are inputs")
        val (out, into) = if (a.provides) a to b else b to a
        val verdict = shape.verdict(out.node, out.key, into.node, into.key, single, terms::fits, terms::conversion)
        if (verdict.ok || a.provides == b.provides) return verdict
        return adapter(out, into)?.let { Verdict(true, "Convert through ${it.label} · adds a ${it.label} node between them") } ?: verdict
    }

    fun adapter(out: PinEnd, into: PinEnd): Template? {
        val from = shape.node(out.node)?.port(out.key, true)?.type ?: return null
        val to = shape.node(into.node)?.port(into.key, false)?.type ?: return null
        if (out.node == into.node || terms.fits(from, to)) return null
        return choices.firstOrNull { t -> t.part == null && t.ports.count { !it.provides } == 1 && t.ports.count { it.provides } == 1 &&
            t.ports.any { !it.provides && terms.fits(from, it.type) } && t.ports.any { it.provides && terms.fits(it.type, to) } }
    }

    private fun bridge(out: PinEnd, into: PinEnd, adapter: Template) {
        val (ax, ay) = anchor(out) ?: return
        val (bx, by) = anchor(into) ?: return
        val id = fresh("node")
        val node = NodeShape(id, shape.node(into.node)?.scope ?: scopeAt(Spot(bx, by), null), unique(adapter.label), adapter.type, adapter.kind, ports = adapter.ports.map { it.reset() })
        val input = adapter.ports.first { !it.provides }.key
        val output = adapter.ports.first { it.provides }.key
        val height = BlueprintEngine.cardHeight(rows(pins(node)))
        val at = free(snapped(Spot((ax + bx) / 2 - cardWidth / 2, (ay + by) / 2 - height / 2)), height)
        edit("Convert through ${node.label}") { s, p ->
            var next = s.with(node)
            next.wire(out.node, out.key, id, input, single, terms::fits)?.let { next = next.connect(it, single) }
            next.wire(id, output, into.node, into.key, single, terms::fits)?.let { next = next.connect(it, single) }
            next to p.copy(cards = p.cards + (id to at))
        }
        select(Selection(setOf(id)))
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

    private fun clip(target: Selection): Clip? {
        val nodes = shape.nodes.filter { it.id in target.nodes }.ifEmpty { return null }
        val ids = nodes.mapTo(hashSetOf()) { it.id }
        return Clip(nodes, shape.wires.filter { it.provider in ids && it.consumer in ids }, ids.mapNotNull { id -> layout.cards[id]?.let { id to Spot(it.x, it.y) } }.toMap())
    }

    fun copy(target: Selection = selection): Int {
        val clip = clip(target) ?: return 0
        clipboard = clip
        system?.write(fragment(clip, "Copied from $title").encode())
        notice = "Copied ${clip.nodes.size} node${if (clip.nodes.size == 1) "" else "s"}"
        return clip.nodes.size
    }

    fun cut() { if (copy() > 0) delete(Selection(selection.nodes)) }

    fun paste(at: Spot = pointer) {
        val held = clipboard
        val reader = system
        if (reader == null) held?.let { paste(at, it) }
        else reader.read { text -> (text?.let(::clipOf) ?: held)?.let { paste(at, it) } }
    }

    fun paste(at: Spot, text: String): Boolean = clipOf(text)?.let { paste(at, it); true } ?: false

    fun fragment(target: Selection = selection, name: String = "Copied from $title"): ChangeSet? = clip(target)?.let { fragment(it, name) }

    fun template(name: String) {
        val keep = onTemplate ?: run { notice = "Templates are not available here"; return }
        val made = fragment(selection, name.trim().ifEmpty { return }) ?: run { notice = "Select the nodes to keep as a template"; return }
        val old = saved.firstOrNull { it.title == made.title }
        val set = old?.let { made.copy(id = it.id, revision = it.revision + 1) } ?: made
        keep(set)
        saved = listOf(set) + saved.filterNot { it.id == set.id }
        notice = "Saved the template ${set.title}" + (old?.let { " · revision ${set.revision}" } ?: "") + stale(set.id).size.takeIf { it > 0 }?.let { " · $it use${if (it == 1) "" else "s"} can be updated" }.orEmpty()
    }

    fun recovery(now: Long = nowMillis()): ChangeSet = changeSet(draft(now)).copy(revision = revision)

    fun restore(set: ChangeSet) {
        if (refused()) return
        val part = set.graph ?: return
        val replay = base.replay(part)
        id = set.id; revision = set.revision
        edit("Restore unsaved edits") { _, p -> replay.shape.injected(base) to (part.layout?.let(origin::with) ?: p) }
        title = set.title
        recovered = null
    }

    fun group(name: String, target: Selection = selection) {
        val nodes = target.nodes.mapNotNull(shape::node).ifEmpty { return }
        val cards = nodes.mapNotNull { layout.cards[it.id] }.ifEmpty { return }
        val ids = nodes.mapTo(hashSetOf()) { it.id }
        val id = fresh("graph")
        val parent = nodes.first().scope
        val left = cards.minOf { it.x } - BlueprintEngine.FrameSide; val top = cards.minOf { it.y } - BlueprintEngine.FrameTop
        val note = Note(id, name, left, top, cards.maxOf { it.x + it.width } + BlueprintEngine.FrameSide - left, cards.maxOf { it.y + it.height } + BlueprintEngine.FrameSide - top,
            parent = parent.takeIf { scope -> placement.notes.any { it.id == scope } })
        edit("Group ${nodes.size} node${if (nodes.size == 1) "" else "s"} into $name") { s, p ->
            s.copy(scopes = s.scopes + ScopeShape(id, name, parent), nodes = s.nodes.map { if (it.id in ids) it.copy(scope = id) else it }) to p.copy(notes = p.notes + note)
        }
        select(Selection(notes = setOf(id)))
    }

    fun fold(note: String) {
        if (placement.notes.none { it.id == note }) return
        folded = folded + note
        select(Selection(notes = setOf(note)))
    }

    fun unfold(note: String) { folded = folded - note }

    fun open(note: String?) {
        focus = note?.takeIf { id -> placement.notes.any { it.id == id } }
        folded = folded - setOfNotNull(focus)
        select(Selection())
        layout.cards.values.toList().takeIf { it.isNotEmpty() }?.let { aim = Aim(it) }
    }

    private fun clipOf(text: String): Clip? = runCatching { ChangeSet.decode(text) }.getOrNull()?.graph?.let(::clipOf)

    private fun clipOf(part: GraphPart): Clip? {
        val nodes = part.changes.filter { it.op == "addNode" }.mapNotNull { it.node }.ifEmpty { return null }
        val ids = nodes.mapTo(hashSetOf()) { it.id }
        return Clip(nodes, part.changes.filter { it.op == "connect" }.mapNotNull { it.wire }.filter { it.provider in ids && it.consumer in ids }, part.layout?.cards.orEmpty().filterKeys { it in ids })
    }

    private fun fragment(clip: Clip, name: String): ChangeSet {
        val byId = clip.nodes.associateBy { it.id }
        fun end(id: String, key: String) = byId[id]?.let { terms.end(it, key) } ?: "$id.$key"
        val changes = clip.nodes.map { GraphChange("addNode", "Add ${terms.summary(it, Change.Added)} named ${it.label}", node = it) } +
            clip.wires.map { GraphChange("connect", "Connect ${end(it.provider, it.providerPort)} → ${end(it.consumer, it.consumerPort)}", wire = it) }
        return ChangeSet(about = FragmentAbout, id = fresh("nodes"), title = name, revision = 1, savedAt = savedAt(nowMillis()), summary = changes.map { it.summary },
            graph = GraphPart(changes = changes, layout = Placement(clip.spots)))
    }

    private fun paste(at: Spot, clip: Clip, stamp: (NodeShape) -> NodeShape = { it }, join: Pair<PinEnd, PortShape>? = null): Map<String, String> {
        val left = clip.spots.values.minOfOrNull { it.x } ?: 0.0
        val top = clip.spots.values.minOfOrNull { it.y } ?: 0.0
        val renamed = clip.nodes.associate { it.id to fresh("node") }
        val scope = scopeAt(at, join?.first)
        val taken = shape.nodes.mapTo(hashSetOf()) { it.label }
        val nodes = clip.nodes.map { node -> stamp(node).copy(id = renamed.getValue(node.id), label = unique(node.label, taken).also { taken += it }, scope = if (shape.scopes.any { it.id == node.scope }) node.scope else scope,
            attachedTo = null, definitionId = "", ports = node.ports.map { it.reset() }) }
        val wires = clip.wires.map { it.copy(provider = renamed.getValue(it.provider), consumer = renamed.getValue(it.consumer), definitionId = "") }
        val spots = clip.nodes.associate { node -> renamed.getValue(node.id) to (clip.spots[node.id] ?: Spot(left, top)).let { snapped(Spot(it.x - left + at.x, it.y - top + at.y)) } }
        edit { s, p ->
            var next = nodes.fold(s) { acc, node -> acc.copy(nodes = acc.nodes + node) }.let { it.copy(wires = it.wires + wires) }.recounted()
            join?.let { (pin, port) ->
                val inner = renamed[port.key.substringBefore(FoldSeparator)] ?: return@let
                val key = port.key.substringAfter(FoldSeparator)
                (if (pin.provides) next.wire(pin.node, pin.key, inner, key, single, terms::fits) else next.wire(inner, key, pin.node, pin.key, single, terms::fits))?.let { next = next.connect(it, single) }
            }
            next to p.copy(cards = p.cards + spots)
        }
        select(Selection(renamed.values.toSet()))
        return renamed
    }

    fun keepPart(node: String) {
        val keep = onTemplate ?: run { notice = "Templates are not available here"; return }
        val owner = shape.node(node) ?: return
        val id = owner.part ?: return
        val members = (selection.nodes.takeIf { node in it && it.size > 1 } ?: shape.nodes.filter { it.attributes[PartUseKey] == owner.attributes[PartUseKey] }.map { it.id }).mapNotNull(shape::node)
        val old = saved.firstOrNull { it.id == id }
        val original = members.associate { it.id to (it.attributes[PartNodeKey] ?: it.id) }
        val clip = clip(Selection(original.keys)) ?: return
        val plain = Clip(clip.nodes.map { it.copy(id = original.getValue(it.id), attributes = it.attributes.filterKeys { key -> !key.startsWith(PartPrefix) }) },
            clip.wires.map { it.copy(provider = original.getValue(it.provider), consumer = original.getValue(it.consumer)) }, clip.spots.mapKeys { original.getValue(it.key) })
        val set = fragment(plain, old?.title ?: owner.label).copy(id = id, revision = (old?.revision ?: 0) + 1)
        keep(set)
        saved = listOf(set) + saved.filterNot { it.id == id }
        edit("Keep ${set.title} as revision ${set.revision}") { s, p -> s.copy(nodes = s.nodes.map { if (it.id in original) it.copy(attributes = it.attributes + (PartIdKey to id) + (PartUseKey to owner.attributes[PartUseKey].orEmpty()) + (PartNodeKey to original.getValue(it.id)) + (PartRevisionKey to set.revision.toString())) else it }) to p }
        val stale = shape.uses(id).count { it.revision < set.revision }
        notice = "Kept ${set.title} as revision ${set.revision}" + if (stale > 0) " · $stale other use${if (stale == 1) "" else "s"} can be updated" else ""
    }

    fun stale(part: String): List<PartUse> = saved.firstOrNull { it.id == part }?.let { set -> shape.uses(part).filter { it.revision < set.revision } }.orEmpty()

    fun updateUses(part: String) {
        val set = saved.firstOrNull { it.id == part } ?: return
        val clip = set.graph?.let(::clipOf) ?: return
        val uses = stale(part)
        if (uses.isEmpty()) { notice = "Every use of ${set.title} is up to date"; return }
        edit("Update ${uses.size} use${if (uses.size == 1) "" else "s"} of ${set.title} to revision ${set.revision}") { s, p -> uses.fold(s to p) { (shape, place), use -> restamped(shape, place, use, clip, set) } }
        notice = "Updated ${uses.size} use${if (uses.size == 1) "" else "s"} of ${set.title}"
    }

    private fun restamped(s: GraphShape, p: Placement, use: PartUse, clip: Clip, set: ChangeSet): Pair<GraphShape, Placement> {
        val old = s.nodes.filter { it.id in use.nodes }
        val byPart = old.associateBy { it.attributes[PartNodeKey].orEmpty() }
        val ids = clip.nodes.associate { it.id to (byPart[it.id]?.id ?: fresh("node")) }
        val mine = use.nodes.toSet()
        val taken = s.nodes.filter { it.id !in mine }.mapTo(hashSetOf()) { it.label }
        val nodes = clip.nodes.map { node ->
            val was = byPart[node.id]
            node.linked(set, use.use, node.id).copy(id = ids.getValue(node.id), label = was?.label ?: unique(node.label, taken).also { taken += it }, scope = was?.scope ?: old.first().scope,
                attachedTo = was?.attachedTo, definitionId = was?.definitionId.orEmpty(), ports = node.ports.map { it.reset() })
        }
        val now = ids.values.toSet()
        val inside = clip.wires.map { it.copy(provider = ids.getValue(it.provider), consumer = ids.getValue(it.consumer), definitionId = "") }
        val outside = s.wires.filter { (it.provider in mine) != (it.consumer in mine) && (it.provider !in mine || it.provider in now) && (it.consumer !in mine || it.consumer in now) }
        val next = s.copy(nodes = s.nodes.filter { it.id !in mine } + nodes, wires = s.wires.filter { it.provider !in mine && it.consumer !in mine } + inside + outside)
            .let { g -> g.copy(wires = g.wires.filter { w -> g.node(w.provider)?.port(w.providerPort, true) != null && g.node(w.consumer)?.port(w.consumerPort, false) != null }) }.recounted()
        val anchor = byPart.entries.firstNotNullOfOrNull { (original, node) -> clip.spots[original]?.let { from -> p.cards[node.id]?.let { at -> Spot(at.x - from.x, at.y - from.y) } } }
            ?: old.mapNotNull { p.cards[it.id] }.let { spots -> Spot((spots.minOfOrNull { it.x } ?: 0.0) - (clip.spots.values.minOfOrNull { it.x } ?: 0.0), (spots.minOfOrNull { it.y } ?: 0.0) - (clip.spots.values.minOfOrNull { it.y } ?: 0.0)) }
        val spots = clip.nodes.filter { byPart[it.id] == null }.associate { node -> ids.getValue(node.id) to snapped((clip.spots[node.id] ?: Spot(0.0, 0.0)).let { Spot(it.x + anchor.x, it.y + anchor.y) }) }
        return next to p.copy(cards = p.cards + spots)
    }

    fun duplicate() {
        val clip = clip(selection) ?: return
        paste(Spot((clip.spots.values.minOfOrNull { it.x } ?: pointer.x) + grid * 2, (clip.spots.values.minOfOrNull { it.y } ?: pointer.y) + grid * 2), clip)
    }

    fun draft(now: Long): GraphDraft = GraphDraft(id, title, revision + 1, now, base, shape,
        placement.copy(cards = placement.cards.filterKeys { id -> shape.node(id) != null || base.node(id) != null }, remarks = placement.remarks.filterKeys { shape.node(it) != null }))

    fun mark(slot: Int) {
        val seen = camera?.seen ?: return
        val before = placement.marks[slot]
        edit("Set bookmark $slot") { s, p -> s to p.copy(marks = p.marks + (slot to Mark(seen.x, seen.y, seen.width, seen.height))) }
        if (placement.marks[slot] != before) notice = "Bookmark $slot set · press $slot to come back here"
    }

    fun goTo(slot: Int) {
        val mark = placement.marks[slot] ?: run { notice = "No bookmark $slot yet · ⌃$slot sets one"; return }
        camera?.frame(Area(mark.x, mark.y, mark.width, mark.height))
    }

    fun remark(id: String, text: String) {
        val value = text.trim()
        if (placement.remarks[id].orEmpty() == value) return
        val name = node(id)?.label ?: id
        edit(if (value.isEmpty()) "Remove the note on $name" else "Note on $name") { s, p -> s to p.copy(remarks = if (value.isEmpty()) p.remarks - id else p.remarks + (id to value)) }
    }

    fun watch(pin: PinEnd) {
        watched = if (pin in watched) watched - pin else watched + pin
        notice = if (pin in watched) "Watching ${node(pin.node)?.let { terms.end(it, pin.key) } ?: pin.key} · its latest value shows on the pin" else null
    }

    fun prefer(next: EditorPrefs) { prefs = next; onPrefs(next) }

    fun templateOf(id: String): Template? = shape.node(id)?.let { node -> templates.firstOrNull { it.part == null && it.kind == node.kind && it.type == node.type } }

    fun favour(template: Template) = prefer(prefs.copy(favourites = if (template.key in prefs.favourites) prefs.favourites - template.key else listOf(template.key) + prefs.favourites))

    fun describe(text: String) {
        val send = onDescribe ?: run { notice = "Describing a change needs an agent, and none is connected here"; return }
        val value = text.trim().ifEmpty { return }
        send(value, selection.nodes)
        notice = "Sent to the agent · its draft opens here as a diff when it is ready"
    }

    fun drop(window: Offset) {
        val carried = carrying ?: return
        carrying = null
        camera?.world(window)?.let { add(carried.template, Spot(it.x.toDouble(), it.y.toDouble())) } ?: run { notice = "Drop it on the canvas to add it" }
    }

    fun compare(version: ChangeSet?) {
        against = version?.takeIf { it.graph != null && it != against }
        showChanges = true
    }

    fun rollback(version: ChangeSet) {
        val part = version.graph ?: return
        against = null
        edit("Restore revision ${version.revision}") { _, _ -> base.replay(part).shape.injected(base) to (part.layout?.let(origin::with) ?: origin) }
    }

    fun bundle(pin: PinEnd) {
        val (x, y) = anchor(pin) ?: return
        val ids = links(pin).map { it.id }.filter { id -> layout.links.any { it.id == id } }
        if (ids.size < 2) return
        val knot = Spot(snap(x + if (pin.provides) BundleReach else -BundleReach), snap(y))
        edit("Bundle ${ids.size} links through one knot") { s, p -> s to p.copy(bends = p.bends + ids.associateWith { id -> p.bends[id].orEmpty().let { if (pin.provides) listOf(knot) + it else it + knot } }) }
    }

    override fun travel(dx: Int, dy: Int): Boolean {
        val shown = layout
        val from = (selection.nodes.singleOrNull() ?: selection.notes.singleOrNull()?.takeIf { it in folded })?.let(shown.cards::get) ?: return false
        fun card(id: String) = (shape.visible(id) ?: id).let { shown.cards[hiddenBy[it] ?: it] }?.takeIf { it.id != from.id }
        val fromId = shape.nodes.filter { (shape.visible(it.id) ?: it.id).let { v -> (hiddenBy[v] ?: v) == from.id } }.mapTo(hashSetOf()) { it.id } + from.id
        val feeds = shape.wires.filter { it.provider in fromId }.map { it.consumer }
        val fed = shape.wires.filter { it.consumer in fromId }.map { it.provider }
        val linked = when {
            dx > 0 -> feeds
            dx < 0 -> fed
            else -> shape.wires.filter { it.provider in fed.toSet() }.map { it.consumer } + shape.wires.filter { it.consumer in feeds.toSet() }.map { it.provider }
        }.mapNotNull(::card).distinct()
        fun ahead(card: Card) = when { dx > 0 -> card.x > from.x; dx < 0 -> card.x < from.x; dy > 0 -> card.y > from.y; else -> card.y < from.y }
        fun cost(card: Card) = if (dx != 0) abs(card.y - from.y) + abs(card.x - from.x) / 4 else abs(card.y - from.y) + abs(card.x - from.x) * 2
        val next = (if (dx != 0) linked.minByOrNull(::cost) else linked.filter(::ahead).minByOrNull(::cost))
            ?: shown.cards.values.filter { it.id != from.id && ghost(it.id) == null && ahead(it) }.minByOrNull(::cost) ?: return true
        if (next.id in folded) select(Selection(notes = setOf(next.id))) else select(Selection(setOf(next.id)))
        camera?.reveal(next)
        return true
    }

    fun saved(draft: GraphDraft) {
        revision = draft.revision
        keptShape = draft.shape; keptPlacement = placement; keptTitle = draft.title
    }

    fun save(now: Long) {
        if (!valid) { notice = problems.first().text; return }
        onSave(draft(now))
    }

    fun revert() {
        if (refused()) return
        record(Step(shape, placement, title, "Discard changes"))
        shape = keptShape; placement = keptPlacement; title = keptTitle
        select(Selection())
    }

    fun revert(entry: Difference) {
        val subject = entry.subject
        val base = diff.base
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
                    val verdict = shape.verdict(wire.provider, wire.providerPort, wire.consumer, wire.consumerPort, single, terms::fits, terms::conversion)
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
        show(id)
        when {
            layout.cards.containsKey(id) -> select(Selection(setOf(id)))
            shape.wires.any { it.id == id } -> select(Selection(wires = setOf(id)))
            placement.notes.any { it.id == id } -> select(Selection(notes = setOf(id)))
        }
        boxes(id).takeIf { it.isNotEmpty() }?.let { aim = Aim(it) }
    }

    private fun show(id: String) {
        val target = shape.visible(id) ?: id
        repeat(Nesting) {
            if (layout.cards.containsKey(target) || shape.node(target) == null) return
            val fold = hiddenBy[target]
            when {
                fold != null -> unfold(fold)
                focus != null -> focus = null
                else -> return
            }
        }
    }

    private fun place(id: String): String? = hiddenBy[id]?.let { fold -> placement.notes.firstOrNull { it.id == fold }?.text?.let { "inside $it" } }
        ?: focus?.takeIf { id !in layout.cards }?.let { "outside ${crumbs.lastOrNull()?.text ?: "this view"}" }

    private fun boxes(id: String): List<Bounds> {
        val shown = layout
        shown.cards[id]?.let { return listOf(it) }
        shown.frames.firstOrNull { it.key == id }?.let { return listOf(it) }
        fun ends(from: String, to: String) = listOfNotNull(shown.cards[shape.visible(from) ?: base.visible(from) ?: from], shown.cards[shape.visible(to) ?: base.visible(to) ?: to])
        (shape.wires + base.wires).firstOrNull { it.id == id }?.let { return ends(it.provider, it.consumer) }
        (shape.routes + base.routes).firstOrNull { it.id == id }?.let { return ends(it.from, it.to) }
        link(id)?.let { return listOfNotNull(shown.cards[it.from], shown.cards[it.to]) }
        return shown.cards.values.filter { card -> (shape.node(card.id) ?: ghost(card.id))?.scope == id }
    }

    override fun framed(): List<Bounds> = selection.let { chosen ->
        chosen.nodes.mapNotNull(layout.cards::get) + layout.frames.filter { it.key in chosen.notes } + chosen.wires.flatMap(::boxes)
    }

    fun find(text: String): List<Hit> {
        val words = text.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        fun matches(fields: List<String>) = words.all { word -> fields.any { it.lowercase().contains(word) } }
        val scopes = shape.scopes.associateBy { it.id }
        val shown = layout
        return buildList {
            (shape.nodes.filter { it.kind != NodeKind.Route }.map { it.id } + shown.cards.keys.filter { ghost(it) != null }).forEach { id ->
                val node = node(id) ?: ghost(id) ?: return@forEach
                val named = listOfNotNull(node.label, node.type.takeIf { terms.types })
                val ports = node.ports.filterNot { it.internal }.map { terms.port(node, it.key) }.distinct()
                val where = if (id in shown.cards) null else place(id)
                if (matches(named)) add(Hit(id, node.label, listOfNotNull(node.type.takeIf { terms.types }, scopes[node.scope]?.label, where).joinToString(" · ")))
                else if (matches(named + ports)) add(Hit(id, node.label, listOfNotNull(ports.first { port -> words.any { port.lowercase().contains(it) } }, where).joinToString(" · ")))
            }
            placement.notes.forEach { note -> if (note.id !in scopes && matches(listOf(note.text))) add(Hit(note.id, note.text, "comment")) }
            shape.scopes.forEach { scope -> if (matches(listOf(scope.label))) add(Hit(scope.id, scope.label, terms.scope)) }
        }.sortedWith(compareBy({ !it.title.lowercase().startsWith(words.first()) }, { it.title.lowercase() }))
    }

    fun selectLinked(upstream: Boolean) {
        if (selection.nodes.isEmpty()) return
        val next = if (upstream) shape.wires.groupBy({ it.consumer }, { it.provider }) else shape.wires.groupBy({ it.provider }, { it.consumer })
        val seen = HashSet(selection.nodes)
        val frontier = ArrayDeque(selection.nodes)
        while (frontier.isNotEmpty()) next[frontier.removeFirst()]?.forEach { if (seen.add(it)) frontier.addLast(it) }
        val chosen = seen.mapNotNullTo(hashSetOf()) { id -> (shape.visible(id) ?: id).takeIf { it in layout.cards } }
        notice = "Selected ${chosen.size - selection.nodes.size} more node${if (chosen.size - selection.nodes.size == 1) "" else "s"}"
        select(Selection(chosen))
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
            "edit-find" -> request = Request.Find(pointer, pointerWindow)
            "edit-rename" -> (selection.nodes.singleOrNull() ?: selection.notes.singleOrNull())?.let { subject ->
                val box = layout.cards[subject] ?: layout.frames.firstOrNull { it.key == subject }
                request = Request.Rename(box?.let { Spot(it.x, it.y) } ?: pointer, pointerWindow, subject, note = subject !in layout.cards)
            }
            "edit-tidy" -> tidy()
            "edit-remark" -> selection.nodes.singleOrNull()?.let { subject -> request = Request.Remark(layout.cards[subject]?.let { Spot(it.x, it.y) } ?: pointer, pointerWindow, subject) }
            "edit-describe" -> request = Request.Describe(pointer, pointerWindow)
            "edit-keys" -> request = Request.Keys(pointer, pointerWindow)
            "edit-minimap" -> prefer(prefs.copy(minimap = !prefs.minimap))
            "edit-escape" -> if (request != null) request = null else if (gesture != null) cancel() else if (!selection.empty || focus == null) select(Selection()) else open(crumbs.dropLast(1).lastOrNull()?.id)
            else -> command.value.substringAfter("edit-mark-", "").toIntOrNull()?.let(::mark) ?: command.value.substringAfter("edit-go-", "").toIntOrNull()?.let(::goTo)
        }
    }

    private fun cancel() {
        when (val g = gesture) {
            is Gesture.Move -> placement = g.before
            is Gesture.Resize -> placement = g.before
            is Gesture.Bend -> placement = g.before
            is Gesture.Marquee -> select(g.start)
            is Gesture.Wiring, null -> {}
        }
        gesture = null
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
        if (locked) {
            cardAt(at)?.let { card -> if (clicks >= 2 && card.id !in folded) onOpen(card.id) else if (card.id in folded) select(Selection(notes = setOf(card.id))) else select(Selection(setOf(card.id))); return true }
            noteAt(at)?.let { (note, _) -> select(Selection(notes = setOf(note.id))); return true }
            refused()
            return false
        }
        knotAt(at, reach)?.let { (wire, index) ->
            val spot = placement.bends.getValue(wire)[index]
            if (clicks >= 2) edit("Remove a reroute point") { s, p -> s to p.copy(bends = p.bends.mapValues { (_, spots) -> spots.filter { it != spot } }.filterValues { it.isNotEmpty() }) }
            else gesture = Gesture.Bend(wire, index, placement, spot)
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
        cardAt(at)?.takeIf { it.id in folded }?.let { card ->
            if (clicks >= 2) { unfold(card.id); return true }
            val note = placement.notes.first { it.id == card.id }
            val inside = inside(note)
            select(Selection(notes = setOf(note.id)))
            move(Gesture.Move(at, placement, Spot(note.x, note.y), inside.first, inside.second))
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
            is Gesture.Bend -> Spot(snap(at.x), snap(at.y)).let { to -> placement = g.before.copy(bends = g.before.bends.mapValues { (_, spots) -> spots.map { if (it == g.spot) to else it } }) }
            null -> {}
        }
    }

    override fun release(x: Float, y: Float, window: Offset) {
        point(x, y, window)
        val g = gesture
        gesture = null
        when (g) {
            is Gesture.Move -> if (g.moved) { record(Step(shape, g.before, title, named(shape, shape, g.before, placement))); adopt(g.cards) } else g.only?.let { select(Selection(setOf(it))) }
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
            is Gesture.Resize -> if (placement != g.before) record(Step(shape, g.before, title, "Resize a comment"))
            is Gesture.Bend -> {
                val moved = Spot(snap(pointer.x), snap(pointer.y))
                val near = placement.bends.values.asSequence().flatten().firstOrNull { it != moved && hypot(it.x - moved.x, it.y - moved.y) <= Knot * 2 }
                if (near != null) placement = placement.copy(bends = placement.bends.mapValues { (_, spots) -> spots.map { if (it == moved) near else it }.distinct() })
                if (placement != g.before) record(Step(shape, g.before, title, if (near != null) "Join two reroute points" else "Reroute a wire"))
            }
            is Gesture.Marquee, null -> {}
        }
    }

    override fun menu(x: Float, y: Float, window: Offset) {
        point(x, y, window)
        val at = pointer
        val target = pinAt(at)?.takeIf { ghost(it.node) == null }?.let { Target.OnPin(it) } ?: cardAt(at)?.let { if (it.id in folded) Target.OnNote(it.id) else Target.OnCard(it.id) } ?: wireAt(at, 6f)?.let { Target.OnWire(it) }
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
        else g.carried.map { wire -> if (target.provides) shape.copy(wires = shape.wires - wire).verdict(target.node, target.key, wire.consumer, wire.consumerPort, single, terms::fits, terms::conversion)
            else shape.copy(wires = shape.wires - wire).verdict(wire.provider, wire.providerPort, target.node, target.key, single, terms::fits, terms::conversion) }.firstOrNull { !it.ok } ?: Verdict(true, "Move ${g.carried.size} link${if (g.carried.size == 1) "" else "s"}")

    private fun moved(g: Gesture.Wiring) = hypot(pointer.x - g.from.x, pointer.y - g.from.y) > ClickSlop

    private fun rewire(g: Gesture.Wiring, target: PinEnd) {
        val verdict = carried(g, target)
        if (!verdict.ok) { notice = verdict.message; return }
        edit { s, p ->
            g.carried.fold(s.disconnect(g.carried.mapTo(hashSetOf()) { it.id })) { acc, wire ->
                (if (target.provides) acc.wire(target.node, target.key, wire.consumer, wire.consumerPort, single, terms::fits) else acc.wire(wire.provider, wire.providerPort, target.node, target.key, single, terms::fits))?.let { acc.connect(it, single) } ?: acc
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

    private fun adopt(cards: Set<String>) {
        val scopes = shape.scopes.associateBy { it.id }
        val frames = placement.notes.filter { it.id in scopes }
        if (frames.isEmpty()) return
        val moves = cards.mapNotNull { id ->
            val node = shape.node(id) ?: return@mapNotNull null
            val card = layout.cards[id] ?: return@mapNotNull null
            val x = card.x + card.width / 2; val y = card.y + card.height / 2
            val home = frames.filter { x in it.x..(it.x + it.width) && y in it.y..(it.y + it.height) }.minByOrNull { it.width * it.height }?.id ?: return@mapNotNull null
            (id to home).takeIf { home != node.scope }
        }.toMap()
        if (moves.isEmpty()) return
        shape = shape.copy(nodes = shape.nodes.map { node -> moves[node.id]?.let { node.copy(scope = it) } ?: node })
        notice = moves.entries.singleOrNull()?.let { (id, scope) -> "Moved ${shape.node(id)?.label} into ${scopes[scope]?.label}" } ?: "Moved ${moves.size} nodes into another ${terms.scope}"
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

    private fun unique(label: String, taken: Set<String> = shape.nodes.mapTo(hashSetOf()) { it.label }): String {
        if (label !in taken) return label
        return generateSequence(2) { it + 1 }.map { "$label $it" }.first { it !in taken }
    }

    private fun inside(note: Note): Pair<Set<String>, Set<String>> {
        val cards = layout.cards.values.filter { ghost(it.id) == null && it.id != note.id && it.x >= note.x && it.y >= note.y && it.x + it.width <= note.x + note.width && it.y + it.height <= note.y + note.height }.mapTo(hashSetOf()) { it.id } +
            hiddenBy.filterValues { it == note.id }.keys.filter { ghost(it) == null }
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
        val sided = pinZone == null && card.id !in folded && (card.pins.all { it.provides } || card.pins.none { it.provides })
        val reach = pinZone ?: (card.width * .4)
        val pin = card.pins.firstOrNull { pin ->
            abs(card.y + pin.y - at.y) <= BlueprintEngine.RowHeight / 2 && (sided || if (pin.provides) at.x >= card.x + card.width - reach else at.x <= card.x + reach)
        } ?: return null
        return if (card.id in folded) PinEnd(pin.key.substringBefore(FoldSeparator), pin.key.substringAfter(FoldSeparator), pin.provides) else PinEnd(card.id, pin.key, pin.provides)
    }

    fun anchor(pin: PinEnd): Pair<Double, Double>? {
        val shown = layout
        val (card, key) = shown.cards[pin.node]?.let { it to pin.key } ?: hiddenBy[pin.node]?.let(shown.cards::get)?.let { it to "${pin.node}$FoldSeparator${pin.key}" } ?: return null
        return card.pins.firstOrNull { it.key == key && it.provides == pin.provides }?.let { (if (pin.provides) card.x + card.width else card.x) to card.y + it.y }
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
        val notes = placement.notes.associateBy { it.id }
        val focused = focus?.let(notes::get)
        if (focused != null) cards.keys.retainAll { focused.holds(cards.getValue(it)) }
        val shut = folded.mapNotNull(notes::get).filter { it.id != focus && (focused == null || focused.holds(it)) }
        val outer = shut.filter { fold -> shut.none { it !== fold && it.holds(fold) && it.width * it.height > fold.width * fold.height } }
        val into = HashMap<String, String>()
        outer.forEach { fold -> cards.values.forEach { if (fold.holds(it)) into.getOrPut(it.id) { fold.id } } }
        into.keys.forEach(cards::remove)
        hiddenBy = into
        fun end(id: String) = (visible(id) ?: id).let { into[it] ?: it }
        fun key(id: String, port: String) = (visible(id) ?: id).let { if (it in into) "$it$FoldSeparator$port" else port }
        val bounds = HashMap<String, LinkedHashMap<Pair<String, Boolean>, String>>()
        (shape.wires + diff?.removedWires.orEmpty()).forEach { wire ->
            if (wire.providerPort in Internal || wire.consumerPort in Internal) return@forEach
            val from = end(wire.provider); val to = end(wire.consumer)
            if (from == to) return@forEach
            if (from != (visible(wire.provider) ?: wire.provider)) bounds.getOrPut(from) { LinkedHashMap() }[key(wire.provider, wire.providerPort) to true] = wire.type
            if (to != (visible(wire.consumer) ?: wire.consumer)) bounds.getOrPut(to) { LinkedHashMap() }[key(wire.consumer, wire.consumerPort) to false] = wire.type
        }
        outer.forEach { fold ->
            val ends = bounds[fold.id].orEmpty().entries
            val inputs = ends.filter { !it.key.second }; val outputs = ends.filter { it.key.second }
            val pins = inputs.mapIndexed { i, (end, type) -> Pin(end.first, type, false, BlueprintEngine.pinY(i), PortWiring.Connected) } +
                outputs.mapIndexed { i, (end, type) -> Pin(end.first, type, true, BlueprintEngine.pinY(i), PortWiring.Connected) }
            cards[fold.id] = Card(fold.id, fold.x, fold.y, cardWidth, BlueprintEngine.cardHeight(max(inputs.size, outputs.size)), pins, into.count { it.value == fold.id })
        }
        val links = ArrayList<Link>(shape.wires.size + shape.routes.size)
        fun wireLink(wire: WireShape) {
            if (wire.providerPort in Internal || wire.consumerPort in Internal) return
            val from = end(wire.provider).takeIf { it in cards } ?: return
            val to = end(wire.consumer).takeIf { it in cards } ?: return
            if (from == to) return
            val fromPort = key(wire.provider, wire.providerPort); val toPort = key(wire.consumer, wire.consumerPort)
            val id = "$from.$fromPort>$to.$toPort"
            val (points, curve) = path(BlueprintEngine.anchor(cards, from, fromPort, true), BlueprintEngine.anchor(cards, to, toPort, false), placement.bends[id].orEmpty())
            links += Link(id, from, to, fromPort, toPort, LinkKind.Wire, listOf(wire.id), points, family = family(wire), curve = curve)
        }
        shape.wires.forEach(::wireLink)
        diff?.removedWires?.forEach(::wireLink)
        val routeIds = HashSet<String>()
        val old = diff?.base
        val gone = diff?.let { d -> d.base.routes.filter { d.wires[it.id] == Change.Removed } }.orEmpty()
        fun former(id: String) = old?.nodes?.firstOrNull { it.attachedTo == id && it.kind != NodeKind.Route }?.id
        (shape.routes + gone).forEach { route ->
            val from = (visible(route.from) ?: former(route.from))?.let { into[it] ?: it } ?: return@forEach
            val to = (visible(route.to) ?: former(route.to))?.let { into[it] ?: it } ?: return@forEach
            if (from == to || from !in cards || to !in cards || !routeIds.add("$from>$to")) return@forEach
            val (points, curve) = path(BlueprintEngine.anchor(cards, from, null, true), BlueprintEngine.anchor(cards, to, null, false), emptyList())
            links += Link(route.id, from, to, null, null, LinkKind.Route, listOf(route.id), points, curve = curve)
        }
        return assembled(cards, drawn(placement.notes), links)
    }

    private fun drawn(notes: List<Note>): List<Note> {
        if (focus == null && folded.isEmpty()) return notes
        val byId = notes.associateBy { it.id }
        val focused = focus?.let(byId::get)
        val shut = folded.mapNotNull(byId::get).filter { it.id != focus && (focused == null || focused.holds(it)) }
        return notes.filter { note -> (focused == null || note.id == focus || focused.holds(note)) && shut.none { it.id == note.id || it.holds(note) } }
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
        return assembled(cards, drawn(placement.notes), links)
    }

    internal fun hiddenOf(id: String): String? = hiddenBy[id]

    internal fun link(id: String): Link? {
        val shown = layout
        return built?.takeIf { it.layout === shown }?.links?.slots?.get(id)?.let(shown.links::getOrNull)?.takeIf { it.id == id } ?: shown.links.firstOrNull { it.id == id }
    }

    private fun path(from: Pair<Double, Double>, to: Pair<Double, Double>, bends: List<Spot>): Pair<List<Pair<Double, Double>>, Boolean> =
        if (bends.isEmpty()) BlueprintEngine.bezier(from, to) to true
        else (listOf(from) + bends.map { it.x to it.y } + listOf(to)).zipWithNext().flatMapIndexed { k, (a, b) -> BlueprintEngine.curve(a, b).let { if (k == 0) it else it.drop(1) } } to false

    private class Built(val shape: GraphShape, val diff: GraphDiff?, val placement: Placement, val layout: BlueprintLayout<String>, val links: Links, val view: Pair<Set<String>, String?>)

    private class Links(links: List<Link>) {
        val slots: Map<String, Int> by lazy { HashMap<String, Int>(links.size * 2).also { map -> links.forEachIndexed { i, link -> map[link.id] = i } } }
        val touching: Map<String, List<Int>> by lazy {
            HashMap<String, MutableList<Int>>().also { map -> links.forEachIndexed { i, link -> map.getOrPut(link.from) { ArrayList(4) } += i; if (link.to != link.from) map.getOrPut(link.to) { ArrayList(4) } += i } }
        }
    }

    private class Hold(val shape: GraphShape, val changes: Boolean, val layout: BlueprintLayout<String>)

    companion object {
        private var clipboard: Clip? = null
        internal val recent = mutableListOf<String>()
        private val Internal = setOf("routeBinding", "navBinding")
        private const val Recents = 8
        private const val HistoryLimit = 200
        private const val PinReach = 10.0
        private const val ClickSlop = 4.0
        private const val Corner = 14.0
        private const val Knot = 6.0
        private const val MinNote = 160.0
        private const val Nesting = 8
        private const val BundleReach = 96.0

        fun placement(layout: BlueprintLayout<*>): Placement = Placement(
            cards = layout.cards.values.associate { it.id to Spot(it.x, it.y) },
            notes = layout.frames.map { Note(it.key, it.label, it.x, it.y, it.width, it.height, parent = it.parent) },
        )
    }
}

fun editorPins(node: NodeShape): List<Pin> {
    val shown = node.ports.filterNot { it.internal }
    return shown.filter { !it.provides }.mapIndexed { i, port -> Pin(port.key, port.type, false, BlueprintEngine.pinY(i), port.wiring) } +
        shown.filter { it.provides }.mapIndexed { i, port -> Pin(port.key, port.type, true, BlueprintEngine.pinY(i), port.wiring) }
}

private fun rows(pins: List<Pin>) = pins.maxOfOrNull { ((it.y - BlueprintEngine.pinY(0)) / BlueprintEngine.RowHeight).roundToLong().toInt() + 1 }?.coerceAtLeast(0) ?: 0

private fun PortShape.reset() = copy(wiring = if (provides) PortWiring.Unused else if (wiring == PortWiring.Injected) PortWiring.Injected else PortWiring.Unmatched, connections = 0, definitionId = "")

private fun segment(at: Spot, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val dx = b.first - a.first; val dy = b.second - a.second
    val t = (((at.x - a.first) * dx + (at.y - a.second) * dy) / (dx * dx + dy * dy).coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
    return hypot(at.x - a.first - t * dx, at.y - a.second - t * dy)
}

private fun Note.holds(box: Bounds) = box.x + box.width / 2 in x..(x + width) && box.y + box.height / 2 in y..(y + height)

private fun Note.holds(note: Note) = note.x >= x && note.y >= y && note.x + note.width <= x + width && note.y + note.height <= y + height

private fun Note.holding(at: Spot, width: Double, height: Double): Note {
    val left = min(x, at.x - BlueprintEngine.FrameSide); val top = min(y, at.y - BlueprintEngine.FrameTop)
    val right = max(x + this.width, at.x + width + BlueprintEngine.FrameSide); val bottom = max(y + this.height, at.y + height + BlueprintEngine.FrameSide)
    return copy(x = left, y = top, width = right - left, height = bottom - top)
}

const val FoldSeparator = "›"

const val SavedCategory = "Templates"

private const val Reach = 40

private const val FragmentAbout = "Nodes copied from a Reaktor graph editor. graph lists them as addNode operations, the wires between them as connect operations, " +
    "and layout holds their positions. Paste this text into a graph editor to add copies of them."

internal fun fresh(prefix: String) = "$prefix-" + Random.nextLong().toULong().toString(16)

internal fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

internal val EditorCommands = CommandSet(listOf(
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
    Command(CommandId("edit-find"), "Find in the graph", Chord.Of(KeyName.F, primary = true)),
    Command(CommandId("edit-rename"), "Rename", Chord.Of(KeyName.F2)),
    Command(CommandId("edit-tidy"), "Tidy up the selection", Chord.Of(KeyName.T, shift = true, alt = true)),
    Command(CommandId("edit-escape"), "Cancel", Chord.Of(KeyName.Escape)),
    Command(CommandId("edit-remark"), "Note on the node", Chord.Of(KeyName.C, alt = true)),
    Command(CommandId("edit-describe"), "Describe a change for an agent", Chord.Of(KeyName.Slash)),
    Command(CommandId("edit-keys"), "Keyboard shortcuts", Chord.Of(KeyName.Slash, primary = true)),
    Command(CommandId("edit-minimap"), "Show or hide the minimap", Chord.Of(KeyName.M)),
) + (1..9).flatMap { slot ->
    val key = KeyName.valueOf("Digit$slot")
    listOf(Command(CommandId("edit-mark-$slot"), "Set bookmark $slot", Chord.Of(key, control = true)), Command(CommandId("edit-go-$slot"), "Go to bookmark $slot", Chord.Of(key)))
})
