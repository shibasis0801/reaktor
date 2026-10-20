@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.product

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SurfaceGraphMotionTest {
    private class Counted<T>(private val items: List<T>) : List<T> by items {
        var compared = 0
        override fun equals(other: Any?): Boolean { compared++; return items == other }
        override fun hashCode() = items.hashCode()
    }

    private class Motion(val name: String, val millis: List<Double>) {
        override fun toString(): String {
            val sorted = millis.sorted()
            fun at(q: Double) = sorted[((sorted.size - 1) * q).toInt()]
            return "$name p50=${"%.2f".format(at(.5))} p95=${"%.2f".format(at(.95))} max=${"%.2f".format(sorted.last())} ms (${millis.size} frames)"
        }
    }

    @Test
    fun cameraMotionRepaintsThroughTheEngineWithoutRecomposingTheExplorer() {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            failure = runCatching {
                val nodes = Counted((0 until 12).flatMap { s ->
                    (0 until 30).map { n -> SurfaceGraphNode("n$s-$n", "Card $s.$n", "Book · ${n + 4} units", scope = "scope$s", rows = (0 until n % 5).map { SurfaceGraphRow("n$s-$n-$it", "Unit $it") }) }
                })
                val links = nodes.zipWithNext().filterIndexed { i, _ -> i % 30 != 29 }.mapIndexed { i, (a, b) -> SurfaceGraphLink("link$i", a.id, b.id, proposed = i % 7 == 0) }
                val canvas = Color(0xFFF7F4EC)
                val appearance = SurfaceGraphAppearance(canvas, Color.White, Color(0xFF1F2A24), Color(0xFF6B7A70), Color(0xFFD9D4C7), Color(0xFF2E6B4F), 272.0, 46.0, grid = true)
                val state = SurfaceGraphExplorerState().apply { insetTop = 0.0 }
                var selected by mutableStateOf<String?>(null)
                val scene = ImageComposeScene(1440, 900, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                    SurfaceGraphExplorer(nodes, links, selected, appearance, state, onSelect = {}, modifier = Modifier.fillMaxSize())
                }
                var time = 0L
                fun frame(): Double {
                    val start = System.nanoTime()
                    Snapshot.sendApplyNotifications()
                    time += 8_333_333L
                    scene.render(time).close()
                    return (System.nanoTime() - start) / 1e6
                }
                fun motion(name: String, steps: Int, step: (Int) -> Unit) = Motion(name, (0 until steps).map { step(it); frame() })
                fun pixels() = scene.render(time).use { image -> requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { ImageIO.read(ByteArrayInputStream(it.bytes)) } }
                try {
                    repeat(600) { if (state.showing == null) frame() }
                    state.centre("n5-10", level = .9)
                    repeat(60) { frame() }
                    val composed = nodes.compared
                    val results = listOf(
                        motion("pan", 90) { state.flow.panBy(-6.0, -3.0) },
                        motion("zoom", 90) { state.flow.zoomTo(.9 + it * .002, 720.0, 450.0, .03, 3.0) },
                        motion("zoom out past far", 60) { state.flow.zoomTo(.6 * Math.pow(.97, it.toDouble()), 720.0, 450.0, .03, 3.0) },
                        motion("idle", 60) { },
                    )
                    assertEquals(composed, nodes.compared, "camera motion recomposed the explorer")
                    state.centre("n5-10", level = .9)
                    repeat(60) { frame() }
                    val card = expandedSurfaceGraphPositions(nodes, 272.0, 46.0).getValue("n5-10")
                    val view = state.viewport()
                    val cx = (view.x + (card.x + 136) * view.zoom).toInt(); val cy = (view.y + (card.y + 30) * view.zoom).toInt()
                    val gx = (view.x + (card.x - 4) * view.zoom).toInt(); val gy = (view.y + (card.y + 23) * view.zoom).toInt()
                    val plain = pixels()
                    selected = "n5-10"
                    (results + motion("select", 1) { } + motion("after select", 30) { }).forEach(::println)
                    val chosen = pixels()
                    assertEquals(Color.White.toArgb(), chosen.getRGB(cx, cy), "the selected card body is painted from its layer")
                    assertNotEquals(plain.getRGB(gx, gy), chosen.getRGB(gx, gy), "the selection glow is painted from the card mesh")
                } finally { scene.close() }
            }.exceptionOrNull()
        }
        failure?.let { throw it }
    }

    @Test
    fun smallGraphSelectsPansAndZoomsThroughTheEngineWithoutRecomposing() {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            failure = runCatching {
                val nodes = Counted((0 until 9).map { n -> SurfaceGraphNode("s$n", "Source $n", "Book · ${n + 4} units") })
                val links = nodes.zipWithNext().mapIndexed { i, (a, b) -> SurfaceGraphLink("link$i", a.id, b.id, proposed = i % 5 == 0) }
                val forest = Color(0xFF286551)
                val appearance = SurfaceGraphAppearance(Color(0xFFF6F5EF), Color.White, Color(0xFF203D35), Color(0xFF718078), Color(0xFFD8E0D7), forest)
                var selected by mutableStateOf<String?>(null)
                val scene = ImageComposeScene(960, 640, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                    SurfaceGraphCanvas(nodes, links, selected, appearance, onSelect = { selected = it }, modifier = Modifier.fillMaxSize(), sceneKey = "motion")
                }
                var time = 0L
                fun frame(): Double {
                    val start = System.nanoTime()
                    Snapshot.sendApplyNotifications()
                    time += 8_333_333L
                    scene.render(time).close()
                    return (System.nanoTime() - start) / 1e6
                }
                fun pixels() = scene.render(time).use { image -> requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { ImageIO.read(ByteArrayInputStream(it.bytes)) } }
                fun count(image: java.awt.image.BufferedImage, argb: Int) = (0 until image.height).sumOf { y -> (0 until image.width).count { x -> image.getRGB(x, y) == argb } }
                val pressed = PointerButtons(isPrimaryPressed = true)
                try {
                    repeat(30) { frame() }
                    val plain = pixels()
                    val card = (0 until plain.height).firstNotNullOf { y -> (0 until plain.width).firstOrNull { x -> plain.getRGB(x, y) == Color.White.toArgb() }?.let { Offset(it + 20f, y + 20f) } }
                    scene.sendPointerEvent(PointerEventType.Press, card, buttons = pressed, button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, card, button = PointerButton.Primary)
                    repeat(10) { frame() }
                    assertNotEquals(null, selected, "tapping a card selects it")
                    val chosen = pixels()
                    assertTrue(count(chosen, forest.toArgb()) > count(plain, forest.toArgb()) + 100, "the selected card is outlined from its layer")
                    val composed = nodes.compared
                    scene.sendPointerEvent(PointerEventType.Press, Offset(480f, 320f), buttons = pressed, button = PointerButton.Primary)
                    val pan = Motion("pan", (1..60).map { scene.sendPointerEvent(PointerEventType.Move, Offset(480f - it * 4, 320f - it * 2), buttons = pressed); frame() })
                    scene.sendPointerEvent(PointerEventType.Release, Offset(240f, 200f), button = PointerButton.Primary)
                    val moved = pixels()
                    val zoom = Motion("zoom", (1..60).map { scene.sendPointerEvent(PointerEventType.Scroll, Offset(480f, 320f), scrollDelta = Offset(0f, if (it <= 30) -1f else 1f), keyboardModifiers = PointerKeyboardModifiers(isCtrlPressed = true)); frame() })
                    val idle = Motion("idle", (1..30).map { frame() })
                    listOf(pan, zoom, idle).forEach(::println)
                    assertEquals(composed, nodes.compared, "camera motion recomposed the small graph")
                    assertNotEquals(count(chosen, Color.White.toArgb()), count(moved, Color.White.toArgb()), "dragging moved the camera")
                } finally { scene.close() }
            }.exceptionOrNull()
        }
        failure?.let { throw it }
    }
}
