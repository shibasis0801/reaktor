@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Density
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphEngine
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphPen
import kotlinx.coroutines.Dispatchers
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class SurfaceGraphBudgetTest {
    @Test fun slowStationaryFrameFinishesDirtyMeshesThenStopsRequestingPaints() {
        lateinit var engine: BudgetEngine
        lateinit var scene: ImageComposeScene
        val tick = mutableIntStateOf(0)
        var time = 0L
        fun edt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
        fun frame(): Color {
            var pixel = Color.Transparent
            edt {
                tick.intValue++
                Snapshot.sendApplyNotifications()
                scene.render(++time * 16_666_667L).use { image ->
                    pixel = image.toComposeImageBitmap().toPixelMap()[40, 40]
                }
            }
            return pixel
        }
        edt {
            scene = ImageComposeScene(180, 120, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                val graphics = LocalGraphicsContext.current
                engine = remember(graphics) { BudgetEngine(graphics) }
                Canvas(Modifier.fillMaxSize()) { tick.intValue; engine.draw(this) }
            }
        }
        try {
            assertEquals(Color.Red, frame())
            engine.drain()
            Thread.sleep(150) // Camera settling is a real 120ms engine contract.
            assertEquals(Color.Red, frame())
            assertFalse(engine.drain(), "A clean slow frame must become idle")

            edt { engine.repaint(Color.Blue); engine.view = Viewport(x = 1.0) }
            val painted = engine.faces
            assertEquals(Color.Red, frame(), "Moving keeps the existing small-budget deferral")
            assertEquals(painted, engine.faces)
            assertTrue(engine.drain(), "Deferred dirty geometry must request completion")
            Thread.sleep(150)

            assertEquals(Color.Blue, frame(), "Stationary geometry must finish even with a 500ms elapsed budget")
            assertEquals(painted + 1, engine.faces)
            assertFalse(engine.meshesDirty)
            engine.drain()
            repeat(3) {
                assertEquals(Color.Blue, frame())
                assertFalse(engine.drain(), "Completed meshes must not create an idle paint loop")
            }
        } finally {
            edt { engine.release(); scene.close() }
        }
    }

    @Test fun coldWorldCardDrawsItsDetailOnTheFirstPanFrame() {
        lateinit var scene: ImageComposeScene
        lateinit var engine: BudgetEngine
        var time = 0L
        fun edt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
        fun frame(): Color {
            var pixel = Color.Transparent
            edt {
                Snapshot.sendApplyNotifications()
                scene.render(++time * 16_666_667L).use { image ->
                    pixel = image.toComposeImageBitmap().toPixelMap()[40, 40]
                }
            }
            return pixel
        }
        edt {
            scene = ImageComposeScene(180, 120, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                val graphics = LocalGraphicsContext.current
                engine = remember(graphics) { BudgetEngine(graphics, worldPictures = true) }
                Canvas(Modifier.fillMaxSize()) { engine.draw(this) }
            }
        }
        try {
            assertEquals(Color.Red, frame())
            edt { engine.detailRecords.clear(); engine.view = Viewport(x = -1100.0) }
            assertEquals(Color.Blue, frame(), "A cold card revealed by panning must not flash a placeholder")
            assertEquals(listOf(1), engine.detailRecords, "Only the newly visible picture needs immediate recording")
            repeat(4) { assertEquals(Color.Blue, frame()) }
        } finally {
            edt { engine.release(); scene.close() }
        }
    }

    private class BudgetEngine(graphics: GraphicsContext, override val worldPictures: Boolean = false) : SurfaceGraphEngine(graphics, scaled = true) {
        var view by mutableStateOf(Viewport())
        val detailRecords = mutableListOf<Int>()
        var faces = 0
        private var tone = Color.Red
        val meshesDirty get() = dirty.any { it }
        override val warmups = 0
        override val warmable = false
        override val canvas = Color.White
        override val cardRadius = 0f
        init {
            index(emptyList(), emptyList(), if (worldPictures) floatArrayOf(20f, 20f, 120f, 80f, 1120f, 20f, 1220f, 80f)
                else floatArrayOf(20f, 20f, 120f, 80f))
            if (worldPictures) remap(intArrayOf(-1, -1))
            level = if (worldPictures) 2 else 0
        }
        fun draw(scope: DrawScope) = with(scope) { frame(view, Size(180f, 120f), 1f, elapsed = 500.0) }
        fun drain(): Boolean { var found = false; while (signal.tryReceive().isSuccess) found = true; return found }
        fun repaint(color: Color) { tone = color; dirty.fill(true) }
        override fun face(i: Int, x0: Float, y0: Float, x1: Float, y1: Float, z: Float, fringe: Float, tolerance: Float) {
            faces++
            stroker.roundRect(x0, y0, x1, y1, 0f, tolerance)
            stroker.fill(solid, fringe, tone.toArgb(), minOf(x1 - x0, y1 - y0))
        }
        override fun pen(i: Int, pen: SurfaceGraphPen) = false
        override fun body(i: Int, x0: Float, y0: Float, x1: Float, y1: Float, z: Float, fringe: Float, tolerance: Float) {}
        override fun current(i: Int, slot: Int) = !worldPictures || layers[i * 2 + slot] != null
        override fun record(i: Int, slot: Int, deadline: TimeSource.Monotonic.ValueTimeMark?) {
            check(worldPictures) { "Overview must not record detail" }
            if (deadline != null) return // This fixture has no spare budget for recording.
            detailRecords += i
            tile(i, slot, 20.0 + 1100 * i, 20.0, 100.0, 60.0) { _, _ -> drawRect(if (i == 0) Color.Red else Color.Blue) }
        }
        override fun DrawScope.placeholder(i: Int, slot: Int) {}
        override fun DrawScope.warm(step: Int) = error("This fixture has no optional warmups")
    }
}
