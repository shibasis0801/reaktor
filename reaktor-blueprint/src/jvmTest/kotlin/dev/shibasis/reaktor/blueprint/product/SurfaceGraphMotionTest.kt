@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.blueprint.Blueprint
import dev.shibasis.reaktor.blueprint.BlueprintFrameLook
import dev.shibasis.reaktor.blueprint.BlueprintGraph
import dev.shibasis.reaktor.blueprint.BlueprintCanvas
import dev.shibasis.reaktor.blueprint.BlueprintCardLook
import dev.shibasis.reaktor.blueprint.BlueprintLayout
import dev.shibasis.reaktor.blueprint.Card
import dev.shibasis.reaktor.blueprint.Frame
import dev.shibasis.reaktor.ui.machinesignal.*
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SurfaceGraphMotionTest {
    @Test
    fun readOnlyBlueprintHooksKeepRowIdentityWithoutClickNavigationOrChangingPlacement() = runBlocking(Dispatchers.Swing) {
        val nodes = listOf(SurfaceGraphNode("source", "Source", "Book", scope = "group", rows = listOf(SurfaceGraphRow("unit", "Unit", "unit-key", aliases = listOf("unit-alias")))))
        val appearance = SurfaceGraphAppearance(Color.White, Color.White, Color.Black, Color.Gray, Color.Gray, Color.Blue)
        val geometry = dev.shibasis.reaktor.blueprint.Card("source", 80.0, 120.0, 272.0, 130.0, nodes.single().pins(), 0)
        val group = dev.shibasis.reaktor.blueprint.Frame("group", "Source group", 40.0, 60.0, 352.0, 210.0, setOf("source"), emptyList(), false)
        val layout = dev.shibasis.reaktor.blueprint.BlueprintLayout(Unit, mapOf("source" to geometry), listOf(group), emptyList(), 392.0, 270.0)
        val model = SurfaceGraphSimple(nodes, emptyList(), appearance, groups = listOf(SurfaceGraphGroup("group", "Source group", owner = "source-owner")), initialLayout = layout)
        val editor = model.editor
        val canvas = GraphCanvasState()
        val pins = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val framesClicked = mutableListOf<String>()
        val framesOpened = mutableListOf<String>()
        var cleared = 0
        val scene = ImageComposeScene(960, 640, Density(1f), coroutineContext = Dispatchers.Unconfined) {
            BlueprintGraph(editor, canvas, { model.look(it.id, appearance) }, { BlueprintFrameLook(SurfaceGraphSymbol, "") },
                { Blueprint.link(Color.Gray, highlighting = false, lit = false) }, { null }, Modifier.fillMaxSize(),
                onPinClick = { _, pin -> pins += pin.id }, onCardOpen = { opened += it },
                onPaneClick = { cleared++ }, editable = false,
                onFrameClick = { framesClicked += it.key }, onFrameOpen = { framesOpened += it.key })
        }
        var time = 0L
        suspend fun frame() {
            Snapshot.sendApplyNotifications()
            time += 16_666_667L
            scene.render(time).close()
            delay(10)
        }
        fun click(point: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
        }
        try {
            repeat(30) { frame() }
            val card = editor.layout.cards.getValue("source")
            canvas.look(card.x + card.width / 2, card.y + card.height / 2, 2.0)
            repeat(20) { frame() }
            val before = editor.layout.cards
            val shape = editor.shape
            val view = canvas.viewport()
            val pin = card.pins.first { it.id == "unit" && !it.provides }
            click(Offset((view.x + (card.x + card.width / 4) * view.zoom).toFloat(), (view.y + (card.y + pin.y) * view.zoom).toFloat()))
            repeat(5) { frame() }
            assertEquals(listOf("unit"), pins)
            assertEquals(view, canvas.viewport())
            click(Offset((view.x + (card.x + card.width / 2) * view.zoom).toFloat(), (view.y + (card.y + 12) * view.zoom).toFloat()))
            repeat(5) { frame() }
            assertEquals(setOf("source"), editor.selection.nodes)
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyUp))
            repeat(5) { frame() }
            assertTrue(opened.isEmpty(), "Enter does not navigate into a map card")
            assertEquals(view, canvas.viewport())
            val group = editor.layout.frames.single { it.key == "group" }
            val header = Offset((view.x + (group.x + group.width / 2) * view.zoom).toFloat(), (view.y + (group.y + 12) * view.zoom).toFloat())
            click(header)
            repeat(5) { frame() }
            assertEquals(listOf("group"), framesClicked)
            assertEquals(view, canvas.viewport())
            delay(350)
            click(header)
            click(header)
            repeat(30) { frame() }
            assertTrue(framesOpened.isEmpty(), "Double-click does not navigate into a map frame")
            val zoomed = canvas.viewport()
            assertTrue(zoomed.zoom > view.zoom, "Double-click zooms the same map")
            click(Offset(930f, 610f))
            repeat(5) { frame() }
            assertEquals(1, cleared)
            assertEquals(zoomed, canvas.viewport())
            assertEquals(before, editor.layout.cards)
            assertEquals(shape, editor.shape)
        } finally { scene.close() }
    }

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
                    assertEquals(plain.getRGB(cx, cy), chosen.getRGB(cx, cy), "selection keeps the shared MachineSignal card body unchanged")
                    assertNotEquals(plain.getRGB(gx, gy), chosen.getRGB(cx, cy), "the card body is distinct from the surrounding canvas")
                    assertNotEquals(plain.getRGB(gx, gy), chosen.getRGB(gx, gy), "the selection glow is painted from the card mesh")
                } finally { scene.close() }
            }.exceptionOrNull()
        }
        failure?.let { throw it }
    }

    @Test
    fun smallGraphSelectsPansAndZoomsThroughTheEngineWithoutRecomposing() = runBlocking(Dispatchers.Swing) {
        val nodes = Counted((0 until 9).map { n -> SurfaceGraphNode("s$n", "Source $n", "Book · ${n + 4} units") })
        val links = nodes.zipWithNext().mapIndexed { i, (a, b) -> SurfaceGraphLink("link$i", a.id, b.id, proposed = i % 5 == 0) }
        val forest = Color(0xFF286551)
        val appearance = SurfaceGraphAppearance(Color(0xFFF6F5EF), Color.White, Color(0xFF203D35), Color(0xFF718078), Color(0xFFD8E0D7), forest)
        var selected by mutableStateOf<String?>(null)
        val scene = ImageComposeScene(960, 640, Density(1f), coroutineContext = Dispatchers.Unconfined) {
            SurfaceGraphCanvas(nodes, links, selected, appearance, onSelect = { selected = it }, modifier = Modifier.fillMaxSize(), sceneKey = "motion")
        }
        var time = 0L
        suspend fun frame(): Double {
            val start = System.nanoTime()
            Snapshot.sendApplyNotifications()
            time += 8_333_333L
            scene.render(time).close()
            val elapsed = (System.nanoTime() - start) / 1e6
            delay(10)
            return elapsed
        }
        fun pixels() = scene.render(time).use { image -> requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { ImageIO.read(ByteArrayInputStream(it.bytes)) } }
        fun changed(a: java.awt.image.BufferedImage, b: java.awt.image.BufferedImage) = (0 until a.height).sumOf { y -> (0 until a.width).count { x -> a.getRGB(x, y) != b.getRGB(x, y) } }
        fun cardBounds(): Map<String, androidx.compose.ui.geometry.Rect> {
            fun flatten(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            return scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }.mapNotNull { node ->
                node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.takeIf { it.startsWith("surface-card:") }?.let { it to node.boundsInRoot }
            }.toMap()
        }
        val pressed = PointerButtons(isPrimaryPressed = true)
        try {
            repeat(30) { frame() }
            val plain = pixels()
            val before = cardBounds()
            val card = before.getValue("surface-card:s0").center
            scene.sendPointerEvent(PointerEventType.Press, card, buttons = pressed, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, card, button = PointerButton.Primary)
            repeat(10) { frame() }
            assertNotEquals(null, selected, "tapping a card selects it")
            val chosen = pixels()
            assertTrue(changed(chosen, plain) > 100, "the selected card is outlined from its layer")
            assertEquals(before, cardBounds(), "selection keeps every card and anchor in place")
            val composed = nodes.compared
            val chosenId = selected
            scene.sendKeyEvent(KeyEvent(Key.Spacebar, KeyEventType.KeyDown))
            scene.sendPointerEvent(PointerEventType.Press, Offset(940f, 620f), buttons = pressed, button = PointerButton.Primary)
            val pan = Motion("pan", (1..60).map { scene.sendPointerEvent(PointerEventType.Move, Offset(940f - it * 4, 620f - it * 2), buttons = pressed); frame() })
            scene.sendPointerEvent(PointerEventType.Release, Offset(700f, 500f), button = PointerButton.Primary)
            scene.sendKeyEvent(KeyEvent(Key.Spacebar, KeyEventType.KeyUp))
            assertEquals(chosenId, selected, "panning keeps the selected card")
            val moved = pixels()
            val zoom = Motion("zoom", (1..60).map { scene.sendPointerEvent(PointerEventType.Scroll, Offset(480f, 320f), scrollDelta = Offset(0f, if (it <= 30) -1f else 1f), keyboardModifiers = PointerKeyboardModifiers(isCtrlPressed = true)); frame() })
            val idle = Motion("idle", (1..30).map { frame() })
            listOf(pan, zoom, idle).forEach(::println)
            assertEquals(chosenId, selected, "zooming keeps the selected card")
            assertEquals(composed, nodes.compared, "camera motion recomposed the small graph")
            assertTrue(changed(chosen, moved) > 100, "dragging moved the camera")
        } finally { scene.close() }
    }
}
