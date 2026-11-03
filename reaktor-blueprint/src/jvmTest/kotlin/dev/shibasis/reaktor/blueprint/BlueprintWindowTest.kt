@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalGraphicsContext
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphEngine
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphPen
import kotlinx.coroutines.Dispatchers
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class BlueprintWindowTest {
    private class Probe(graphics: GraphicsContext, count: Int, slot: Int) : SurfaceGraphEngine(graphics, false) {
        override val warmups = 0
        override val warmable = false
        override val canvas = Color.Black
        override val cardRadius = 0f
        override fun pen(i: Int, pen: SurfaceGraphPen) = false
        override fun body(i: Int, x0: Float, y0: Float, x1: Float, y1: Float, z: Float, fringe: Float, tolerance: Float) {}
        override fun current(i: Int, slot: Int) = false
        override fun record(i: Int, slot: Int, deadline: TimeSource.Monotonic.ValueTimeMark?) {}
        override fun DrawScope.placeholder(i: Int, slot: Int) = drawRect(Color.Green, Offset(cardBox[i * 4], cardBox[i * 4 + 1]), Size(80f, 80f))
        override fun DrawScope.warm(step: Int) = false
        init {
            layers = arrayOfNulls(count * 2)
            index(emptyList(), emptyList(), FloatArray(count * 4).also { boxes -> repeat(count) { i ->
                boxes[i * 4] = 20f + i * 120; boxes[i * 4 + 1] = 20f; boxes[i * 4 + 2] = 100f + i * 120; boxes[i * 4 + 3] = 100f
            } })
            repeat(count) { i -> tile(i, slot, 20.0 + i * 120, 20.0, 80.0, 80.0) { x, y -> drawRect(Color.Red, Offset(x, y), Size(80f, 80f)) } }
            level = 1
        }
        fun draw(scope: DrawScope) = with(scope) { frame(Viewport(0.0, 0.0, 1.0), size, 1f) }
        fun retained() = layers.indices.count { it % 2 == 0 && (layers[it] != null || layers[it + 1] != null) }
    }

    private fun check(slot: Int, count: Int, inspect: (Probe, Int) -> Unit) {
        SwingUtilities.invokeAndWait {
            lateinit var probe: Probe
            val scene = ImageComposeScene(200, 200, coroutineContext = Dispatchers.Unconfined) {
                val graphics = LocalGraphicsContext.current
                val painter = androidx.compose.runtime.remember { Probe(graphics, count, slot).also { probe = it } }
                DisposableEffect(painter) { onDispose { painter.release() } }
                Canvas(Modifier.fillMaxSize()) { painter.draw(this) }
            }
            try { scene.render(16_666_667L).use { image -> inspect(probe, image.encodeToData()!!.use { data -> javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(data.bytes)).getRGB(60, 60) }) } }
            finally { scene.close() }
        }
    }

    @Test fun missingOverviewLayerDoesNotBorrowDetail() = check(1, 1) { _, pixel -> assertEquals(Color.Green.toArgb(), pixel) }
    @Test fun outdatedSameSlotDoesNotReadAsCurrent() = check(0, 1) { _, pixel -> assertEquals(Color.Green.toArgb(), pixel) }
    @Test fun farLayersAreReleasedEvenWhenFewerThanSixHundredExist() = check(0, 200) { probe, _ -> assertTrue(probe.retained() in 1..4) }
}
