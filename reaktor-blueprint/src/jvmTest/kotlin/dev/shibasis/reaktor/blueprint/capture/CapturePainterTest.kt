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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.shibasis.reaktor.blueprint.editor.Spot
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalColors
import kotlinx.coroutines.Dispatchers
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CapturePainterTest {
    private val author = CaptureAuthor("(codex, xhigh, sol 6.1, designer)", "bpo-designer", 3)
    private fun world(x: Double, y: Double) = CaptureAnchor.World(Spot(x, y))
    private fun sample() = listOf(
        Capture.Note("note", "Circles\nCheck the invitation path before launch.", 260.0, 130.0, world(40.0, 60.0), author),
        Capture.Text("text", "Launch plan\nCapture stays in the presentation until explicitly converted.", world(340.0, 60.0), author),
        Capture.Comment("comment", "Question: who verifies the native playback receipt?", world(650.0, 60.0), author),
        Capture.Box("box", 120.0, 80.0, world(40.0, 270.0)),
        Capture.Diamond("diamond", 120.0, 80.0, world(210.0, 270.0), angle = .15),
        Capture.Ellipse("ellipse", 120.0, 80.0, world(380.0, 270.0)),
        Capture.Image("image", "screenshot", 200.0, 100.0, world(650.0, 270.0), author, "Local screenshot", crop = CaptureCrop(.5, 0.0, 1.0, 1.0)),
        Capture.Frame("frame", "Launch checks · capture frame", 820.0, 160.0, world(40.0, 440.0), author),
        Capture.Arrow("arrow", world(90.0, 510.0), world(400.0, 540.0), listOf(Spot(230.0, 490.0)), author),
        Capture.Line("line", world(450.0, 520.0), world(600.0, 550.0), author = author),
        Capture.Freehand("pencil", listOf(Spot(0.0, 0.0), Spot(20.0, -30.0), Spot(40.0, -5.0), Spot(60.0, -35.0)), world(720.0, 550.0), author),
    )
    private fun bitmap(): ImageBitmap = ImageBitmap(40, 20).also { image ->
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, BitmapCanvas(image), Size(40f, 20f)) {
            drawRect(Color.Red)
            drawRect(Color.Blue, Offset(20f, 0f), Size(20f, 20f))
        }
    }

    @Test fun everyToolPaintsAtAllThreeLevelsWithoutChangingItsGeometry() {
        val output = File("build/reports/blueprint-capture-tools").apply { mkdirs() }
        val captures = sample()
        val original = captures.map { CaptureHit.geometry(it) }
        val source = bitmap()
        val portrait = ImageBitmap(20, 20).also { image ->
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, BitmapCanvas(image), Size(20f, 20f)) { drawCircle(Color.Blue) }
        }
        val hashes = mutableSetOf<Int>()
        for (light in listOf(false, true)) for (level in CapturePainter.Level.entries) {
            val density = if (light) 2f else 1f
            var faces = 0
            SwingUtilities.invokeAndWait {
                val scene = ImageComposeScene((920 * density).toInt(), (650 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    val painter = CapturePainter(rememberTextMeasurer(), if (light) MachineSignalColors.Light else MachineSignalColors.Board)
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(if (light) MachineSignalColors.Light.canvas else MachineSignalColors.Board.canvas)
                        scale(density, pivot = Offset.Zero) {
                            with(painter) { paint(captures, 1.0, level, selected = "diamond", image = { source }, portrait = { faces++; portrait }) }
                        }
                    }
                }
                try {
                    repeat(4) { scene.render((it + 1L) * 16_666_667L).close() }
                    scene.render(100_000_000L).use { rendered ->
                        val bytes = requireNotNull(rendered.encodeToData()).bytes
                        File(output, "capture-${if (light) "light-2x" else "dark-1x"}-${level.name.lowercase()}.png").writeBytes(bytes)
                        hashes += bytes.contentHashCode()
                        if (level != CapturePainter.Level.Overview) assertEquals(Color.Blue, rendered.toComposeImageBitmap().toPixelMap()[(750 * density).toInt(), (320 * density).toInt()])
                    }
                } finally { scene.close() }
            }
            assertEquals(level == CapturePainter.Level.Detail, faces > 0)
        }
        assertEquals(6, hashes.size)
        assertEquals(original, captures.map { CaptureHit.geometry(it) })
    }

    @Test fun viewportSkipsOffscreenCapturesBeforeResolvingImages() {
        var images = 0
        SwingUtilities.invokeAndWait {
            val scene = ImageComposeScene(300, 200, coroutineContext = Dispatchers.Unconfined) {
                val painter = CapturePainter(rememberTextMeasurer(), MachineSignalColors.Board)
                Canvas(Modifier.fillMaxSize()) { with(painter) {
                    paint(listOf(Capture.Image("offscreen", "image", 100.0, 100.0, world(10000.0, 10000.0))), .25, CapturePainter.Level.Detail,
                        viewport = CaptureHit.Bounds(0.0, 0.0, 300.0, 200.0), image = { images++; null })
                } }
            }
            try { repeat(3) { scene.render((it + 1L) * 16_666_667L).close() } } finally { scene.close() }
        }
        assertEquals(0, images)
    }
}
