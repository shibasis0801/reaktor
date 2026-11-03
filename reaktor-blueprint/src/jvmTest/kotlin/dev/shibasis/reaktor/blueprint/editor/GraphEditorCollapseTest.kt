package dev.shibasis.reaktor.blueprint.editor

import dev.shibasis.reaktor.blueprint.capture.Capture
import dev.shibasis.reaktor.blueprint.capture.CaptureAnchor
import dev.shibasis.reaktor.blueprint.capture.CaptureAsset
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.graph.core.WireShape
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphEditorCollapseTest {
    private fun node(id: String, scope: String) = NodeShape(id, scope, id, "Task", NodeKind.Service,
        ports = listOf(PortShape("out", "Int", true, PortWiring.Connected),
            PortShape("in", "Int", false, PortWiring.Connected), PortShape("other", "Int", false, PortWiring.Connected)))
    private val shape = GraphShape(listOf(ScopeShape("root", "Root"), ScopeShape("left", "Left", "root"),
        ScopeShape("right", "Right", "root")), listOf(node("a", "left"), node("b", "root"), node("c", "right"), node("z", "root")),
        listOf(WireShape("c", "in", "a", "out", "Int", "internal"),
            WireShape("z", "in", "a", "out", "Int", "first"), WireShape("z", "other", "c", "out", "Int", "second")), emptyList()).let { GraphEditor(it, Placement()).shape }
    private val placement = Placement(cards = mapOf("a" to Spot(0.0, 0.0), "b" to Spot(250.0, 0.0),
        "c" to Spot(500.0, 0.0), "z" to Spot(1200.0, 0.0)),
        notes = listOf(Note("context", "Context", -900.0, -900.0, 100.0, 100.0)),
        bends = mapOf("authored" to listOf(Spot(6.0, 7.0))), remarks = mapOf("a" to "Retain"),
        capture = listOf(Capture.Text("text", "Retain", CaptureAnchor.World(Spot(30.0, 40.0)))),
        assets = mapOf("asset" to CaptureAsset("asset", "document/assets/asset", "image/png")), folded = setOf("context"))
    private fun editor(source: GraphShape = shape, placed: Placement = placement) = GraphEditor(source, placed).also {
        it.editRevision = EditRevision("source:1", "presentation:1")
        it.permits = { _, _ -> true }
        it.validate = { Verdict(true, "") }
        it.commit = { _, revision -> EditReceipt.Accepted(revision.copy(source = revision.source + "+")) }
        it.savePlacement = { _, revision -> EditReceipt.Accepted(revision.copy(presentation = revision.presentation + "+")) }
    }
    private fun GraphEditor.collapse(): String {
        select(Selection(nodes = setOf("a", "c")))
        group("Pair")
        return placement.collapsed.keys.single()
    }
    private data class Snapshot(val shape: GraphShape, val placement: Placement, val selection: Selection,
        val revision: EditRevision, val history: List<String>, val future: List<String>, val folds: Set<String>)
    private fun GraphEditor.snapshot() = Snapshot(shape, placement, selection, editRevision, history, future, folded)

    @Test fun membershipUsesSelectionAcrossParentsAndLeavesOverlappingCardsAlone() {
        val editor = editor()
        val group = editor.collapse()
        assertEquals("root", editor.shape.scopes.single { it.id == group }.parent)
        assertEquals(mapOf("a" to Placement.Origin("left", 0), "c" to Placement.Origin("right", 2)), editor.placement.collapsed[group])
        assertEquals(setOf("b", "z", group), editor.layout.cards.keys)
        assertEquals(group, editor.layout.cardOf["a"])
        assertEquals("b", editor.layout.cardOf["b"])
        assertEquals(group, editor.layout.cardOf["c"])
        assertEquals(setOf(group), editor.selection.notes)
        assertEquals(1, editor.history.size)
    }

    @Test fun crossingLinksRetainWireAndDefinitionIdentityThroughBoundaryPins() {
        val editor = editor()
        val group = editor.collapse()
        assertEquals(shape.wires, editor.shape.wires)
        val crossing = shape.wires.filter { it.consumer == "z" }
        assertEquals(crossing.map { it.id }.toSet(), editor.layout.links.flatMap { it.members }.toSet())
        for (wire in crossing) {
            val link = editor.layout.links.single { wire.id in it.members }
            assertEquals(group, link.from)
            assertEquals("z", link.to)
            assertNotNull(link.fromPort)
            assertEquals(wire.definitionId, editor.shape.wires.single { it.id == wire.id }.definitionId)
        }
        assertEquals(2, editor.layout.cards.getValue(group).pins.size)
    }

    @Test fun expandRestoresAllShapeAndPlacementAndEachOperationUndoesInOneStep() {
        val editor = editor()
        editor.collapse()
        val collapsedShape = editor.shape
        val collapsedPlacement = editor.placement
        editor.ungroup()
        assertEquals(shape, editor.shape)
        assertEquals(placement, editor.placement)
        assertEquals(2, editor.history.size)
        editor.undo()
        assertEquals(collapsedShape, editor.shape)
        assertEquals(collapsedPlacement, editor.placement)
        assertEquals(1, editor.history.size)
        assertEquals(1, editor.future.size)
        assertEquals(setOf("b", "z") + collapsedPlacement.collapsed.keys, editor.layout.cards.keys)
        editor.undo()
        assertEquals(shape, editor.shape)
        assertEquals(placement, editor.placement)
        assertFalse(editor.canUndo)
        editor.redo()
        assertEquals(collapsedShape, editor.shape)
        assertEquals(collapsedPlacement, editor.placement)
        editor.redo()
        assertEquals(shape, editor.shape)
        assertEquals(placement, editor.placement)
        assertFalse(editor.canRedo)
    }

    @Test fun synchronousRefusalPreservesSelectionPlacementRevisionAndBothHistoryStacks() {
        val editor = editor()
        editor.collapse()
        editor.undo()
        editor.select(Selection(nodes = setOf("a", "c")))
        val before = editor.snapshot()
        editor.commit = { _, _ -> EditReceipt.Refused("Moved", true) }
        editor.group("Refused")
        assertEquals(before, editor.snapshot())
        assertNull(editor.pending)
        editor.commit = { _, revision -> EditReceipt.Accepted(revision) }
        editor.group("Accepted")
        val grouped = editor.snapshot()
        editor.commit = { _, _ -> EditReceipt.Refused("Later", true) }
        editor.ungroup()
        assertEquals(grouped, editor.snapshot())
    }

    @Test fun asynchronousRefusalAndSecondEditPreserveTheCompleteState() = runBlocking {
        val editor = editor()
        editor.editScope = this
        editor.select(Selection(nodes = setOf("a", "c")))
        val before = editor.snapshot()
        val answer = CompletableDeferred<EditReceipt>()
        var calls = 0
        editor.commit = { _, _ -> calls++; answer.await() }
        editor.group("Pending")
        assertNotNull(editor.pending)
        assertEquals(before, editor.snapshot())
        editor.group("Second")
        editor.ungroup()
        editor.undo()
        editor.redo()
        assertEquals(1, calls)
        assertEquals(before, editor.snapshot())
        answer.complete(EditReceipt.Refused("Refused asynchronously"))
        yield()
        assertNull(editor.pending)
        assertEquals(before, editor.snapshot())
    }

    @Test fun foldAndUnfoldUseTheReceiptAndUndoAsOnePlacementEdit() {
        val editor = editor()
        val group = editor.collapse()
        val grouped = editor.snapshot()
        editor.savePlacement = { _, _ -> EditReceipt.Refused("Presentation moved", true) }
        editor.unfold(group)
        assertEquals(grouped, editor.snapshot())
        editor.savePlacement = { _, revision -> EditReceipt.Accepted(revision.copy(presentation = revision.presentation + "+")) }
        editor.unfold(group)
        assertEquals(2, editor.history.size)
        assertFalse(group in editor.folded)
        assertFalse(group in editor.placement.folded)
        assertEquals(setOf("a", "c"), editor.layout.frames.single { it.key == group }.nodes)
        editor.undo()
        assertTrue(group in editor.folded)
        assertEquals(grouped.placement, editor.placement)
        editor.redo()
        assertFalse(group in editor.folded)
        editor.fold(group)
        assertTrue(group in editor.folded)
        assertEquals(3, editor.history.size)
    }

    @Test fun savedCollapsedGraphReopensAndExpandsToTheExactOriginalDocument() {
        val original = editor()
        val group = original.collapse()
        val source = Json.decodeFromString(GraphShape.serializer(), Json.encodeToString(GraphShape.serializer(), original.shape))
        val placed = Json.decodeFromString(Placement.serializer(), Json.encodeToString(Placement.serializer(), original.placement))
        val reopened = editor(source, placed)
        reopened.fold(group)
        assertEquals(setOf("b", "z", group), reopened.layout.cards.keys)
        reopened.select(Selection(notes = setOf(group)))
        reopened.ungroup()
        assertEquals(shape, reopened.shape)
        assertEquals(placement, reopened.placement)
    }
}
