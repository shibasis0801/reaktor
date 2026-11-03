package dev.shibasis.reaktor.blueprint.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.ScopeShape
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphEditorWiringTest {
    private val shape = GraphShape(listOf(ScopeShape("app", "App")), listOf(
        NodeShape("a", "app", "A", "A", NodeKind.Service, ports = listOf(PortShape("out", "Int", true, PortWiring.Unused))),
        NodeShape("b", "app", "B", "B", NodeKind.Service, ports = listOf(PortShape("in", "Int", false, PortWiring.Unmatched))),
        NodeShape("c", "app", "C", "C", NodeKind.Service, ports = listOf(PortShape("out", "Int", true, PortWiring.Unused))),
    ), emptyList(), emptyList())
    private val placement = Placement(mapOf("a" to Spot(0.0, 0.0), "b" to Spot(400.0, 0.0), "c" to Spot(0.0, 400.0)))
    private val sink = Template("Sink", NodeKind.Service, "Sink", listOf(PortShape("in", "Int", false, PortWiring.Unmatched)))

    private fun editor(catalogue: ((GraphShape) -> List<Template>)? = null) = GraphEditor(shape, placement, catalogue = catalogue).also {
        it.editRevision = EditRevision("source", "placement")
        it.permits = { _, _ -> true }
        it.validate = { Verdict(true, "") }
        it.commit = { _, expected -> EditReceipt.Accepted(expected.copy(source = expected.source + "+")) }
        it.savePlacement = { _, expected -> EditReceipt.Accepted(expected.copy(presentation = expected.presentation + "+")) }
    }

    private fun GraphEditor.snapshot(): List<Any> = listOf(shape, placement, editRevision, history.toList(), canUndo, canRedo, selection)

    private fun GraphEditor.drop(pin: PinEnd) {
        val (x, y) = anchor(pin)!!
        assertTrue(press(x.toFloat(), y.toFloat(), Offset.Zero, 4f, PointerKeyboardModifiers(), 1, false))
        drag(900f, 900f, Offset.Zero)
        release(900f, 900f, Offset.Zero)
    }

    private fun GraphEditor.drop(from: String, x: Double, y: Double) {
        tool = EditorTool.Arrow
        val card = layout.cards.getValue(from)
        press((card.x + 20).toFloat(), (card.y + 20).toFloat(), Offset.Zero, 4f, PointerKeyboardModifiers(), 1, false)
        drag(x.toFloat(), y.toFloat(), Offset.Zero)
        release(x.toFloat(), y.toFloat(), Offset.Zero)
    }

    @Test fun `dropping a link in empty space searches the cards it can reach`() {
        val editor = editor()
        editor.drop("a", 900.0, 900.0)
        val request = editor.request as Request.Palette
        assertEquals("a", request.link)
        val joins = editor.joins(request)
        assertEquals(listOf("b"), joins.map { it.node })
        editor.join(request, joins.single())
        assertEquals(listOf("a" to "b"), editor.shape.wires.map { it.provider to it.consumer })
        assertEquals(null, editor.request)
    }

    @Test fun `adding from that search links the new card`() {
        val editor = editor { listOf(sink) }
        editor.drop("a", 900.0, 900.0)
        editor.added(editor.request as Request.Palette, sink)
        val added = editor.shape.nodes.single { it.label == "Sink" }.id
        assertEquals(listOf("a" to added), editor.shape.wires.map { it.provider to it.consumer })
    }

    @Test fun `dragging near another card's edge snaps to it and shows a guide`() {
        val editor = editor()
        val c = editor.layout.cards.getValue("c")
        editor.press((c.x + 10).toFloat(), (c.y + 10).toFloat(), Offset.Zero, 4f, PointerKeyboardModifiers(), 1, false)
        editor.drag((c.x + 10 + 403).toFloat(), (c.y + 10).toFloat(), Offset.Zero)
        val guides = (editor.gesture as Gesture.Move).guides
        assertTrue(guides.any { it.x0 == 400.0 && it.x1 == 400.0 }, "$guides")
        editor.release((c.x + 10 + 403).toFloat(), (c.y + 10).toFloat(), Offset.Zero)
        assertEquals(400.0, editor.layout.cards.getValue("c").x)
        assertEquals(null, editor.gesture)
    }

    @Test fun `away from other cards a drag still snaps to the grid`() {
        val editor = editor()
        val c = editor.layout.cards.getValue("c")
        editor.press((c.x + 10).toFloat(), (c.y + 10).toFloat(), Offset.Zero, 4f, PointerKeyboardModifiers(), 1, false)
        editor.drag((c.x + 10 + 1203).toFloat(), (c.y + 10 + 805).toFloat(), Offset.Zero)
        assertTrue((editor.gesture as Gesture.Move).guides.isEmpty())
        editor.release((c.x + 10 + 1203).toFloat(), (c.y + 10 + 805).toFloat(), Offset.Zero)
        val moved = editor.layout.cards.getValue("c")
        assertEquals(0.0, moved.x % editor.grid)
        assertEquals(0.0, moved.y % editor.grid)
    }

    @Test fun typedBlankDropOffersExistingPinsAndConnectsWithOneUndo() {
        val editor = editor()
        editor.drop(PinEnd("a", "out", true))
        val request = editor.request as Request.Palette
        assertEquals(PinEnd("a", "out", true), request.from)
        val choice = editor.joins(request).single()
        assertEquals(PinEnd("b", "in", false), choice.pin)
        editor.join(request, choice)
        assertEquals(1, editor.shape.wires.size)
        assertEquals(1, editor.history.size)
        editor.undo()
        assertEquals(shape, editor.shape)
        assertEquals(placement, editor.placement)
        assertFalse(editor.canUndo)
    }

    @Test fun typedAddAndConnectIsOneAcceptedBatchAndOneUndo() {
        val editor = editor { listOf(sink) }
        val before = editor.placement
        var commits = 0
        editor.commit = { draft, expected ->
            commits++
            if (commits == 1) {
                assertEquals(shape.nodes.size + 1, draft.shape.nodes.size)
                assertEquals(1, draft.shape.wires.size)
            }
            EditReceipt.Accepted(expected.copy(source = "accepted"))
        }
        editor.drop(PinEnd("a", "out", true))
        editor.added(editor.request as Request.Palette, sink)
        val added = editor.shape.nodes.single { it.type == "Sink" }
        assertEquals(setOf(added.id), editor.selection.nodes)
        assertEquals(1, commits)
        assertEquals(1, editor.history.size)
        assertTrue(added.id in editor.placement.cards)
        editor.undo()
        assertEquals(shape, editor.shape)
        assertEquals(before, editor.placement)
        assertFalse(editor.canUndo)
        editor.redo()
        assertEquals(added, editor.shape.node(added.id))
        assertEquals(1, editor.shape.wires.size)
    }

    @Test fun domainAddAndRelateIsOneBatchAndOneUndo() {
        val editor = editor { listOf(sink) }
        editor.relater = { graph, from, to -> graph.wire(from.id, "out", to.id, "in", true, String::equals)?.let { graph.connect(it, true) } }
        val request = Request.Palette(Spot(900.0, 900.0), Offset.Zero, link = "a", relate = true)
        editor.added(request, sink)
        assertEquals(1, editor.history.size)
        assertEquals(1, editor.shape.wires.size)
        editor.undo()
        assertEquals(shape, editor.shape)
        assertEquals(placement, editor.placement)
        assertFalse(editor.canUndo)
    }

    @Test fun synchronousRefusalKeepsGraphPlacementRevisionSelectionAndBothStacks() {
        listOf(false, true).forEach { domain ->
            val editor = editor { listOf(sink) }
            editor.rename("b", "Before")
            editor.undo()
            editor.select(Selection(setOf("c")))
            val before = editor.snapshot()
            var commits = 0
            editor.commit = { _, _ -> commits++; EditReceipt.Refused("Host refused", stale = true) }
            val request = Request.Palette(Spot(900.0, 900.0), Offset.Zero,
                from = PinEnd("a", "out", true).takeUnless { domain }, link = "a".takeIf { domain })
            editor.added(request, sink)
            assertEquals(1, commits)
            assertEquals(before, editor.snapshot())
            assertNull(editor.pending)
            assertEquals("Host refused", editor.notice)
        }
    }

    @Test fun asynchronousRefusalCannotLeaveAnOrphanOrConsumeRedo() = runBlocking {
        listOf(false, true).forEach { domain ->
            val editor = editor { listOf(sink) }
            editor.rename("b", "Before")
            editor.undo()
            editor.select(Selection(setOf("c")))
            val before = editor.snapshot()
            val reply = CompletableDeferred<EditReceipt>()
            editor.editScope = this
            editor.commit = { _, _ -> reply.await() }
            editor.added(Request.Palette(Spot(900.0, 900.0), Offset.Zero,
                from = PinEnd("a", "out", true).takeUnless { domain }, link = "a".takeIf { domain }), sink)
            assertTrue(editor.pending != null)
            assertEquals(before, editor.snapshot())
            editor.added(Request.Palette(Spot(1000.0, 1000.0), Offset.Zero), sink)
            assertEquals(before, editor.snapshot())
            reply.complete(EditReceipt.Refused("Asynchronous refusal"))
            kotlinx.coroutines.yield()
            assertNull(editor.pending)
            assertEquals(before, editor.snapshot())
            editor.commit = { _, expected -> EditReceipt.Accepted(expected.copy(source = expected.source + "+")) }
            editor.redo()
            kotlinx.coroutines.yield()
            assertEquals("Before", editor.shape.node("b")?.label)
        }
    }

    @Test fun incompatibleTemplateAndDomainRefusalNeverAddAnUnlinkedNode() {
        val editor = editor()
        val before = editor.snapshot()
        editor.added(Request.Palette(Spot(900.0, 900.0), Offset.Zero, PinEnd("b", "in", false)), sink)
        assertEquals(before, editor.snapshot())
        editor.linker = { _, _, _ -> null }
        editor.added(Request.Palette(Spot(900.0, 900.0), Offset.Zero, link = "a"), sink)
        assertEquals(before, editor.snapshot())
    }
}
