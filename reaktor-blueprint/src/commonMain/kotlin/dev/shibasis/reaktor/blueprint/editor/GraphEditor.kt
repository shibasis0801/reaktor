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
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.reaktor.blueprint.BlueprintEngine
import dev.shibasis.reaktor.blueprint.BlueprintBody
import dev.shibasis.reaktor.blueprint.BlueprintFace
import dev.shibasis.reaktor.blueprint.BlueprintLayout
import dev.shibasis.reaktor.blueprint.BlueprintOpening
import dev.shibasis.reaktor.blueprint.Bounds
import dev.shibasis.reaktor.blueprint.Card
import dev.shibasis.reaktor.blueprint.Frame
import dev.shibasis.reaktor.blueprint.Link
import dev.shibasis.reaktor.blueprint.LinkKind
import dev.shibasis.reaktor.blueprint.LayoutOptions
import dev.shibasis.reaktor.blueprint.Pin
import dev.shibasis.reaktor.blueprint.canvas.Aim
import dev.shibasis.reaktor.blueprint.canvas.Area
import dev.shibasis.reaktor.blueprint.canvas.CanvasCamera
import dev.shibasis.reaktor.blueprint.capture.Capture
import dev.shibasis.reaktor.blueprint.capture.CaptureAnchor
import dev.shibasis.reaktor.blueprint.capture.CaptureHit
import dev.shibasis.reaktor.blueprint.capture.CapturePlacement
import dev.shibasis.reaktor.blueprint.capture.CaptureTool
import dev.shibasis.reaktor.blueprint.capture.CaptureTools
import dev.shibasis.reaktor.blueprint.capture.translated
import dev.shibasis.reaktor.blueprint.canvas.CanvasEditing
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.graph.core.WireShape
import dev.shibasis.reaktor.surface.Availability
import dev.shibasis.reaktor.surface.KeyConvention
import dev.shibasis.reaktor.surface.findings
import dev.shibasis.reaktor.surface.label
import dev.shibasis.reaktor.surface.Chord
import dev.shibasis.reaktor.surface.Command
import dev.shibasis.reaktor.surface.CommandId
import dev.shibasis.reaktor.surface.CommandSet
import dev.shibasis.reaktor.surface.KeyName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

data class Template(val label: String, val kind: NodeKind, val type: String, val ports: List<PortShape>, val scope: String? = null, val category: String = kind.name, val part: GraphPart? = null, val attributes: Map<String, String> = emptyMap(), val captureTool: CaptureTool? = null) {
    val key: String get() = captureTool?.let { "capture:$it" } ?: "$kind:$type:$label"
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

data class EditRevision(val source: String, val presentation: String)

data class PendingEdit(val part: GraphPart, val expected: EditRevision)

sealed interface EditReceipt {
    data class Accepted(val revision: EditRevision) : EditReceipt
    data class Refused(val message: String, val stale: Boolean = false) : EditReceipt
}

data class Guide(val x0: Double, val y0: Double, val x1: Double, val y1: Double)

data class Join(val node: String, val label: String, val pin: PinEnd?)

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
    data class Palette(override val at: Spot, override val window: Offset, val from: PinEnd? = null, val link: String? = null, val relate: Boolean = false) : Request
    data class Rename(override val at: Spot, override val window: Offset, val subject: String, val note: Boolean) : Request
    data class Find(override val at: Spot, override val window: Offset) : Request
    data class Naming(override val at: Spot, override val window: Offset, val graph: Boolean) : Request
    data class Remark(override val at: Spot, override val window: Offset, val subject: String) : Request
    data class Describe(override val at: Spot, override val window: Offset) : Request
    data class Keys(override val at: Spot, override val window: Offset, val commands: Boolean = false) : Request
}

data class Carry(val template: Template, val window: Offset)

data class Hit(val id: String, val title: String, val detail: String)

sealed interface Gesture {
    data class Move(val from: Spot, val before: Placement, val anchor: Spot, val cards: Set<String>, val notes: Set<String>, val moved: Boolean = false, val only: String? = null,
                    val guides: List<Guide> = emptyList()) : Gesture
    data class Duplicating(val from: Spot, val at: Spot, val target: Selection) : Gesture
    data class Wiring(val pin: PinEnd, val at: Spot, val window: Offset, val target: PinEnd? = null, val verdict: Verdict? = null, val carried: List<WireShape> = emptyList(), val picked: PinEnd? = null, val from: Spot = at) : Gesture
    data class Marquee(val from: Spot, val to: Spot, val start: Selection, val mode: Int) : Gesture
    data class Resize(val note: String, val from: Spot, val before: Placement) : Gesture
    data class Bend(val wire: String, val index: Int, val before: Placement, val spot: Spot) : Gesture
    data class Link(val from: String, val at: Spot, val relate: Boolean, val target: String? = null, val verdict: Verdict? = null) : Gesture
    data class Framing(val from: Spot, val to: Spot) : Gesture
    data class Erase(val hits: Selection) : Gesture
}

enum class EditorTool(val label: String, val sticky: Boolean) {
    Select("Selection", true),
    Hand("Hand", true),
    Box("Rectangle", false),
    Diamond("Diamond", false),
    Ellipse("Ellipse", false),
    Arrow("Arrow", false),
    Line("Line", false),
    Text("Text", false),
    Note("Note", false),
    Comment("Comment", false),
    Frame("Frame", false),
    Eraser("Eraser", true),
    Pencil("Pencil", false),
    Image("Image", false),
}

enum class Edge { Top, Middle, Bottom, Left, Centre, Right }

private data class Step(val shape: GraphShape, val placement: Placement, val title: String, val name: String = "")

data class HostCapability(val id: CommandId, val availability: () -> Availability = { Availability.Available }, val action: () -> Unit)

private data class HostCommands(val commands: () -> CommandSet, val capabilities: Map<CommandId, HostCapability>, val invoke: (CommandId) -> Unit)

private data class Clip(val nodes: List<NodeShape>, val wires: List<WireShape>, val spots: Map<String, Spot>,
    val notes: List<Note> = emptyList(), val bends: Map<String, List<Spot>> = emptyMap(), val remarks: Map<String, String> = emptyMap(),
    val capture: List<Capture> = emptyList(), val assets: Map<String, dev.shibasis.reaktor.blueprint.capture.CaptureAsset> = emptyMap())

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
    pins: (NodeShape) -> List<Pin> = ::editorPins,
    val terms: GraphTerms = GraphTerms,
    val origin: Placement = placement,
    val conflicts: List<GraphChange> = emptyList(),
    val implied: (GraphShape) -> Set<String> = { emptySet() },
    val bundles: Boolean = false,
    val foldNode: (String) -> String? = { null },
    bodies: Map<String, BlueprintBody> = emptyMap(),
) : CanvasEditing {
    private var bodies = bodies.toMap()
    private var measuredPins = pins
    val pins: (NodeShape) -> List<Pin> = { measuredPins(it) }
    private var measurementRevision by mutableIntStateOf(0)
    private var routeRevision by mutableIntStateOf(0)
    private var movedCards = emptySet<String>()
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
    var folded by mutableStateOf(placement.folded.intersect(placement.collapsed.keys))
        private set
    var focus by mutableStateOf<String?>(null)
        private set
    var system: SystemClipboard? = null
    var onSelect: (String?) -> Unit = {}
    var onOpen: (String) -> Unit = {}
    var onSave: (GraphDraft) -> Unit = {}
    var onTemplate: ((ChangeSet) -> Unit)? = null
    private var preferences by mutableStateOf(EditorPrefs())
    private var hostCommands by mutableStateOf<HostCommands?>(null)
    private var domainCommands = CommandSet(emptyList())
    var prefs: EditorPrefs
        get() = preferences
        set(value) {
            val domain = hostCommands?.commands?.invoke() ?: CommandSet(emptyList())
            checkedCommands(domain, value)
            preferences = value
            domainCommands = domain
        }
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
    var tool by mutableStateOf(EditorTool.Select)
    var keepTool by mutableStateOf(false)
    private val captureTools = CaptureTools(placement, { fresh("capture") }, resolve = ::resolveCapture)
    private var captureMode by mutableStateOf(false)
    private var captureDrawing by mutableStateOf(false)
    private var captureKeyboard by mutableStateOf(false)
    internal val capturePreview: List<Capture> get() = (if (captureDrawing) captureTools.preview
        else pending?.part?.layout?.let(placement::with)?.captures ?: placement.captures)
        .filter { value -> placement.notes.none { it.id == value.id } }
    internal fun resolveCapture(anchor: CaptureAnchor): Spot = CapturePlacement(layout, shape, placement).resolve(anchor)
    var onCommit: ((GraphShape, GraphShape) -> Unit)? = null
    var onPlace: ((Placement) -> Unit)? = null
    private var acceptedRevision by mutableStateOf(EditRevision("", ""))
    private var deferredRevision: EditRevision? = null
    private val refreshes = ArrayList<() -> Unit>()
    internal var editScope: CoroutineScope? = null
    private var invoking: CommandId? = null
    var editRevision: EditRevision
        get() = acceptedRevision
        set(value) {
            if (pending != null) deferredRevision = value else acceptedRevision = value
        }
    var pending by mutableStateOf<PendingEdit?>(null)
        private set
    var permits: (CommandId, Target) -> Boolean = { _, _ -> false }
    var validate: (GraphPart) -> Verdict = { Verdict(false, "Edit authority is not installed") }
    var commit: suspend (GraphDraft, EditRevision) -> EditReceipt = { _, _ -> EditReceipt.Refused("Edit authority is not installed") }
    var savePlacement: suspend (Placement, EditRevision) -> EditReceipt = { _, _ -> EditReceipt.Refused("Presentation authority is not installed") }
    var linker: ((GraphShape, NodeShape, NodeShape) -> GraphShape?)? = null
    var relater: ((GraphShape, NodeShape, NodeShape) -> GraphShape?)? = null
    var enters: (String) -> Boolean = { false }
    var entersNote: (String) -> Boolean = { false }
    var remover: ((Set<String>) -> Unit)? = null
    private val aliases = HashMap<String, String>()
    var stencil: (EditorTool) -> Template? = { choices.firstOrNull { it.part == null } }
    var stamp: (Long) -> String = { savedAt(it).take(16).replace('T', ' ') }
    var camera: CanvasCamera? by mutableStateOf(null)
    private data class CommandHost(val run: (CommandId) -> Unit, val focus: () -> Unit)
    private var commandHost: CommandHost? = null
    private var viewRead: (() -> Viewport)? = null
    private var viewRestore: ((Viewport) -> Unit)? = null
    private data class View(val focus: String?, val folds: Set<String>, val opened: List<String>, val selection: Selection, val camera: Viewport?)
    private var opened by mutableStateOf(emptyList<String>())
    private var projectedOpening: BlueprintOpening<String>? = null
    val opening: BlueprintOpening<String>? get() = layout.let { projectedOpening }
    private val views = ArrayList<View>()
    private var coveredCards = emptySet<String>()
    private var coveredRoutes = emptySet<String>()
    private var coveredRouteKeys = emptySet<String>()
    private var coveredLayout: BlueprintLayout<String>? = null
    private var visiblePins: (String) -> Boolean = { true }
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
    var routing by mutableStateOf(LayoutOptions(bundles = false))
    private var hold: Hold? = null
    private var hiddenBy: Map<String, String> = emptyMap()
    private var aside: Set<String> = emptySet()
    val layout: BlueprintLayout<String> by derivedStateOf {
        val measured = measurementRevision
        val routes = routeRevision
        val shown = if (showChanges) diff else null
        val view = Triple(folded, focus, opened)
        val last = built?.takeIf { it.shape === this.shape && it.diff === shown && it.view == view && it.routing == routing && it.measurementRevision == measured && ((folded.isEmpty() && opened.isEmpty()) || it.placement.notes == this.placement.notes) }
        (if (last != null) moved(last, this.placement, movedCards.takeIf { last.routeRevision != routes }.orEmpty())
            else avoidMoved(scene(this.shape, this.placement, shown), movedCards.takeIf { built?.routeRevision != routes }.orEmpty()))
            .also {
                projectedOpening = projectedOpening?.copy(layout = it)
                built = Built(this.shape, shown, this.placement, it, last?.links ?: Links(it.links), view, routing, measured, routes)
            }
    }
    val applied: Boolean get() = revision > 0 && conflicts.isEmpty() && (if (against == null) diff.empty else GraphDiff(base, shape, terms).empty) && !dirty
    val history: List<String> get() = undos.asReversed().map { it.name }
    val future: List<String> get() = redos.asReversed().map { it.name }
    val folds: Map<String, Fold> get() = layout.let { hiddenBy.entries.groupBy({ it.value }, { it.key }).mapValues { (id, members) -> Fold(id, placement.notes.firstOrNull { it.id == id }?.text ?: id, members.toSet()) } }
    val context: Set<String> get() = layout.let { aside }
    val crumbs: List<Note> get() = generateSequence(placement.notes.firstOrNull { it.id == focus }) { note -> note.parent?.let { parent -> placement.notes.firstOrNull { it.id == parent } } }.toList().asReversed()
    val settled: BlueprintLayout<String> by derivedStateOf {
        hold?.takeIf { gesture is Gesture.Move && it.shape === shape && it.changes == showChanges }?.layout ?: layout
    }
    val canUndo: Boolean get() = undos.isNotEmpty()
    val canRedo: Boolean get() = redos.isNotEmpty()
    val valid: Boolean get() = problems.isEmpty()

    val templates: List<Template> get() {
        catalogue?.let { return it(this.shape) }
        val seen = (this.base.nodes + this.shape.nodes).filter { it.kind != NodeKind.Route }
            .distinctBy { it.kind to it.type }
            .map { node -> Template(node.type.substringAfterLast('.'), node.kind, node.type, node.ports.filterNot(PortShape::internal).map { it.reset() }, node.scope) }
        val blank = listOf(NodeKind.Service, NodeKind.Screen, NodeKind.Interactor, NodeKind.Actor, NodeKind.Controller, NodeKind.Node)
            .map { Template("New ${it.name.lowercase()}", it, it.name, emptyList()) }
        return blank + seen.sortedBy { it.label.lowercase() }
    }

    val choices: List<Template> get() = saved.mapNotNull { set -> set.graph?.let { Template(set.title, NodeKind.Node, set.id, it.signature(), category = SavedCategory, part = it) } }.let { parts ->
        val favourites = prefs.favourites
        (parts + templates).let { all -> if (favourites.isEmpty()) all else all.sortedBy { favourites.indexOf(it.key).let { at -> if (at < 0) Int.MAX_VALUE else at } } }
    }

    suspend fun propose(part: GraphPart): EditReceipt {
        if (refused()) return EditReceipt.Refused(notice ?: "Edit unavailable")
        if (gesture != null) cancel()
        val replay = shape.replay(part)
        if (replay.conflicts.isNotEmpty()) return EditReceipt.Refused("The proposal conflicts with the current graph", stale = true)
        return propose(part, Step(replay.shape, part.layout?.let(placement::with) ?: placement, title), invoking ?: CommandId("edit-change")) { record(it) }
    }

    internal suspend fun propose(part: GraphPart, canonicalBefore: GraphShape, canonicalAfter: GraphShape): EditReceipt {
        if (refused()) return EditReceipt.Refused(notice ?: "Edit unavailable")
        if (shape != canonicalBefore) return EditReceipt.Refused("The complete source graph changed", stale = true)
        if (gesture != null) cancel()
        val replay = shape.replay(part)
        if (replay.conflicts.isNotEmpty() ||
            replay.shape.scopes.groupBy { it.id } != canonicalAfter.scopes.groupBy { it.id } ||
            replay.shape.nodes.groupBy { it.id } != canonicalAfter.nodes.groupBy { it.id } ||
            replay.shape.wires.groupBy { it.id } != canonicalAfter.wires.groupBy { it.id } ||
            replay.shape.routes.groupBy { it.id } != canonicalAfter.routes.groupBy { it.id }) {
            return EditReceipt.Refused("The complete proposal conflicts with its supplied snapshot", stale = true)
        }
        return propose(part, Step(canonicalAfter, part.layout?.let(placement::with) ?: placement, title), invoking ?: CommandId("edit-change")) { record(it) }
    }


    private fun part(next: Step): GraphPart {
        val changes = shape.changesTo(next.shape, terms)
        return GraphPart(changes = changes, layout = next.placement.since(placement), definitions = shape.definitionsFor(changes))
    }

    private fun targets(next: Step): Set<Target> = buildSet {
        (shape.nodes + next.shape.nodes).map { it.id }.distinct().filter { shape.node(it) != next.shape.node(it) }.forEach { add(Target.OnCard(it)) }
        (shape.wires + next.shape.wires).map { it.id }.distinct().filter { id -> shape.wires.find { it.id == id } != next.shape.wires.find { it.id == id } }.forEach { add(Target.OnWire(it)) }
        (placement.cards.keys + next.placement.cards.keys).filter { placement.cards[it] != next.placement.cards[it] }.forEach { add(Target.OnCard(it)) }
        (placement.notes + next.placement.notes).map { it.id }.distinct().filter { id -> placement.notes.find { it.id == id } != next.placement.notes.find { it.id == id } }.forEach { add(Target.OnNote(it)) }
        (placement.bends.keys + next.placement.bends.keys).filter { placement.bends[it] != next.placement.bends[it] }.forEach { add(Target.OnWire(it)) }
        (placement.capture + next.placement.capture).map { it.id }.distinct()
            .filter { id -> placement.capture.find { it.id == id } != next.placement.capture.find { it.id == id } }.forEach { add(Target.OnNote(it)) }
        if (shape.scopes != next.shape.scopes || shape.routes != next.shape.routes || placement.assets != next.placement.assets ||
            placement.folded != next.placement.folded || placement.marks != next.placement.marks || placement.remarks != next.placement.remarks ||
            placement.removedCaptures != next.placement.removedCaptures || placement.removedAssets != next.placement.removedAssets ||
            placement.unfolded != next.placement.unfolded || placement.removedNotes != next.placement.removedNotes || isEmpty()) add(Target.Empty)
    }

    private suspend fun propose(part: GraphPart, next: Step, command: CommandId, history: (Step) -> Unit): EditReceipt {
        if (refused()) return EditReceipt.Refused(notice ?: "Edit unavailable")
        val before = Step(shape, placement, title, next.name.ifEmpty { named(shape, next.shape, placement, next.placement) })
        if (before.shape == next.shape && before.placement == next.placement && before.title == next.title) return EditReceipt.Accepted(editRevision)
        if (targets(next).any { !permits(command, it) }) return EditReceipt.Refused("This edit is not permitted").also { notice = it.message }
        val verdict = validate(part)
        if (!verdict.ok) return EditReceipt.Refused(verdict.message).also { notice = it.message }
        val expected = editRevision
        pending = PendingEdit(part, expected)
        return try {
            val receipt = if (next.shape != shape || part.changes.isNotEmpty()) {
                commit(GraphDraft(id, next.title, revision + 1, nowMillis(), base, next.shape, next.placement), expected)
            } else savePlacement(next.placement, expected)
            currentCoroutineContext().ensureActive()
            when (receipt) {
                is EditReceipt.Accepted -> {
                    movedCards = (before.placement.cards.keys + next.placement.cards.keys)
                        .filterTo(HashSet()) { before.placement.cards[it] != next.placement.cards[it] }
                    if (movedCards.isNotEmpty()) routeRevision++
                    shape = next.shape
                    placement = next.placement
                    folded = (folded - (before.placement.folded - placement.folded)) +
                        (placement.folded - before.placement.folded)
                    title = next.title
                    acceptedRevision = receipt.revision
                    history(before)
                    selection = Selection(selection.nodes.filterTo(hashSetOf()) { shape.node(it) != null },
                        selection.wires.filterTo(hashSetOf()) { id -> shape.wires.any { it.id == id } },
                        selection.notes.filterTo(hashSetOf()) { id -> placement.captures.any { it.id == id } })
                }
                is EditReceipt.Refused -> notice = receipt.message
            }
            receipt
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            EditReceipt.Refused(failure.message ?: "The host could not accept this edit").also { notice = it.message }
        } finally {
            pending = null
            deferredRevision?.let { acceptedRevision = it }
            deferredRevision = null
            val waiting = refreshes.toList()
            refreshes.clear()
            waiting.forEach { it() }
        }
    }

    private fun submit(next: Step, command: CommandId = invoking ?: CommandId("edit-change"), history: (Step) -> Unit = ::record) {
        if (refused()) return
        if (gesture != null) cancel()
        val part = part(next)
        (editScope ?: CoroutineScope(Dispatchers.Unconfined)).launch(start = CoroutineStart.UNDISPATCHED) {
            propose(part, next, command, history)
        }
    }

    fun registerCommands(commands: () -> CommandSet, invoke: (CommandId) -> Unit): () -> Unit =
        registerCommands(commands, emptySet(), invoke)

    fun registerCommands(commands: () -> CommandSet, capabilities: Set<HostCapability>, invoke: (CommandId) -> Unit): () -> Unit {
        val domain = commands()
        checkedCommands(domain, prefs)
        require(capabilities.map { it.id }.distinct().size == capabilities.size) { "Host capability IDs must be unique" }
        require(capabilities.all { it.id.value in UnimplementedCommandIds }) { "Host capabilities supply reserved actions only" }
        val registration = HostCommands(commands, capabilities.associateBy { it.id }, invoke)
        hostCommands = registration
        domainCommands = domain
        return {
            if (hostCommands === registration) {
                hostCommands = null
                domainCommands = CommandSet(emptyList())
            }
        }
    }

    private fun hostAvailability(command: CommandId): Availability {
        val capability = hostCommands?.capabilities?.get(command)
            ?: return Availability.Unavailable("This action needs its host or capture capability")
        return runCatching { capability.availability() }.getOrElse { Availability.Unavailable("The host capability is unavailable") }
    }

    private fun checkedCommands(domain: CommandSet, preferences: EditorPrefs): CommandSet {
        val canonical = EditorCommands.commands.mapTo(hashSetOf()) { it.id }
        require(domain.commands.none { it.id in canonical }) { "Domain commands cannot replace canonical commands" }
        val merged = CommandSet(EditorCommands.commands + domain.commands, EditorCommands.menus + domain.menus).remapped(preferences.keys)
        require(merged.commands.map { it.id }.distinct().size == merged.commands.size) { "Command IDs must be unique" }
        val active = merged.copy(commands = merged.commands.filter { it.availability == Availability.Available })
        require(KeyConvention.entries.all { active.findings(it).isEmpty() }) { "Command shortcuts collide on Mac or Pc" }
        return merged
    }

    private fun commandTargets(): List<Target> = (selection.nodes.map { Target.OnCard(it) } +
        selection.wires.map { Target.OnWire(it) } + selection.notes.map { Target.OnNote(it) }).ifEmpty { listOf(Target.Empty) }

    private fun available(command: CommandId): Boolean {
        when (command.value) {
            "edit-undo" -> if (!canUndo) return false
            "edit-redo", "edit-redo-y" -> if (!canRedo) return false
            "edit-delete", "edit-delete-back", "edit-cut", "edit-copy", "edit-duplicate", "edit-group" -> if (selection.empty) return false
            "edit-rename", "edit-rename-enter" -> if (!captureDrawing && (selection.size != 1 || selection.wires.isNotEmpty())) return false
            in UnimplementedCommandIds -> if (hostAvailability(command) != Availability.Available) return false
            "edit-remark" -> if (selection.nodes.size != 1) return false
            "tool-link" -> if (linker == null && shape.nodes.none { it.ports.isNotEmpty() }) return false
            "tool-relate" -> if (relater == null && linker == null && shape.nodes.none { it.ports.isNotEmpty() }) return false
        }
        if (command.value in ViewCommandIds || command.value in InspectionCommandIds || command.value.startsWith("edit-go-") || command.value.startsWith("edit-walk-")) return true
        return pending == null && !locked && commandTargets().all { permits(command, it) }
    }

    override val commands: CommandSet
        get() {
            val registration = hostCommands
            val updated = runCatching {
                val domain = registration?.commands?.invoke() ?: CommandSet(emptyList())
                checkedCommands(domain, prefs).also { domainCommands = domain }
            }.getOrElse { checkedCommands(domainCommands, prefs) }
            return updated.copy(commands = updated.commands.map { command ->
                val supplied = if (command.id.value in UnimplementedCommandIds) hostAvailability(command.id) else command.availability
                when {
                    supplied != Availability.Available -> command.copy(availability = supplied)
                    available(command.id) -> command
                    else -> command.copy(availability = Availability.Unavailable(if (pending != null) "An edit is awaiting the host" else "This action is not permitted"))
                }
            })
        }

    override fun keyed(set: CommandSet): CommandSet = commands

    override fun attach(camera: CanvasCamera?) { this.camera = camera }

    internal fun navigation(read: (() -> Viewport)?, restore: ((Viewport) -> Unit)?) {
        viewRead = read
        viewRestore = restore
    }

    internal fun commandsFromCanvas(run: (CommandId) -> Unit, focus: () -> Unit): () -> Unit {
        val host = CommandHost(run, focus)
        commandHost = host
        return { if (commandHost === host) commandHost = null }
    }

    internal fun commandChoices(query: String, convention: KeyConvention): List<Command> {
        val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        return commands.commands.filter { command ->
            val text = listOf(command.label, command.id.value, command.chord?.label(convention).orEmpty()).joinToString(" ")
            terms.all { text.contains(it, ignoreCase = true) }
        }.sortedWith(compareBy<Command> { it.availability != Availability.Available }.thenBy { it.label })
    }

    internal fun menuCommand(key: String): Command? {
        val id = when (key) {
            "add" -> "edit-add-node"
            "group" -> "edit-group-scope"
            "distribute-x" -> "edit-distribute-horizontal"
            "distribute-y" -> "edit-distribute-vertical"
            else -> "edit-$key"
        }
        return commands.commands.firstOrNull { it.id.value == id }
    }

    internal fun resumeCommands() { if (request == null) commandHost?.focus?.invoke() }

    internal fun runCommand(id: CommandId): Boolean {
        if (commands.commands.firstOrNull { it.id == id }?.availability != Availability.Available) return false
        val host = commandHost
        if (host != null) host.run(id) else invoke(id)
        if (request == null) host?.focus?.invoke()
        return true
    }

    internal fun dismissCommandRequest(current: Request) {
        if (request !== current) return
        request = null
        commandHost?.focus?.invoke()
    }

    internal fun cover(cards: Set<String>, routes: Set<String>) {
        val scene = if (routes.isEmpty()) null else layout
        if (cards == coveredCards && routes == coveredRouteKeys && scene === coveredLayout) return
        coveredCards = cards.toSet()
        coveredRouteKeys = routes.toSet()
        coveredLayout = scene
        coveredRoutes = if (scene == null) emptySet() else routes + scene.links.filter { it.id in routes }.flatMap { it.members }
    }

    private fun rememberView() {
        val view = View(focus, folded.toSet(), opened.toList(), selection, viewRead?.invoke())
        if (views.lastOrNull() == view) return
        views += view
        if (views.size > 80) views.removeAt(0)
    }

    private fun restoreView(view: View) {
        aim = null
        focus = view.focus
        folded = view.folds
        opened = view.opened
        select(view.selection)
        view.camera?.let { viewRestore?.invoke(it) }
    }

    fun back(): Boolean {
        if (views.isEmpty()) return false
        restoreView(views.removeAt(views.lastIndex))
        return true
    }
    override fun pins(visible: (String) -> Boolean) { visiblePins = visible }

    fun ghost(id: String): NodeShape? = if (showChanges && diff.nodes[id] == Change.Removed) diff.base.node(id) else null

    fun change(id: String): Change? = if (showChanges) diff.nodes[id] ?: diff.wires[id] else null

    fun node(id: String): NodeShape? = shape.node(id)

    fun select(next: Selection) {
        val shown = layout
        val owners = next.wires.mapNotNull(shown.foldedLinks::get).toSet()
        val notes = placement.notes.mapTo(HashSet()) { it.id }
        selection = next.copy(nodes = next.nodes + (owners - notes),
            wires = next.wires - shown.foldedLinks.keys, notes = next.notes + owners.intersect(notes))
        onSelect(selection.nodes.singleOrNull()?.takeIf { selection.size == 1 })
    }

    fun selectAll() = select(Selection(shape.nodes.filter { it.kind != NodeKind.Route && it.id !in coveredCards }.mapTo(hashSetOf()) { it.id }, notes = placement.captures.mapTo(hashSetOf()) { it.id }))

    fun undo() {
        if (refused()) return
        val step = undos.lastOrNull() ?: return
        submit(step, CommandId("edit-undo")) { before ->
            undos.removeAt(undos.lastIndex)
            redos += before.copy(name = step.name)
        }
    }

    fun redo() {
        if (refused()) return
        val step = redos.lastOrNull() ?: return
        submit(step, CommandId("edit-redo")) { before ->
            redos.removeAt(redos.lastIndex)
            undos += before.copy(name = step.name)
        }
    }

    fun undo(steps: Int) = repeat(steps) { undo() }

    fun redo(steps: Int) = repeat(steps) { redo() }

    fun edit(name: String? = null, change: (GraphShape, Placement) -> Pair<GraphShape, Placement>) {
        if (refused()) return
        if (gesture != null) cancel()
        val (nextShape, nextPlacement) = change(shape, placement)
        if (nextShape == shape && nextPlacement == placement) return
        submit(Step(nextShape, nextPlacement, title, name ?: named(shape, nextShape, placement, nextPlacement)))
    }

    fun rebase(next: GraphShape, renamed: Map<String, String> = emptyMap(), spots: Map<String, Spot> = emptyMap()) {
        if (pending != null) { refreshes += { rebase(next, renamed, spots) }; return }
        aliases.putAll(renamed)
        val incoming = next.injected()
        val echo = shape.renamed(renamed).sameGraph(incoming)
        if (renamed.isNotEmpty()) {
            val steps = undos.map { it.copy(shape = it.shape.renamed(renamed), placement = it.placement.renamed(renamed)) }
            val ahead = redos.map { it.copy(shape = it.shape.renamed(renamed), placement = it.placement.renamed(renamed)) }
            undos.clear(); undos += steps; redos.clear(); redos += ahead
        }
        if (!echo) { undos.clear(); redos.clear() }
        shape = incoming
        placement = placement.renamed(renamed).let { kept -> kept.copy(cards = spots.filterKeys { it !in kept.cards } + kept.cards) }
        select(Selection(selection.nodes.mapNotNullTo(hashSetOf()) { id -> (renamed[id] ?: id).takeIf { shape.node(it) != null } },
            selection.wires.filterTo(hashSetOf()) { id -> shape.wires.any { it.id == id } }, selection.notes))
    }

    /** Refresh measured presentation without changing the graph, placement, selection or history. */
    fun refreshMeasurements(bodies: Map<String, BlueprintBody>, pins: (NodeShape) -> List<Pin>) {
        if (pending != null) { refreshes += { refreshMeasurements(bodies, pins) }; return }
        val next = bodies.toMap()
        if (this.bodies == next && shape.nodes.all { this.pins(it) == pins(it) }) return
        this.bodies = next
        measuredPins = pins
        measurementRevision++
    }

    fun settle(next: GraphShape, renamed: Map<String, String>, laid: BlueprintLayout<*>) {
        if (pending != null) { refreshes += { settle(next, renamed, laid) }; return }
        val known = placement.renamed(renamed).cards.keys.toHashSet()
        rebase(next, renamed)
        val frames = laid.frames.associateBy { it.key }
        val notes = placement.notes.associateByTo(LinkedHashMap()) { it.id }
        fun depth(frame: Frame) = generateSequence(frame) { it.parent?.let(frames::get) }.count()
        laid.frames.sortedBy(::depth).filter { it.key !in notes }.forEach { frame ->
            val parent = frame.parent?.let(frames::get)
            val holder = frame.parent?.let(notes::get)
            val at = if (parent != null && holder != null) Spot(holder.x + frame.x - parent.x, holder.y + frame.y - parent.y)
                else Spot((notes.values.maxOfOrNull { it.x + it.width } ?: 0.0) + BlueprintEngine.LayerGap, frame.y)
            notes[frame.key] = Note(frame.key, frame.label, at.x, at.y, frame.width, frame.height, parent = frame.parent)
        }
        val taken = layout.cards.values.filterTo(ArrayList<Bounds>()) { it.id in known }
        val spots = HashMap<String, Spot>()
        laid.cards.values.filter { it.id !in known && shape.node(it.id) != null }.forEach { card ->
            val frame = laid.frames.filter { card.id in it.nodes }.minByOrNull { it.width * it.height }
            val note = frame?.let { notes[it.key] }
            val target = if (frame != null && note != null) Spot(note.x + card.x - frame.x, note.y + card.y - frame.y) else Spot(card.x, card.y)
            val spot = clearOf(taken, snapped(target), card.width, card.height)
            spots[card.id] = spot
            taken += card.copy(x = spot.x, y = spot.y)
            var inner = Note(card.id, "", spot.x, spot.y, card.width, card.height)
            var holder = note
            while (holder != null) {
                val grown = holder.holding(Spot(inner.x, inner.y), inner.width, inner.height)
                notes[grown.id] = grown
                inner = grown
                holder = grown.parent?.let(notes::get)
            }
        }
        val settled = notes.values.toList()
        if (spots.isNotEmpty() || settled != placement.notes) placement = placement.copy(cards = placement.cards + spots, notes = settled)
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

    private fun refused(): Boolean {
        if (pending != null) { notice = "An edit is awaiting the host"; return true }
        return locked.also { shut ->
        if (!shut) return@also
        notice = others.firstOrNull { it.editing && it.ahead }?.let { "${it.name} is editing this draft · take over to change it" }
        onLocked()
        }
    }

    fun takeOver() { others = others.map { it.copy(ahead = false) }; onTakeOver() }

    private fun record(step: Step) {
        undos += step
        if (undos.size > HistoryLimit) undos.removeAt(0)
        redos.clear()
    }

    fun rename(stale: String, text: String) {
        val value = text.trim().ifEmpty { return }
        val id = aliases[stale] ?: stale
        if (captureDrawing && captureTools.preview.any { it.id == id }) {
            captureTools.setText(id, value)
            finishCapture()
        }
        else if (placement.capture.any { it.id == id }) {
            captureTools.sync(placement)
            if (captureTools.setText(id, value)) { captureDrawing = true; finishCapture() }
        }
        else if (placement.notes.any { it.id == id }) edit { s, p ->
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

    fun add(template: Template, at: Spot, from: PinEnd? = null): String = add(template, Request.Palette(at, Offset.Zero, from))

    private fun add(template: Template, request: Request.Palette): String {
        if (refused()) return ""
        val at = request.at
        val from = request.from
        template.captureTool?.let { capture ->
            if (from != null || request.link != null) {
                notice = "A capture shape cannot connect to this link"
                return ""
            }
            pointer = at
            pick(EditorTool.valueOf(capture.name))
            return captureTools.selected.orEmpty()
        }
        val match = from?.let { template.joins(shape.node(it.node)?.port(it.key, it.provides), terms::fits) }
        if (from != null && match == null) {
            notice = "This node has no compatible port"
            return ""
        }
        recent.remove(template.key); recent.add(0, template.key); if (recent.size > Recents) recent.removeAt(recent.lastIndex)
        template.part?.let { part ->
            val clip = clipOf(part) ?: return ""
            val set = saved.firstOrNull { it.id == template.type }
            val use = fresh("use")
            return paste(at, clip, { node -> set?.let { node.linked(it, use, node.id) } ?: node },
                from?.let { pin -> match?.let { pin to it } }, request).values.firstOrNull().orEmpty()
        }
        val id = fresh("node")
        val scope = scopeAt(at, from)
        val node = NodeShape(id, scope, unique(template.label), template.type, template.kind, ports = template.ports.map { it.reset() }, attributes = template.attributes)
        val height = BlueprintEngine.cardHeight(BlueprintFace.rows(node, rows(pins(node))))
        val start = free(snapped(Spot(at.x - cardWidth / 2, at.y - BlueprintEngine.HeaderHeight / 2)), height)
        var next = shape.with(node)
        if (from != null && match != null) {
            val wire = if (from.provides) next.wire(from.node, from.key, id, match.key, single, terms::fits)
                else next.wire(id, match.key, from.node, from.key, single, terms::fits)
            if (wire == null) { notice = "This node cannot be connected here"; return "" }
            next = next.connect(wire, single)
        }
        request.link?.let { source ->
            next = linked(next, source, id, request.relate) ?: run { notice = "This node cannot be linked here"; return "" }
        }
        val placed = placement.copy(cards = placement.cards + (id to start),
            notes = placement.notes.map { if (it.id == scope) it.holding(start, cardWidth, height) else it })
        submit(Step(next, placed, title, "Add and connect ${node.label}".takeIf { from != null || request.link != null } ?: "Add ${node.label}")) {
            record(it)
            select(Selection(setOf(id)))
        }
        return id
    }

    private fun free(start: Spot, height: Double): Spot = clearOf(layout.cards.values, start, cardWidth, height)

    private fun clearOf(boxes: Collection<Bounds>, start: Spot, width: Double, height: Double): Spot {
        val gap = BlueprintEngine.CardGap
        fun clear(at: Spot) = boxes.none { at.x < it.x + it.width + gap && at.x + width + gap > it.x && at.y < it.y + it.height + gap && at.y + height + gap > it.y }
        if (clear(start)) return start
        val step = grid.takeIf { it.isFinite() && it > 0.0 }?.times(2) ?: 32.0
        var ring = 1
        while (true) {
            val distance = ring * step
            val cardinal = listOf(Spot(start.x, start.y + distance), Spot(start.x + distance, start.y),
                Spot(start.x, start.y - distance), Spot(start.x - distance, start.y))
            cardinal.firstOrNull(::clear)?.let { return it }
            for (offset in -ring..ring) {
                if (offset == 0) continue
                val shift = offset * step
                listOf(Spot(start.x + shift, start.y - distance), Spot(start.x + shift, start.y + distance),
                    Spot(start.x - distance, start.y + shift), Spot(start.x + distance, start.y + shift))
                    .firstOrNull(::clear)?.let { return it }
            }
            ring++
        }
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
        if (refused()) return
        val nodes = target.nodes
        if (nodes.isNotEmpty() || target.wires.isNotEmpty() || target.notes.isNotEmpty()) {
            val routes = nodes.mapNotNull { shape.node(it)?.attachedTo }.filter { route -> shape.node(route)?.kind == NodeKind.Route && shape.nodes.none { it.attachedTo == route && it.id !in nodes } }
            edit { s, p -> s.without(nodes + routes).disconnect(target.wires) to p.copy(notes = p.notes.filter { it.id !in target.notes }, capture = p.capture.filter { it.id !in target.notes }, bends = p.bends - target.wires) }
        }
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
        val node = NodeShape(id, shape.node(into.node)?.scope ?: scopeAt(Spot(bx, by), null), unique(adapter.label), adapter.type, adapter.kind, ports = adapter.ports.map { it.reset() }, attributes = adapter.attributes)
        val input = adapter.ports.first { !it.provides }.key
        val output = adapter.ports.first { it.provides }.key
        val height = BlueprintEngine.cardHeight(BlueprintFace.rows(node, rows(pins(node))))
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
        val first = cards.first()
        val last = cards.last()
        val start = if (horizontal) first.x else first.y
        val end = if (horizontal) last.x + last.width else last.y + last.height
        val total = cards.sumOf { if (horizontal) it.width else it.height }
        val gap = (end - start - total) / (cards.size - 1)
        var cursor = start
        val spots = cards.associate { card ->
            val spot = if (horizontal) Spot(cursor, card.y) else Spot(card.x, cursor)
            cursor += (if (horizontal) card.width else card.height) + gap
            card.id to spot
        }
        edit("Distribute ${cards.size} cards ${if (horizontal) "horizontally" else "vertically"}") { s, p -> s to p.copy(cards = p.cards + spots) }
    }

    fun straighten() {
        val chosen = shape.wires.filter { it.id in selection.wires || it.consumer in selection.nodes && (it.provider in selection.nodes || selection.nodes.size == 1) }
        val moves = chosen.mapNotNull { wire ->
            val from = layout.cards[wire.provider]?.let { card -> card.pins.firstOrNull { it.provides && it.key == wire.providerPort }?.let { card.y + it.y } } ?: return@mapNotNull null
            val to = layout.cards[wire.consumer] ?: return@mapNotNull null
            val pin = to.pins.firstOrNull { !it.provides && it.key == wire.consumerPort } ?: return@mapNotNull null
            wire.consumer to Spot(to.x, from - pin.y)
        }.distinctBy { it.first }
        if (moves.isEmpty()) return
        edit("Straighten connections") { s, p -> s to p.copy(cards = p.cards + moves, bends = p.bends - chosen.map { it.id }.toSet()) }
    }

    private fun clip(target: Selection): Clip? {
        val nodes = shape.nodes.filter { it.id in target.nodes }
        val ids = nodes.mapTo(hashSetOf()) { it.id }
        val wires = shape.wires.filter { it.provider in ids && it.consumer in ids }
        val wireIds = wires.mapTo(hashSetOf()) { it.id }
        fun attached(anchor: CaptureAnchor): Boolean = when (anchor) {
            is CaptureAnchor.Node -> anchor.node in ids
            is CaptureAnchor.Row -> anchor.node in ids
            is CaptureAnchor.Port -> anchor.node in ids
            is CaptureAnchor.Wire -> anchor.wire in wireIds
            is CaptureAnchor.Frame -> anchor.frame in target.notes
            is CaptureAnchor.World -> false
        }
        val captures = placement.capture.filter { it.id in target.notes || attached(it.anchor) }
        val notes = placement.notes.filter { it.id in target.notes && captures.none { capture -> capture.id == it.id } }
        if (nodes.isEmpty() && notes.isEmpty() && captures.isEmpty()) return null
        val assets = captures.filterIsInstance<Capture.Image>().mapTo(hashSetOf()) { it.asset }
        return Clip(nodes, wires, ids.mapNotNull { id -> layout.cards[id]?.let { id to Spot(it.x, it.y) } }.toMap(),
            notes, placement.bends.filterKeys { it in wireIds }, placement.remarks.filterKeys { it in ids }, captures, placement.assets.filterKeys { it in assets })
    }

    private fun corner(clip: Clip): Spot {
        val points = clip.spots.values + clip.notes.map { Spot(it.x, it.y) } + clip.capture.map { resolveCapture(it.anchor) }
        return Spot(points.minOfOrNull { it.x } ?: pointer.x, points.minOfOrNull { it.y } ?: pointer.y)
    }

    fun copy(target: Selection = selection): Int {
        val clip = clip(target) ?: return 0
        clipboard = clip
        system?.write(fragment(clip, "Copied from $title").encode())
        val count = clip.nodes.size + clip.notes.size + clip.capture.size
        notice = "Copied $count item${if (count == 1) "" else "s"}"
        return count
    }

    fun cut() { if (copy() > 0) delete(selection) }

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
        if (replay.conflicts.isNotEmpty()) { notice = "The saved edits conflict with this graph"; return }
        submit(Step(replay.shape.injected(base), part.layout?.let(origin::with) ?: placement, set.title, "Restore unsaved edits")) { before ->
            record(before)
            id = set.id
            revision = set.revision
            recovered = null
        }
    }

    fun group(name: String, target: Selection = selection) {
        if (refused()) return
        val nodes = target.nodes.mapNotNull(shape::node).ifEmpty { return }
        val cards = nodes.mapNotNull { layout.cards[it.id] }.ifEmpty { return }
        val ids = nodes.mapTo(hashSetOf()) { it.id }
        val scopes = shape.scopes.associateBy { it.id }
        fun ancestors(scope: String): List<String> {
            val path = ArrayList<String>()
            var at = scope
            while (at.isNotEmpty() && at !in path) { path += at; at = scopes[at]?.parent.orEmpty() }
            return path
        }
        val paths = nodes.map { ancestors(it.scope) }
        val parent = paths.first().firstOrNull { candidate -> paths.all { candidate in it } }.orEmpty()
        val id = fresh("graph")
        val left = cards.minOf { it.x } - BlueprintEngine.FrameSide
        val top = cards.minOf { it.y } - BlueprintEngine.FrameTop
        val parentNote = placement.notes.firstOrNull { it.id == parent || it.id == "feature:$parent" }?.id
        val note = Note(id, name, left, top,
            cards.maxOf { it.x + it.width } + BlueprintEngine.FrameSide - left,
            cards.maxOf { it.y + it.height } + BlueprintEngine.FrameSide - top, parent = parentNote)
        val origins = shape.nodes.mapIndexedNotNull { index, node ->
            if (node.id in ids) node.id to Placement.Origin(node.scope.takeIf { it.isNotEmpty() }, index) else null
        }.toMap()
        val next = shape.copy(scopes = shape.scopes + ScopeShape(id, name, parent.takeIf { it.isNotEmpty() }),
            nodes = shape.nodes.map { if (it.id in ids) it.copy(scope = id) else it })
        val placed = placement.copy(notes = placement.notes + note, folded = placement.folded + id,
            collapsed = placement.collapsed + (id to origins))
        submit(Step(next, placed, title, "Collapse ${nodes.size} nodes into $name")) { before ->
            record(before)
            select(Selection(notes = setOf(id)))
        }
    }

    fun fold(note: String) {
        if (note in folded || placement.notes.none { it.id == note }) return
        if (note in placement.collapsed) {
            if (refused()) return
            submit(Step(shape, placement.copy(folded = placement.folded + note), title, "Fold group")) { before ->
                record(before)
                opened = opened.filter { it != note }
                select(Selection(notes = setOf(note)))
            }
            return
        }
        rememberView()
        val bounds = placement.notes.first { it.id == note }
        opened = opened.filter { id -> placement.notes.firstOrNull { it.id == id }?.let { !bounds.holds(it) } == true }
        folded = folded + note
        select(Selection(notes = setOf(note)))
    }

    fun unfold(note: String) {
        if (note !in folded) return
        if (note in placement.collapsed) {
            if (refused()) return
            submit(Step(shape, placement.copy(folded = placement.folded - note), title, "Unfold group"))
            return
        }
        rememberView()
        folded = folded - note
    }

    fun open(note: String?) {
        if (note != null && placement.notes.none { it.id == note }) return
        if (focus == note && note !in folded) return
        val previous = views.indexOfLast { it.focus == note }
        if (previous >= 0) {
            val view = views[previous]
            views.subList(previous, views.size).clear()
            restoreView(view)
            return
        }
        rememberView()
        aim = null
        if (note == null) opened = emptyList()
        else if (note in folded && note !in opened) opened = opened + note
        focus = note
        folded = folded - setOfNotNull(focus)
        select(Selection())
    }

    private fun clipOf(text: String): Clip? = runCatching { ChangeSet.decode(text) }.getOrNull()?.graph?.let(::clipOf)

    private fun clipOf(part: GraphPart): Clip? {
        val nodes = part.changes.filter { it.op == "addNode" }.mapNotNull { it.node }
        val ids = nodes.mapTo(hashSetOf()) { it.id }
        val layout = part.layout ?: Placement()
        if (nodes.isEmpty() && layout.notes.isEmpty() && layout.capture.isEmpty()) return null
        val wires = part.changes.filter { it.op == "connect" }.mapNotNull { it.wire }.filter { it.provider in ids && it.consumer in ids }
        return Clip(nodes, wires, layout.cards.filterKeys { it in ids }, layout.notes, layout.bends.filterKeys { key -> wires.any { it.id == key } },
            layout.remarks.filterKeys { it in ids }, layout.capture, layout.assets)
    }

    private fun fragment(clip: Clip, name: String): ChangeSet {
        val byId = clip.nodes.associateBy { it.id }
        fun end(id: String, key: String) = byId[id]?.let { terms.end(it, key) } ?: "$id.$key"
        val changes = clip.nodes.map { GraphChange("addNode", "Add ${terms.summary(it, Change.Added)} named ${it.label}", node = it) } +
            clip.wires.map { GraphChange("connect", "Connect ${end(it.provider, it.providerPort)} → ${end(it.consumer, it.consumerPort)}", wire = it) }
        return ChangeSet(about = FragmentAbout, id = fresh("nodes"), title = name, revision = 1, savedAt = savedAt(nowMillis()), summary = changes.map { it.summary },
            graph = GraphPart(changes = changes, layout = Placement(cards = clip.spots, notes = clip.notes, bends = clip.bends, remarks = clip.remarks, capture = clip.capture, assets = clip.assets)))
    }

    private fun paste(at: Spot, clip: Clip, stamp: (NodeShape) -> NodeShape = { it }, join: Pair<PinEnd, PortShape>? = null, request: Request.Palette? = null): Map<String, String> {
        if (refused()) return emptyMap()
        val corner = corner(clip)
        val destination = snapped(at)
        val delta = Spot(destination.x - corner.x, destination.y - corner.y)
        val renamed = clip.nodes.associate { it.id to fresh("node") }
        val noteIds = (clip.notes.map { it.id } + clip.capture.map { it.id }).associateWith { fresh("capture") }
        val scope = scopeAt(at, join?.first)
        val taken = shape.nodes.mapTo(hashSetOf()) { it.label }
        val nodes = clip.nodes.map { node -> stamp(node).copy(id = renamed.getValue(node.id), label = unique(node.label, taken).also { taken += it }, scope = if (shape.scopes.any { it.id == node.scope }) node.scope else scope,
            attachedTo = null, definitionId = "", ports = node.ports.map { it.reset() }) }
        val wires = clip.wires.map { it.copy(provider = renamed.getValue(it.provider), consumer = renamed.getValue(it.consumer), definitionId = "") }
        val spots = clip.nodes.associate { node -> renamed.getValue(node.id) to (clip.spots[node.id] ?: corner).let { Spot(it.x + delta.x, it.y + delta.y) } }
        val wireIds = clip.wires.zip(wires).associate { (old, new) -> old.id to new.id }
        val assetIds = clip.assets.mapValues { (id, asset) -> if (placement.assets[id]?.let { it != asset } == true) fresh("asset") else id }
        val notes = clip.notes.map { it.copy(id = noteIds.getValue(it.id), x = it.x + delta.x, y = it.y + delta.y, parent = noteIds[it.parent]) }
        val captures = clip.capture.map { copied(it, noteIds, renamed, wireIds, assetIds, delta) }
        val placed = placement.copy(cards = placement.cards + spots, notes = placement.notes + notes,
            bends = placement.bends + clip.bends.mapNotNull { (id, bends) -> wireIds[id]?.let { next -> next to bends.map { Spot(it.x + delta.x, it.y + delta.y) } } }.toMap(),
            remarks = placement.remarks + clip.remarks.mapNotNull { (id, text) -> renamed[id]?.let { it to text } }.toMap(),
            capture = placement.capture + captures,
            assets = placement.assets + clip.assets.map { (id, asset) -> assetIds.getValue(id).let { it to asset.copy(id = it) } })
        var next = if (nodes.isEmpty()) shape else shape.copy(nodes = shape.nodes + nodes, wires = shape.wires + wires).recounted()
        join?.let { (pin, port) ->
            val inner = renamed[port.key.substringBefore(FoldSeparator)] ?: return emptyMap()
            val key = port.key.substringAfter(FoldSeparator)
            val wire = if (pin.provides) next.wire(pin.node, pin.key, inner, key, single, terms::fits)
                else next.wire(inner, key, pin.node, pin.key, single, terms::fits)
            if (wire == null) { notice = "This part cannot be connected here"; return emptyMap() }
            next = next.connect(wire, single)
        }
        request?.link?.let { from ->
            val target = renamed.values.firstOrNull { linked(next, from, it, request.relate) != null }
                ?: run { notice = "This part cannot be linked here"; return emptyMap() }
            next = linked(next, from, target, request.relate) ?: return emptyMap()
        }
        submit(Step(next, placed, title,
            if (join != null || request?.link != null) "Add and connect a part" else "Paste ${nodes.size + notes.size + captures.size} items")) {
            record(it)
            select(Selection(renamed.values.toSet(), notes = noteIds.values.toSet()))
        }
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
        val corner = corner(clip)
        paste(Spot(corner.x + grid * 2, corner.y + grid * 2), clip)
    }

    internal fun duplicateOffset(gesture: Gesture.Duplicating): Spot {
        val clip = clip(gesture.target) ?: return Spot(0.0, 0.0)
        val from = corner(clip)
        val to = snapped(Spot(from.x + gesture.at.x - gesture.from.x, from.y + gesture.at.y - gesture.from.y))
        return Spot(to.x - from.x, to.y - from.y)
    }

    private fun copied(value: Capture, ids: Map<String, String>, nodes: Map<String, String>, wires: Map<String, String>,
        assets: Map<String, String>, delta: Spot): Capture {
        fun shifted(point: Spot) = Spot(point.x + delta.x, point.y + delta.y)
        fun anchor(value: CaptureAnchor): CaptureAnchor = when (value) {
            is CaptureAnchor.World -> value.copy(at = shifted(value.at))
            is CaptureAnchor.Node -> nodes[value.node]?.let { value.copy(node = it, fallback = shifted(value.fallback)) }
                ?: CaptureAnchor.World(shifted(resolveCapture(value)))
            is CaptureAnchor.Row -> nodes[value.node]?.let { value.copy(node = it, fallback = shifted(value.fallback)) }
                ?: CaptureAnchor.World(shifted(resolveCapture(value)))
            is CaptureAnchor.Port -> nodes[value.node]?.let { value.copy(node = it, fallback = shifted(value.fallback)) }
                ?: CaptureAnchor.World(shifted(resolveCapture(value)))
            is CaptureAnchor.Wire -> wires[value.wire]?.let { value.copy(wire = it, fallback = shifted(value.fallback)) }
                ?: CaptureAnchor.World(shifted(resolveCapture(value)))
            is CaptureAnchor.Frame -> ids[value.frame]?.let { value.copy(frame = it, fallback = shifted(value.fallback)) }
                ?: CaptureAnchor.World(shifted(resolveCapture(value)))
        }
        val id = ids.getValue(value.id)
        val at = anchor(value.anchor)
        return when (value) {
            is Capture.Note -> value.copy(id = id, anchor = at)
            is Capture.Text -> value.copy(id = id, anchor = at)
            is Capture.Box -> value.copy(id = id, anchor = at)
            is Capture.Diamond -> value.copy(id = id, anchor = at)
            is Capture.Ellipse -> value.copy(id = id, anchor = at)
            is Capture.Freehand -> value.copy(id = id, anchor = at)
            is Capture.Arrow -> value.copy(id = id, anchor = at, end = anchor(value.end), bends = value.bends.map(::shifted))
            is Capture.Line -> value.copy(id = id, anchor = at, end = anchor(value.end), bends = value.bends.map(::shifted))
            is Capture.Frame -> value.copy(id = id, anchor = at, parent = ids[value.parent])
            is Capture.Image -> value.copy(id = id, anchor = at, asset = assets[value.asset] ?: value.asset)
            is Capture.Comment -> value.copy(id = id, anchor = at, replyTo = ids[value.replyTo])
        }
    }

    fun draft(now: Long): GraphDraft = GraphDraft(id, title, revision + 1, now, base, shape,
        placement.copy(cards = placement.cards.filterKeys { id -> shape.node(id) != null || base.node(id) != null }, remarks = placement.remarks.filterKeys { shape.node(it) != null }))

    fun mark(slot: Int) {
        val seen = camera?.seen ?: return
        val before = placement.marks[slot]
        edit("Set bookmark $slot") { s, p -> s to p.copy(marks = p.marks + (slot to Mark(seen.x, seen.y, seen.width, seen.height))) }
        if (placement.marks[slot] != before) notice = "Bookmark $slot set · press ⌥$slot to come back here"
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

    fun prefer(next: EditorPrefs) {
        try { prefs = next } catch (refused: IllegalArgumentException) { notice = refused.message; return }
        onPrefs(prefs)
    }

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

    override fun travel(dx: Int, dy: Int): Boolean = nudge(dx, dy, 1)

    fun nudge(dx: Int, dy: Int, steps: Int): Boolean {
        val framed = placement.notes.filter { it.id in selection.notes }.map(::inside)
        val cards = (selection.nodes.filter { it in layout.cards && ghost(it) == null } + framed.flatMap { it.first }).toSet()
        val notes = selection.notes + framed.flatMap { it.second }
        val captures = placement.capture.filter { it.id in selection.notes && it.id !in coveredCards }
        if (cards.isEmpty() && notes.isEmpty() && captures.isEmpty()) return false
        val by = grid * steps
        val count = selection.nodes.size + selection.notes.size
        edit("Nudge $count item${if (count == 1) "" else "s"}") { s, p ->
            s to p.copy(cards = p.cards + cards.associateWith { id -> (p.cards[id] ?: layout.cards[id]?.let { Spot(it.x, it.y) } ?: Spot(0.0, 0.0)).let { Spot(it.x + dx * by, it.y + dy * by) } },
                notes = p.notes.map { if (it.id in notes) it.copy(x = it.x + dx * by, y = it.y + dy * by) else it },
                capture = p.capture.map { if (it in captures) it.translated(Spot(dx * by, dy * by)) else it })
        }
        return true
    }

    fun pick(next: EditorTool) {
        if (gesture != null) cancel()
        captureTools.cancel()
        captureTools.sync(placement)
        tool = next
        captureMode = next !in setOf(EditorTool.Select, EditorTool.Hand)
        captureDrawing = false
        captureKeyboard = false
        if (!captureMode) return
        captureTools.select(CaptureTool.valueOf(next.name), keepTool)
        if (next == EditorTool.Image) { notice = "Image insertion needs a host image picker"; return }
        captureTools.begin(pointer)
        captureTools.update(Spot(pointer.x + captureTools.metrics.defaultWidth, pointer.y + captureTools.metrics.defaultHeight))
        captureDrawing = true
        captureKeyboard = true
    }

    private fun finishCapture() {
        if (!captureDrawing) return
        val id = captureTools.selected
        val part = captureTools.finish()
        captureDrawing = false
        captureKeyboard = false
        if (part == null) return
        val command = invoking ?: CommandId("edit-change")
        (editScope ?: CoroutineScope(Dispatchers.Unconfined)).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val next = Step(shape, part.layout?.let(placement::with) ?: placement, title)
                if (propose(part, next, command) { record(it) } is EditReceipt.Accepted && id != null && placement.captures.any { it.id == id }) select(Selection(notes = setOf(id)))
            } finally {
                captureTools.sync(placement)
            }
        }
        used()
        captureMode = keepTool || tool == EditorTool.Eraser
    }

    private fun captureText() {
        val id = captureTools.selected ?: return
        request = Request.Rename(pointer, pointerWindow, id, note = true)
    }

    fun link(from: String, to: String, relate: Boolean = false): Verdict {
        val verdict = linkVerdict(from, to, relate)
        if (!verdict.ok) { notice = verdict.message; return verdict }
        val source = shape.node(from)!!; val target = shape.node(to)!!
        val join = joiner(relate)
        if (join != null) join(shape, source, target)?.let { next -> edit("${if (relate) "Relate" else "Link"} ${source.label} to ${target.label}") { _, p -> next to p } }
        else pairFor(source, target)?.let { (out, into) -> connect(PinEnd(from, out, true), PinEnd(to, into, false)) }
        return verdict
    }

    fun linkVerdict(from: String, to: String, relate: Boolean = false): Verdict {
        val source = shape.node(from) ?: return Verdict(false, "Nothing to link from")
        val target = shape.node(to) ?: return Verdict(false, "Nothing to link to")
        if (from == to) return Verdict(false, "A card can't link to itself")
        val fits = joiner(relate)?.let { join -> join(shape, source, target) != null } ?: (pairFor(source, target) != null)
        val verb = if (relate) "Relate" else "Link"
        return if (fits) Verdict(true, "$verb ${source.label} to ${target.label}") else Verdict(false, "${target.label} can't take a ${verb.lowercase()} from ${source.label}")
    }

    private fun joiner(relate: Boolean) = if (relate) relater ?: linker else linker

    private fun used() { if (!tool.sticky && !keepTool) tool = EditorTool.Select }

    private fun frame(from: Spot, to: Spot) {
        val left = snap(min(from.x, to.x)); val top = snap(min(from.y, to.y))
        val width = max(MinNote, snap(abs(to.x - from.x))); val height = max(MinNote / 2, snap(abs(to.y - from.y)))
        val note = Note(fresh("note"), "Frame", left, top, width, height)
        edit { s, p -> s to p.copy(notes = p.notes + note) }
        select(Selection(notes = setOf(note.id)))
        request = Request.Rename(Spot(note.x, note.y), pointerWindow, note.id, note = true)
    }

    fun flip(horizontal: Boolean) {
        val cards = selection.nodes.mapNotNull(layout.cards::get)
        if (cards.size < 2) return
        val low = if (horizontal) cards.minOf { it.x } else cards.minOf { it.y }
        val high = if (horizontal) cards.maxOf { it.x + it.width } else cards.maxOf { it.y + it.height }
        edit("Flip ${cards.size} cards ${if (horizontal) "horizontally" else "vertically"}") { s, p ->
            s to p.copy(cards = p.cards + cards.associate { card -> card.id to if (horizontal) Spot(low + high - card.x - card.width, card.y) else Spot(card.x, low + high - card.y - card.height) })
        }
    }

    private fun linked(graph: GraphShape, from: String, to: String, relate: Boolean): GraphShape? {
        val source = graph.node(from) ?: return null
        val target = graph.node(to) ?: return null
        val join = joiner(relate)
        if (join != null) return join(graph, source, target)
        val (out, into) = pairFor(source, target, graph) ?: return null
        val wire = graph.wire(from, out, to, into, single, terms::fits) ?: return null
        return graph.connect(wire, single)
    }

    private fun pairFor(source: NodeShape, target: NodeShape, graph: GraphShape = shape): Pair<String, String>? = source.ports.filter { it.provides && !it.internal }.firstNotNullOfOrNull { out ->
        target.ports.filter { !it.provides && !it.internal }.sortedBy { it.wiring == PortWiring.Connected }
            .firstOrNull { into -> graph.verdict(source.id, out.key, target.id, into.key, single, terms::fits, terms::conversion).ok }?.let { out.key to it.key }
    }

    private fun place(at: Spot, shape: EditorTool): String? {
        val template = stencil(shape) ?: run { notice = "Nothing can be added here"; return null }
        val id = add(template, at).takeIf { it.isNotEmpty() }
        used()
        id?.let(layout.cards::get)?.let { card -> request = Request.Rename(Spot(card.x, card.y), pointerWindow, card.id, note = false) }
        return id
    }

    fun grow(dx: Int, dy: Int) {
        val card = selection.nodes.singleOrNull()?.let(layout.cards::get) ?: run { notice = "Select one card to add the next one"; return }
        follow(card.id, Spot(card.x + card.width / 2 + dx * (card.width + GrowGap), card.y + BlueprintEngine.HeaderHeight / 2 + dy * (card.height + GrowGap)))
    }

    private fun follow(from: String, at: Spot) {
        val template = stencil(EditorTool.Box) ?: run { notice = "Nothing can be added here"; return }
        add(template, Request.Palette(at, pointerWindow, link = from))
        used()
    }

    private fun touched(at: Spot, reach: Float, notes: Boolean): Selection =
        cardAt(at)?.takeIf { ghost(it.id) == null }?.let { if (it.id in folded) Selection(notes = setOf(it.id)) else Selection(setOf(it.id)) }
            ?: wireAt(at, reach)?.let { Selection(wires = setOf(it)) }
            ?: noteAt(at)?.takeIf { notes }?.let { Selection(notes = setOf(it.first.id)) } ?: Selection()


    fun ungroup() {
        if (refused()) return
        val members = selection.nodes.flatMap { node ->
            placement.collapsed.filter { (id, origins) -> node == id || node in origins }.keys
        }.toSet()
        val cards = selection.nodes.mapNotNull(layout.cards::get)
        val chosen = selection.notes.ifEmpty { members }.ifEmpty {
            placement.notes.filter { note -> cards.isNotEmpty() && cards.all { note.holds(it) } }
                .minByOrNull { it.width * it.height }?.let { setOf(it.id) }.orEmpty()
        }
        if (chosen.isEmpty()) { notice = "Select a frame or the cards inside one"; return }
        if (chosen.any { id -> shape.scopes.any { it.id == id } && id !in placement.collapsed }) {
            notice = "This group has no saved membership to restore"
            return
        }
        val origins = chosen.flatMap { placement.collapsed[it].orEmpty().entries }.associate { it.key to it.value }
        if (shape.nodes.any { it.scope in chosen && it.id !in origins }) {
            notice = "This group contains members without a saved parent"
            return
        }
        val restored = shape.nodes.filter { it.id !in origins }.toMutableList()
        origins.entries.sortedBy { it.value.order }.forEach { (id, origin) ->
            shape.node(id)?.let { restored.add(origin.order.coerceIn(0, restored.size), it.copy(scope = origin.parent.orEmpty())) }
        }
        val next = shape.copy(scopes = shape.scopes.filter { it.id !in chosen }, nodes = restored)
        val placed = placement.copy(notes = placement.notes.filter { it.id !in chosen },
            folded = placement.folded - chosen, collapsed = placement.collapsed - chosen)
        val selected = if (origins.isEmpty()) selection.nodes else origins.keys
        submit(Step(next, placed, title, "Expand ${chosen.size} groups")) { before ->
            record(before)
            select(Selection(nodes = selected))
        }
    }

    fun walk(dx: Int, dy: Int): Boolean {
        val shown = layout
        val from = (selection.nodes.singleOrNull() ?: selection.notes.singleOrNull()?.takeIf { it in folded })?.let(shown.cards::get) ?: return false
        fun card(id: String) = (shape.visible(id) ?: id).let { shown.cards[hiddenBy[it] ?: it] }?.takeIf { it.id != from.id && it.id !in coveredCards }
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
            ?: shown.cards.values.filter { it.id != from.id && it.id !in coveredCards && ghost(it.id) == null && ahead(it) }.minByOrNull(::cost) ?: return true
        if (next.id in folded) select(Selection(notes = setOf(next.id))) else select(Selection(setOf(next.id)))
        camera?.reveal(next)
        return true
    }

    fun saved(draft: GraphDraft) {
        if (pending != null) { refreshes += { saved(draft) }; return }
        revision = draft.revision
        keptShape = draft.shape; keptPlacement = placement; keptTitle = draft.title
    }

    fun save(now: Long) {
        if (refused()) return
        if (!valid) { notice = problems.first().text; return }
        onSave(draft(now))
    }

    fun revert() {
        if (refused()) return
        submit(Step(keptShape, keptPlacement, keptTitle, "Discard changes")) { before -> record(before); select(Selection()) }
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
        shown.foldedLinks[id]?.let(shown.cards::get)?.let { return listOf(it) }
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

    internal fun perform(command: CommandId, action: () -> Unit) {
        val current = commands.commands.firstOrNull { it.id == command } ?: return
        if (current.availability != Availability.Available || !available(command)) return
        val previous = invoking
        invoking = command
        try { action() } finally { invoking = previous }
    }

    override fun invoke(command: CommandId) = perform(command) {
        when {
            domainCommands.commands.any { it.id == command } -> hostCommands?.invoke?.invoke(command)
            command.value in UnimplementedCommandIds -> hostCommands?.capabilities?.get(command)?.action?.invoke()
            else -> dispatch(command)
        }
    }

    private fun dispatch(command: CommandId) {
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
            "edit-add-node" -> {
                val pin = (gesture as? Gesture.Wiring)?.takeIf { it.carried.isEmpty() }?.pin
                val link = gesture as? Gesture.Link
                pick(EditorTool.Select)
                request = Request.Palette(pointer, pointerWindow, pin, link = link?.from, relate = link?.relate ?: false)
            }
            "edit-palette" -> request = Request.Keys(pointer, pointerWindow, commands = true)
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
            "edit-escape" -> if (captureDrawing) { captureTools.cancel(); captureDrawing = false; captureKeyboard = false; captureMode = false; request = null; tool = EditorTool.Select }
                else if (request != null) request = null else if (gesture != null) cancel() else if (tool != EditorTool.Select) { tool = EditorTool.Select; captureMode = false }
                else if (!selection.empty) select(Selection())
            "tool-select", "tool-select-key" -> pick(EditorTool.Select)
            "tool-hand" -> pick(EditorTool.Hand)
            "tool-box", "tool-box-key" -> pick(EditorTool.Box)
            "tool-diamond", "tool-diamond-key" -> pick(EditorTool.Diamond)
            "tool-ellipse", "tool-ellipse-key" -> pick(EditorTool.Ellipse)
            "tool-arrow", "tool-arrow-key" -> pick(EditorTool.Arrow)
            "tool-line", "tool-line-key" -> pick(EditorTool.Line)
            "tool-link", "tool-relate" -> {
                pick(EditorTool.Select)
                tool = if (command.value == "tool-relate") EditorTool.Line else EditorTool.Arrow
            }
            "tool-text", "tool-text-key" -> pick(EditorTool.Text)
            "tool-eraser", "tool-eraser-key" -> pick(EditorTool.Eraser)
            "tool-frame" -> pick(EditorTool.Frame)
            "tool-lock" -> { keepTool = !keepTool; notice = if (keepTool) "Tools stay chosen after use · Q to stop" else "Tools go back to Selection after use" }
            "edit-group" -> comment()
            "edit-ungroup" -> ungroup()
            "edit-collapse" -> selection.notes.singleOrNull()?.let { if (it in folded) unfold(it) else fold(it) }
            "edit-rename-enter" -> if (captureDrawing) {
                if (tool in setOf(EditorTool.Text, EditorTool.Note, EditorTool.Comment)) captureText() else finishCapture()
            } else invoke(CommandId("edit-rename"))
            "pan-left" -> nudge(-1, 0, 1)
            "pan-right" -> nudge(1, 0, 1)
            "pan-up" -> nudge(0, -1, 1)
            "pan-down" -> nudge(0, 1, 1)
            "tool-pencil", "tool-pencil-x", "tool-pencil-key" -> pick(EditorTool.Pencil)
            "tool-image" -> pick(EditorTool.Image)
            "edit-palette-p" -> request = Request.Keys(pointer, pointerWindow, commands = true)
            "edit-distribute-horizontal" -> distribute(true)
            "edit-distribute-vertical" -> distribute(false)
            "edit-flip-horizontal" -> flip(true)
            "edit-flip-vertical" -> flip(false)
            "edit-walk-left" -> walk(-1, 0)
            "edit-walk-right" -> walk(1, 0)
            "edit-walk-up" -> walk(0, -1)
            "edit-walk-down" -> walk(0, 1)
            "edit-nudge-far-left" -> nudge(-1, 0, FarNudge)
            "edit-nudge-far-right" -> nudge(1, 0, FarNudge)
            "edit-nudge-far-up" -> nudge(0, -1, FarNudge)
            "edit-nudge-far-down" -> nudge(0, 1, FarNudge)
            "edit-grow-left" -> grow(-1, 0)
            "edit-grow-right" -> grow(1, 0)
            "edit-grow-up" -> grow(0, -1)
            "edit-grow-down" -> grow(0, 1)
            else -> command.value.substringAfter("edit-mark-", "").toIntOrNull()?.let(::mark) ?: command.value.substringAfter("edit-go-", "").toIntOrNull()?.let(::goTo)
        }
    }

    internal fun cancelPointer(selected: Selection) {
        cancel()
        captureTools.cancel()
        captureDrawing = false
        captureKeyboard = false
        request = null
        select(selected)
    }

    private fun cancel() {
        when (val g = gesture) {
            is Gesture.Move -> placement = g.before
            is Gesture.Resize -> placement = g.before
            is Gesture.Bend -> placement = g.before
            is Gesture.Marquee -> select(g.start)
            is Gesture.Erase -> select(Selection())
            is Gesture.Duplicating, is Gesture.Wiring, is Gesture.Link, is Gesture.Framing, null -> {}
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
        if (tool == EditorTool.Hand) return false
        if (locked || pending != null) {
            cardAt(at)?.let { card -> if (card.id in folded) select(Selection(notes = setOf(card.id))) else select(Selection(setOf(card.id))); return true }
            noteAt(at)?.let { (note, _) -> select(Selection(notes = setOf(note.id))); return true }
            refused()
            return false
        }
        if (captureMode) {
            captureTools.cancel()
            captureTools.sync(placement)
            captureTools.select(CaptureTool.valueOf(tool.name), keepTool)
            val hit = CaptureHit.find(placement.captures, at, camera?.zoom ?: 1.0, selection.notes.singleOrNull(), resolve = ::resolveCapture)
            captureDrawing = captureTools.begin(at, hit = hit)
            captureKeyboard = false
            if (captureDrawing && tool in setOf(EditorTool.Text, EditorTool.Note, EditorTool.Comment)) captureText()
            return true
        }
        if (tool == EditorTool.Select) {
            CaptureHit.find(placement.capture, at, camera?.zoom ?: 1.0, selection.notes.singleOrNull(), resolve = ::resolveCapture)?.let { hit ->
                if (keys.isAltPressed) {
                    val target = if (hit.id in selection.notes) selection else Selection(notes = setOf(hit.id))
                    select(target)
                    gesture = Gesture.Duplicating(at, at, target)
                    return true
                }
                captureTools.sync(placement)
                captureTools.select(CaptureTool.Select)
                select(if (toggle || adding) selection + Selection(notes = setOf(hit.id)) else Selection(notes = setOf(hit.id)))
                captureDrawing = captureTools.begin(at, hit = hit)
                captureKeyboard = false
                if (clicks >= 2) captureText()
                return true
            }
        }
        when (tool) {
            EditorTool.Box, EditorTool.Diamond, EditorTool.Ellipse -> { place(at, tool); return true }
            EditorTool.Pencil, EditorTool.Image -> return true
            EditorTool.Text, EditorTool.Note, EditorTool.Comment -> { used(); comment(Selection()); return true }
            EditorTool.Arrow, EditorTool.Line -> {
                cardAt(at)?.takeIf { it.id !in folded && ghost(it.id) == null }?.let { gesture = Gesture.Link(it.id, at, relate = tool == EditorTool.Line) }
                return true
            }
            EditorTool.Frame -> { gesture = Gesture.Framing(at, at); return true }
            EditorTool.Eraser -> { touched(at, reach, notes = true).let { hit -> gesture = Gesture.Erase(hit); select(hit) }; return true }
            EditorTool.Select, EditorTool.Hand -> {}
        }
        knotAt(at, reach)?.let { (wire, index) ->
            val spot = placement.bends.getValue(wire)[index]
            if (keys.isAltPressed) edit("Break a link") { s, p -> s.disconnect(setOf(wire)) to p.copy(bends = p.bends - wire) }
            else if (clicks >= 2) edit("Remove a reroute point") { s, p -> s to p.copy(bends = p.bends.mapValues { (_, spots) -> spots.filter { it != spot } }.filterValues { it.isNotEmpty() }) }
            else gesture = Gesture.Bend(wire, index, placement, spot)
            return true
        }
        if (!toggle && !adding && !keys.isAltPressed) endpointAt(at, reach)?.let { (wire, pin) ->
            val far = if (pin.provides) PinEnd(wire.consumer, wire.consumerPort, false) else PinEnd(wire.provider, wire.providerPort, true)
            gesture = Gesture.Wiring(far, at, window, carried = listOf(wire), picked = pin)
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
        cardAt(at)?.takeIf { it.id in aside }?.let { card ->
            val note = placement.notes.firstOrNull { it.id == card.id }
            when {
                note != null -> select(Selection(notes = setOf(note.id)))
                else -> select(Selection(setOf(card.id)))
            }
            return true
        }
        cardAt(at)?.takeIf { it.id in folded }?.let { card ->
            val note = placement.notes.first { it.id == card.id }
            val inside = inside(note)
            select(Selection(notes = setOf(note.id)))
            move(Gesture.Move(at, placement, Spot(note.x, note.y), inside.first, inside.second))
            return true
        }
        cardAt(at)?.let { card ->
            if (clicks >= 2 && !toggle && !adding) {
                request = Request.Rename(Spot(card.x, card.y), window, card.id, note = false)
                return true
            }
            if (keys.isAltPressed && ghost(card.id) == null) {
                val target = if (card.id in selection.nodes) selection else Selection(setOf(card.id))
                select(target)
                gesture = Gesture.Duplicating(at, at, target)
                return true
            }
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
            if (keys.isAltPressed) { edit("Break a link") { s, p -> s.disconnect(setOf(wire)) to p.copy(bends = p.bends - wire) }; return true }
            if (clicks >= 2) { bend(wire, at); return true }
            select(if (toggle || adding) selection + Selection(wires = setOf(wire)) else Selection(wires = setOf(wire)))
            return true
        }
        if (touch) return false
        if (clicks >= 2 && !toggle && !adding) { place(at, EditorTool.Box); return true }
        val start = if (toggle || adding) selection else Selection()
        if (!toggle && !adding) select(Selection())
        gesture = Gesture.Marquee(at, at, start, if (toggle) -1 else if (adding) 1 else 0)
        return true
    }

    private fun aligned(g: Gesture.Move, rawX: Double, rawY: Double): Triple<Double, Double, List<Guide>> {
        val gridX = snap(g.anchor.x + rawX) - g.anchor.x
        val gridY = snap(g.anchor.y + rawY) - g.anchor.y
        val start = hold?.layout ?: layout
        val moving: List<Bounds> = g.cards.mapNotNull(start.cards::get) + start.frames.filter { it.key in g.notes }
        if (moving.isEmpty()) return Triple(gridX, gridY, emptyList())
        val left = moving.minOf { it.x }; val top = moving.minOf { it.y }
        val width = moving.maxOf { it.x + it.width } - left; val height = moving.maxOf { it.y + it.height } - top
        val seen = camera?.seen
        val others = start.cards.values.filter { card -> card.id !in g.cards && card.id !in aside &&
            (seen == null || card.x + card.width >= seen.x && card.x <= seen.x + seen.width && card.y + card.height >= seen.y && card.y <= seen.y + seen.height) }
        val reach = AlignReach / (camera?.zoom ?: 1.0).coerceAtLeast(.05)
        fun lines(at: Double, size: Double) = doubleArrayOf(at, at + size / 2, at + size)
        fun nearest(mine: DoubleArray, theirs: (Card) -> DoubleArray): Triple<Double, Double, Card>? {
            var best: Triple<Double, Double, Card>? = null
            for (other in others) for (line in theirs(other)) for (edge in mine) {
                val shift = line - edge
                if (abs(shift) <= reach && (best == null || abs(shift) < abs(best.first))) best = Triple(shift, line, other)
            }
            return best
        }
        val x = nearest(lines(left + rawX, width)) { lines(it.x, it.width) }
        val y = nearest(lines(top + rawY, height)) { lines(it.y, it.height) }
        val dx = x?.let { rawX + it.first } ?: gridX
        val dy = y?.let { rawY + it.first } ?: gridY
        val guides = listOfNotNull(
            x?.let { (_, line, other) -> Guide(line, min(top + dy, other.y), line, max(top + dy + height, other.y + other.height)) },
            y?.let { (_, line, other) -> Guide(min(left + dx, other.x), line, max(left + dx + width, other.x + other.width), line) },
        )
        return Triple(dx, dy, guides)
    }

    fun templates(request: Request.Palette, query: String = ""): List<Template> {
        val pin = request.from?.let { shape.node(it.node)?.port(it.key, it.provides) }
        if (request.from != null && pin == null) return emptyList()
        val words = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        return choices.filter { template ->
            (template.captureTool == null || request.from == null && request.link == null) && template.offers(pin, terms::fits) && words.all { word ->
                listOf(template.label, template.type, template.category).any { it.contains(word, ignoreCase = true) } ||
                    template.ports.any { it.key.contains(word, ignoreCase = true) }
            }
        }
    }

    private fun spliced(graph: GraphShape, wire: WireShape, id: String): GraphShape? {
        if (id == wire.provider || id == wire.consumer) return null
        val middle = graph.node(id) ?: return null
        val detached = graph.disconnect(setOf(wire.id))
        if (linker != null) {
            val first = linked(detached, wire.provider, id, false) ?: return null
            return linked(first, id, wire.consumer, false)
        }
        val source = graph.node(wire.provider)?.port(wire.providerPort, true) ?: return null
        val sink = graph.node(wire.consumer)?.port(wire.consumerPort, false) ?: return null
        val input = middle.ports.filter { !it.provides && !it.internal && terms.fits(source.type, it.type) }.singleOrNull() ?: return null
        val output = middle.ports.filter { it.provides && !it.internal && terms.fits(it.type, sink.type) }.singleOrNull() ?: return null
        val before = detached.wire(wire.provider, source.key, id, input.key, single, terms::fits) ?: return null
        val first = detached.connect(before, single)
        val after = first.wire(id, output.key, wire.consumer, sink.key, single, terms::fits) ?: return null
        return first.connect(after, single)
    }

    fun joins(request: Request.Palette): List<Join> {
        val cards = layout.cards.values.filter { ghost(it.id) == null && it.id !in folded && it.id !in aside }
        val found = when {
            request.link != null -> cards.filter { it.id != request.link && linkVerdict(request.link, it.id, request.relate).ok }
                .mapNotNull { card -> node(card.id)?.let { Join(it.id, it.label, null) } }
            request.from != null -> cards.filter { it.id != request.from.node }.mapNotNull { card ->
                node(card.id)?.let { target -> target.ports.firstOrNull { port -> port.provides != request.from.provides && !port.internal && verdict(request.from, PinEnd(target.id, port.key, port.provides)).ok }
                    ?.let { Join(target.id, target.label, PinEnd(target.id, it.key, it.provides)) } }
            }
            else -> emptyList()
        }
        fun distance(id: String) = layout.cards[id]?.let { hypot(it.x + it.width / 2 - request.at.x, it.y + it.height / 2 - request.at.y) } ?: Double.MAX_VALUE
        return found.sortedBy { distance(it.node) }
    }

    fun join(request: Request.Palette, to: Join) {
        if (this.request === request) this.request = null
        when {
            request.link != null -> link(request.link, to.node, request.relate)
            request.from != null && to.pin != null -> connect(request.from, to.pin)
        }
        if (this.request == null) commandHost?.focus?.invoke()
    }

    fun added(request: Request.Palette, template: Template) {
        if (this.request === request) this.request = null
        add(template, request)
        if (this.request == null) commandHost?.focus?.invoke()
    }

    private fun move(gesture: Gesture.Move) {
        hold = Hold(shape, showChanges, layout)
        this.gesture = gesture
    }

    override fun drag(x: Float, y: Float, window: Offset) {
        point(x, y, window)
        val at = pointer
        if (captureDrawing && !captureKeyboard) {
            val hit = CaptureHit.find(placement.captures, at, camera?.zoom ?: 1.0, selection.notes.singleOrNull(), resolve = ::resolveCapture)
            captureTools.update(at, hit = hit)
            return
        }
        when (val g = gesture) {
            is Gesture.Duplicating -> gesture = g.copy(at = at)
            is Gesture.Move -> {
                if (!g.moved && hypot(at.x - g.from.x, at.y - g.from.y) < 3) return
                val (dx, dy, guides) = aligned(g, at.x - g.from.x, at.y - g.from.y)
                placement = g.before.copy(
                    cards = g.before.cards + g.cards.associateWith { id -> (g.before.cards[id] ?: layout.cards[id]?.let { Spot(it.x, it.y) } ?: g.anchor).let { Spot(it.x + dx, it.y + dy) } },
                    notes = g.before.notes.map { if (it.id in g.notes) it.copy(x = it.x + dx, y = it.y + dy) else it },
                    bends = g.before.bends.mapValues { (wire, spots) -> if (shape.wires.any { it.id == wire && it.provider in g.cards && it.consumer in g.cards }) spots.map { Spot(it.x + dx, it.y + dy) } else spots },
                )
                gesture = g.copy(moved = true, guides = guides)
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
            is Gesture.Link -> {
                val target = cardAt(at)?.id?.takeIf { it != g.from && it !in folded && ghost(it) == null }
                gesture = g.copy(target = target, verdict = target?.let { linkVerdict(g.from, it, g.relate) })
            }
            is Gesture.Framing -> gesture = g.copy(to = at)
            is Gesture.Erase -> touched(at, EraseReach, notes = false).takeIf { !it.empty }?.let { hit -> (g.hits + hit).let { hits -> gesture = g.copy(hits = hits); select(hits) } }
            null -> {}
        }
    }

    override fun release(x: Float, y: Float, window: Offset) {
        point(x, y, window)
        if (captureDrawing && !captureKeyboard) {
            if (tool !in setOf(EditorTool.Text, EditorTool.Note, EditorTool.Comment)) finishCapture()
            return
        }
        val g = gesture
        gesture = null
        when (g) {
            is Gesture.Duplicating -> if (hypot(pointer.x - g.from.x, pointer.y - g.from.y) >= 3) {
                clip(g.target)?.let { clip ->
                    val corner = corner(clip)
                    paste(Spot(corner.x + pointer.x - g.from.x, corner.y + pointer.y - g.from.y), clip)
                }
            }
            is Gesture.Move -> if (g.moved) {
                val moved = placement
                val id = g.cards.singleOrNull()?.takeIf { g.notes.isEmpty() }
                val crossing = id?.let { card ->
                    hold?.layout?.links?.asReversed()?.firstOrNull { link ->
                        link.from != card && link.to != card && link.members.size == 1 && link.id !in coveredRoutes &&
                            (1 until link.trace.size).any { k -> segment(pointer, link.trace[k - 1], link.trace[k]) <= 8 / (camera?.zoom ?: 1.0) }
                    }?.members?.singleOrNull()?.let { member -> shape.wires.firstOrNull { it.id == member } }
                }
                val adopted = adopt(g.cards)
                val adoptedNode = id?.let(adopted::node)?.takeIf { it.scope != shape.node(it.id)?.scope }
                val connected = crossing?.let { wire -> spliced(adopted, wire, id!!) }
                placement = g.before
                if (crossing != null && connected == null) {
                    notice = "This card needs one compatible input and output to insert into the link"
                    return
                }
                val next = Step(connected ?: adopted, if (crossing == null) moved else moved.copy(bends = moved.bends - crossing.id), title,
                    if (crossing == null) "Move cards" else "Insert a card into a link")
                submit(next) { before ->
                    record(before)
                    if (adoptedNode != null) {
                        val home = adopted.scopes.firstOrNull { it.id == adoptedNode.scope }?.label ?: adoptedNode.scope
                        notice = "Moved ${adoptedNode.label} into $home"
                    }
                }
            } else g.only?.let { select(Selection(setOf(it))) }
            is Gesture.Link -> {
                val target = g.target
                if (target != null) link(g.from, target, g.relate)
                else if (cardAt(pointer) == null && hypot(pointer.x - g.at.x, pointer.y - g.at.y) > ClickSlop) request = Request.Palette(pointer, window, link = g.from, relate = g.relate)
                used()
            }
            is Gesture.Framing -> { used(); frame(g.from, g.to) }
            is Gesture.Erase -> if (!g.hits.empty) delete(g.hits)
            is Gesture.Wiring -> {
                val target = g.target
                when {
                    g.carried.isNotEmpty() && !moved(g) -> {}
                    target != null && g.carried.isNotEmpty() -> rewire(g, target)
                    target != null -> connect(g.pin, target)
                    g.carried.isEmpty() && cardAt(pointer) == null && g.verdict == null && moved(g) -> request = Request.Palette(pointer, window, g.pin)
                    g.carried.isNotEmpty() && cardAt(pointer) == null -> {
                        val ids = g.carried.mapTo(hashSetOf()) { it.id }
                        edit("Break ${ids.size} link${if (ids.size == 1) "" else "s"}") { s, p -> s.disconnect(ids) to p.copy(bends = p.bends - ids) }
                    }
                    g.carried.isEmpty() && !moved(g) -> select(Selection(setOf(g.pin.node)))
                }
            }
            is Gesture.Resize -> {
                val next = Step(shape, placement, title, "Resize a comment")
                placement = g.before
                submit(next)
            }
            is Gesture.Bend -> {
                val moved = Spot(snap(pointer.x), snap(pointer.y))
                val near = placement.bends.values.asSequence().flatten().firstOrNull { it != moved && hypot(it.x - moved.x, it.y - moved.y) <= Knot * 2 }
                if (near != null) placement = placement.copy(bends = placement.bends.mapValues { (_, spots) -> spots.map { if (it == moved) near else it }.distinct() })
                val next = Step(shape, placement, title, if (near != null) "Join two reroute points" else "Reroute a wire")
                placement = g.before
                submit(next)
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

    override fun overlay(scope: DrawScope, zoom: Double) { drawOverlay(scope, zoom) }

    override fun cursor(x: Float, y: Float, reach: Float): Boolean {
        val at = Spot(x.toDouble(), y.toDouble())
        return pinAt(at) != null || cardAt(at) != null || noteAt(at) != null || wireAt(at, reach) != null
    }

    internal fun carried(g: Gesture.Wiring, target: PinEnd): Verdict {
        if (!visiblePins(hiddenBy[target.node] ?: target.node)) return Verdict(false, "Zoom in to wire this port")
        if (g.carried.isEmpty()) return verdict(g.pin, target)
        if (target.provides != g.picked?.provides) return Verdict(false, if (g.picked?.provides == true) "Drop on an output" else "Drop on an input")
        if (single && !target.provides && g.carried.size > 1) return Verdict(false, "This input accepts one link")
        if (shape.node(target.node)?.port(target.key, target.provides) == null) return Verdict(false, "Drop on a port")
        var graph = shape.disconnect(g.carried.mapTo(hashSetOf()) { it.id })
        for (wire in g.carried) {
            val next = relocated(wire, target)
            val verdict = graph.verdict(next.provider, next.providerPort, next.consumer, next.consumerPort, single, terms::fits, terms::conversion)
            if (!verdict.ok) return verdict
            graph = graph.connect(next, single)
        }
        return Verdict(true, "Move ${g.carried.size} link${if (g.carried.size == 1) "" else "s"}")
    }

    private fun moved(g: Gesture.Wiring) = hypot(pointer.x - g.from.x, pointer.y - g.from.y) > ClickSlop

    private fun rewire(g: Gesture.Wiring, target: PinEnd) {
        val verdict = carried(g, target)
        if (!verdict.ok) { notice = verdict.message; return }
        if (refused()) return
        val replacements = g.carried.map { relocated(it, target) }
        val next = replacements.fold(shape.disconnect(g.carried.mapTo(hashSetOf()) { it.id })) { graph, wire -> graph.connect(wire, single) }
        if (next == shape) return
        val ids = next.wires.mapTo(hashSetOf()) { it.id }
        val bends = placement.bends.filterKeys { it in ids }.toMutableMap()
        g.carried.zip(replacements).forEach { (before, after) -> placement.bends[before.id]?.let { bends[after.id] = it } }
        submit(Step(next, placement.copy(bends = bends), title, "Move ${g.carried.size} link${if (g.carried.size == 1) "" else "s"}")) {
            record(it)
            select(Selection(wires = replacements.mapTo(hashSetOf()) { wire -> wire.id }))
        }
    }

    private fun relocated(wire: WireShape, target: PinEnd): WireShape =
        if (target.provides) wire.copy(provider = target.node, providerPort = target.key)
        else wire.copy(consumer = target.node, consumerPort = target.key, type = shape.node(target.node)!!.port(target.key, false)!!.type)

    fun bend(wire: String, at: Spot) {
        val link = layout.links.firstOrNull { it.id == wire } ?: return
        val existing = placement.bends[wire].orEmpty()
        val ends = listOf(link.trace.first()) + existing.map { it.x to it.y } + listOf(link.trace.last())
        val index = ends.zipWithNext().withIndex().minByOrNull { (_, pair) -> segment(at, pair.first, pair.second) }?.index ?: existing.size
        val spot = Spot(snap(at.x), snap(at.y))
        edit { s, p -> s to p.copy(bends = p.bends + (wire to existing.toMutableList().apply { add(index.coerceIn(0, size), spot) })) }
    }

    private fun adopt(cards: Set<String>): GraphShape {
        val scopes = shape.scopes.associateBy { it.id }
        val frames = placement.notes.filter { it.id in scopes }
        if (frames.isEmpty()) return shape
        val moves = cards.mapNotNull { id ->
            val node = shape.node(id) ?: return@mapNotNull null
            val card = layout.cards[id] ?: return@mapNotNull null
            val x = card.x + card.width / 2; val y = card.y + card.height / 2
            val home = frames.filter { x in it.x..(it.x + it.width) && y in it.y..(it.y + it.height) }.minByOrNull { it.width * it.height }?.id ?: return@mapNotNull null
            (id to home).takeIf { home != node.scope }
        }.toMap()
        return shape.copy(nodes = shape.nodes.map { node -> moves[node.id]?.let { node.copy(scope = it) } ?: node })
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
        return layout.cards.values.filter { it.id !in coveredCards && ghost(it.id) == null && it.x < right && it.x + it.width > left && it.y < bottom && it.y + it.height > top }.mapTo(hashSetOf()) { it.id }
    }

    fun cardAt(at: Spot): Card? = layout.cards.values.lastOrNull { it.id !in coveredCards && at.x >= it.x && at.x <= it.x + it.width && at.y >= it.y && at.y <= it.y + it.height }

    private fun endpointAt(at: Spot, reach: Float): Pair<WireShape, PinEnd>? =
        shape.wires.asSequence().filter { it.id in selection.wires && it.id !in coveredRoutes }.flatMap { wire ->
            sequenceOf(PinEnd(wire.provider, wire.providerPort, true), PinEnd(wire.consumer, wire.consumerPort, false)).mapNotNull { pin ->
                anchor(pin)?.takeIf { (hiddenBy[pin.node] ?: pin.node) !in coveredCards && visiblePins(hiddenBy[pin.node] ?: pin.node) }?.let { (x, y) -> hypot(at.x - x, at.y - y) to (wire to pin) }
            }
        }.filter { it.first <= max(reach.toDouble(), Knot) }.minByOrNull { it.first }?.second

    fun pinAt(at: Spot): PinEnd? {
        val card = layout.cards.values.lastOrNull { it.id !in coveredCards && at.x >= it.x - PinReach && at.x <= it.x + it.width + PinReach && at.y >= it.y && at.y <= it.y + it.height } ?: return null
        if (!visiblePins(card.id)) return null
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
        link.id !in coveredRoutes && (1 until trace.size).any { k -> segment(at, trace[k - 1], trace[k]) <= reach + 2 }
    }?.id?.takeIf { id -> diff.wires[id] != Change.Removed || shape.wires.any { it.id == id } }

    private fun knotAt(at: Spot, reach: Float): Pair<String, Int>? = placement.bends.firstNotNullOfOrNull { (wire, spots) ->
        spots.indexOfFirst { hypot(it.x - at.x, it.y - at.y) <= Knot + reach }.takeIf { it >= 0 && wire !in coveredRoutes }?.let { wire to it }
    }

    private fun snap(value: Double) = (value / grid).roundToLong() * grid

    private fun snapped(spot: Spot) = Spot(snap(spot.x), snap(spot.y))

    private fun scene(shape: GraphShape, placement: Placement, diff: GraphDiff?): BlueprintLayout<String> {
        var shut = folded + opened
        var scene = projection(shape, placement, diff, shut, opened.isNotEmpty())
        projectedOpening = null
        for (note in opened) {
            shut = shut - note
            val expanded = projection(shape, placement, diff, shut, true)
            if (scene.cards[note]?.folded?.let { it > 0 } == true && expanded.frames.any { it.key == note }) {
                val opening = BlueprintEngine.open(scene, expanded, note)
                scene = opening.layout
                projectedOpening = opening
            } else scene = expanded
        }
        hiddenBy = scene.cardOf.filter { (source, card) -> source != card }
        return if (opened.isEmpty()) scene else scene.copy(links = scene.links.filter { it.points.isNotEmpty() })
    }

    private fun projection(shape: GraphShape, placement: Placement, diff: GraphDiff?, folds: Set<String>, preserveHidden: Boolean): BlueprintLayout<String> {
        val byId = shape.nodes.associateBy { it.id }
        val attached = shape.nodes.filter { it.attachedTo != null && it.kind != NodeKind.Route }.associate { it.attachedTo!! to it.id }
        fun visible(id: String) = byId[id]?.let { if (it.kind == NodeKind.Route) attached[id] else id }
        val cards = LinkedHashMap<String, Card>(shape.nodes.size * 2)
        var spare = placement.cards.values.maxOfOrNull { it.x }?.plus(cardWidth + BlueprintEngine.LayerGap) ?: 0.0
        var spareY = 0.0
        val shown = shape.nodes.filter { it.kind != NodeKind.Route } + diff?.removedNodes.orEmpty().filter { it.kind != NodeKind.Route }
        shown.forEach { node ->
            val pins = pins(node)
            val body = bodies[node.id]
            val width = max(cardWidth, body?.width ?: 0.0)
            val height = max(BlueprintEngine.cardHeight(BlueprintFace.rows(node, rows(pins))), body?.height ?: 0.0)
            val spot = placement.cards[node.id] ?: Spot(spare, spareY).also { spareY += height + BlueprintEngine.CardGap; if (spareY > 2400) { spareY = 0.0; spare += cardWidth + BlueprintEngine.LayerGap } }
            cards[node.id] = Card(node.id, spot.x, spot.y, width, height, pins, 0)
        }
        val notes = placement.notes.associateBy { it.id }
        val focused = focus?.let(notes::get)
        val quiet = implied(shape)
        val into = HashMap<String, String>()
        val beside = if (focused == null) emptySet() else neighbours(shape, quiet, focused, cards, ::visible)
        val shut = folds.mapNotNull(notes::get)
        fun contains(outer: Note, inner: Note): Boolean {
            if (outer.id !in placement.collapsed && inner.id !in placement.collapsed)
                return outer.holds(inner) && outer.width * outer.height > inner.width * inner.height
            val seen = HashSet<String>()
            var parent = inner.parent
            while (parent != null && seen.add(parent)) {
                if (parent == outer.id) return true
                parent = notes[parent]?.parent
            }
            return false
        }
        val outer = shut.filter { fold -> shut.none { it !== fold && contains(it, fold) } }
            .sortedBy { if (it.id in placement.collapsed) 0 else 1 }
        outer.forEach { fold ->
            val members = placement.collapsed[fold.id]
            cards.values.forEach {
                if (if (members == null) fold.holds(it) else it.id in members)
                    into.getOrPut(it.id) { fold.id }
            }
        }
        into.keys.forEach(cards::remove)
        hiddenBy = into
        aside = beside.mapTo(HashSet()) { into[it] ?: it }
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
            if (bundles) { cards[fold.id] = Card(fold.id, fold.x, fold.y, cardWidth, foldHeight(fold.id), emptyList(), into.count { it.value == fold.id }); return@forEach }
            val ends = bounds[fold.id].orEmpty().entries
            val inputs = ends.filter { !it.key.second }; val outputs = ends.filter { it.key.second }
            val pins = inputs.mapIndexed { i, (end, type) -> Pin(end.first, type, false, BlueprintEngine.pinY(i), PortWiring.Connected) } +
                outputs.mapIndexed { i, (end, type) -> Pin(end.first, type, true, BlueprintEngine.pinY(i), PortWiring.Connected) }
            cards[fold.id] = Card(fold.id, fold.x, fold.y, cardWidth, BlueprintEngine.cardHeight(max(inputs.size, outputs.size)), pins, into.count { it.value == fold.id })
        }
        val links = ArrayList<Link>(shape.wires.size + shape.routes.size)
        val bundled = HashMap<String, Int>()
        val selfLinks = HashMap<String, Int>()
        val foldedLinks = LinkedHashMap<String, String>()
        fun wireLink(wire: WireShape) {
            if (wire.providerPort in Internal || wire.consumerPort in Internal || wire.id in quiet) return
            val from = end(wire.provider).takeIf { it in cards } ?: return
            val to = end(wire.consumer).takeIf { it in cards } ?: return
            if (from == to && (wire.provider != wire.consumer || from != wire.provider)) {
                foldedLinks[wire.id] = from
                return
            }
            if ((bundles || routing.bundles) && (from != (visible(wire.provider) ?: wire.provider) || to != (visible(wire.consumer) ?: wire.consumer))) {
                val id = "$from>$to"
                bundled[id]?.let { at -> links[at] = links[at].let { it.copy(members = it.members + wire.id) }; return }
                val (points, curve) = path(BlueprintEngine.anchor(cards, from, null, true), BlueprintEngine.anchor(cards, to, null, false), placement.bends[id].orEmpty())
                bundled[id] = links.size
                links += Link(id, from, to, null, null, LinkKind.Wire, listOf(wire.id), points, family = family(wire), curve = curve)
                return
            }
            val fromPort = key(wire.provider, wire.providerPort); val toPort = key(wire.consumer, wire.consumerPort)
            val id = "$from.$fromPort>$to.$toPort"
            val start = BlueprintEngine.anchor(cards, from, fromPort, true)
            val finish = BlueprintEngine.anchor(cards, to, toPort, false)
            val bends = placement.bends[id] ?: if (from == to) {
                val card = cards.getValue(from)
                val lane = selfLinks.getOrElse(from) { 0 } + 1
                selfLinks[from] = lane
                val gap = BlueprintEngine.CardGap * lane
                listOf(Spot(start.first + gap, start.second),
                    Spot(start.first + gap, card.y + card.height + gap),
                    Spot(finish.first - gap, card.y + card.height + gap),
                    Spot(finish.first - gap, finish.second))
            } else emptyList()
            val (points, curve) = path(start, finish, bends)
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
            if (from !in cards || to !in cards) return@forEach
            if (from == to) {
                foldedLinks[route.id] = from
                return@forEach
            }
            if (!routeIds.add("$from>$to") && !preserveHidden) return@forEach
            val (points, curve) = path(BlueprintEngine.anchor(cards, from, null, true), BlueprintEngine.anchor(cards, to, null, false), emptyList())
            links += Link(route.id, from, to, null, null, LinkKind.Route, listOf(route.id), points, curve = curve)
        }
        val result = assembled(cards, drawn(placement.notes, folds, placement.collapsed), links)
        return result.copy(
            frames = result.frames.map { frame ->
                placement.collapsed[frame.key]?.let { members ->
                    frame.copy(nodes = members.keys.filterTo(LinkedHashSet()) { it in cards })
                } ?: frame
            },
            cardOf = shown.associate { it.id to (into[it.id] ?: it.id) }, foldedLinks = foldedLinks)
    }

    private fun neighbours(shape: GraphShape, quiet: Set<String>, focused: Note, cards: Map<String, Card>, visible: (String) -> String?): Set<String> {
        val inside = cards.values.filter { focused.holds(it) }.mapTo(HashSet()) { it.id }
        return buildSet {
            shape.wires.forEach { wire ->
                if (wire.providerPort in Internal || wire.consumerPort in Internal || wire.id in quiet) return@forEach
                val from = visible(wire.provider)?.takeIf { it in cards } ?: return@forEach
                val to = visible(wire.consumer)?.takeIf { it in cards } ?: return@forEach
                if (from in inside && to !in inside) add(to)
                if (to in inside && from !in inside) add(from)
            }
        }
    }

    private fun foldHeight(note: String): Double =
        foldNode(note)?.let(shape::node)?.let { node -> BlueprintEngine.cardHeight(BlueprintFace.rows(node, rows(pins(node)))) } ?: BlueprintEngine.cardHeight(0)

    private fun drawn(notes: List<Note>, folds: Set<String> = folded,
        collapsed: Map<String, Map<String, Placement.Origin>> = placement.collapsed): List<Note> {
        if (folds.isEmpty()) return notes
        val byId = notes.associateBy { it.id }
        val shut = folds.mapNotNull(byId::get)
        return notes.filter { note -> shut.none { fold ->
            if (fold.id == note.id) true
            else if (fold.id !in collapsed && note.id !in collapsed) fold.holds(note)
            else {
                val seen = HashSet<String>()
                var parent = note.parent
                var inside = false
                while (parent != null && seen.add(parent)) {
                    if (parent == fold.id) { inside = true; break }
                    parent = byId[parent]?.parent
                }
                inside
            }
        } }
    }

    private fun assembled(cards: Map<String, Card>, notes: List<Note>, links: List<Link>): BlueprintLayout<String> {
        val byId = notes.associateBy { it.id }
        val scopes = shape.scopes.associateBy { it.id }
        fun owner(card: Card): String? {
            val node = shape.node(card.id)
            val seen = HashSet<String>()
            var scope = node?.scope
            while (scope != null && seen.add(scope)) {
                if (scope in byId) return scope
                if ("feature:$scope" in byId) return "feature:$scope"
                scope = scopes[scope]?.parent
            }
            placement.notes.firstOrNull { it.id == card.id }?.parent?.takeIf { it in byId }?.let { return it }
            return notes.filter { it.id != card.id && it.holds(card) }
                .minWithOrNull(compareBy<Note> { it.width * it.height }.thenBy { it.id })?.id
        }
        val owners = cards.values.groupBy(::owner)
        val frames = notes.map { note ->
            Frame(note.id, note.text, note.x, note.y, note.width, note.height,
                owners[note.id].orEmpty().mapTo(LinkedHashSet()) { it.id },
                emptyList(), false, note.parent)
        }
        val boxes = cards.values + frames
        val loops = links.filter { it.from == it.to }.flatMap { it.trace }
        return BlueprintLayout(id, cards, frames, links,
            maxOf(boxes.maxOfOrNull { it.x + it.width } ?: 0.0, loops.maxOfOrNull { it.first } ?: 0.0),
            maxOf(boxes.maxOfOrNull { it.y + it.height } ?: 0.0, loops.maxOfOrNull { it.second } ?: 0.0))
    }

    private fun moved(last: Built, placement: Placement, settled: Set<String>): BlueprintLayout<String> {
        val old = last.layout
        if (placement == last.placement && settled.isEmpty()) return old
        val changed = old.cards.keys.filterTo(hashSetOf()) { id ->
            id in settled || placement.cards[id] != last.placement.cards[id] && placement.cards[id] != null
        }
        val bent = (placement.bends.keys + last.placement.bends.keys).filterTo(hashSetOf()) { placement.bends[it] != last.placement.bends[it] }
        if (changed.isEmpty() && bent.isEmpty() && placement.notes == last.placement.notes) return old
        val cards = LinkedHashMap(old.cards)
        changed.forEach { id -> placement.cards[id]?.let { spot ->
            val card = cards.getValue(id)
            val before = last.placement.cards[id]
            cards[id] = if (opened.isNotEmpty() && before != null) card.copy(x = card.x + spot.x - before.x, y = card.y + spot.y - before.y)
                else card.copy(x = spot.x, y = spot.y)
        } }
        val touched = changed.flatMapTo(hashSetOf()) { last.links.touching[it].orEmpty() }.apply { bent.mapNotNullTo(this, last.links.slots::get) }
        val settledMove = gesture !is Gesture.Move
        if (settledMove && changed.isNotEmpty()) old.links.forEachIndexed { index, link ->
            if (placement.bends[link.id].isNullOrEmpty() && changed.any { id ->
                listOfNotNull(old.cards[id], cards[id]).any { crosses(link.trace, it, BlueprintEngine.CardGap) }
            }) touched += index
        }
        val links = if (touched.isEmpty()) old.links else ArrayList(old.links).apply {
            for (k in touched) this[k] = this[k].let { link ->
                val bends = placement.bends[link.id].orEmpty()
                if (link.from == link.to && bends.isEmpty() && last.placement.bends[link.id].isNullOrEmpty()) {
                    val before = old.cards.getValue(link.from)
                    val after = cards.getValue(link.from)
                    val points = link.points.map { (x, y) -> x + after.x - before.x to y + after.y - before.y }
                    if (settledMove && cards.values.any { it.id != link.from && crosses(points, it) })
                        path(BlueprintEngine.anchor(cards, link.from, link.fromPort, true), BlueprintEngine.anchor(cards, link.to, link.toPort, false), emptyList(), cards.values)
                            .let { (route, curve) -> link.copy(points = route, curve = curve) }
                    else link.copy(points = points)
                } else path(BlueprintEngine.anchor(cards, link.from, link.fromPort, true), BlueprintEngine.anchor(cards, link.to, link.toPort, false), bends,
                    cards.values.takeIf { settledMove }).let { (points, curve) -> link.copy(points = points, curve = curve) }
            }
        }
        val next = assembled(cards, drawn(placement.notes), links)
        return if (opened.isEmpty()) next.copy(cardOf = old.cardOf, foldedLinks = old.foldedLinks) else next.copy(frames = old.frames,
            width = maxOf(old.width, next.width), height = maxOf(old.height, next.height), cardOf = old.cardOf, foldedLinks = old.foldedLinks)
    }

    internal fun hiddenOf(id: String): String? = hiddenBy[id]

    internal fun link(id: String): Link? {
        val shown = layout
        return built?.takeIf { it.layout === shown }?.links?.slots?.get(id)?.let(shown.links::getOrNull)?.takeIf { it.id == id } ?: shown.links.firstOrNull { it.id == id }
    }

    private fun avoidMoved(layout: BlueprintLayout<String>, moved: Set<String>): BlueprintLayout<String> {
        if (moved.isEmpty()) return layout
        return layout.copy(links = layout.links.map { link ->
            if (placement.bends[link.id].isNullOrEmpty() && (link.from in moved || link.to in moved ||
                moved.any { id -> layout.cards[id]?.let { crosses(link.trace, it, BlueprintEngine.CardGap) } == true }))
                path(BlueprintEngine.anchor(layout.cards, link.from, link.fromPort, true),
                    BlueprintEngine.anchor(layout.cards, link.to, link.toPort, false), emptyList(), layout.cards.values)
                    .let { (points, curve) -> link.copy(points = points, curve = curve) }
            else link
        })
    }

    private fun crosses(points: List<Pair<Double, Double>>, box: Bounds, gap: Double = 0.0): Boolean =
        points.zipWithNext().any { (a, b) ->
            maxOf(a.first, b.first) > box.x - gap && minOf(a.first, b.first) < box.x + box.width + gap &&
                maxOf(a.second, b.second) > box.y - gap && minOf(a.second, b.second) < box.y + box.height + gap
        }

    private fun path(from: Pair<Double, Double>, to: Pair<Double, Double>, bends: List<Spot>, boxes: Collection<Card>? = null): Pair<List<Pair<Double, Double>>, Boolean> =
        if (bends.isNotEmpty()) (listOf(from) + bends.map { it.x to it.y } + listOf(to)).zipWithNext()
            .flatMapIndexed { k, (a, b) -> BlueprintEngine.curve(a, b).let { if (k == 0) it else it.drop(1) } } to false
        else if (boxes != null) {
            fun inside(point: Pair<Double, Double>, box: Card) = point.first > box.x && point.first < box.x + box.width &&
                point.second > box.y && point.second < box.y + box.height
            // Overlapping endpoint cards cannot be avoided without moving the user's placement.
            val avoid = boxes.filter { !inside(from, it) && !inside(to, it) }
            BlueprintEngine.openRoute(from, to, avoid) to false
        } else BlueprintEngine.bezier(from, to) to true

    private class Built(val shape: GraphShape, val diff: GraphDiff?, val placement: Placement, val layout: BlueprintLayout<String>, val links: Links, val view: Triple<Set<String>, String?, List<String>>, val routing: LayoutOptions, val measurementRevision: Int, val routeRevision: Int)

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
        private const val AlignReach = 6.0
        private const val Corner = 14.0
        private const val Knot = 6.0
        private const val MinNote = 160.0
        private const val GrowGap = 96.0
        private const val Nesting = 8
        private const val BundleReach = 96.0
        private const val EraseReach = 6f
        private const val FarNudge = 5

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

private fun GraphShape.renamed(ids: Map<String, String>): GraphShape = if (ids.isEmpty()) this else copy(
    nodes = nodes.map { node -> ids[node.id]?.let { node.copy(id = it) } ?: node },
    wires = wires.map { wire -> wire.copy(consumer = ids[wire.consumer] ?: wire.consumer, provider = ids[wire.provider] ?: wire.provider) },
    routes = routes.map { route -> route.copy(from = ids[route.from] ?: route.from, to = ids[route.to] ?: route.to) },
)

private fun Placement.renamed(ids: Map<String, String>): Placement =
    if (ids.isEmpty()) this else copy(cards = cards.mapKeys { (id, _) -> ids[id] ?: id }, remarks = remarks.mapKeys { (id, _) -> ids[id] ?: id })

private fun GraphShape.sameGraph(other: GraphShape): Boolean =
    nodes.associate { it.id to it.label } == other.nodes.associate { it.id to it.label } && wires.mapTo(hashSetOf()) { it.id } == other.wires.mapTo(hashSetOf()) { it.id }

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

internal val ViewCommandIds = setOf("fit", "fit-selection", "zoom-in-primary-shift", "zoom-in", "zoom-out", "reset-zoom", "zoom-in-primary", "zoom-out-primary", "reset-zoom-primary", "centre-selection", "next-card", "previous-card", "pan-left", "pan-right", "pan-up", "pan-down", "page-up", "page-down", "page-left", "page-right")

private val UnimplementedCommandIds = setOf("edit-theme", "edit-load", "edit-clear", "edit-export", "edit-copy-styles", "edit-paste-styles", "edit-send-backward", "edit-bring-forward", "edit-copy-png", "edit-grid", "edit-zen", "edit-snap", "edit-stats", "edit-view", "edit-hyperlink", "edit-lock", "edit-send-to-back", "edit-bring-to-front", "tool-image")

private val InspectionCommandIds = setOf("tool-select", "tool-select-key", "tool-hand", "tool-lock", "edit-select-all", "edit-copy", "edit-find", "edit-keys", "edit-escape", "edit-minimap")

internal fun viewerCommands(enterOpens: Boolean): CommandSet = CommandSet(EditorCommands.commands.filter { it.id.value in ViewCommandIds })

internal val EditorCommands = CommandSet(listOf(
    Command(CommandId("edit-theme"), "Toggle theme", Chord.Of(KeyName.D, shift = true, alt = true)),
    Command(CommandId("edit-load"), "Load scene", Chord.Of(KeyName.O, primary = true)),
    Command(CommandId("edit-clear"), "Clear canvas", Chord.Of(KeyName.Delete, primary = true)),
    Command(CommandId("edit-export"), "Export image", Chord.Of(KeyName.E, primary = true, shift = true)),
    Command(CommandId("edit-copy-styles"), "Copy styles", Chord.Of(KeyName.C, primary = true, alt = true)),
    Command(CommandId("edit-paste-styles"), "Paste styles", Chord.Of(KeyName.V, primary = true, alt = true)),
    Command(CommandId("edit-send-backward"), "Send backward", Chord.Of(KeyName.LeftBracket, primary = true)),
    Command(CommandId("edit-bring-forward"), "Bring forward", Chord.Of(KeyName.RightBracket, primary = true)),
    Command(CommandId("edit-copy-png"), "Copy as PNG", Chord.Of(KeyName.C, shift = true, alt = true)),
    Command(CommandId("edit-grid"), "Toggle grid", Chord.Of(KeyName.Apostrophe, primary = true)),
    Command(CommandId("edit-zen"), "Toggle zen mode", Chord.Of(KeyName.Z, alt = true)),
    Command(CommandId("edit-snap"), "Toggle object snapping", Chord.Of(KeyName.S, alt = true)),
    Command(CommandId("edit-stats"), "Toggle statistics", Chord.Of(KeyName.Slash, alt = true)),
    Command(CommandId("edit-view"), "Toggle view mode", Chord.Of(KeyName.R, alt = true)),
    Command(CommandId("edit-hyperlink"), "Hyperlink", Chord.Of(KeyName.K, primary = true)),
    Command(CommandId("edit-lock"), "Toggle element lock", Chord.Of(KeyName.L, primary = true, shift = true)),
    Command(CommandId("edit-send-to-back"), "Send to back", Chord.Platform(Chord.Of(KeyName.LeftBracket, primary = true, alt = true), Chord.Of(KeyName.LeftBracket, primary = true, shift = true))),
    Command(CommandId("edit-bring-to-front"), "Bring to front", Chord.Platform(Chord.Of(KeyName.RightBracket, primary = true, alt = true), Chord.Of(KeyName.RightBracket, primary = true, shift = true))),
    Command(CommandId("zoom-in-primary-shift"), "Zoom in", Chord.Of(KeyName.Equals, primary = true, shift = true)),
    Command(CommandId("fit-selection"), "Fill view with selection", Chord.Of(KeyName.Digit3, shift = true)),
    Command(CommandId("fit"), "Frame everything", Chord.Of(KeyName.Digit1, shift = true)),
    Command(CommandId("zoom-in"), "Zoom in", Chord.Of(KeyName.Equals)),
    Command(CommandId("zoom-out"), "Zoom out", Chord.Of(KeyName.Minus)),
    Command(CommandId("reset-zoom"), "Reset zoom", Chord.Of(KeyName.Digit0, shift = true)),
    Command(CommandId("zoom-in-primary"), "Zoom in", Chord.Of(KeyName.Equals, primary = true)),
    Command(CommandId("zoom-out-primary"), "Zoom out", Chord.Of(KeyName.Minus, primary = true)),
    Command(CommandId("reset-zoom-primary"), "Reset zoom", Chord.Of(KeyName.Digit0, primary = true)),
    Command(CommandId("centre-selection"), "Frame the selection", Chord.Of(KeyName.Digit2, shift = true)),
    Command(CommandId("next-card"), "Next card", Chord.Of(KeyName.RightBracket)),
    Command(CommandId("previous-card"), "Previous card", Chord.Of(KeyName.LeftBracket)),
    Command(CommandId("pan-left"), "Pan left", Chord.Of(KeyName.Left)),
    Command(CommandId("pan-right"), "Pan right", Chord.Of(KeyName.Right)),
    Command(CommandId("pan-up"), "Pan up", Chord.Of(KeyName.Up)),
    Command(CommandId("pan-down"), "Pan down", Chord.Of(KeyName.Down)),
    Command(CommandId("page-up"), "Move a page up", Chord.Of(KeyName.PageUp)),
    Command(CommandId("page-down"), "Move a page down", Chord.Of(KeyName.PageDown)),
    Command(CommandId("page-left"), "Move a page left", Chord.Of(KeyName.PageUp, shift = true)),
    Command(CommandId("page-right"), "Move a page right", Chord.Of(KeyName.PageDown, shift = true)),
    Command(CommandId("tool-select"), "Selection", Chord.Of(KeyName.V)),
    Command(CommandId("tool-select-key"), "Selection", Chord.Of(KeyName.Digit1)),
    Command(CommandId("tool-hand"), "Hand: drag to pan", Chord.Of(KeyName.H)),
    Command(CommandId("tool-box"), "Rectangle: draw a capture", Chord.Of(KeyName.R)),
    Command(CommandId("tool-box-key"), "Rectangle: draw a capture", Chord.Of(KeyName.Digit2)),
    Command(CommandId("tool-diamond"), "Diamond: draw a capture", Chord.Of(KeyName.D)),
    Command(CommandId("tool-diamond-key"), "Diamond: draw a capture", Chord.Of(KeyName.Digit3)),
    Command(CommandId("tool-ellipse"), "Ellipse: draw a capture", Chord.Of(KeyName.O)),
    Command(CommandId("tool-ellipse-key"), "Ellipse: draw a capture", Chord.Of(KeyName.Digit4)),
    Command(CommandId("tool-arrow"), "Arrow: draw a capture", Chord.Of(KeyName.A)),
    Command(CommandId("tool-arrow-key"), "Arrow: draw a capture", Chord.Of(KeyName.Digit5)),
    Command(CommandId("tool-line"), "Line: draw a capture", Chord.Of(KeyName.L)),
    Command(CommandId("tool-line-key"), "Line: draw a capture", Chord.Of(KeyName.Digit6)),
    Command(CommandId("tool-link"), "Link cards", Chord.Of(KeyName.A, shift = true, alt = true)),
    Command(CommandId("tool-relate"), "Relate cards", Chord.Of(KeyName.L, shift = true, alt = true)),
    Command(CommandId("tool-pencil"), "Pencil", Chord.Of(KeyName.P)),
    Command(CommandId("tool-pencil-x"), "Pencil", Chord.Of(KeyName.X)),
    Command(CommandId("tool-pencil-key"), "Pencil", Chord.Of(KeyName.Digit7)),
    Command(CommandId("tool-image"), "Insert image", Chord.Of(KeyName.Digit9)),
    Command(CommandId("tool-text"), "Text: add a comment", Chord.Of(KeyName.T)),
    Command(CommandId("tool-text-key"), "Text: add a comment", Chord.Of(KeyName.Digit8)),
    Command(CommandId("tool-eraser"), "Eraser: click or drag over what to remove", Chord.Of(KeyName.E)),
    Command(CommandId("tool-eraser-key"), "Eraser: click or drag over what to remove", Chord.Of(KeyName.Digit0)),
    Command(CommandId("tool-frame"), "Frame: drag around cards", Chord.Of(KeyName.F)),
    Command(CommandId("tool-lock"), "Keep the tool after use", Chord.Of(KeyName.Q)),
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
    Command(CommandId("edit-group"), "Group into a frame", Chord.Of(KeyName.G, primary = true)),
    Command(CommandId("edit-ungroup"), "Ungroup", Chord.Of(KeyName.G, primary = true, shift = true)),
    Command(CommandId("edit-collapse"), "Collapse or restore the group", Chord.Of(KeyName.G, primary = true, alt = true)),
    Command(CommandId("edit-align-top"), "Align top", Chord.Of(KeyName.Up, primary = true, shift = true)),
    Command(CommandId("edit-align-bottom"), "Align bottom", Chord.Of(KeyName.Down, primary = true, shift = true)),
    Command(CommandId("edit-align-left"), "Align left", Chord.Of(KeyName.Left, primary = true, shift = true)),
    Command(CommandId("edit-align-right"), "Align right", Chord.Of(KeyName.Right, primary = true, shift = true)),
    Command(CommandId("edit-distribute-horizontal"), "Distribute horizontally", Chord.Of(KeyName.H, alt = true)),
    Command(CommandId("edit-distribute-vertical"), "Distribute vertically", Chord.Of(KeyName.V, alt = true)),
    Command(CommandId("edit-flip-horizontal"), "Flip horizontally", Chord.Of(KeyName.H, shift = true)),
    Command(CommandId("edit-flip-vertical"), "Flip vertically", Chord.Of(KeyName.V, shift = true)),
    Command(CommandId("edit-nudge-far-left"), "Move the selection left", Chord.Of(KeyName.Left, shift = true)),
    Command(CommandId("edit-nudge-far-right"), "Move the selection right", Chord.Of(KeyName.Right, shift = true)),
    Command(CommandId("edit-nudge-far-up"), "Move the selection up", Chord.Of(KeyName.Up, shift = true)),
    Command(CommandId("edit-nudge-far-down"), "Move the selection down", Chord.Of(KeyName.Down, shift = true)),
    Command(CommandId("edit-rename-enter"), "Edit the text", Chord.Of(KeyName.Enter)),
    Command(CommandId("edit-escape"), "Cancel", Chord.Of(KeyName.Escape)),
    Command(CommandId("edit-save"), "Save", Chord.Of(KeyName.S, primary = true)),
    Command(CommandId("edit-find"), "Find on the canvas", Chord.Of(KeyName.F, primary = true)),
    Command(CommandId("edit-add-node"), "Add a node", Chord.Of(KeyName.Tab)),
    Command(CommandId("edit-palette"), "Command palette", Chord.Of(KeyName.Slash, primary = true)),
    Command(CommandId("edit-palette-p"), "Command palette", Chord.Of(KeyName.P, primary = true, shift = true)),
    Command(CommandId("edit-keys"), "Keyboard shortcuts", Chord.Of(KeyName.Slash, shift = true)),
    Command(CommandId("edit-rename"), "Rename", Chord.Of(KeyName.F2)),
    Command(CommandId("edit-comment"), "Comment the selection", Chord.Of(KeyName.C)),
    Command(CommandId("edit-straighten"), "Straighten connections", Chord.Of(KeyName.Q, shift = true)),
    Command(CommandId("edit-align-middle"), "Align middle", Chord.Of(KeyName.W, shift = true, alt = true)),
    Command(CommandId("edit-align-centre"), "Align centre", Chord.Of(KeyName.S, shift = true, alt = true)),
    Command(CommandId("edit-tidy"), "Tidy up the selection", Chord.Of(KeyName.T, shift = true, alt = true)),
    Command(CommandId("edit-remark"), "Note on the node", Chord.Of(KeyName.C, alt = true)),
    Command(CommandId("edit-describe"), "Describe a change for an agent", Chord.Of(KeyName.Slash)),
    Command(CommandId("edit-minimap"), "Show or hide the minimap", Chord.Of(KeyName.M)),
    Command(CommandId("edit-walk-left"), "Go to the card before", Chord.Of(KeyName.Left, alt = true)),
    Command(CommandId("edit-walk-right"), "Go to the card after", Chord.Of(KeyName.Right, alt = true)),
    Command(CommandId("edit-walk-up"), "Go to the card above", Chord.Of(KeyName.Up, alt = true)),
    Command(CommandId("edit-walk-down"), "Go to the card below", Chord.Of(KeyName.Down, alt = true)),
    Command(CommandId("edit-grow-right"), "Add a linked card to the right", Chord.Of(KeyName.Right, primary = true)),
    Command(CommandId("edit-grow-left"), "Add a linked card to the left", Chord.Of(KeyName.Left, primary = true)),
    Command(CommandId("edit-grow-down"), "Add a linked card below", Chord.Of(KeyName.Down, primary = true)),
    Command(CommandId("edit-grow-up"), "Add a linked card above", Chord.Of(KeyName.Up, primary = true)),
) + (1..9).flatMap { slot ->
    val key = KeyName.valueOf("Digit$slot")
    listOf(Command(CommandId("edit-mark-$slot"), "Set bookmark $slot", Chord.Of(key, control = true)), Command(CommandId("edit-go-$slot"), "Go to bookmark $slot", Chord.Of(key, alt = true)))
})
