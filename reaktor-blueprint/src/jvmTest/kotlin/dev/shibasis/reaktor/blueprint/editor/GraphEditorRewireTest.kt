package dev.shibasis.reaktor.blueprint.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
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
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphEditorRewireTest {
    private val wire = WireShape("b", "in", "a", "out", "Int", "member-a-b")
    private val other = WireShape("d", "in", "a", "out", "Int", "member-a-d")
    private val shape = GraphShape(listOf(ScopeShape("app", "App")), listOf(
        NodeShape("a", "app", "A", "A", NodeKind.Service, ports = listOf(PortShape("out", "Int", true, PortWiring.Unused))),
        NodeShape("b", "app", "B", "B", NodeKind.Service, ports = listOf(PortShape("in", "Int", false, PortWiring.Unmatched))),
        NodeShape("c", "app", "C", "C", NodeKind.Service, ports = listOf(PortShape("out", "Int", true, PortWiring.Unused))),
        NodeShape("d", "app", "D", "D", NodeKind.Service, ports = listOf(PortShape("in", "Int", false, PortWiring.Unmatched))),
    ), listOf(wire), emptyList()).recounted()
    private val placement = Placement(cards = mapOf("a" to Spot(0.0, 0.0), "b" to Spot(600.0, 0.0), "c" to Spot(0.0, 300.0), "d" to Spot(600.0, 300.0)))
    private val plain = PointerKeyboardModifiers()
    private val alt = PointerKeyboardModifiers(isAltPressed = true)
    private val ctrl = PointerKeyboardModifiers(isCtrlPressed = true)

    private fun editor(shape: GraphShape = this.shape, placement: Placement = this.placement) = GraphEditor(shape, placement).also {
        it.editRevision = EditRevision("source", "placement")
        it.permits = { _, _ -> true }
        it.validate = { Verdict(true, "") }
        it.commit = { _, revision -> EditReceipt.Accepted(revision.copy(source = revision.source + "+")) }
        it.savePlacement = { _, revision -> EditReceipt.Accepted(revision.copy(presentation = revision.presentation + "+")) }
    }

    private fun GraphEditor.snapshot(): List<Any> = listOf(shape, placement, editRevision, history.toList(), canUndo, canRedo, selection)
    private fun GraphEditor.at(pin: PinEnd): Spot = assertNotNull(anchor(pin)).let { Spot(it.first, it.second) }
    private fun GraphEditor.click(at: Spot, keys: PointerKeyboardModifiers = plain, clicks: Int = 1) {
        assertTrue(press(at.x.toFloat(), at.y.toFloat(), Offset.Zero, 5f, keys, clicks, false))
        release(at.x.toFloat(), at.y.toFloat(), Offset.Zero)
    }
    private fun GraphEditor.drag(from: Spot, to: Spot, keys: PointerKeyboardModifiers = plain) {
        assertTrue(press(from.x.toFloat(), from.y.toFloat(), Offset.Zero, 5f, keys, 1, false))
        drag(to.x.toFloat(), to.y.toFloat(), Offset.Zero)
        release(to.x.toFloat(), to.y.toFloat(), Offset.Zero)
    }
    private fun GraphEditor.middle(id: String = wire.id): Spot = layout.links.single { it.id == id }.trace.zipWithNext()
        .map { (a, b) -> hypot(a.first - b.first, a.second - b.second) to Spot((a.first + b.first) / 2, (a.second + b.second) / 2) }
        .filter { (_, at) -> cardAt(at) == null && pinAt(at) == null }.maxBy { it.first }.second

    @Test fun consumerHandleMovesMemberAndAuthoredBendsAsOneUndoableEdit() {
        val bends = listOf(Spot(400.0, 120.0), Spot(450.0, 120.0))
        val editor = editor(placement = placement.copy(bends = mapOf(wire.id to bends)))
        val before = editor.placement
        editor.select(Selection(wires = setOf(wire.id)))
        editor.drag(editor.at(PinEnd("b", "in", false)), editor.at(PinEnd("d", "in", false)))
        val next = wire.copy(consumer = "d")
        assertEquals(listOf(next), editor.shape.wires)
        assertEquals(mapOf(next.id to bends), editor.placement.bends)
        assertEquals(setOf(next.id), editor.selection.wires)
        assertEquals(1, editor.history.size)
        editor.undo()
        assertEquals(shape, editor.shape)
        assertEquals(before, editor.placement)
        assertFalse(editor.canUndo)
        editor.redo()
        assertEquals(listOf(next), editor.shape.wires)
    }

    @Test fun providerHandleMovesOnlyTheSelectedWire() {
        val editor = editor(shape.connect(other))
        editor.select(Selection(wires = setOf(wire.id)))
        editor.drag(editor.at(PinEnd("a", "out", true)), editor.at(PinEnd("c", "out", true)))
        assertEquals(setOf(wire.copy(provider = "c"), other), editor.shape.wires.toSet())
        assertEquals(1, editor.history.size)
        editor.undo()
        assertEquals(setOf(wire, other), editor.shape.wires.toSet())
    }

    @Test fun clickingAHandleDoesNotBreakOrCreateHistory() {
        val editor = editor()
        editor.select(Selection(wires = setOf(wire.id)))
        val before = editor.snapshot()
        editor.click(editor.at(PinEnd("b", "in", false)))
        assertEquals(before, editor.snapshot())
    }

    @Test fun altClickOnLinkBreaksItAndItsBendsInOneEdit() {
        val editor = editor(placement = placement.copy(bends = mapOf(wire.id to listOf(Spot(400.0, 100.0)))))
        val before = editor.placement
        editor.click(editor.middle(), alt)
        assertTrue(editor.shape.wires.isEmpty())
        assertTrue(editor.placement.bends.isEmpty())
        assertEquals(1, editor.history.size)
        editor.undo()
        assertEquals(shape, editor.shape)
        assertEquals(before, editor.placement)
    }

    @Test fun altClickOnPortBreaksAllLinksWithOneUndo() {
        val connected = shape.connect(other)
        val editor = editor(connected)
        editor.click(editor.at(PinEnd("a", "out", true)), alt)
        assertTrue(editor.shape.wires.isEmpty())
        assertEquals(1, editor.history.size)
        editor.undo()
        assertEquals(connected, editor.shape)
    }

    @Test fun controlDragMovesEveryLinkFromThePortInOneEdit() {
        val connected = shape.connect(other)
        val editor = editor(connected)
        editor.drag(editor.at(PinEnd("a", "out", true)), editor.at(PinEnd("c", "out", true)), ctrl)
        assertEquals(setOf(wire.copy(provider = "c"), other.copy(provider = "c")), editor.shape.wires.toSet())
        assertEquals(1, editor.history.size)
        editor.undo()
        assertEquals(connected, editor.shape)
    }

    @Test fun doubleClickAddsThenDragsThenDeletesARerouteWithOneUndoEach() {
        val editor = editor()
        editor.click(editor.middle(), clicks = 2)
        val added = editor.placement
        val knot = added.bends.getValue(wire.id).single()
        assertEquals(1, editor.history.size)
        editor.drag(knot, Spot(knot.x + 40, knot.y + 80))
        val moved = editor.placement.bends.getValue(wire.id).single()
        assertTrue(moved != knot)
        assertEquals(2, editor.history.size)
        editor.click(moved, clicks = 2)
        assertTrue(editor.placement.bends.isEmpty())
        assertEquals(3, editor.history.size)
        editor.undo()
        assertEquals(moved, editor.placement.bends.getValue(wire.id).single())
        editor.undo()
        assertEquals(added, editor.placement)
        editor.undo()
        assertEquals(placement, editor.placement)
        assertEquals(shape, editor.shape)
    }

    @Test fun invalidEndpointAndSynchronousRefusalPreserveTheCompleteDraft() {
        val editor = editor()
        editor.select(Selection(wires = setOf(wire.id)))
        val before = editor.snapshot()
        editor.drag(editor.at(PinEnd("b", "in", false)), editor.at(PinEnd("c", "out", true)))
        assertEquals(before, editor.snapshot())
        editor.commit = { _, _ -> EditReceipt.Refused("Host refused") }
        editor.drag(editor.at(PinEnd("b", "in", false)), editor.at(PinEnd("d", "in", false)))
        assertEquals(before, editor.snapshot())
        editor.click(editor.middle(), alt)
        assertEquals(before, editor.snapshot())
        editor.savePlacement = { _, _ -> EditReceipt.Refused("Placement refused") }
        editor.click(editor.middle(), clicks = 2)
        assertEquals(before, editor.snapshot())
    }

    @Test fun asynchronousRefusalAndSecondGestureCannotPublishAnIntermediateDisconnect() = runBlocking {
        val editor = editor()
        editor.rename("c", "Before")
        editor.undo()
        editor.select(Selection(wires = setOf(wire.id)))
        val before = editor.snapshot()
        val reply = CompletableDeferred<EditReceipt>()
        editor.editScope = this
        editor.commit = { _, _ -> reply.await() }
        editor.drag(editor.at(PinEnd("b", "in", false)), editor.at(PinEnd("d", "in", false)))
        assertNotNull(editor.pending)
        assertEquals(before, editor.snapshot())
        val at = editor.middle()
        assertFalse(editor.press(at.x.toFloat(), at.y.toFloat(), Offset.Zero, 5f, alt, 1, false))
        editor.release(at.x.toFloat(), at.y.toFloat(), Offset.Zero)
        assertEquals(before, editor.snapshot())
        reply.complete(EditReceipt.Refused("Host refused"))
        yield()
        assertNull(editor.pending)
        assertEquals(before, editor.snapshot())
        editor.commit = { _, revision -> EditReceipt.Accepted(revision.copy(source = revision.source + "+")) }
        editor.redo()
        yield()
        assertEquals("Before", editor.shape.node("c")?.label)
    }

    @Test fun occupiedInputReplacementRestoresBothOriginalMembersOnUndo() {
        val occupied = other.copy(provider = "c")
        val connected = shape.connect(occupied)
        val editor = editor(connected)
        editor.select(Selection(wires = setOf(wire.id)))
        editor.drag(editor.at(PinEnd("b", "in", false)), editor.at(PinEnd("d", "in", false)))
        assertEquals(listOf(wire.copy(consumer = "d")), editor.shape.wires)
        assertEquals(1, editor.history.size)
        editor.undo()
        assertEquals(connected, editor.shape)
    }
}
