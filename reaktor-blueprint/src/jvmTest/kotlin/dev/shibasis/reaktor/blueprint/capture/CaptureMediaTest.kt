@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.capture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as BitmapCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.shibasis.reaktor.blueprint.editor.EditReceipt
import dev.shibasis.reaktor.blueprint.editor.EditRevision
import dev.shibasis.reaktor.blueprint.editor.GraphEditor
import dev.shibasis.reaktor.blueprint.editor.Placement
import dev.shibasis.reaktor.blueprint.editor.Spot
import dev.shibasis.reaktor.blueprint.editor.Verdict
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CaptureMediaTest {
    private val designer = CaptureAuthor("(codex, xhigh, sol 6.1, designer)", "bpo-designer", 3)
    private val lead = CaptureAuthor("(claude, max, opus, architect)", "bpo-lead", 1)
    private val anchor = CaptureAnchor.Node("Circles", Spot(20.0, 30.0), Spot(40.0, 60.0), "Circles")
    private var serial = 0
    private fun tools(placement: Placement, author: CaptureAuthor = designer) = CaptureTools(placement, { "media-${++serial}" }, author)

    @Test fun imagesAndTwoAuthorRepliesSaveReopenAndUndoWithoutWritingFacts(): Unit = runBlocking {
        val shape = GraphShape(emptyList(), emptyList(), emptyList(), emptyList())
        val editor = GraphEditor(shape, Placement(cards = mapOf("Circles" to Spot(20.0, 30.0))))
        editor.editRevision = EditRevision("source", "presentation")
        editor.permits = { _, _ -> true }
        editor.validate = { Verdict(true, "") }
        var saved = editor.placement
        editor.savePlacement = { layout, expected -> saved = layout; EditReceipt.Accepted(expected.copy(presentation = "saved")) }
        editor.commit = { _, _ -> error("Capture media must never write typed facts") }
        val first = tools(editor.placement)
        first.insertImage(CaptureAsset("shot", "presentation/assets/graph.png", "image/png"), Spot(40.0, 60.0), 320.0, 160.0, anchor, "Circles graph")
        assertIs<EditReceipt.Accepted>(editor.propose(first.finish()!!))
        first.sync(editor.placement)
        first.select(CaptureTool.Comment)
        first.begin(Spot(400.0, 60.0))
        val root = first.selected!!
        first.setText(root, "Check whole titles at Fit before launch.")
        assertIs<EditReceipt.Accepted>(editor.propose(first.finish()!!))
        val second = tools(editor.placement, lead)
        val reply = second.reply(root, "The retained Fit capture is ready for review.")
        assertIs<EditReceipt.Accepted>(editor.propose(second.finish()!!))
        val reopened = Json.decodeFromString(Placement.serializer(), Json.encodeToString(Placement.serializer(), saved))
        assertEquals(editor.placement, reopened)
        val comments = reopened.captures.filterIsInstance<Capture.Comment>()
        assertEquals(listOf(designer, lead), comments.map { it.author })
        assertEquals(root, comments.single { it.id == reply }.replyTo)
        assertEquals("presentation/assets/graph.png", reopened.assets.getValue("shot").reference)
        assertEquals(shape, editor.shape)
        editor.undo()
        assertFalse(editor.placement.captures.any { it.id == reply })
        assertTrue(editor.placement.captures.any { it.id == root })
        editor.redo()
        assertEquals(reopened, editor.placement)
        assertEquals(shape, editor.shape)
    }

    @Test fun repliesStayAnchoredAndReserveNonOverlappingSpace() {
        val root = Capture.Comment("question", "Check Circles invitation links.", anchor, designer)
        val tools = tools(Placement(capture = listOf(root)), lead)
        val first = tools.reply(root.id, "The send link is covered.")
        val second = tools.reply(root.id, "The expired invitation needs a receipt.")
        val comments = tools.preview.filterIsInstance<Capture.Comment>()
        val boxes = comments.map { CaptureHit.geometry(it).bounds }
        assertTrue(boxes.zipWithNext().all { (a, b) -> a.bottom < b.top })
        assertEquals(listOf(root.id, root.id), comments.drop(1).map { it.replyTo })
        for (comment in comments) {
            assertIs<CaptureAnchor.Node>(comment.anchor)
            val moved = CaptureHit.geometry(comment, { it.fallbackSpot().let { p -> Spot(p.x + 500.0, p.y - 100.0) } })
            val original = CaptureHit.geometry(comment)
            assertEquals(original.bounds.left + 500, moved.bounds.left)
            assertEquals(original.bounds.top - 100, moved.bounds.top)
        }
        assertNotNull(CaptureHit.find(comments, CaptureHit.geometry(comments.single { it.id == second }).center, 1.0))
        assertFailsWith<IllegalArgumentException> { tools.reply("missing", "A reply") }
        assertFailsWith<IllegalArgumentException> { tools.reply(first, " ") }
        tools.cancel()
        assertEquals(listOf(root), tools.preview)
    }

    @Test fun imagesKeepReferencesAndReservedSizesBeforeTheirBytesArrive() {
        val tools = tools(Placement())
        val asset = CaptureAsset("shot", "assets/launch.webp", "image/webp")
        val first = tools.insertImage(asset, Spot(40.0, 60.0), 320.0, 160.0, anchor, "Launch plan")
        tools.insertImage(asset, Spot(400.0, 60.0), 160.0, 80.0)
        tools.crop(first, CaptureCrop(.25, 0.0, .75, 1.0))
        val part = tools.finish()!!
        val placement = assertNotNull(part.layout)
        assertTrue(part.changes.isEmpty())
        assertEquals(mapOf(asset.id to asset), placement.assets)
        val image = placement.captures.filterIsInstance<Capture.Image>().first()
        assertEquals(CaptureHit.Bounds(40.0, 60.0, 320.0, 160.0), CaptureHit.geometry(image).bounds)
        assertEquals(CaptureCrop(.25, 0.0, .75, 1.0), image.crop)
        assertFailsWith<IllegalArgumentException> { tools.insertImage(asset.copy(mediaType = "text/html"), Spot(0.0, 0.0), 10.0, 10.0) }
        val loaded = tools(placement)
        assertFailsWith<IllegalArgumentException> { loaded.insertImage(asset.copy(reference = "other.png"), Spot(0.0, 0.0), 10.0, 10.0) }
    }

    @Test fun imagesAndThreadsPaintAtEveryLevelInBothThemesWithoutChangingGeometry() {
        val output = File("build/reports/blueprint-capture-media").apply { mkdirs() }
        val root = Capture.Comment("question", "Check whole titles at Fit before launch.", CaptureAnchor.World(Spot(420.0, 70.0)), designer)
        val tools = tools(Placement(capture = listOf(root)), lead)
        tools.reply(root.id, "The launch capture is ready for review.")
        val captures = listOf(
            Capture.Image("ready", "ready", 320.0, 160.0, CaptureAnchor.World(Spot(40.0, 70.0)), designer, "Circles graph"),
            Capture.Image("missing", "missing", 320.0, 160.0, CaptureAnchor.World(Spot(40.0, 310.0)), designer, "Launch screenshot"),
        ) + tools.preview
        val geometry = captures.map { CaptureHit.geometry(it) }
        val bitmap = ImageBitmap(320, 160).also { image -> CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, BitmapCanvas(image), Size(320f, 160f)) {
            val colors = MachineSignalColors.Board
            drawRect(colors.canvas)
            drawLine(colors.controlAccent, Offset(90f, 80f), Offset(230f, 80f), 2f)
            drawRect(colors.surfaceAlt, Offset(20f, 40f), Size(100f, 80f))
            drawRect(colors.surfaceAlt, Offset(200f, 40f), Size(100f, 80f))
        } }
        for (light in listOf(false, true)) for (level in CapturePainter.Level.entries) {
            var requests = 0
            SwingUtilities.invokeAndWait {
                val colors = if (light) MachineSignalColors.Light else MachineSignalColors.Board
                val scene = ImageComposeScene(820, 540, coroutineContext = Dispatchers.Unconfined) {
                    val painter = CapturePainter(rememberTextMeasurer(), colors)
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(colors.canvas)
                        with(painter) { paint(captures, 1.0, level, image = { requests++; if (it == "ready") bitmap else null }) }
                    }
                }
                try {
                    repeat(3) { scene.render((it + 1L) * 16_666_667L).close() }
                    scene.render(100_000_000L).use { rendered -> File(output, "media-${if (light) "light" else "dark"}-${level.name.lowercase()}.png").writeBytes(requireNotNull(rendered.encodeToData()).bytes) }
                } finally { scene.close() }
            }
            assertEquals(level != CapturePainter.Level.Overview, requests > 0)
            assertEquals(geometry, captures.map { CaptureHit.geometry(it) })
        }
    }
}
