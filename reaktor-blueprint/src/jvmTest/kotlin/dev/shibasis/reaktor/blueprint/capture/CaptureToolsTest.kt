package dev.shibasis.reaktor.blueprint.capture

import dev.shibasis.reaktor.blueprint.editor.EditReceipt
import dev.shibasis.reaktor.blueprint.editor.EditRevision
import dev.shibasis.reaktor.blueprint.editor.GraphEditor
import dev.shibasis.reaktor.blueprint.editor.GraphPart
import dev.shibasis.reaktor.blueprint.editor.Placement
import dev.shibasis.reaktor.blueprint.editor.Spot
import dev.shibasis.reaktor.blueprint.editor.Verdict
import dev.shibasis.reaktor.blueprint.editor.with
import dev.shibasis.reaktor.graph.core.GraphShape
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureToolsTest {
    private val author = CaptureAuthor("(codex, xhigh, sol 6.1, designer)", "bpo-designer", 3)
    private val start = Spot(100.0, 200.0)
    private val end = Spot(40.0, 120.0)
    private val anchor = CaptureAnchor.Node("Circles", Spot(10.0, 20.0), start, "Circles")
    private var serial = 0
    private fun tools(initial: Placement = Placement(), resolve: (CaptureAnchor) -> Spot = CaptureAnchor::fallbackSpot) = CaptureTools(initial, { "capture-${++serial}" }, author, resolve = resolve)

    @Test fun theCaptureCatalogueHasEveryCreationToolAndCannotOfferATopologyConnection() {
        val choices = CaptureCatalogue.templates
        assertEquals(CaptureTool.entries.toSet() - setOf(CaptureTool.Select, CaptureTool.Eraser), choices.map { it.captureTool }.toSet())
        assertEquals(choices.size, choices.map { it.key }.distinct().size)
        assertTrue(choices.all { it.category == "Capture" && it.label.isNotBlank() && it.ports.isEmpty() && it.part == null })
        val pin = dev.shibasis.reaktor.graph.core.PortShape("send", "Message", true, dev.shibasis.reaktor.graph.core.PortWiring.Unused)
        assertTrue(choices.all { it.offers(null) && !it.offers(pin) })
    }

    @Test fun catalogueChoicesProduceOnlyUndoableCaptureValuesAtTheChosenWorldPoint() {
        for (choice in CaptureCatalogue.templates.filter { it.captureTool != CaptureTool.Image }) {
            val placement = Placement(cards = mapOf("Circles" to start))
            val controller = tools(placement)
            controller.select(choice.captureTool!!)
            assertTrue(controller.begin(start))
            controller.update(end)
            controller.setText(controller.selected!!, "Review this connection")
            val part = assertNotNull(controller.finish())
            assertTrue(part.changes.isEmpty())
            assertTrue(part.definitions.isEmpty())
            val next = placement.with(part.layout!!)
            assertEquals(placement.cards, next.cards)
            assertEquals(1, next.captures.size)
            assertEquals(author, next.captures.single().author)
            assertTrue(placement.captures.isEmpty())
        }
    }

    @Test fun reverseDragsKeepRealAnchorsAndNeverChangeTheOriginalPlacement() {
        val origin = Placement(cards = mapOf("Circles" to start), folded = setOf("Messages"))
        for (tool in listOf(CaptureTool.Box, CaptureTool.Diamond, CaptureTool.Ellipse, CaptureTool.Frame, CaptureTool.Note)) {
            val tools = tools(origin)
            tools.select(tool)
            assertTrue(tools.begin(start, anchor))
            tools.update(end)
            tools.setText(tools.selected!!, "Check the circle invitation")
            assertEquals(emptyList(), origin.capture)
            val part = assertNotNull(tools.finish())
            assertTrue(part.changes.isEmpty())
            assertTrue(part.definitions.isEmpty())
            val capture = part.layout!!.capture.single()
            assertEquals(author, capture.author)
            assertEquals(anchor.copy(offset = Spot(-50.0, -60.0), fallback = end), capture.anchor)
            val geometry = CaptureHit.geometry(capture)
            assertEquals(60.0, geometry.bounds.width)
            assertEquals(80.0, geometry.bounds.height)
            assertEquals(origin.cards, origin.with(part.layout!!).cards)
            assertEquals(origin.folded, origin.with(part.layout!!).folded)
            assertEquals(CaptureTool.Select, tools.tool)
        }
    }

    @Test fun pencilPointsAreRelativeButLineEndsKeepTheirOwnAnchors() {
        val tools = tools()
        tools.select(CaptureTool.Pencil, lock = true)
        tools.begin(start, anchor)
        tools.update(end)
        tools.update(end)
        val pencil = assertIs<Capture.Freehand>(tools.finish()!!.layout!!.capture.single())
        assertEquals(listOf(Spot(0.0, 0.0), Spot(-60.0, -80.0)), pencil.points)
        assertEquals(listOf(start, end), CaptureHit.geometry(pencil).points)
        assertEquals(CaptureTool.Pencil, tools.tool)
        for (tool in listOf(CaptureTool.Line, CaptureTool.Arrow)) {
            tools.select(tool)
            tools.begin(start, anchor)
            val target = CaptureAnchor.Port("Messages", "send", Spot(0.0, 0.0), end, "Send")
            tools.update(end, target)
            val capture = tools.finish()!!.layout!!.capture.single()
            assertEquals(listOf(start, end), CaptureHit.geometry(capture).points)
            assertEquals(target, when (capture) { is Capture.Line -> capture.end; is Capture.Arrow -> capture.end; else -> error("Expected a line") })
        }
    }

    @Test fun selectionMovesBothEndsAndBendsWithoutLosingTheirLinks() {
        val line = Capture.Arrow("arrow", anchor, CaptureAnchor.World(end), listOf(Spot(70.0, 240.0)), author)
        val tools = tools(Placement(capture = listOf(line)))
        tools.begin(start, hit = CaptureHit.Result(line.id, CaptureHit.Handle.Body))
        tools.update(Spot(120.0, 230.0))
        val moved = assertIs<Capture.Arrow>(tools.finish()!!.layout!!.capture.single())
        assertEquals(anchor.copy(offset = Spot(30.0, 50.0), fallback = Spot(120.0, 230.0)), moved.anchor)
        assertEquals(CaptureAnchor.World(Spot(60.0, 150.0)), moved.end)
        assertEquals(listOf(Spot(90.0, 270.0)), moved.bends)
    }

    @Test fun resizeAndEndpointHandlesEditOnlyThePickedCapture() {
        val box = Capture.Box("box", 120.0, 80.0, CaptureAnchor.World(start))
        val tools = tools(Placement(capture = listOf(box)))
        tools.begin(start, hit = CaptureHit.Result(box.id, CaptureHit.Handle.NorthWest))
        tools.update(Spot(80.0, 180.0))
        val resized = assertIs<Capture.Box>(tools.finish()!!.layout!!.capture.single())
        assertEquals(140.0, resized.width)
        assertEquals(100.0, resized.height)
        assertEquals(CaptureAnchor.World(Spot(80.0, 180.0)), resized.anchor)
        val line = Capture.Line("line", anchor, CaptureAnchor.World(end))
        tools.sync(Placement(capture = listOf(line)))
        tools.begin(end, hit = CaptureHit.Result(line.id, CaptureHit.Handle.End))
        val target = CaptureAnchor.Frame("launch", Spot(2.0, 4.0), Spot(500.0, 600.0), "Launch")
        tools.update(Spot(500.0, 600.0), target)
        assertEquals(target, assertIs<Capture.Line>(tools.finish()!!.layout!!.capture.single()).end)
    }

    @Test fun cancelledAndEmptyGesturesLeaveNoLayoutPatch() {
        val tools = tools()
        tools.select(CaptureTool.Box)
        tools.begin(start)
        tools.update(end)
        tools.cancel()
        assertTrue(tools.preview.isEmpty())
        assertNull(tools.finish())
        for (tool in listOf(CaptureTool.Box, CaptureTool.Text, CaptureTool.Arrow, CaptureTool.Pencil, CaptureTool.Note, CaptureTool.Comment)) {
            tools.select(tool)
            tools.begin(start)
            assertNull(tools.finish(), tool.name)
        }
        tools.select(CaptureTool.Note)
        tools.begin(start)
        tools.update(Spot(start.x, start.y + 100))
        tools.setText(tools.selected!!, "A note needs an area")
        assertNull(tools.finish())
        assertFailsWith<IllegalArgumentException> { tools.begin(Spot(Double.NaN, 1.0)) }
    }

    @Test fun textCommentsAndFramesAreEditedWithoutCreatingFacts() {
        for (tool in listOf(CaptureTool.Text, CaptureTool.Comment, CaptureTool.Note, CaptureTool.Frame)) {
            val tools = tools()
            tools.select(tool)
            tools.begin(start)
            if (tool == CaptureTool.Frame) tools.update(end)
            assertTrue(tools.setText(tools.selected!!, "Review launch checks"))
            val part = tools.finish()!!
            assertTrue(part.changes.isEmpty())
            assertEquals(1, part.layout!!.capture.size)
            assertNull(tools.finish())
        }
    }

    @Test fun imagesCropAndStylesSurviveSerializationAndReopen() {
        val tools = tools()
        val asset = CaptureAsset("image", "presentation/assets/screenshot.png", "image/png")
        val id = tools.insertImage(asset, start, 240.0, 160.0, anchor, "Graph pane")
        val crop = CaptureCrop(.1, .2, .8, .9)
        val style = CaptureStyle(0xFF775533, 0xFF223344, CaptureStyle.Weight.Bold)
        assertTrue(tools.crop(id, crop))
        assertTrue(tools.style(id, style))
        val part = tools.finish()!!
        val reopened = Json.decodeFromString(GraphPart.serializer(), Json.encodeToString(GraphPart.serializer(), part))
        assertEquals(part, reopened)
        val image = assertIs<Capture.Image>(reopened.layout!!.capture.single())
        assertEquals(crop, image.crop)
        assertEquals(style, image.style)
        assertEquals(asset, reopened.layout!!.assets[image.asset])
        assertEquals(anchor, image.anchor)
        assertEquals(-1L, CaptureStyle(stroke = -1L).stroke)
        assertFailsWith<IllegalArgumentException> { CaptureCrop(.8, .2, .1, .9) }
        assertFailsWith<IllegalArgumentException> { CaptureCrop(Double.NaN, .2, .8, .9) }
        assertFailsWith<IllegalArgumentException> { tools.insertImage(asset, start, -1.0, 20.0) }
    }

    @Test fun oldSavedCapturesDecodeWithUnchangedDefaultStyleAndNoCrop() {
        val oldNote = """{"type":"note","id":"note","text":"Review launch","width":240.0,"height":120.0,"anchor":{"type":"world","at":{"x":10.0,"y":20.0}}}"""
        val oldImage = """{"type":"image","id":"image","asset":"asset","width":240.0,"height":120.0,"anchor":{"type":"world","at":{"x":10.0,"y":20.0}}}"""
        val note = Json.decodeFromString(Capture.serializer(), oldNote)
        val image = assertIs<Capture.Image>(Json.decodeFromString(Capture.serializer(), oldImage))
        assertEquals(CaptureStyle(), note.style)
        assertEquals(CaptureStyle(), image.style)
        assertNull(image.crop)
        assertFalse("\"style\"" in Json.encodeToString(Capture.serializer(), note))
        assertFalse("\"crop\"" in Json.encodeToString(Capture.serializer(), image))
    }

    @Test fun erasingExistingAndNewCapturesIsOneUndoableRemovalWithoutDeletingSharedAssets() {
        val image = Capture.Image("image", "asset", 100.0, 80.0, anchor)
        val asset = CaptureAsset("asset", "assets/image", "image/png")
        val origin = Placement(capture = listOf(image), assets = mapOf(asset.id to asset))
        val tools = tools(origin)
        tools.select(CaptureTool.Eraser)
        tools.begin(start, hit = CaptureHit.Result(image.id, CaptureHit.Handle.Body))
        val part = tools.finish()!!
        assertEquals(setOf(image.id), part.layout!!.removedCaptures)
        assertEquals(origin.assets, origin.with(part.layout!!).assets)
        assertTrue(origin.with(part.layout!!).captures.isEmpty())
        val new = tools.insertImage(asset, start, 100.0, 80.0)
        tools.erase(new)
        assertNull(tools.finish())
    }

    @Test fun hitToleranceStaysTheSameSizeOnTheScreenAndHandlesWin() {
        val line = Capture.Line("line", CaptureAnchor.World(Spot(0.0, 0.0)), CaptureAnchor.World(Spot(200.0, 0.0)))
        for (zoom in listOf(.1, .5, 1.0, 4.0)) {
            assertEquals("line", CaptureHit.find(listOf(line), Spot(100.0, 5 / zoom), zoom)?.id)
            assertNull(CaptureHit.find(listOf(line), Spot(100.0, 7 / zoom), zoom))
            assertEquals(CaptureHit.Handle.End, CaptureHit.find(listOf(line), Spot(200.0, 0.0), zoom, "line")?.handle)
        }
        assertFailsWith<IllegalArgumentException> { CaptureHit.find(listOf(line), start, 0.0) }
    }

    @Test fun shapesRespectRotationAndFramesDoNotStealTheirInterior() {
        val diamond = Capture.Diamond("diamond", 100.0, 100.0, CaptureAnchor.World(Spot(0.0, 0.0)))
        val ellipse = Capture.Ellipse("ellipse", 100.0, 100.0, diamond.anchor)
        for (shape in listOf(diamond, ellipse)) {
            assertNull(CaptureHit.find(listOf(shape), Spot(1.0, 1.0), 10.0))
            assertEquals(shape.id, CaptureHit.find(listOf(shape), Spot(50.0, 50.0), 10.0)?.id)
        }
        val box = Capture.Box("rotated", 100.0, 20.0, diamond.anchor, angle = PI / 2)
        assertEquals("rotated", CaptureHit.find(listOf(box), Spot(50.0, -35.0), 10.0)?.id)
        assertNull(CaptureHit.find(listOf(box), Spot(5.0, 10.0), 10.0))
        val frame = Capture.Frame("frame", "Launch", 400.0, 300.0, diamond.anchor)
        assertNull(CaptureHit.find(listOf(frame), Spot(200.0, 150.0), 1.0))
        assertEquals("frame", CaptureHit.find(listOf(frame), Spot(200.0, 2.0), 1.0)?.id)
    }

    @Test fun aRejectedProposalKeepsTopologyPresentationAndUndoHistoryUnchanged(): Unit = runBlocking {
        val shape = GraphShape(emptyList(), emptyList(), emptyList(), emptyList())
        val editor = GraphEditor(shape, Placement())
        editor.editRevision = EditRevision("source", "presentation")
        editor.permits = { _, _ -> true }
        editor.validate = { Verdict(true, "") }
        var saves = 0
        editor.savePlacement = { _, _ -> saves++; EditReceipt.Refused("Presentation changed", stale = true) }
        editor.commit = { _, _ -> error("A capture must not write source") }
        val tools = tools(editor.placement)
        tools.select(CaptureTool.Text)
        tools.begin(start, anchor)
        tools.setText(tools.selected!!, "Review source")
        val part = tools.finish()!!
        assertFalse(editor.canUndo)
        assertIs<EditReceipt.Refused>(editor.propose(part))
        assertEquals(1, saves)
        assertEquals(Placement(), editor.placement)
        assertEquals(shape, editor.shape)
        assertFalse(editor.canUndo)
        editor.savePlacement = { _, expected -> EditReceipt.Accepted(expected.copy(presentation = "next")) }
        assertIs<EditReceipt.Accepted>(editor.propose(part))
        tools.sync(editor.placement)
        assertEquals(part.layout!!.capture, tools.preview)
        assertTrue(editor.canUndo)
        editor.undo()
        assertTrue(editor.placement.captures.isEmpty())
        assertTrue(editor.canRedo)
        editor.redo()
        assertEquals(part.layout!!.capture, editor.placement.captures)
        assertEquals(shape, editor.shape)
    }
}
