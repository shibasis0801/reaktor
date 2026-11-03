package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.reaktor.blueprint.BlueprintCardLook
import dev.shibasis.reaktor.blueprint.CardMarker
import dev.shibasis.reaktor.blueprint.editor.GraphEditor
import dev.shibasis.reaktor.blueprint.editor.Placement
import dev.shibasis.reaktor.blueprint.editor.Request
import dev.shibasis.reaktor.blueprint.editor.Spot
import dev.shibasis.reaktor.blueprint.editor.Target
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.ScopeShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class GraphEditorPointerTest {
    private class InputScene(val width: Int, val density: Float) : AutoCloseable {
        val editor = GraphEditor(GraphShape(listOf(ScopeShape("app", "App")),
            listOf(NodeShape("card", "app", "Card title", "Node", NodeKind.Service)), emptyList(), emptyList()),
            Placement(mapOf("card" to Spot(0.0, 0.0))))
        val state = GraphCanvasState()
        var blur: () -> Unit = {}
        private var frame = 0L
        private val scene = ImageComposeScene((width * density).toInt(), (982 * density).toInt(), Density(density), coroutineContext = Dispatchers.Swing) {
            val manager = LocalFocusManager.current
            SideEffect { blur = { manager.clearFocus(force = true) } }
            GraphView(null, state, { _, _, _ -> true }, { _, _, _ -> true }, editing = editor,
                markers = { focus -> LaunchedEffect(Unit) { focus() } }, draw = { _, _, _ -> })
        }
        val at get() = editor.layout.cards.getValue("card").let { card ->
            Offset(((state.viewport().x + (card.x + card.width * .75) * state.zoom) * density).toFloat(),
                ((state.viewport().y + (card.y + 12) * state.zoom) * density).toFloat())
        }
        suspend fun settle() { repeat(8) { Snapshot.sendApplyNotifications(); frame += 16_666_667L; scene.render(frame).close(); delay(16) } }
        suspend fun start() { settle(); state.restore(220.0, 160.0, .9); settle() }
        fun key(type: KeyEventType) { scene.sendKeyEvent(KeyEvent(Key.Spacebar, type)) }
        fun press(point: Offset) { scene.sendPointerEvent(PointerEventType.Move, point); scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary) }
        fun release(point: Offset) { scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons(), button = PointerButton.Primary) }
        fun move(point: Offset) { scene.sendPointerEvent(PointerEventType.Move, point, buttons = PointerButtons(isPrimaryPressed = true)) }
        fun wheel(point: Offset, control: Boolean = true) { scene.sendPointerEvent(PointerEventType.Scroll, point, scrollDelta = Offset(0f, -1f), keyboardModifiers = PointerKeyboardModifiers(isCtrlPressed = control)) }
        override fun close() = scene.close()
    }

    @Test
    fun unmodifiedWheelPansOverTheCardAtBothDensities() = runBlocking(Dispatchers.Swing) {
        for (density in listOf(1f, 2f)) InputScene(1512, density).use { input ->
            input.start()
            val at = input.at
            val before = input.state.viewport()
            val shape = input.editor.shape
            val placement = input.editor.placement
            val selection = input.editor.selection
            input.wheel(at, control = false)
            input.settle()
            val after = input.state.viewport()
            assertEquals(before.zoom, after.zoom)
            assertEquals(before.x, after.x, 1e-4)
            assertTrue(after.y > before.y)
            assertEquals(shape, input.editor.shape)
            assertEquals(placement, input.editor.placement)
            assertEquals(selection, input.editor.selection)
        }
    }

    @Test
    fun physicalDoubleClickZoomsAtThePointerWithoutOpeningOrEditing() = runBlocking(Dispatchers.Swing) {
        for (density in listOf(1f, 2f)) InputScene(1512, density).use { input ->
            input.start()
            var opened = 0
            input.editor.onOpen = { opened++ }
            input.editor.enters = { true }
            val at = input.at
            val world = input.state.flow.screenToFlowPosition(at.x.toDouble(), at.y.toDouble())
            val zoom = input.state.zoom
            val shape = input.editor.shape
            val placement = input.editor.placement
            input.press(at); input.release(at)
            input.press(at); input.release(at)
            repeat(8) { input.settle() }
            assertEquals(zoom * 2, input.state.zoom, 1e-6)
            val after = input.state.flow.screenToFlowPosition(at.x.toDouble(), at.y.toDouble())
            assertEquals(world.x, after.x, 1e-4)
            assertEquals(world.y, after.y, 1e-4)
            assertEquals(0, opened)
            assertEquals(null, input.editor.request)
            assertEquals(shape, input.editor.shape)
            assertEquals(placement, input.editor.placement)
        }
    }


    @Test
    fun historicalOversizedPhysicalConstraintsExplainTheRecordedFailure() {
        for ((width, height) in listOf(73280 to 14340, 73280 to 16646, 73280 to 14445,
            82856 to 23329, 100000 to 23329, 100000 to 100000)) {
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                androidx.compose.ui.unit.Constraints.fixed(width, height)
            }
        }
    }

    @Test
    fun historicalAndHundredThousandPixelMarkersKeepGeometryAtFitAndDeepestZoom() = runBlocking(Dispatchers.Swing) {
        for (density in listOf(1f, 2f)) for ((width, height) in listOf(73280.0 to 14340.0,
            73280.0 to 16646.0, 73280.0 to 14445.0, 82856.0 to 23329.0,
            100000.0 to 23329.0, 100000.0 to 100000.0)) {
            val state = GraphCanvasState(.002..16.0)
            val box = Area(200.0, 300.0, width, height)
            state.density(density.toDouble())
            state.resize(androidx.compose.ui.unit.IntSize((1100 * density).toInt(), (800 * density).toInt()))
            state.fit(box.x, box.y, box.width, box.height, maxZoom = 16.0, minZoom = .002)
            val fitted = state.viewport()
            var coordinates: androidx.compose.ui.layout.LayoutCoordinates? = null
            val scene = ImageComposeScene((1100 * density).toInt(), (800 * density).toInt(), Density(density), coroutineContext = Dispatchers.Swing) {
                GraphView(null, state, { _, _, _ -> false }, { _, _, _ -> false }, markers = {
                    GraphMarkers(state, listOf(box), { "measurement-specimen" }) { _, _ ->
                        Box(Modifier.at(box.x, box.y, box.width, box.height)) {
                            Box(Modifier.fillMaxSize().onGloballyPositioned { coordinates = it })
                        }
                    }
                }, draw = { _, _, _ -> })
            }
            var frame = 0L
            suspend fun settle() {
                repeat(24) {
                    Snapshot.sendApplyNotifications()
                    frame += 16_666_667L
                    scene.render(frame).close()
                    delay(16)
                }
            }
            fun checkBounds() {
                val node = kotlin.test.assertNotNull(coordinates)
                assertTrue(node.isAttached)
                assertTrue(node.size.width in 1..8191 && node.size.height in 1..8191)
                val first = node.localToRoot(Offset.Zero)
                val last = node.localToRoot(Offset(node.size.width.toFloat(), node.size.height.toFloat()))
                val view = state.viewport()
                assertEquals((view.x + box.x * view.zoom) * density, first.x.toDouble(), 1.0)
                assertEquals((view.y + box.y * view.zoom) * density, first.y.toDouble(), 1.0)
                assertEquals((view.x + (box.x + box.width) * view.zoom) * density, last.x.toDouble(), 1.0)
                assertEquals((view.y + (box.y + box.height) * view.zoom) * density, last.y.toDouble(), 1.0)
            }
            try {
                settle()
                checkBounds()
                assertEquals(fitted, state.viewport())
                assertTrue((fitted.x + box.x * fitted.zoom) * density >= -1)
                assertTrue((fitted.y + box.y * fitted.zoom) * density >= -1)
                assertTrue((fitted.x + (box.x + box.width) * fitted.zoom) * density <= 1100 * density + 1)
                assertTrue((fitted.y + (box.y + box.height) * fitted.zoom) * density <= 800 * density + 1)
                val pointer = Offset(550f * density, 400f * density)
                val beforeWheel = state.viewport()
                scene.sendPointerEvent(PointerEventType.Scroll, pointer, scrollDelta = Offset(0f, 1f))
                settle()
                val afterWheel = state.viewport()
                assertEquals(beforeWheel.zoom, afterWheel.zoom)
                assertEquals(beforeWheel.x, afterWheel.x, 1e-6)
                assertTrue(beforeWheel.y != afterWheel.y)
                checkBounds()
                val anchored = state.flow.screenToFlowPosition(pointer.x.toDouble(), pointer.y.toDouble())
                scene.sendPointerEvent(PointerEventType.Scroll, pointer, scrollDelta = Offset(0f, -1f),
                    keyboardModifiers = PointerKeyboardModifiers(isCtrlPressed = true))
                settle()
                assertTrue(state.zoom > afterWheel.zoom)
                val actualAnchor = state.flow.screenToFlowPosition(pointer.x.toDouble(), pointer.y.toDouble())
                assertTrue(kotlin.math.hypot((actualAnchor.x - anchored.x) * state.flow.viewport.zoom,
                    (actualAnchor.y - anchored.y) * state.flow.viewport.zoom) <= 1.0)
                checkBounds()
                state.restore(80.0 - box.x * 16, 70.0 - box.y * 16, 16.0)
                settle()
                assertEquals(16.0, state.zoom, 1e-10)
                checkBounds()
                state.restore(80.0 - (box.x + box.width - 8) * 16,
                    70.0 - (box.y + box.height - 8) * 16, 16.0)
                settle()
                assertEquals(16.0, state.zoom, 1e-10)
                checkBounds()
                state.restore(fitted.x, fitted.y, fitted.zoom)
                settle()
                assertEquals(fitted, state.viewport())
                checkBounds()
            } finally { scene.close() }
        }
    }

    @Test
    fun oversizedMarkersPreserveTheirScreenRectanglesWithoutPackedConstraintOverflow() = runBlocking(Dispatchers.Swing) {
        for (density in listOf(1f, 2f)) {
            val state = GraphCanvasState(.002..16.0)
            val box = dev.shibasis.reaktor.blueprint.canvas.Area(200.0, 300.0, 41015.0, 32884.0)
            val scene = ImageComposeScene((1100 * density).toInt(), (800 * density).toInt(), Density(density), coroutineContext = Dispatchers.Swing) {
                GraphView(null, state, { _, _, _ -> false }, { _, _, _ -> false }, markers = {
                    GraphMarkers(state, listOf(box), { "large" }) { _, _ ->
                        Box(Modifier.at(box.x, box.y, box.width, box.height).testTag("large"))
                    }
                }, draw = { _, _, _ -> })
            }
            var frame = 0L
            suspend fun settle() { repeat(12) { Snapshot.sendApplyNotifications(); frame += 16_666_667L; scene.render(frame).close(); delay(16) } }
            try {
                state.density(density.toDouble())
                state.restore(80.0, 70.0, .015)
                settle(); settle()
                fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                val marker = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }.single {
                    it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "large"
                }.boundsInRoot
                assertEquals(.015, state.zoom, 1e-10)
                assertEquals((80 + box.x * .015) * density, marker.left.toDouble(), .1)
                assertEquals((70 + box.y * .015) * density, marker.top.toDouble(), .1)
                assertEquals(box.width * .015 * density, marker.width.toDouble(), .1)
                assertEquals(box.height * .015 * density, marker.height.toDouble(), .1)
            } finally { scene.close() }
        }
    }

    @Test
    fun physicalSelectionAtEveryWidthLeavesTheCameraAndPlacementStill() = runBlocking(Dispatchers.Swing) {
        for (density in listOf(1f, 2f)) for (width in listOf(900, 1100, 1512, 1920)) {
            InputScene(width, density).use { input ->
                input.start()
                val before = input.state.viewport()
                val placement = input.editor.placement
                input.press(input.at); input.release(input.at); input.settle()
                assertEquals(setOf("card"), input.editor.selection.nodes, "$width @$density")
                assertEquals(before, input.state.viewport())
                assertEquals(placement, input.editor.placement)
            }
        }
    }

    @Test
    fun releasingSpaceAfterPanningRestoresPhysicalSelection() = runBlocking(Dispatchers.Swing) {
        for (density in listOf(1f, 2f)) InputScene(1512, density).use { input ->
            input.start()
            val before = input.state.viewport()
            val at = input.at
            input.key(KeyEventType.KeyDown)
            input.press(at); input.move(at + Offset(40f * density, 20f * density)); input.release(at + Offset(40f * density, 20f * density))
            input.key(KeyEventType.KeyUp); input.settle()
            assertTrue(input.state.viewport() != before)
            assertTrue(input.editor.selection.nodes.isEmpty())
            val panned = input.state.viewport()
            input.press(input.at); input.release(input.at); input.settle()
            assertEquals(setOf("card"), input.editor.selection.nodes)
            assertEquals(panned, input.state.viewport())
        }
    }

    @Test
    fun losingFocusWhileSpaceIsHeldRestoresPhysicalSelection() = runBlocking(Dispatchers.Swing) {
        for (density in listOf(1f, 2f)) InputScene(1512, density).use { input ->
            input.start()
            val before = input.state.viewport()
            input.key(KeyEventType.KeyDown); input.blur(); input.settle()
            input.press(input.at); input.release(input.at); input.settle()
            assertEquals(setOf("card"), input.editor.selection.nodes)
            assertEquals(before, input.state.viewport())
        }
    }

    @Test
    fun modifiedWheelKeepsTheWorldPointUnderThePointer() = runBlocking(Dispatchers.Swing) {
        for (density in listOf(1f, 2f)) InputScene(1512, density).use { input ->
            input.start()
            val point = Offset(670f * density, 310f * density)
            fun world() = input.state.flow.screenToFlowPosition(point.x.toDouble(), point.y.toDouble())
            val before = world()
            val zoom = input.state.zoom
            input.wheel(point); input.settle()
            assertTrue(input.state.zoom > zoom)
            assertEquals(before.x, world().x, 1e-6)
            assertEquals(before.y, world().y, 1e-6)
            assertTrue(input.editor.selection.nodes.isEmpty())
        }
    }

    @Test
    fun secondaryClickOnTheVisibleHeaderTargetsItsCard() = runBlocking(Dispatchers.Swing) {
        for (density in listOf(1f, 2f)) for (world in listOf(0.0, 10_000.0)) {
            val shape = GraphShape(listOf(ScopeShape("app", "App")),
                listOf(NodeShape("card", "app", "Card title", "Node", NodeKind.Service)), emptyList(), emptyList())
            val editor = GraphEditor(shape, Placement(mapOf("card" to Spot(world, world))))
            val card = editor.layout.cards.getValue("card")
            val state = GraphCanvasState()
            val icon = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).build()
            val scene = ImageComposeScene(1000, 800, Density(density), coroutineContext = Dispatchers.Swing) {
                Box(Modifier.fillMaxSize().padding(start = 160.dp, top = 47.dp)) {
                    GraphView(null, state, { _, _, _ -> true }, { _, _, _ -> true }, editing = editor, markers = {
                        GraphMarkers(state, listOf(card), { "card" }) { _, _ ->
                            CardMarker(card, BlueprintCardLook(Color.Black, icon, "Card title", "Subtitle"), false, { _, _ -> null }, {}, null)
                        }
                    }, draw = { _, _, _ -> })
                }
            }
            var frame = 0L
            suspend fun settle() { repeat(16) { Snapshot.sendApplyNotifications(); frame += 16_666_667L; scene.render(frame).close(); delay(16) } }
            try {
                settle()
                state.flow.setViewport(Viewport(100.0 - card.x * .9, 100.0 - card.y * .9, .9))
                settle()
                fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                val title = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }.single { node ->
                    node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == "Card title" }
                }
                val at = title.boundsInWindow.center
                scene.sendPointerEvent(PointerEventType.Move, at)
                scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
                scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Secondary)
                settle()
                assertEquals(Target.OnCard("card"), assertIs<Request.Menu>(editor.request).target, "world $world, density $density, at $at")
            } finally { scene.close() }
        }
    }
}
