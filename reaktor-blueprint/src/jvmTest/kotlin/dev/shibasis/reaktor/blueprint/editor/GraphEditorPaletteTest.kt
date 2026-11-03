package dev.shibasis.reaktor.blueprint.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import dev.shibasis.reaktor.blueprint.capture.CaptureAnchor
import dev.shibasis.reaktor.blueprint.capture.Capture
import dev.shibasis.reaktor.blueprint.capture.CaptureTool
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.graph.core.WireShape
import dev.shibasis.reaktor.surface.CommandId
import dev.shibasis.reaktor.surface.KeyConvention
import dev.shibasis.reaktor.surface.KeyName
import dev.shibasis.reaktor.surface.KeyStroke
import dev.shibasis.reaktor.surface.find
import dev.shibasis.reaktor.surface.findings
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphEditorPaletteTest {
    private fun input(type: String = "Int") = PortShape("in", type, false, PortWiring.Unmatched)
    private fun output(type: String = "Int") = PortShape("out", type, true, PortWiring.Unused)
    private fun node(id: String, vararg ports: PortShape) = NodeShape(id, "app", id, id, NodeKind.Service, ports = ports.toList())
    private val shape = GraphShape(listOf(ScopeShape("app", "App")),
        listOf(node("a", output()), node("b", input()), node("middle", input(), output())),
        listOf(WireShape("b", "in", "a", "out", "Int")), emptyList())
    private val places = Placement(mapOf("a" to Spot(0.0, 0.0), "b" to Spot(1000.0, 0.0), "middle" to Spot(0.0, 600.0)))
    private val sink = Template("Number sink", NodeKind.Service, "Sink", listOf(input()), category = "Numbers")
    private val source = Template("Number source", NodeKind.Service, "Source", listOf(output()), category = "Numbers")
    private val incompatible = Template("Text sink", NodeKind.Service, "TextSink", listOf(input("String")))

    private fun editor(catalogue: () -> List<Template> = { listOf(sink, source, incompatible) }) =
        GraphEditor(shape, places, catalogue = { catalogue() }).also {
            it.editRevision = EditRevision("source", "placement")
            it.permits = { _, _ -> true }
            it.validate = { Verdict(true, "") }
            it.commit = { _, expected -> EditReceipt.Accepted(expected.copy(source = expected.source + "+")) }
            it.savePlacement = { _, expected -> EditReceipt.Accepted(expected.copy(presentation = expected.presentation + "+")) }
        }

    private fun snapshot(editor: GraphEditor) = listOf(editor.shape, editor.placement, editor.editRevision, editor.history.toList(), editor.future.toList())

    @Test
    fun aHostCaptureChoiceStartsAtThePaletteSpotAndCommitsWithoutAGraphNode() {
        val capture = Template("Rectangle", NodeKind.Node, "capture-box", emptyList(), category = "Capture", captureTool = CaptureTool.Box)
        val editor = editor { listOf(capture) }
        val before = snapshot(editor)
        val request = Request.Palette(Spot(1700.0, 1300.0), Offset.Zero)
        editor.added(request, capture)
        val preview = editor.capturePreview.single()
        assertEquals(CaptureAnchor.World(request.at), preview.anchor)
        assertEquals(before, snapshot(editor))
        editor.invoke(editor.commands.find(KeyStroke(KeyName.Enter), KeyConvention.Pc)!!.id)
        assertEquals(before[0], editor.shape)
        assertEquals(1, editor.placement.captures.size)
        assertEquals(1, editor.history.size)
        val after = editor.placement
        editor.undo()
        assertEquals(before.take(2), snapshot(editor).take(2))
        editor.redo()
        assertEquals(after, editor.placement)
    }

    @Test
    fun noteAndCommentChoicesPreviewEditTextCommitUndoAndCancelAsCaptures() {
        for (tool in listOf(CaptureTool.Note, CaptureTool.Comment)) {
            val capture = Template(tool.name, NodeKind.Node, "capture-${tool.name}", emptyList(), captureTool = tool)
            for (cancel in listOf(true, false)) {
                val editor = editor { listOf(capture) }
                val before = snapshot(editor)
                val at = Spot(1700.0, 1300.0)
                editor.added(Request.Palette(at, Offset.Zero), capture)
                assertEquals(CaptureAnchor.World(at), editor.capturePreview.single().anchor)
                assertEquals(before, snapshot(editor))
                editor.invoke(editor.commands.find(KeyStroke(KeyName.Enter), KeyConvention.Pc)!!.id)
                val rename = assertIs<Request.Rename>(editor.request)
                assertEquals(editor.capturePreview.single().id, rename.subject)
                if (cancel) {
                    editor.invoke(CommandId("edit-escape"))
                    assertEquals(before, snapshot(editor))
                    assertTrue(editor.capturePreview.isEmpty())
                    assertNull(editor.request)
                } else {
                    editor.rename(rename.subject, "A useful annotation")
                    val value = editor.placement.captures.single()
                    when (tool) {
                        CaptureTool.Note -> assertEquals("A useful annotation", assertIs<Capture.Note>(value).text)
                        CaptureTool.Comment -> assertEquals("A useful annotation", assertIs<Capture.Comment>(value).text)
                        else -> error("Unexpected tool")
                    }
                    assertEquals(before[0], editor.shape)
                    assertEquals(1, editor.history.size)
                    editor.undo()
                    assertEquals(before.take(2), snapshot(editor).take(2))
                    editor.redo()
                    assertEquals(value, editor.placement.captures.single())
                }
            }
        }
    }

    @Test
    fun captureChoicesCancelAndRefuseWithoutChangingShapePlacementOrHistory() {
        val capture = Template("Rectangle", NodeKind.Node, "capture-box", emptyList(), captureTool = CaptureTool.Box)
        for (cancel in listOf(true, false)) {
            val editor = editor { listOf(capture) }
            val before = snapshot(editor)
            editor.added(Request.Palette(Spot(900.0, 700.0), Offset.Zero), capture)
            if (cancel) editor.invoke(CommandId("edit-escape"))
            else {
                editor.savePlacement = { _, _ -> EditReceipt.Refused("Host refused") }
                editor.invoke(editor.commands.find(KeyStroke(KeyName.Enter), KeyConvention.Pc)!!.id)
            }
            assertEquals(before, snapshot(editor))
            assertTrue(editor.capturePreview.isEmpty())
        }
    }

    @Test
    fun captureChoicesAreExcludedFromPhysicalAndSemanticLinkOrigins() {
        val capture = Template("Rectangle", NodeKind.Node, "capture-box", emptyList(), captureTool = CaptureTool.Box)
        val editor = editor { listOf(capture, sink) }
        val at = Spot(900.0, 700.0)
        val physical = Request.Palette(at, Offset.Zero, PinEnd("a", "out", true))
        val semantic = Request.Palette(at, Offset.Zero, link = "a")
        assertEquals(listOf(sink), editor.templates(physical))
        assertEquals(listOf(sink), editor.templates(semantic))
        val before = snapshot(editor)
        editor.added(semantic, capture)
        assertEquals(before, snapshot(editor))
        assertTrue(editor.capturePreview.isEmpty())
    }

    @Test
    fun tabOpensTheNodeCatalogueAtThePointerWithoutChangingExcalidrawKeys() {
        KeyConvention.entries.forEach { convention ->
            val editor = editor()
            editor.point(1300f, 900f, Offset(25f, 60f))
            val before = snapshot(editor)
            editor.invoke(editor.commands.find(KeyStroke(KeyName.Tab), convention)!!.id)
            val request = assertIs<Request.Palette>(editor.request)
            assertEquals(Spot(1300.0, 900.0), request.at)
            assertEquals(Offset(25f, 60f), request.window)
            assertEquals(before, snapshot(editor))
            assertEquals("tool-text", editor.commands.find(KeyStroke(KeyName.T), convention)?.id?.value)
            assertTrue(editor.commands.findings(convention).isEmpty())
        }
    }

    @Test
    fun tabCancelsAnUncommittedCaptureOrMoveBeforeOpeningANodeProposal() {
        listOf(false, true).forEach { moving ->
            val editor = editor()
            val before = snapshot(editor)
            if (moving) {
                val card = editor.layout.cards.getValue("a")
                editor.press((card.x + card.width / 2).toFloat(), (card.y + card.height / 2).toFloat(),
                    Offset.Zero, 4f, PointerKeyboardModifiers(), 1, false)
                editor.drag(1600f, 1200f, Offset.Zero)
                assertIs<Gesture.Move>(editor.gesture)
                assertTrue(editor.placement != before[1])
            } else {
                editor.invoke(CommandId("tool-box"))
                assertTrue(editor.capturePreview.isNotEmpty())
            }
            editor.invoke(CommandId("edit-add-node"))
            assertEquals(before, snapshot(editor))
            assertNull(editor.gesture)
            assertEquals(EditorTool.Select, editor.tool)
            assertTrue(editor.capturePreview.isEmpty())
            val request = assertIs<Request.Palette>(editor.request)
            editor.perform(CommandId("edit-add-node")) { editor.added(request, sink) }
            assertEquals(1, editor.history.size)
            assertTrue(editor.placement.captures.isEmpty())
            editor.undo()
            assertEquals(before.take(2), snapshot(editor).take(2))
        }
    }

    @Test
    fun cataloguesAreLiveAndSearchFiltersTypeDirectionCategoryAndPortNames() {
        var catalogue = listOf(sink, source, incompatible)
        val editor = editor { catalogue }
        val fromOutput = Request.Palette(Spot(0.0, 0.0), Offset.Zero, PinEnd("a", "out", true))
        val fromInput = fromOutput.copy(from = PinEnd("b", "in", false))
        assertEquals(listOf(sink), editor.templates(fromOutput))
        assertEquals(listOf(source), editor.templates(fromInput))
        assertEquals(listOf(sink), editor.templates(fromOutput, "NUMBERS in"))
        assertTrue(editor.templates(fromOutput, "text").isEmpty())
        assertTrue(editor.templates(fromOutput.copy(from = PinEnd("missing", "out", true))).isEmpty())
        catalogue = listOf(source)
        assertTrue(editor.templates(fromOutput).isEmpty())
        assertEquals(listOf(source), editor.templates(fromInput, "Source"))
    }

    @Test
    fun aPinDropAddsTheChosenCompatibleNodeConnectedInOneUndoAndReturnsFocus() {
        val editor = editor()
        var focused = 0
        editor.commandsFromCanvas(editor::invoke, { focused++ })
        val before = editor.shape
        val from = assertNotNull(editor.anchor(PinEnd("a", "out", true)))
        assertTrue(editor.press(from.first.toFloat(), from.second.toFloat(), Offset.Zero, 4f, PointerKeyboardModifiers(), 1, false))
        editor.drag(1800f, 1400f, Offset.Zero)
        editor.release(1800f, 1400f, Offset.Zero)
        val request = assertIs<Request.Palette>(editor.request)
        assertEquals(PinEnd("a", "out", true), request.from)
        assertEquals(before, editor.shape)
        editor.perform(CommandId("edit-add-node")) { editor.added(request, editor.templates(request, "sink").single()) }
        val added = editor.shape.nodes.single { it.type == sink.type }
        assertTrue(editor.shape.wires.any { it.provider == "a" && it.consumer == added.id })
        val after = editor.shape
        val placed = editor.placement
        assertEquals(1, editor.history.size)
        assertNull(editor.request)
        assertEquals(1, focused)
        editor.undo()
        assertEquals(before, editor.shape)
        editor.redo()
        assertEquals(after, editor.shape)
        assertEquals(placed, editor.placement)
    }

    @Test
    fun cancellingTheCatalogueLeavesShapePlacementRevisionAndBothHistoryStacksAlone() {
        val editor = editor()
        val before = snapshot(editor)
        editor.invoke(CommandId("edit-add-node"))
        val request = assertIs<Request.Palette>(editor.request)
        editor.dismissCommandRequest(request)
        assertNull(editor.request)
        assertEquals(before, snapshot(editor))
    }

    private fun dropOntoWire(editor: GraphEditor) {
        val wire = editor.layout.links.single { shape.wires.single().id in it.members }
        val segment = wire.trace.zipWithNext().filter { (a, b) ->
            val x = (a.first + b.first) / 2
            val y = (a.second + b.second) / 2
            editor.layout.cards.values.none { x in it.x..(it.x + it.width) && y in it.y..(it.y + it.height) }
        }.maxBy { (a, b) -> hypot(b.first - a.first, b.second - a.second) }
        val at = Spot((segment.first.first + segment.second.first) / 2, (segment.first.second + segment.second.second) / 2)
        val card = editor.layout.cards.getValue("middle")
        editor.select(Selection(setOf("middle")))
        assertTrue(editor.press((card.x + 18).toFloat(), (card.y + 18).toFloat(), Offset.Zero, 4f, PointerKeyboardModifiers(), 1, false))
        assertIs<Gesture.Move>(editor.gesture)
        editor.drag(at.x.toFloat(), at.y.toFloat(), Offset.Zero)
        editor.release(at.x.toFloat(), at.y.toFloat(), Offset.Zero)
    }

    @Test
    fun droppingACardIntoAWireSplicesAndMovesItInOneUndoableReceipt() {
        val editor = editor()
        val before = editor.shape
        val placed = editor.placement
        dropOntoWire(editor)
        assertEquals(setOf("a" to "middle", "middle" to "b"), editor.shape.wires.map { it.provider to it.consumer }.toSet())
        assertFalse(placed == editor.placement)
        assertEquals(1, editor.history.size)
        val after = editor.shape
        val afterPlacement = editor.placement
        editor.undo()
        assertEquals(before, editor.shape)
        assertEquals(placed, editor.placement)
        editor.redo()
        assertEquals(after, editor.shape)
        assertEquals(afterPlacement, editor.placement)
    }

    @Test
    fun refusedSplicingRestoresCompletePlacementRevisionAndBothHistoryStacks() {
        val editor = editor()
        editor.commit = { _, _ -> EditReceipt.Refused("Source does not permit insertion") }
        val before = snapshot(editor)
        dropOntoWire(editor)
        assertEquals(before, snapshot(editor))
        assertEquals("Source does not permit insertion", editor.notice)
    }

    @Test
    fun semanticHostsSupplyTheRelationshipWithoutRequiringGraphPorts() {
        val editor = editor()
        editor.rebase(shape.copy(nodes = shape.nodes.map { it.copy(ports = emptyList()) }))
        val calls = mutableListOf<Pair<String, String>>()
        editor.linker = { graph, from, to ->
            calls += from.id to to.id
            graph.copy(wires = graph.wires + WireShape(to.id, "after ${from.id}", from.id, "item", "After"))
        }
        val before = editor.shape
        dropOntoWire(editor)
        assertEquals(listOf("a" to "middle", "middle" to "b"), calls)
        assertEquals(2, editor.shape.wires.size)
        assertTrue(editor.shape.wires.all { it.type == "After" })
        assertEquals(1, editor.history.size)
        editor.undo()
        assertEquals(before, editor.shape)
    }

    @Test
    fun semanticLinkCommandsReachHostRelationshipsWhileExcalidrawArrowAndLineStayCaptures() {
        KeyConvention.entries.forEach { convention ->
            listOf(false, true).forEach { relate ->
                val editor = editor()
                editor.rebase(shape.copy(nodes = shape.nodes.map { it.copy(ports = emptyList()) }))
                editor.linker = { graph, from, to -> graph.copy(wires = graph.wires +
                    WireShape(to.id, "after ${from.id}", from.id, "item", "After")) }
                editor.relater = { graph, from, to -> graph.copy(wires = graph.wires +
                    WireShape(to.id, "related ${from.id}", from.id, "item", "Related")) }
                val before = snapshot(editor)
                val key = if (relate) KeyName.L else KeyName.A
                val command = editor.commands.find(KeyStroke(key, shift = true, alt = true), convention)!!
                assertEquals(if (relate) "tool-relate" else "tool-link", command.id.value)
                assertEquals(if (relate) "tool-line" else "tool-arrow", editor.commands.find(KeyStroke(key), convention)?.id?.value)
                editor.invoke(command.id)
                val from = editor.layout.cards.getValue("a")
                val to = editor.layout.cards.getValue("middle")
                assertTrue(editor.press((from.x + from.width / 2).toFloat(), (from.y + from.height / 2).toFloat(),
                    Offset.Zero, 4f, PointerKeyboardModifiers(), 1, false))
                assertIs<Gesture.Link>(editor.gesture)
                editor.drag((to.x + to.width / 2).toFloat(), (to.y + to.height / 2).toFloat(), Offset.Zero)
                editor.release((to.x + to.width / 2).toFloat(), (to.y + to.height / 2).toFloat(), Offset.Zero)
                assertTrue(editor.shape.wires.any { it.provider == "a" && it.consumer == "middle" && it.type == if (relate) "Related" else "After" })
                assertEquals(1, editor.history.size)
                assertTrue(editor.placement.captures.isEmpty())
                editor.undo()
                assertEquals(before.take(2), snapshot(editor).take(2))
            }
        }
    }

    @Test
    fun aSemanticLinkDroppedOnEmptySpaceKeepsItsOriginForConnectedInsertion() {
        val editor = editor()
        editor.rebase(shape.copy(nodes = shape.nodes.map { it.copy(ports = emptyList()) }))
        editor.linker = { graph, from, to -> graph.copy(wires = graph.wires +
            WireShape(to.id, "after ${from.id}", from.id, "item", "After")) }
        val before = editor.shape
        editor.invoke(CommandId("tool-link"))
        val from = editor.layout.cards.getValue("a")
        editor.press((from.x + from.width / 2).toFloat(), (from.y + from.height / 2).toFloat(),
            Offset.Zero, 4f, PointerKeyboardModifiers(), 1, false)
        editor.drag(1800f, 1400f, Offset.Zero)
        editor.release(1800f, 1400f, Offset.Zero)
        val request = assertIs<Request.Palette>(editor.request)
        assertEquals("a", request.link)
        assertNull(request.from)
        editor.perform(CommandId("edit-add-node")) { editor.added(request, sink) }
        val added = editor.shape.nodes.single { it.type == sink.type }
        assertTrue(editor.shape.wires.any { it.provider == "a" && it.consumer == added.id && it.type == "After" })
        assertEquals(1, editor.history.size)
        editor.undo()
        assertEquals(before, editor.shape)
    }

    @Test
    fun anIncompatibleCardCannotDisconnectTheOriginalWireOrRetainItsPreviewMove() {
        val editor = editor()
        editor.rebase(shape.copy(nodes = shape.nodes.map { if (it.id == "middle") it.copy(ports = listOf(input("String"), output("String"))) else it }))
        val before = snapshot(editor)
        dropOntoWire(editor)
        assertEquals(before, snapshot(editor))
        assertTrue(editor.notice.orEmpty().contains("compatible input and output"))
    }
}
