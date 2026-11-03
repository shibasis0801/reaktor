@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.capture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import dev.shibasis.reaktor.blueprint.BlueprintEngine
import dev.shibasis.reaktor.blueprint.BlueprintLayout
import dev.shibasis.reaktor.blueprint.Card
import dev.shibasis.reaktor.blueprint.Frame
import dev.shibasis.reaktor.blueprint.Link
import dev.shibasis.reaktor.blueprint.LinkKind
import dev.shibasis.reaktor.blueprint.Pin
import dev.shibasis.reaktor.blueprint.editor.EditReceipt
import dev.shibasis.reaktor.blueprint.editor.GraphEditor
import dev.shibasis.reaktor.blueprint.editor.Placement
import dev.shibasis.reaktor.blueprint.editor.Spot
import dev.shibasis.reaktor.blueprint.editor.Verdict
import dev.shibasis.reaktor.blueprint.editor.id
import dev.shibasis.reaktor.blueprint.editor.with
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.graph.core.WireShape
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapturePlacementTest {
    private val zero = Spot(0.0, 0.0)
    private val fallback = Spot(730.0, 470.0)
    private val author = CaptureAuthor("(codex, xhigh, sol 6.1, designer)", "bpo-designer", 3)
    private val wire = WireShape("Messages", "receive", "Circles", "send", "Message")
    private val shape = GraphShape(listOf(ScopeShape("island", "Circles island")), listOf(
        NodeShape("Circles", "island", "Circles", "Circles", NodeKind.Service,
            ports = listOf(PortShape("send", "Message", true, PortWiring.Connected)), attributes = mapOf("status" to "Ready")),
        NodeShape("Messages", "island", "Messages", "Messages", NodeKind.Service,
            ports = listOf(PortShape("receive", "Message", false, PortWiring.Connected))),
    ), listOf(wire), emptyList())
    private val layout = BlueprintLayout("anchor", linkedMapOf(
        "Circles" to Card("Circles", 30.0, 130.0, 240.0, BlueprintEngine.cardHeight(1), listOf(Pin("send", "Message", true, BlueprintEngine.pinY(0), PortWiring.Connected)), 0),
        "Messages" to Card("Messages", 520.0, 130.0, 240.0, BlueprintEngine.cardHeight(1), listOf(Pin("receive", "Message", false, BlueprintEngine.pinY(0), PortWiring.Connected)), 0),
    ), listOf(Frame("island", "Circles island", 10.0, 80.0, 770.0, 430.0, setOf("Circles", "Messages"), emptyList(), false)),
        listOf(Link(wire.id, "Circles", "Messages", "send", "receive", LinkKind.Wire, listOf(wire.id), listOf(270.0 to 190.0, 520.0 to 190.0))), 800.0, 550.0)
    private val offset = Spot(10.0, 20.0)
    private val anchors = listOf(
        CaptureAnchor.Node("Circles", offset, fallback, "Circles"),
        CaptureAnchor.Row("Circles", "status", offset, fallback, "Run status"),
        CaptureAnchor.Port("Circles", "send", offset, fallback, "Send message"),
        CaptureAnchor.Wire(wire.id, offset, fallback, "Messages link"),
        CaptureAnchor.Frame("island", offset, fallback, "Circles island"),
    )

    private fun moved(dx: Double, dy: Double) = layout.copy(
        cards = layout.cards.mapValues { (_, card) -> card.copy(x = card.x + dx, y = card.y + dy) },
        frames = layout.frames.map { it.copy(x = it.x + dx, y = it.y + dy) },
        links = layout.links.map { link -> link.copy(points = link.points.map { (x, y) -> x + dx to y + dy }) },
    )
    private fun resolver(value: BlueprintLayout<*> = layout, facts: GraphShape = shape, placement: Placement = Placement()) =
        CapturePlacement(value, facts, placement, rows = value.cards["Circles"]?.let { mapOf(("Circles" to "status") to Spot(it.x, it.y + BlueprintEngine.pinY(0))) }.orEmpty())

    private fun folded() = BlueprintLayout("anchor", mapOf("summary:island" to Card("summary:island", 30.0, 100.0, 240.0, BlueprintEngine.HeaderHeight, emptyList(), 2)),
        emptyList(), emptyList(), 800.0, 550.0, cardOf = shape.nodes.associate { it.id to "summary:island" })

    @Test fun allFiveTargetKindsFollowMovementAndUseFreshLabelsWithoutChangingIds() {
        val before = resolver()
        val renamed = shape.copy(nodes = shape.nodes.map { if (it.id == "Circles") it.copy(label = "Circles invitations") else it }, scopes = listOf(ScopeShape("island", "Invitation island")))
        val after = resolver(moved(120.0, -30.0), renamed)
        for (anchor in anchors) {
            val first = before.attachment(anchor)
            val next = after.attachment(anchor)
            assertEquals(CapturePlacement.State.Attached, first.state)
            assertEquals(Spot(first.at.x + 120, first.at.y - 30), next.at)
            assertEquals(CapturePlacement.State.Attached, next.state)
            assertFalse(next.label.contains("Run status"))
        }
        assertTrue(after.attachment(anchors.first()).label.contains("invitations"))
        assertEquals("Invitation island", after.attachment(anchors.last()).label)
        assertEquals(Spot(270.0 + offset.x, 130.0 + BlueprintEngine.pinY(0) + offset.y), before.resolve(anchors[2]))
        val world = CaptureAnchor.World(fallback)
        assertEquals(fallback, after.resolve(world))
        assertEquals(CapturePlacement.State.World, after.attachment(world).state)
    }

    @Test fun foldedTargetsUseTheirVisibleSummaryAndReopenAtTheirOriginalTargets() {
        val closed = resolver(folded())
        for (anchor in anchors) {
            val target = closed.attachment(anchor)
            assertEquals(CapturePlacement.State.Folded, target.state)
            assertEquals("In Circles island", target.caption)
            assertTrue(target.at.x < fallback.x)
            assertEquals(resolver().resolve(anchor), resolver(layout).resolve(anchor))
        }
        val captures = anchors.mapIndexed { i, anchor -> Capture.Comment("comment-$i", "Check the retained target", anchor, author) }
        val saved = Placement(capture = captures).with(closed.let { CapturePlacement(folded(), shape, Placement(capture = captures)).remember()!! }.layout!!)
        for ((original, stored) in captures.zip(saved.capture)) {
            assertEquals(resolver().resolve(original.anchor), resolver(layout, placement = saved).resolve(stored.anchor))
            assertEquals(original.author, stored.author)
        }
    }

    @Test fun rememberedPositionsAndLabelsSurviveDeletionSaveReopenAndRestoration(): Unit = runBlocking {
        val comments = anchors.mapIndexed { i, anchor -> Capture.Comment("comment-$i", "Check target $i", anchor, author, if (i == 1) "comment-0" else null) }
        val arrow = Capture.Arrow("arrow", anchors[2], anchors[4], listOf(Spot(400.0, 400.0)), author)
        val original = Placement(capture = comments + arrow)
        val renamed = shape.copy(nodes = shape.nodes.map { it.copy(label = "${it.label} revised") })
        val followed = resolver(moved(90.0, 40.0), renamed, original)
        val editor = GraphEditor(shape, original)
        editor.permits = { _, _ -> true }
        editor.validate = { Verdict(true, "") }
        var saved = original
        editor.savePlacement = { value, expected -> saved = value; EditReceipt.Accepted(expected) }
        editor.commit = { _, _ -> error("Anchor refresh cannot write graph facts") }
        val part = assertNotNull(followed.remember())
        assertTrue(part.changes.isEmpty())
        assertTrue(part.definitions.isEmpty())
        assertIs<EditReceipt.Accepted>(editor.propose(part))
        assertEquals(shape, editor.shape)
        val reopened = Json.decodeFromString(Placement.serializer(), Json.encodeToString(Placement.serializer(), saved))
        val missing = CapturePlacement(layout.copy(cards = emptyMap(), frames = emptyList(), links = emptyList()), GraphShape(emptyList(), emptyList(), emptyList(), emptyList()), reopened)
        for (value in reopened.captures) {
            val attachment = missing.attachment(value.anchor)
            assertEquals(CapturePlacement.State.Unattached, attachment.state)
            assertTrue(attachment.caption!!.startsWith("Unattached · "))
            assertEquals(followed.resolve(original.captures.single { it.id == value.id }.anchor), attachment.at)
        }
        assertNull(missing.remember())
        assertEquals("comment-0", reopened.captures.filterIsInstance<Capture.Comment>().single { it.id == "comment-1" }.replyTo)
        val storedArrow = reopened.captures.filterIsInstance<Capture.Arrow>().single()
        assertEquals(followed.resolve(arrow.end), missing.resolve(storedArrow.end))
        assertEquals(arrow.bends, storedArrow.bends)
        val restored = resolver(layout, placement = reopened)
        for (value in reopened.captures) assertEquals(resolver().resolve(original.captures.single { it.id == value.id }.anchor), restored.resolve(value.anchor))
        editor.undo()
        assertEquals(original, editor.placement)
        editor.redo()
        assertEquals(reopened, editor.placement)
    }

    @Test fun absentRowsPortsAndFramesNeverJumpToAnotherTargetWithTheSameLabel() {
        val facts = shape.copy(nodes = shape.nodes.map { it.copy(ports = emptyList(), attributes = emptyMap()) }, scopes = emptyList())
        val resolver = CapturePlacement(layout, facts)
        for (anchor in listOf(anchors[1], anchors[2], anchors[4], CaptureAnchor.Node("missing", offset, fallback, "Circles"))) {
            assertEquals(CapturePlacement.State.Unattached, resolver.attachment(anchor).state)
            assertEquals(fallback, resolver.resolve(anchor))
        }
        val hiddenRow = CapturePlacement(layout, shape).attachment(anchors[1])
        assertEquals(CapturePlacement.State.Folded, hiddenRow.state)
        assertEquals("In Circles", hiddenRow.caption)
        assertEquals(Spot(40.0, 150.0), hiddenRow.at)
    }

    @Test fun captureFramesAndThreadsFollowTheirParentAndCyclesStayUnattached() {
        val frame = Capture.Frame("review", "Launch review", 280.0, 180.0, anchors[0], author)
        val frameAnchor = CaptureAnchor.Frame(frame.id, offset, fallback, frame.title)
        val placement = Placement(capture = listOf(frame))
        assertEquals(Spot(50.0, 170.0), resolver(placement = placement).resolve(frameAnchor))
        assertEquals(Spot(150.0, 210.0), resolver(moved(100.0, 40.0), placement = placement).resolve(frameAnchor))
        val self = frame.copy(anchor = frameAnchor)
        assertEquals(CapturePlacement.State.Unattached, resolver(placement = Placement(capture = listOf(self))).attachment(frameAnchor).state)
        val note = Capture.Comment("question", "Review invitation expiry", anchors[0], author)
        val tools = CaptureTools(Placement(capture = listOf(note)), { "reply" }, author, resolve = resolver()::resolve)
        tools.reply(note.id, "Expiry has a retained check.")
        val before = tools.preview.map { CaptureHit.geometry(it, resolver()::resolve) }
        val after = tools.preview.map { CaptureHit.geometry(it, resolver(moved(100.0, 40.0))::resolve) }
        assertEquals(listOf(100.0, 100.0), before.zip(after).map { (a, b) -> b.bounds.left - a.bounds.left })
        assertEquals(listOf(40.0, 40.0), before.zip(after).map { (a, b) -> b.bounds.top - a.bounds.top })
    }

    @Test fun wireAnchorsFollowBundlePathsByLengthAndIndependentArrowEndsStayHittable() {
        val trace = listOf(0.0 to 0.0, 0.0 to 100.0, 300.0 to 100.0)
        val bundled = layout.copy(links = listOf(layout.links.single().copy(id = "bundle", members = listOf(wire.id, "other"), points = trace)))
        val placement = resolver(bundled)
        assertEquals(Spot(110.0, 120.0), placement.resolve(anchors[3]))
        assertEquals("In 2 links", placement.caption(anchors[3]))
        val arrow = Capture.Arrow("arrow", anchors[0], anchors[2], author = author)
        val before = CaptureHit.geometry(arrow, resolver()::resolve)
        val changed = layout.copy(cards = layout.cards.mapValues { (_, card) -> if (card.id == "Circles") card.copy(x = card.x + 100, y = card.y + 40) else card })
        val after = CaptureHit.geometry(arrow, resolver(changed)::resolve)
        assertEquals(before.points.map { Spot(it.x + 100, it.y + 40) }, after.points)
        assertNotNull(CaptureHit.find(listOf(arrow), after.points.last(), 1.0, arrow.id, resolve = resolver(changed)::resolve))
    }

    @Test fun manualNoteFoldMappingsKeepRealIdsAndUnfoldWithoutCoordinatesBeingRewritten() {
        val note = dev.shibasis.reaktor.blueprint.editor.Note("review", "Review frame", 10.0, 80.0, 770.0, 430.0)
        val closed = layout.copy(cards = mapOf(note.id to Card(note.id, 10.0, 80.0, 240.0, BlueprintEngine.HeaderHeight, emptyList(), 2)), frames = emptyList(), links = emptyList(), cardOf = emptyMap())
        val placement = Placement(notes = listOf(note))
        val represented = shape.nodes.associate { it.id to note.id }
        val anchor = anchors[0]
        assertEquals("In Review frame", CapturePlacement(closed, shape, placement, represented).caption(anchor))
        val noteAnchor = CaptureAnchor.Frame(note.id, zero, fallback, note.text)
        assertEquals(Spot(10.0, 80.0), CapturePlacement(closed, shape, placement, represented).resolve(noteAnchor))
        assertEquals(resolver().resolve(anchor), resolver(layout, placement = placement).resolve(anchor))
    }

    @Test fun attachmentStatusPaintsAtTheResolvedPositionInBothThemes() {
        val output = File("build/reports/blueprint-capture-anchor").apply { mkdirs() }
        val note = Capture.Note("review", "Check invitation links before launch.", 320.0, 90.0,
            CaptureAnchor.Node("Circles", Spot(280.0, 10.0), fallback, "Circles"), author)
        val comment = Capture.Comment("question", "Is the expiry state covered?", CaptureAnchor.Row("Circles", "status", Spot(280.0, 160.0), Spot(310.0, 350.0), "Circles · status"), author)
        val start = Placement(capture = listOf(note, comment))
        val saved = start.with(resolver(placement = start).remember()!!.layout!!)
        val empty = GraphShape(emptyList(), emptyList(), emptyList(), emptyList())
        val stages = listOf(Triple("attached", layout, shape), Triple("folded", folded(), shape), Triple("unattached", layout.copy(cards = emptyMap(), frames = emptyList(), links = emptyList()), empty))
        for (light in listOf(false, true)) for ((name, view, facts) in stages) SwingUtilities.invokeAndWait {
            val colors = if (light) MachineSignalColors.Light else MachineSignalColors.Board
            val anchored = resolver(view, facts, saved)
            val scene = ImageComposeScene(820, 590, coroutineContext = Dispatchers.Unconfined) {
                val measurer = rememberTextMeasurer()
                val painter = CapturePainter(measurer, colors)
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(colors.canvas)
                    for (frame in view.frames) drawRect(colors.line, Offset(frame.x.toFloat(), frame.y.toFloat()), Size(frame.width.toFloat(), frame.height.toFloat()), style = Stroke(MachineSignal.Stroke.hairline.value))
                    for (card in view.cards.values) {
                        drawRect(colors.surfaceAlt, Offset(card.x.toFloat(), card.y.toFloat()), Size(card.width.toFloat(), card.height.toFloat()))
                        val title = facts.nodes.singleOrNull { it.id == card.id }?.label ?: "Circles island · 2 nodes"
                        drawText(measurer.measure(title, MachineSignal.Type.base.copy(fontSize = MachineSignal.Type.body, color = colors.textStrong)), topLeft = Offset(card.x.toFloat() + MachineSignal.Space.s3.value, card.y.toFloat() + MachineSignal.Space.s3.value))
                    }
                    with(painter) { paint(saved.captures, 1.0, CapturePainter.Level.Detail, placement = anchored) }
                }
            }
            try {
                repeat(3) { scene.render((it + 1L) * 16_666_667L).close() }
                scene.render(100_000_000L).use { image -> File(output, "anchor-${if (light) "light" else "dark"}-$name.png").writeBytes(requireNotNull(image.encodeToData()).bytes) }
            } finally { scene.close() }
            for (value in saved.captures) assertNotNull(CaptureHit.find(saved.captures, CaptureHit.geometry(value, anchored::resolve).center, 1.0, resolve = anchored::resolve))
        }
    }
}
