package dev.shibasis.reaktor.blueprint.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import dev.shibasis.reaktor.blueprint.capture.*
import dev.shibasis.reaktor.graph.core.*
import dev.shibasis.reaktor.surface.KeyConvention
import dev.shibasis.reaktor.surface.KeyName
import dev.shibasis.reaktor.surface.KeyStroke
import dev.shibasis.reaktor.surface.find
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.*

class GraphEditorArrangeTest {
    private val wire = WireShape("b", "in", "a", "out", "Int", "member")
    private val outside = WireShape("c", "in", "a", "out", "Int", "outside")
    private val shape = GraphShape(listOf(ScopeShape("app", "App")), listOf(
        NodeShape("a", "app", "A", "A", NodeKind.Service, ports = listOf(PortShape("out", "Int", true, PortWiring.Unused))),
        NodeShape("b", "app", "B", "B", NodeKind.Service, ports = listOf(PortShape("in", "Int", false, PortWiring.Unmatched))),
        NodeShape("c", "app", "C", "C", NodeKind.Service, ports = listOf(PortShape("in", "Int", false, PortWiring.Unmatched))),
    ), listOf(wire, outside), emptyList()).recounted()
    private val placement = Placement(cards = mapOf("a" to Spot(3.0, 7.0), "b" to Spot(603.0, 227.0), "c" to Spot(1003.0, 587.0)),
        bends = mapOf(wire.id to listOf(Spot(420.0, 180.0)), outside.id to listOf(Spot(820.0, 480.0))),
        remarks = mapOf("a" to "Keep this explanation"))
    private val both = Selection(setOf("a", "b"))
    private val alt = PointerKeyboardModifiers(isAltPressed = true)

    private fun editor(shape: GraphShape = this.shape, placement: Placement = this.placement) = GraphEditor(shape, placement).also {
        it.editRevision = EditRevision("source", "placement")
        it.permits = { _, _ -> true }
        it.validate = { Verdict(true, "") }
        it.commit = { _, revision -> EditReceipt.Accepted(revision.copy(source = revision.source + "+")) }
        it.savePlacement = { _, revision -> EditReceipt.Accepted(revision.copy(presentation = revision.presentation + "+")) }
        it.select(both)
    }

    private fun GraphEditor.snapshot(): List<Any> = listOf(shape, placement, editRevision, history.toList(), future.toList(), selection)
    private fun GraphEditor.header(): Spot = layout.cards.getValue("a").let { Spot(it.x + it.width / 2, it.y + 10) }
    private fun GraphEditor.beginDuplicate(): Spot = header().also {
        assertTrue(press(it.x.toFloat(), it.y.toFloat(), Offset.Zero, 5f, alt, 1, false))
    }
    private fun GraphEditor.drop(from: Spot) {
        drag((from.x + 160).toFloat(), (from.y + 96).toFloat(), Offset.Zero)
        release((from.x + 160).toFloat(), (from.y + 96).toFloat(), Offset.Zero)
    }
    private fun GraphEditor.escape() = invoke(assertNotNull(commands.find(KeyStroke(KeyName.Escape), KeyConvention.Mac)).id)

    @Test fun duplicateKeepsInternalLinksBendsRemarksAndRelativePlacementWithOneUndo() {
        val editor = editor()
        editor.duplicate()
        assertEquals(5, editor.shape.nodes.size)
        val added = editor.shape.nodes.filter { it.id !in shape.nodes.map { it.id } }.associateBy { it.type }
        val a = added.getValue("A").id
        val b = added.getValue("B").id
        val copied = editor.shape.wires.single { it.provider == a && it.consumer == b }
        assertEquals(wire.type, copied.type)
        assertEquals(wire.providerPort, copied.providerPort)
        assertEquals(wire.consumerPort, copied.consumerPort)
        assertEquals("", copied.definitionId)
        assertEquals(3, editor.shape.wires.size)
        val delta = editor.placement.cards.getValue(a).let { Spot(it.x - 3, it.y - 7) }
        assertEquals(Spot(603 + delta.x, 227 + delta.y), editor.placement.cards[b])
        assertEquals(listOf(Spot(420 + delta.x, 180 + delta.y)), editor.placement.bends[copied.id])
        assertEquals("Keep this explanation", editor.placement.remarks[a])
        assertEquals(1, editor.history.size)
        editor.undo()
        assertEquals(shape, editor.shape)
        assertEquals(placement, editor.placement)
        editor.redo()
        assertEquals(5, editor.shape.nodes.size)
    }

    @Test fun encodedClipboardRoundTripKeepsLayoutAndExcludesCrossingLinks() {
        val source = editor()
        val text = assertNotNull(source.fragment()).encode()
        val target = editor(GraphShape(listOf(ScopeShape("app", "App")), emptyList(), emptyList(), emptyList()), Placement())
        assertTrue(target.paste(Spot(320.0, 640.0), text))
        assertEquals(2, target.shape.nodes.size)
        assertEquals(1, target.shape.wires.size)
        val byType = target.shape.nodes.associateBy { it.type }
        val a = byType.getValue("A").id
        val b = byType.getValue("B").id
        assertEquals(Spot(320.0, 640.0), target.placement.cards[a])
        assertEquals(Spot(920.0, 860.0), target.placement.cards[b])
        assertEquals(listOf(Spot(737.0, 813.0)), target.placement.bends[target.shape.wires.single().id])
        assertEquals("Keep this explanation", target.placement.remarks[a])
        assertEquals(1, target.history.size)
        target.undo()
        assertTrue(target.shape.nodes.isEmpty())
        assertEquals(Placement(), target.placement)
    }

    @Test fun attachedCaptureCopiesToTheNewMemberAndKeepsItsStyle() {
        val capture = Capture.Box("box", 120.0, 60.0, CaptureAnchor.Node("a", Spot(20.0, 30.0), Spot(23.0, 37.0), "A"), angle = .2)
        val editor = editor(placement = placement.copy(capture = listOf(capture)))
        editor.duplicate()
        val copied = editor.placement.capture.filterIsInstance<Capture.Box>().single { it.id != capture.id }
        val anchor = assertIs<CaptureAnchor.Node>(copied.anchor)
        assertTrue(anchor.node in editor.selection.nodes)
        assertEquals(Spot(20.0, 30.0), anchor.offset)
        assertEquals(capture.style, copied.style)
        assertEquals(.2, copied.angle)
        assertEquals(1, editor.history.size)
    }

    @Test fun captureOnlyClipboardRemapsConflictingAssetsAndUndoRestoresAllPlacement() {
        val asset = CaptureAsset("asset", "fixture://original", "image/png")
        val image = Capture.Image("image", "asset", 40.0, 30.0, CaptureAnchor.World(Spot(10.0, 20.0)), alt = "Diagram")
        val source = editor(placement = placement.copy(capture = listOf(image), assets = mapOf(asset.id to asset)))
        source.select(Selection(notes = setOf(image.id)))
        val text = assertNotNull(source.fragment()).encode()
        val before = placement.copy(assets = mapOf("asset" to asset.copy(reference = "fixture://other")))
        val target = editor(placement = before)
        assertTrue(target.paste(Spot(160.0, 320.0), text))
        assertEquals(shape, target.shape)
        val pasted = assertIs<Capture.Image>(target.placement.capture.single())
        assertNotEquals(image.id, pasted.id)
        assertNotEquals(asset.id, pasted.asset)
        assertEquals(asset.reference, target.placement.assets[pasted.asset]?.reference)
        assertEquals("fixture://other", target.placement.assets[asset.id]?.reference)
        assertEquals(CaptureAnchor.World(Spot(160.0, 320.0)), pasted.anchor)
        assertEquals(1, target.history.size)
        target.undo()
        assertEquals(before, target.placement)
    }

    @Test fun altDragCreatesNothingUntilDropAndOnlyOneUndoStep() {
        val editor = editor()
        val before = editor.snapshot()
        val from = editor.beginDuplicate()
        assertIs<Gesture.Duplicating>(editor.gesture)
        editor.drag((from.x + 160).toFloat(), (from.y + 96).toFloat(), Offset.Zero)
        assertEquals(before, editor.snapshot())
        val preview = editor.duplicateOffset(assertIs<Gesture.Duplicating>(editor.gesture))
        editor.release((from.x + 160).toFloat(), (from.y + 96).toFloat(), Offset.Zero)
        assertEquals(5, editor.shape.nodes.size)
        assertEquals(1, editor.history.size)
        assertEquals(placement.cards, editor.placement.cards.filterKeys { it in placement.cards })
        val added = editor.shape.nodes.single { it.type == "A" && it.id != "a" }
        assertEquals(Spot(3.0 + preview.x, 7.0 + preview.y), editor.placement.cards[added.id])
        editor.undo()
        assertEquals(shape, editor.shape)
        assertEquals(placement, editor.placement)
    }

    @Test fun escapeAndAltClickDoNotPublishOrRecordDuplicates() {
        val editor = editor()
        val before = editor.snapshot()
        val from = editor.beginDuplicate()
        editor.drag((from.x + 160).toFloat(), (from.y + 96).toFloat(), Offset.Zero)
        editor.escape()
        editor.release((from.x + 160).toFloat(), (from.y + 96).toFloat(), Offset.Zero)
        assertEquals(before, editor.snapshot())
        val click = editor.beginDuplicate()
        editor.release(click.x.toFloat(), click.y.toFloat(), Offset.Zero)
        assertEquals(before, editor.snapshot())
    }

    @Test fun altDragCaptureOnlyUsesOnePresentationReceiptAndKeepsTheOriginal() {
        val capture = Capture.Box("box", 120.0, 60.0, CaptureAnchor.World(Spot(30.0, 700.0)))
        val original = placement.copy(capture = listOf(capture))
        val editor = editor(placement = original)
        editor.select(Selection(notes = setOf(capture.id)))
        var sourceCalls = 0
        var placementCalls = 0
        editor.commit = { _, revision -> sourceCalls++; EditReceipt.Accepted(revision) }
        editor.savePlacement = { _, revision -> placementCalls++; EditReceipt.Accepted(revision.copy(presentation = revision.presentation + "+")) }
        assertTrue(editor.press(60f, 725f, Offset.Zero, 5f, alt, 1, false))
        editor.drag(220f, 821f, Offset.Zero)
        assertEquals(original, editor.placement)
        editor.release(220f, 821f, Offset.Zero)
        assertEquals(shape, editor.shape)
        assertEquals(0, sourceCalls)
        assertEquals(1, placementCalls)
        assertEquals(2, editor.placement.capture.size)
        assertEquals(capture, editor.placement.capture.first())
        assertEquals(1, editor.history.size)
        editor.undo()
        assertEquals(original, editor.placement)
    }

    @Test fun refusedDuplicatePreservesShapeCompletePlacementRevisionAndBothStacks() {
        val editor = editor()
        editor.rename("c", "Before")
        editor.undo()
        editor.select(both)
        editor.commit = { _, _ -> EditReceipt.Refused("Refused") }
        val before = editor.snapshot()
        editor.duplicate()
        assertEquals(before, editor.snapshot())
        editor.drop(editor.beginDuplicate())
        assertEquals(before, editor.snapshot())
    }

    @Test fun pendingDuplicateHasNoOptimisticSelectionAndRefusesTheSecondEdit() = runBlocking {
        val editor = editor()
        val reply = CompletableDeferred<EditReceipt>()
        editor.editScope = this
        editor.commit = { _, _ -> reply.await() }
        val before = editor.snapshot()
        editor.drop(editor.beginDuplicate())
        assertNotNull(editor.pending)
        assertEquals(before, editor.snapshot())
        editor.duplicate()
        assertEquals(before, editor.snapshot())
        reply.complete(EditReceipt.Refused("Refused later"))
        yield()
        assertNull(editor.pending)
        assertEquals(before, editor.snapshot())
    }

    @Test fun alignIsOneEditAndUndoRestoresEveryPresentationField() {
        val editor = editor()
        editor.align(Edge.Top)
        assertEquals(7.0, editor.placement.cards.getValue("b").y)
        assertEquals(1, editor.history.size)
        assertEquals(placement.bends, editor.placement.bends)
        editor.undo()
        assertEquals(placement, editor.placement)
    }

    @Test fun distributionUsesEqualGapsForDifferentCardHeightsAndKeepsTheExtremes() {
        val taller = shape.copy(nodes = shape.nodes.map { if (it.id == "b") it.copy(ports = it.ports + (1..6).map { i -> PortShape("extra$i", "Int", false, PortWiring.Unmatched) }) else it })
        val editor = editor(taller)
        editor.select(Selection(setOf("a", "b", "c")))
        val before = editor.layout.cards
        editor.distribute(false)
        val a = editor.layout.cards.getValue("a")
        val b = editor.layout.cards.getValue("b")
        val c = editor.layout.cards.getValue("c")
        assertEquals(b.y - a.y - a.height, c.y - b.y - b.height, .00001)
        assertEquals(before.getValue("a").y, a.y)
        assertEquals(before.getValue("c").y, c.y, .00001)
        assertEquals(1, editor.history.size)
        editor.undo()
        assertEquals(placement, editor.placement)
    }

    @Test fun straightenFindsInternalSelectionLinksAndClearsOnlyTheirAuthoredBends() {
        val editor = editor()
        editor.straighten()
        val from = assertNotNull(editor.anchor(PinEnd("a", "out", true)))
        val to = assertNotNull(editor.anchor(PinEnd("b", "in", false)))
        assertEquals(from.second, to.second)
        assertFalse(wire.id in editor.placement.bends)
        assertEquals(placement.bends[outside.id], editor.placement.bends[outside.id])
        assertEquals(1, editor.history.size)
        editor.undo()
        assertEquals(placement, editor.placement)
    }
}
