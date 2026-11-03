package dev.shibasis.reaktor.blueprint.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.unit.IntSize
import dev.shibasis.reaktor.blueprint.Bounds
import dev.shibasis.reaktor.blueprint.canvas.Area
import dev.shibasis.reaktor.blueprint.canvas.CanvasCamera
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.blueprint.capture.Capture
import dev.shibasis.reaktor.blueprint.capture.CaptureAnchor
import dev.shibasis.reaktor.blueprint.capture.CaptureAsset
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.surface.CommandSet
import dev.shibasis.reaktor.surface.KeyConvention
import dev.shibasis.reaktor.surface.KeyName
import dev.shibasis.reaktor.surface.KeyStroke
import dev.shibasis.reaktor.surface.find
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphEditorValidationTest {
    private val source = GraphShape(
        listOf(ScopeShape("app", "App")),
        listOf(
            NodeShape("a", "app", "A", "A", NodeKind.Service, ports = listOf(PortShape("out", "Int", true, PortWiring.Unused))),
            NodeShape("b", "app", "B", "B", NodeKind.Service, ports = listOf(PortShape("in", "Int", false, PortWiring.Unmatched))),
            NodeShape("c", "app", "C", "C", NodeKind.Service, ports = listOf(PortShape("in", "String", false, PortWiring.Unmatched))),
        ),
        emptyList(),
        emptyList(),
    )
    private val placement = Placement(
        cards = mapOf("a" to Spot(0.0, 0.0), "b" to Spot(400.0, 0.0), "c" to Spot(800.0, 0.0)),
        notes = listOf(Note("note-old", "Context", -300.0, -300.0, 120.0, 80.0)),
        bends = mapOf("unused-wire" to listOf(Spot(50.0, 60.0))),
        marks = mapOf(1 to Mark(1.0, 2.0, 900.0, 600.0)),
        remarks = mapOf("a" to "Question"),
        capture = listOf(Capture.Text("text", "Keep", CaptureAnchor.World(Spot(30.0, 40.0)))),
        assets = mapOf("image" to CaptureAsset("image", "document/assets/image", "image/png")),
        folded = setOf("note-old"),
    )
    private fun editor(graph: GraphShape = source): GraphEditor = GraphEditor(graph, placement).also { editor ->
        editor.editRevision = EditRevision("source:00000000000000000001", "presentation:00000000000000000001")
        editor.permits = { _, _ -> true }
        editor.validate = { Verdict(true, "") }
        editor.commit = { _, expected -> EditReceipt.Accepted(expected.copy(source = expected.source + "+")) }
        editor.savePlacement = { _, expected -> EditReceipt.Accepted(expected.copy(presentation = expected.presentation + "+")) }
    }

    private val output = PinEnd("a", "out", true)
    private val input = PinEnd("b", "in", false)
    private val incompatible = PinEnd("c", "in", false)

    @Test
    fun rejectedPortConnectionsPreserveHistoryAndPendingRedo() {
        val editor = editor()
        assertTrue(editor.connect(output, input).ok)
        editor.undo()
        val history = editor.history.toList()
        var commits = 0
        var placements = 0
        editor.onCommit = { _, _ -> commits++ }
        editor.onPlace = { placements++ }

        assertFalse(editor.connect(output, incompatible).ok)

        assertEquals(source, editor.shape)
        assertEquals(placement, editor.placement)
        assertEquals(history, editor.history)
        assertFalse(editor.canUndo)
        assertTrue(editor.canRedo)
        assertEquals(0, commits)
        assertEquals(0, placements)
        editor.redo()
        assertEquals(1, editor.shape.wires.size)
    }

    @Test
    fun repeatedTypedPortPreviewsDoNotPublishOrConsumeRedo() {
        val editor = editor()
        editor.connect(output, input)
        editor.undo()
        var commits = 0
        editor.onCommit = { _, _ -> commits++ }

        repeat(5) {
            assertTrue(editor.verdict(output, input).ok)
            assertFalse(editor.verdict(output, incompatible).ok)
        }

        assertEquals(source, editor.shape)
        assertEquals(placement, editor.placement)
        assertTrue(editor.canRedo)
        assertFalse(editor.canUndo)
        assertEquals(0, commits)
        assertNull(editor.notice)
    }

    @Test
    fun aNoOpEditPreservesRedoAndDoesNotNotifyTheHost() {
        val editor = editor()
        editor.connect(output, input)
        editor.undo()
        var commits = 0
        var placements = 0
        editor.onCommit = { _, _ -> commits++ }
        editor.onPlace = { placements++ }

        editor.edit { shape, spots -> shape to spots }

        assertTrue(editor.canRedo)
        assertFalse(editor.canUndo)
        assertEquals(source, editor.shape)
        assertEquals(placement, editor.placement)
        assertEquals(0, commits)
        assertEquals(0, placements)
    }

    @Test
    fun lockedKeyboardEditsLeaveRedoAndPresentationIntactWhileSelectionWorks() {
        KeyConvention.entries.forEach { convention ->
            val editor = editor()
            editor.connect(output, input)
            editor.undo()
            editor.others = listOf(Holder("Peer", 1L, editing = true))
            assertTrue(editor.locked)
            var commits = 0
            editor.onCommit = { _, _ -> commits++ }

            editor.dispatch(KeyStroke(KeyName.A, meta = convention == KeyConvention.Mac, control = convention == KeyConvention.Pc), convention)
            assertEquals(setOf("a", "b", "c"), editor.selection.nodes)
            editor.dispatch(KeyStroke(KeyName.Delete), convention)
            editor.dispatch(KeyStroke(KeyName.Z, meta = convention == KeyConvention.Mac, control = convention == KeyConvention.Pc, shift = true), convention)

            assertEquals(source, editor.shape, convention.name)
            assertEquals(placement, editor.placement, convention.name)
            assertTrue(editor.canRedo, convention.name)
            assertFalse(editor.canUndo, convention.name)
            assertEquals(0, commits, convention.name)
        }
    }

    @Test
    fun keyboardDeleteUndoAndRedoNotifyTheHostOncePerChange() {
        KeyConvention.entries.forEach { convention ->
            val editor = editor()
            val commits = mutableListOf<Pair<GraphShape, GraphShape>>()
            editor.commit = { draft, expected ->
                commits += editor.shape to draft.shape
                EditReceipt.Accepted(expected.copy(source = expected.source + "+"))
            }
            editor.select(Selection(setOf("a")))

            editor.dispatch(KeyStroke(KeyName.Delete), convention)
            val deleted = editor.shape
            assertNull(deleted.node("a"))
            assertEquals(listOf(source to deleted), commits)
            editor.dispatch(KeyStroke(KeyName.Z, meta = convention == KeyConvention.Mac, control = convention == KeyConvention.Pc), convention)
            assertEquals(source, editor.shape)
            assertEquals(placement, editor.placement)
            editor.dispatch(KeyStroke(KeyName.Z, meta = convention == KeyConvention.Mac, control = convention == KeyConvention.Pc, shift = true), convention)
            assertEquals(deleted, editor.shape)
            assertEquals(listOf(source to deleted, deleted to source, source to deleted), commits)
        }
    }

    @Test
    fun cardAndWireSelectionLeaveTheAttachedCameraAndPlacementAlone() {
        val linked = editor().also { it.connect(output, input) }.shape
        listOf(900, 1100, 1512, 1920).forEach { width ->
            val editor = editor(linked)
            val canvas = GraphCanvasState().also { it.resize(IntSize(width, 900)); it.restore(30.0, -40.0, 0.75) }
            val before = canvas.viewport()
            val moves = mutableListOf<String>()
            editor.attach(object : CanvasCamera {
                override val seen: Area? get() = canvas.seen()
                override val zoom: Double get() = canvas.zoom
                override fun look(x: Double, y: Double) { moves += "look" }
                override fun reveal(box: Bounds) { moves += "reveal" }
                override fun frame(box: Bounds) { moves += "frame" }
                override fun world(window: Offset): Offset = window
            })

            editor.select(Selection(setOf("a")))
            editor.select(Selection(wires = setOf(linked.wires.single().id)))
            editor.selectAll()
            editor.select(Selection())

            assertEquals(emptyList(), moves, width.toString())
            assertEquals(before, canvas.viewport(), width.toString())
            assertEquals(placement, editor.placement)
            assertEquals(linked, editor.shape)
            assertNull(editor.aim)
            assertFalse(editor.canUndo)
            editor.attach(null)
        }
    }

    @Test
    fun explicitZoomAndBackRestoreCameraWithoutChangingGraphSelectionOrFolds() {
        val editor = editor()
        editor.select(Selection(setOf("b")))
        val canvas = GraphCanvasState().also { it.resize(IntSize(1512, 900)); it.restore(30.0, -40.0, 0.75) }
        val before = canvas.viewport()
        val folded = editor.folded.toSet()

        canvas.zoomBy(1.25)
        assertNotEquals(before, canvas.viewport())
        canvas.back()

        assertEquals(before, canvas.viewport())
        assertEquals(Selection(setOf("b")), editor.selection)
        assertEquals(folded, editor.folded)
        assertEquals(source, editor.shape)
        assertEquals(placement, editor.placement)
        assertFalse(editor.canUndo)
    }

    @Test
    fun staleHostRefusalPreservesTheEntireEditState() = runBlocking {
        val editor = editor()
        editor.connect(output, input)
        editor.undo()
        val before = editor.snapshot()
        var validations = 0
        var writes = 0
        editor.validate = { validations++; Verdict(true, "") }
        editor.commit = { draft, expected ->
            writes++
            assertEquals(before.editRevision, expected)
            assertNotEquals(before.shape, draft.shape)
            assertNotEquals(before.placement, draft.placement)
            EditReceipt.Refused("Source moved", stale = true)
        }
        editor.savePlacement = { _, _ -> error("A source batch must not save presentation separately") }
        assertEquals(EditReceipt.Refused("Source moved", true), editor.propose(editor.changed()))
        assertEquals(before, editor.snapshot())
        assertEquals(1, validations)
        assertEquals(1, writes)
        assertNull(editor.pending)
    }

    @Test
    fun asynchronousRefusalDoesNotPublishTheCandidate() = runBlocking {
        val editor = editor()
        val before = editor.snapshot()
        val answer = CompletableDeferred<EditReceipt>()
        var writes = 0
        editor.commit = { _, _ -> writes++; answer.await() }
        val proposal = editor.changed()
        val result = async(start = CoroutineStart.UNDISPATCHED) { editor.propose(proposal) }
        assertEquals(PendingEdit(proposal, before.editRevision), editor.pending)
        assertEquals(before, editor.snapshot())
        answer.complete(EditReceipt.Refused("Later refusal"))
        assertEquals(EditReceipt.Refused("Later refusal"), result.await())
        assertEquals(before, editor.snapshot())
        assertEquals(1, writes)
        assertNull(editor.pending)
    }

    @Test
    fun pendingBlocksASecondProposalEditUndoAndRedoButAllowsSelection() = runBlocking {
        val editor = editor()
        editor.connect(output, input)
        editor.edit { shape, spots -> shape to spots.copy(cards = spots.cards + ("b" to Spot(450.0, 20.0))) }
        editor.undo()
        val before = editor.snapshot()
        val answer = CompletableDeferred<EditReceipt>()
        var writes = 0
        var validations = 0
        editor.validate = { validations++; Verdict(true, "") }
        editor.commit = { _, _ -> writes++; answer.await() }
        editor.savePlacement = { _, _ -> writes++; answer.await() }
        val result = async(start = CoroutineStart.UNDISPATCHED) { editor.propose(editor.changed()) }
        assertTrue(editor.propose(editor.changed()) is EditReceipt.Refused)
        editor.edit { shape, spots -> shape to spots.copy(cards = emptyMap()) }
        editor.undo()
        editor.redo()
        editor.select(Selection(setOf("c")))
        assertEquals(Selection(setOf("c")), editor.selection)
        assertEquals(before, editor.snapshot())
        assertEquals(1, validations)
        assertEquals(1, writes)
        answer.complete(EditReceipt.Refused("No"))
        result.await()
        assertEquals(before, editor.snapshot())
        assertNull(editor.pending)
    }

    @Test
    fun refusedUndoPreservesBothHistoryStacks() {
        val editor = editor()
        editor.connect(output, input)
        editor.edit { shape, spots -> shape to spots.copy(cards = spots.cards + ("b" to Spot(450.0, 20.0))) }
        editor.undo()
        val before = editor.snapshot()
        var writes = 0
        editor.commit = { _, _ -> writes++; EditReceipt.Refused("Cannot undo") }
        editor.undo()
        assertEquals(before, editor.snapshot())
        assertEquals(1, writes)
        assertNull(editor.pending)
    }

    @Test
    fun refusedRedoPreservesBothHistoryStacks() {
        val editor = editor()
        editor.connect(output, input)
        editor.edit { shape, spots -> shape to spots.copy(cards = spots.cards + ("b" to Spot(450.0, 20.0))) }
        editor.undo()
        val before = editor.snapshot()
        var writes = 0
        editor.savePlacement = { _, _ -> writes++; EditReceipt.Refused("Cannot redo") }
        editor.redo()
        assertEquals(before, editor.snapshot())
        assertEquals(1, writes)
        assertNull(editor.pending)
    }

    @Test
    fun acceptedMixedBatchPublishesOnceAndUndoesAsOneStep() = runBlocking {
        val editor = editor()
        val before = editor.snapshot()
        var writes = 0
        var validations = 0
        val receipt = EditRevision("18446744073709551616", "opaque:☕/v2")
        editor.validate = { validations++; Verdict(true, "") }
        editor.commit = { draft, expected ->
            writes++
            assertEquals(before.editRevision, expected)
            assertEquals(before, editor.snapshot())
            assertNotEquals(before.shape, draft.shape)
            assertNotEquals(before.placement, draft.placement)
            EditReceipt.Accepted(receipt)
        }
        editor.savePlacement = { _, _ -> error("Unexpected second host write") }
        editor.onCommit = { _, _ -> error("Legacy source writer must not run") }
        editor.onPlace = { error("Legacy presentation writer must not run") }
        assertEquals(EditReceipt.Accepted(receipt), editor.propose(editor.changed()))
        assertEquals(receipt, editor.editRevision)
        assertEquals(1, editor.history.size)
        assertEquals(1, validations)
        assertEquals(1, writes)
        editor.commit = { _, expected -> EditReceipt.Accepted(expected) }
        editor.undo()
        assertEquals(before.shape, editor.shape)
        assertEquals(before.placement, editor.placement)
        assertFalse(editor.canUndo)
        assertTrue(editor.canRedo)
    }

    @Test
    fun capabilityAndValidationRefusalsNeverCallPersistence() = runBlocking {
        val editor = editor()
        val before = editor.snapshot()
        var validations = 0
        editor.commit = { _, _ -> error("Refusal must not write") }
        editor.savePlacement = { _, _ -> error("Refusal must not write") }
        editor.validate = { validations++; Verdict(false, "Domain policy") }
        editor.permits = { _, _ -> false }
        assertTrue(editor.propose(editor.changed()) is EditReceipt.Refused)
        assertEquals(0, validations)
        editor.permits = { _, _ -> true }
        assertEquals(EditReceipt.Refused("Domain policy"), editor.propose(editor.changed()))
        assertEquals(1, validations)
        assertEquals(before, editor.snapshot())
        assertNull(editor.pending)
    }

    @Test
    fun exceptionsAndCancellationClearPendingWithoutPublishing() = runBlocking {
        val editor = editor()
        val before = editor.snapshot()
        editor.commit = { _, _ -> error("Host failed") }
        assertTrue(editor.propose(editor.changed()) is EditReceipt.Refused)
        assertEquals(before, editor.snapshot())
        assertNull(editor.pending)
        val answer = CompletableDeferred<EditReceipt>()
        editor.commit = { _, _ -> answer.await() }
        val result = async(start = CoroutineStart.UNDISPATCHED) { editor.propose(editor.changed()) }
        assertNotNull(editor.pending)
        result.cancel()
        result.join()
        assertEquals(before, editor.snapshot())
        assertNull(editor.pending)
    }

    @Test
    fun hostRefreshAndRevisionWaitForTheReceipt() = runBlocking {
        val editor = editor()
        val before = editor.snapshot()
        val answer = CompletableDeferred<EditReceipt>()
        editor.commit = { _, _ -> answer.await() }
        val result = async(start = CoroutineStart.UNDISPATCHED) { editor.propose(editor.changed()) }
        val refreshed = source.copy(nodes = source.nodes.map { if (it.id == "c") it.copy(label = "Host") else it })
        val revision = EditRevision("host:new", "host:presentation")
        editor.rebase(refreshed)
        editor.editRevision = revision
        assertEquals(before, editor.snapshot())
        answer.complete(EditReceipt.Refused("Stale", true))
        result.await()
        assertEquals(refreshed, editor.shape)
        assertEquals(revision, editor.editRevision)
        assertNull(editor.pending)
    }

    @Test
    fun captureOnlyRefusalPreservesCompletePlacementAndBothStacks() = runBlocking {
        val editor = editor()
        editor.connect(output, input)
        editor.undo()
        val before = editor.snapshot()
        val candidate = editor.placement.copy(
            capture = listOf(Capture.Text("replacement", "Changed", CaptureAnchor.World(Spot(70.0, 80.0)))),
            assets = mapOf("replacement" to CaptureAsset("replacement", "document/assets/replacement", "image/png")),
            folded = setOf("replacement"),
        )
        val part = GraphPart(changes = emptyList(), layout = candidate.since(editor.placement))
        val answer = CompletableDeferred<EditReceipt>()
        var writes = 0
        var validations = 0
        editor.validate = { validations++; Verdict(true, "") }
        editor.commit = { _, _ -> error("Capture-only edits must not write source") }
        editor.savePlacement = { value, expected ->
            writes++
            assertEquals(candidate, value)
            assertEquals(before.editRevision, expected)
            answer.await()
        }
        val result = async(start = CoroutineStart.UNDISPATCHED) { editor.propose(part) }
        assertNotNull(editor.pending)
        assertEquals(before, editor.snapshot())
        answer.complete(EditReceipt.Refused("Capture document moved", true))
        assertEquals(EditReceipt.Refused("Capture document moved", true), result.await())
        assertEquals(before, editor.snapshot())
        assertEquals(1, validations)
        assertEquals(1, writes)
        assertNull(editor.pending)
    }

    @Test
    fun anExistingDragCannotPublishWhileAProposalIsPending() = runBlocking {
        val editor = editor()
        val before = editor.snapshot()
        val card = editor.layout.cards.getValue("a")
        val x = (card.x + card.width / 2).toFloat()
        val y = (card.y + 4).toFloat()
        editor.press(x, y, Offset(x, y), 8f, PointerKeyboardModifiers(), 1, false)
        editor.drag(x + 50f, y + 50f, Offset(x + 50f, y + 50f))
        assertNotEquals(before.placement, editor.placement)
        val answer = CompletableDeferred<EditReceipt>()
        editor.commit = { _, _ -> answer.await() }
        val result = async(start = CoroutineStart.UNDISPATCHED) { editor.propose(editor.changed()) }
        assertNotNull(editor.pending)
        assertNull(editor.gesture)
        assertEquals(before, editor.snapshot())
        editor.drag(x + 100f, y + 100f, Offset.Zero)
        editor.release(x + 100f, y + 100f, Offset.Zero)
        assertEquals(before, editor.snapshot())
        answer.complete(EditReceipt.Refused("No"))
        result.await()
        assertEquals(before, editor.snapshot())
    }

    private fun GraphEditor.changed(): GraphPart {
        val next = shape.copy(nodes = shape.nodes.map { if (it.id == "a") it.copy(label = "Renamed") else it })
        return GraphPart(changes = shape.changesTo(next), layout = Placement(cards = mapOf("a" to Spot(30.0, 40.0))))
    }

    private data class Snapshot(
        val shape: GraphShape,
        val placement: Placement,
        val revision: Int,
        val editRevision: EditRevision,
        val undo: List<*>,
        val redo: List<*>,
        val title: String,
        val id: String,
    )

    private fun GraphEditor.snapshot(): Snapshot {
        fun stack(name: String): List<*> = (javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(this) as List<*>).toList()
        return Snapshot(shape, placement, revision, editRevision, stack("undos"), stack("redos"), title, id)
    }

    private fun GraphEditor.dispatch(stroke: KeyStroke, convention: KeyConvention) {
        commands.find(stroke, convention)?.let { invoke(it.id) }
    }
}
