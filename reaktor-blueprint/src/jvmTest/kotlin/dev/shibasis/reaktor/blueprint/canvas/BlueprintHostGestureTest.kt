@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.unit.Density
import dev.shibasis.composeflow.compose.interaction.FlowViewportController
import dev.shibasis.composeflow.compose.interaction.FlowViewportGestureConfig
import dev.shibasis.composeflow.compose.interaction.FlowViewportInteractionState
import dev.shibasis.composeflow.compose.interaction.FlowViewportPlatformBridge
import dev.shibasis.composeflow.compose.interaction.FlowViewportPlatformGestureSubscription
import dev.shibasis.composeflow.compose.interaction.ProvideFlowViewportPlatformBridge
import dev.shibasis.composeflow.runtime.ReactFlowState
import dev.shibasis.reaktor.blueprint.editor.EditReceipt
import dev.shibasis.reaktor.blueprint.editor.EditRevision
import dev.shibasis.reaktor.blueprint.editor.GraphEditor
import dev.shibasis.reaktor.blueprint.editor.Verdict
import dev.shibasis.reaktor.blueprint.editor.EditorTool
import dev.shibasis.reaktor.blueprint.editor.Placement
import dev.shibasis.reaktor.blueprint.editor.Selection
import dev.shibasis.reaktor.blueprint.editor.Spot
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.surface.CommandId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

class BlueprintHostGestureTest {
    private class Host(val density: Float, editable: Boolean, preempted: Boolean = false, bridge: FlowViewportPlatformBridge? = null) : AutoCloseable {
        val editor = GraphEditor(GraphShape(listOf(ScopeShape("scope", "Scope")),
            listOf(NodeShape("node", "scope", "Node", "Node", NodeKind.Service)), emptyList(), emptyList()),
            Placement(mapOf("node" to Spot(0.0, 0.0)))).apply {
                editRevision = EditRevision("source", "presentation")
                permits = { _, _ -> true }
                validate = { Verdict(true, "") }
                commit = { _, expected -> EditReceipt.Accepted(expected.copy(source = expected.source + "+")) }
                savePlacement = { _, expected -> EditReceipt.Accepted(expected.copy(presentation = expected.presentation + "+")) }
            }
        val canvas = GraphCanvasState(.002..16.0)
        private var frame = 0L
        private val scene = ImageComposeScene((1100 * density).toInt(), (800 * density).toInt(), Density(density), coroutineContext = Dispatchers.Swing) {
            ProvideFlowViewportPlatformBridge(bridge) {
                GraphView(null, canvas, { _, _, _ -> true }, { _, _, _ -> true }, editing = editor.takeIf { editable },
                    preempt = if (preempted) { _, _, _ -> editor.select(Selection(setOf("node"))); true } else null,
                    markers = { focus -> LaunchedEffect(Unit) { focus() } }, draw = { _, _, _ -> })
            }
        }
        suspend fun settle() { repeat(8) { Snapshot.sendApplyNotifications(); scene.render(++frame * 16_666_667L).close(); delay(16) } }
        suspend fun start() { settle(); canvas.restore(200.0, 170.0, .8); settle() }
        val anchor get() = editor.layout.cards.getValue("node").let { card ->
            Offset(((canvas.viewport().x + (card.x + card.width * .7) * canvas.zoom) * density).toFloat(),
                ((canvas.viewport().y + (card.y + 12) * canvas.zoom) * density).toFloat())
        }
        fun world(at: Offset) = canvas.flow.screenToFlowPosition(at.x.toDouble(), at.y.toDouble())
        fun wheel(at: Offset, delta: Offset = Offset(0f, -1f), ctrl: Boolean = false, meta: Boolean = false, shift: Boolean = false) = scene.sendPointerEvent(PointerEventType.Scroll, at,
            scrollDelta = delta, keyboardModifiers = PointerKeyboardModifiers(isCtrlPressed = ctrl, isMetaPressed = meta, isShiftPressed = shift))
        fun key(key: Key, type: KeyEventType, meta: Boolean = false, ctrl: Boolean = false, shift: Boolean = false) = scene.sendKeyEvent(KeyEvent(key, type, isMetaPressed = meta, isCtrlPressed = ctrl, isShiftPressed = shift))
        fun mouse(type: PointerEventType, at: Offset, button: PointerButton = PointerButton.Primary) = scene.sendPointerEvent(type, at,
            buttons = PointerButtons(isPrimaryPressed = type != PointerEventType.Release && button == PointerButton.Primary,
                isTertiaryPressed = type != PointerEventType.Release && button == PointerButton.Tertiary),
            button = if (type == PointerEventType.Move) null else button)
        fun hover(at: Offset) = scene.sendPointerEvent(PointerEventType.Move, at, buttons = PointerButtons())
        fun touch(type: PointerEventType, vararg pointers: Pair<Offset, Boolean>) = scene.sendPointerEvent(type,
            pointers = pointers.mapIndexed { index, (at, pressed) -> ComposeScenePointer(PointerId(index.toLong()), at, pressed, PointerType.Touch) })
        override fun close() = scene.close()
    }

    @Test fun focusedCanvasPrimaryZoomKeysKeepThePhysicalAnchorAndRawSelectionAtBothDensities() = runBlocking(Dispatchers.Swing) {
        val mac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
        for (density in listOf(1f, 2f)) for (editable in listOf(false, true)) Host(density, editable).use { host ->
            host.start()
            host.canvas.insetStart = 80.0; host.canvas.insetEnd = 240.0
            host.canvas.insetTop = 32.0; host.canvas.insetBottom = 64.0
            host.editor.select(Selection(setOf("node")))
            val selection = host.editor.selection
            val shape = host.editor.shape
            val placement = host.editor.placement
            val revision = host.editor.editRevision
            val geometry = host.editor.layout.cards
            val at = Offset(317f, 293f) * density
            host.hover(at)
            host.settle()
            suspend fun landed() {
                repeat(8) {
                    host.settle()
                    if (host.canvas.aim == null) return
                }
                assertNull(host.canvas.aim)
            }
            suspend fun key(key: Key, shift: Boolean = false) {
                assertTrue(host.key(key, KeyEventType.KeyDown, meta = mac, ctrl = !mac, shift = shift))
                landed()
                val shown = host.canvas.viewport()
                host.key(key, KeyEventType.KeyUp, meta = mac, ctrl = !mac, shift = shift)
                landed()
                assertEquals(shown, host.canvas.viewport())
                assertEquals(selection, host.editor.selection)
                assertEquals(shape, host.editor.shape)
                assertEquals(placement, host.editor.placement)
                assertEquals(revision, host.editor.editRevision)
                assertEquals(geometry, host.editor.layout.cards)
            }
            val before = host.world(at)
            val zoom = host.canvas.zoom
            key(Key.Equals)
            assertEquals(zoom * 1.25, host.canvas.zoom, 1e-8)
            assertEquals(before.x, host.world(at).x, 1e-5)
            assertEquals(before.y, host.world(at).y, 1e-5)
            key(Key.Minus)
            assertEquals(zoom, host.canvas.zoom, 1e-8)
            assertEquals(before.x, host.world(at).x, 1e-5)
            assertEquals(before.y, host.world(at).y, 1e-5)
            key(Key.Equals, shift = true)
            assertEquals(zoom * 1.25, host.canvas.zoom, 1e-8)
            assertEquals(before.x, host.world(at).x, 1e-5)
            assertEquals(before.y, host.world(at).y, 1e-5)
            key(Key.Equals)
            assertEquals(zoom * 1.25 * 1.25, host.canvas.zoom, 1e-8)
            val centre = Offset(((80.0 + 1100.0 - 240.0) / 2 * density).toFloat(),
                ((32.0 + 800.0 - 64.0) / 2 * density).toFloat())
            val centred = host.world(centre)
            key(Key.Zero)
            assertEquals(1.0, host.canvas.zoom, 1e-8)
            assertEquals(centred.x, host.world(centre).x, 1e-5)
            assertEquals(centred.y, host.world(centre).y, 1e-5)
        }
    }

    @Test fun focusedCanvasEqualsAndPlusKeyFormsKeepThePhysicalAnchorAndRawSelectionAtBothDensities() = runBlocking(Dispatchers.Swing) {
        val mac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
        for (density in listOf(1f, 2f)) for (editable in listOf(false, true)) Host(density, editable).use { host ->
            host.start()
            host.canvas.insetStart = 80.0; host.canvas.insetEnd = 240.0
            host.canvas.insetTop = 32.0; host.canvas.insetBottom = 64.0
            host.editor.select(Selection(setOf("node")))
            val selection = host.editor.selection
            val shape = host.editor.shape
            val placement = host.editor.placement
            val revision = host.editor.editRevision
            val geometry = host.editor.settled
            val at = Offset(317f, 293f) * density
            host.hover(at)
            host.settle()
            suspend fun landed() {
                repeat(8) {
                    host.settle()
                    if (host.canvas.aim == null) return
                }
                assertNull(host.canvas.aim)
            }
            suspend fun delivered(key: Key, primary: Boolean, shift: Boolean) {
                assertTrue(host.key(key, KeyEventType.KeyDown, meta = primary && mac, ctrl = primary && !mac, shift = shift))
                landed()
                val shown = host.canvas.viewport()
                host.key(key, KeyEventType.KeyUp, meta = primary && mac, ctrl = primary && !mac, shift = shift)
                landed()
                assertEquals(shown, host.canvas.viewport())
                assertEquals(selection, host.editor.selection)
                assertEquals(shape, host.editor.shape)
                assertEquals(placement, host.editor.placement)
                assertEquals(revision, host.editor.editRevision)
                assertEquals(geometry, host.editor.settled)
            }
            for ((key, primary, shift) in listOf(
                Triple(Key.Equals, false, false), Triple(Key.Plus, false, false),
                Triple(Key.Plus, true, false), Triple(Key.Plus, true, true),
            )) {
                val before = host.world(at)
                val zoom = host.canvas.zoom
                delivered(key, primary, shift)
                assertEquals(zoom * 1.25, host.canvas.zoom, 1e-8)
                assertEquals(before.x, host.world(at).x, 1e-5)
                assertEquals(before.y, host.world(at).y, 1e-5)
                delivered(Key.Minus, primary, false)
                assertEquals(zoom, host.canvas.zoom, 1e-8)
                assertEquals(before.x, host.world(at).x, 1e-5)
                assertEquals(before.y, host.world(at).y, 1e-5)
            }
        }
    }

    @Test fun viewerAndEditableWheelKeepTheWorldAnchorAtBothDensities() = runBlocking(Dispatchers.Swing) {
        for (density in listOf(1f, 2f)) for (editable in listOf(false, true)) for (meta in listOf(false, true)) Host(density, editable).use { host ->
            host.start()
            for (at in listOf(host.anchor, Offset(1000f, 700f) * density)) repeat(20) {
                val before = host.world(at); val zoom = host.canvas.zoom
                host.wheel(at, ctrl = !meta, meta = meta); host.settle()
                assertTrue(host.canvas.zoom > zoom)
                val after = host.world(at)
                assertEquals(before.x, after.x, 1e-5); assertEquals(before.y, after.y, 1e-5)
            }
        }
    }

    @Test fun plainScrollAlwaysPansBothAxesOverCardsAndEmptyCanvas() = runBlocking(Dispatchers.Swing) {
        for (density in listOf(1f, 2f)) for (editable in listOf(false, true)) Host(density, editable).use { host ->
            host.start()
            for (at in listOf(host.anchor, Offset(1000f, 700f) * density)) for (delta in listOf(Offset(0f, -1f), Offset(.3f, -.7f), Offset(0f, -3f))) {
                val before = host.canvas.viewport()
                host.wheel(at, delta); host.settle()
                val after = host.canvas.viewport()
                assertEquals(before.zoom, after.zoom)
                assertTrue(after.y > before.y)
                if (delta.x == 0f) assertEquals(before.x, after.x, 1e-5)
                else {
                    assertTrue(after.x < before.x)
                    assertEquals(delta.x.toDouble() / delta.y, (after.x - before.x) / (after.y - before.y), 1e-5)
                }
            }
        }
    }

    @Test fun viewerPrimaryDragPansWithoutChangingZoomAtBothDensities() = runBlocking(Dispatchers.Swing) {
        for (density in listOf(1f, 2f)) Host(density, false).use { host ->
            host.start(); val before = host.canvas.viewport(); val at = host.anchor
            host.mouse(PointerEventType.Press, at)
            host.mouse(PointerEventType.Move, at + Offset(60f, 40f) * density)
            host.mouse(PointerEventType.Release, at + Offset(60f, 40f) * density); host.settle()
            assertEquals(before.zoom, host.canvas.zoom)
            assertEquals(before.x + 60, host.canvas.viewport().x, 1e-5)
            assertEquals(before.y + 40, host.canvas.viewport().y, 1e-5)
        }
    }

    @Test fun viewerAndEditableDoubleClickZoomOnceAtThePointer() = runBlocking(Dispatchers.Swing) {
        for (density in listOf(1f, 2f)) for (editable in listOf(false, true)) Host(density, editable).use { host ->
            host.start(); val at = host.anchor; val before = host.world(at); val zoom = host.canvas.zoom
            val shape = host.editor.shape; val placement = host.editor.placement
            repeat(2) { host.mouse(PointerEventType.Press, at); host.mouse(PointerEventType.Release, at) }
            repeat(8) { host.settle() }
            assertEquals(zoom * 2, host.canvas.zoom, 1e-5)
            val after = host.world(at)
            assertEquals(before.x, after.x, 1e-5); assertEquals(before.y, after.y, 1e-5)
            assertEquals(shape, host.editor.shape); assertEquals(placement, host.editor.placement)
            assertEquals(null, host.editor.request)
        }
    }

    private suspend fun pinch(editable: Boolean, preempted: Boolean = false, drag: Boolean = false, capture: Boolean = false) {
        for (density in listOf(1f, 2f)) Host(density, editable, preempted).use { host ->
            host.start(); val at = host.anchor; val before = host.world(at); val zoom = host.canvas.zoom
            if (capture) host.editor.pick(EditorTool.Box)
            val shape = host.editor.shape; val placement = host.editor.placement
            val selected = host.editor.selection; val tool = host.editor.tool
            host.touch(PointerEventType.Press, at - Offset(if (drag) 40f else 30f, 0f) * density to true)
            if (drag) host.touch(PointerEventType.Move, at - Offset(30f, 0f) * density to true)
            host.touch(PointerEventType.Press, at - Offset(30f, 0f) * density to true, at + Offset(30f, 0f) * density to true)
            host.touch(PointerEventType.Move, at - Offset(60f, 0f) * density to true, at + Offset(60f, 0f) * density to true)
            host.touch(PointerEventType.Release, at - Offset(60f, 0f) * density to false, at + Offset(60f, 0f) * density to false)
            host.settle()
            assertEquals(zoom * 2, host.canvas.zoom, 1e-5, "Observed two-pointer zoom at density $density")
            val after = host.world(at)
            assertEquals(before.x, after.x, 1e-5); assertEquals(before.y, after.y, 1e-5)
            assertEquals(shape, host.editor.shape); assertEquals(placement, host.editor.placement)
            assertEquals(selected, host.editor.selection)
            assertEquals(tool, host.editor.tool)
        }
    }

    @Test fun viewerTwoPointerPinchPreservesItsCentroid() = runBlocking(Dispatchers.Swing) { pinch(false) }

    @Test fun editableTwoPointerPinchPreservesItsCentroidAndSource() = runBlocking(Dispatchers.Swing) { pinch(true) }

    @Test fun preemptedTwoPointerPinchPreservesTheOriginalSelection() = runBlocking(Dispatchers.Swing) { pinch(true, preempted = true) }

    @Test fun aSecondTouchCancelsTheUncommittedNodeDrag() = runBlocking(Dispatchers.Swing) { pinch(true, drag = true) }

    @Test fun pinchCancelsTheCapturePreviewAndKeepsItsTool() = runBlocking(Dispatchers.Swing) { pinch(true, capture = true) }

    @Test fun singleTouchDragCommitsOneReversibleEdit() = runBlocking(Dispatchers.Swing) {
        for (density in listOf(1f, 2f)) Host(density, true).use { host ->
            host.start(); val at = host.anchor; val camera = host.canvas.viewport(); val placement = host.editor.placement
            host.touch(PointerEventType.Press, at to true)
            host.touch(PointerEventType.Move, at + Offset(64f, 32f) * density to true)
            host.touch(PointerEventType.Release, at + Offset(64f, 32f) * density to false); host.settle()
            assertTrue(placement != host.editor.placement)
            assertEquals(camera, host.canvas.viewport())
            host.editor.invoke(CommandId("edit-undo")); host.settle()
            assertEquals(placement, host.editor.placement)
        }
    }

    @Test fun singleTouchStillSelectsTheOriginalCardWithoutMovingTheCamera() = runBlocking(Dispatchers.Swing) {
        for (density in listOf(1f, 2f)) Host(density, true).use { host ->
            host.start(); val at = host.anchor; val before = host.canvas.viewport()
            host.touch(PointerEventType.Press, at to true)
            host.touch(PointerEventType.Release, at to false); host.settle()
            assertEquals(setOf("node"), host.editor.selection.nodes)
            assertEquals(before, host.canvas.viewport())
        }
    }
    @Test fun shiftWheelPansSidewaysWithoutDoubleSwappingNativeHorizontalDeltas() = runBlocking(Dispatchers.Swing) {
        for (density in listOf(1f, 2f)) for (editable in listOf(false, true)) Host(density, editable).use { host ->
            host.start()
            for (at in listOf(host.anchor, Offset(1000f, 700f) * density)) for (delta in listOf(Offset(0f, -1f), Offset(-1f, 0f))) {
                val before = host.canvas.viewport()
                host.wheel(at, delta, shift = true); host.settle()
                val after = host.canvas.viewport()
                assertEquals(before.zoom, after.zoom)
                assertEquals(before.y, after.y, 1e-5)
                assertTrue(after.x > before.x)
            }
        }
    }

    @Test fun handSpaceAndMiddleDragPanTheEditorWithoutEditingItsCard() = runBlocking(Dispatchers.Swing) {
        for (density in listOf(1f, 2f)) for (mode in listOf("hand", "space", "middle")) Host(density, true).use { host ->
            host.start(); val at = host.anchor; val before = host.canvas.viewport()
            val shape = host.editor.shape; val placement = host.editor.placement
            if (mode == "hand") { host.key(Key.H, KeyEventType.KeyDown); host.key(Key.H, KeyEventType.KeyUp); host.settle() }
            if (mode == "space") host.key(Key.Spacebar, KeyEventType.KeyDown)
            val button = if (mode == "middle") PointerButton.Tertiary else PointerButton.Primary
            host.mouse(PointerEventType.Press, at, button)
            host.mouse(PointerEventType.Move, at + Offset(60f, 40f) * density, button)
            host.mouse(PointerEventType.Release, at + Offset(60f, 40f) * density, button)
            if (mode == "space") host.key(Key.Spacebar, KeyEventType.KeyUp)
            host.settle()
            assertEquals(before.zoom, host.canvas.zoom)
            assertEquals(before.x + 60, host.canvas.viewport().x, 1e-5)
            assertEquals(before.y + 40, host.canvas.viewport().y, 1e-5)
            assertEquals(shape, host.editor.shape); assertEquals(placement, host.editor.placement)
        }
    }

    @Test fun twentyPinchStepsKeepTheOriginalWorldPointWithinOnePixel() = runBlocking(Dispatchers.Swing) {
        for (density in listOf(1f, 2f)) for (editable in listOf(false, true)) Host(density, editable).use { host ->
            host.start(); val at = host.anchor; val before = host.world(at)
            repeat(20) {
                host.touch(PointerEventType.Press, at - Offset(40f, 0f) * density to true)
                host.touch(PointerEventType.Press, at - Offset(40f, 0f) * density to true, at + Offset(40f, 0f) * density to true)
                host.touch(PointerEventType.Move, at - Offset(40.8f, 0f) * density to true, at + Offset(40.8f, 0f) * density to true)
                host.touch(PointerEventType.Release, at - Offset(40.8f, 0f) * density to false, at + Offset(40.8f, 0f) * density to false); host.settle()
                val after = host.world(at)
                assertTrue(kotlin.math.abs(after.x - before.x) * host.canvas.flow.viewport.zoom <= 1)
                assertTrue(kotlin.math.abs(after.y - before.y) * host.canvas.flow.viewport.zoom <= 1)
            }
        }
    }

    @Test fun modifiedHorizontalScrollZoomsAtItsAnchorAtBothDensities() = runBlocking(Dispatchers.Swing) {
        for (density in listOf(1f, 2f)) for (editable in listOf(false, true)) for (meta in listOf(false, true)) Host(density, editable).use { host ->
            host.start()
            for (at in listOf(host.anchor, Offset(1000f, 700f) * density)) {
                val before = host.world(at); val zoom = host.canvas.zoom
                host.wheel(at, Offset(-1f, 0f), ctrl = !meta, meta = meta, shift = true); host.settle()
                assertTrue(host.canvas.zoom > zoom)
                val after = host.world(at)
                assertEquals(before.x, after.x, 1e-5)
                assertEquals(before.y, after.y, 1e-5)
            }
        }
    }

    @Test fun aStateOnlyNativeBridgeKeepsItsSingleRegistrationAndOptionalControllerOverload() = runBlocking(Dispatchers.Swing) {
        for (density in listOf(1f, 2f)) {
            var installs = 0
            var disposals = 0
            lateinit var interaction: FlowViewportInteractionState
            lateinit var config: FlowViewportGestureConfig
            val bridge = object : FlowViewportPlatformBridge {
                override fun installViewportGestures(
                    state: ReactFlowState,
                    interactionState: FlowViewportInteractionState,
                    config: FlowViewportGestureConfig,
                ): FlowViewportPlatformGestureSubscription {
                    installs++
                    interaction = interactionState
                    return FlowViewportPlatformGestureSubscription { disposals++ }
                }
            }
            Host(density, false, bridge = bridge).use { host ->
                host.start()
                config = FlowViewportGestureConfig()
                val controller = object : FlowViewportController {
                    override fun panBy(dx: Double, dy: Double) = Unit
                    override fun zoomBy(factor: Double, anchor: Offset, config: FlowViewportGestureConfig) = Unit
                }
                assertEquals(1, installs)
                assertNull(bridge.installViewportGestures(controller, interaction, config))
                assertEquals(1, installs)
                assertEquals(0, disposals)
            }
            assertEquals(1, disposals)
        }
    }

    @Test fun plainHorizontalWheelPansWithoutZoomAtBothDensities() = runBlocking(Dispatchers.Swing) {
        for (density in listOf(1f, 2f)) for (editable in listOf(false, true)) Host(density, editable).use { host ->
            host.start()
            for (at in listOf(host.anchor, Offset(1000f, 700f) * density)) {
                val before = host.canvas.viewport()
                val shape = host.editor.shape; val placement = host.editor.placement
                val selection = host.editor.selection
                host.wheel(at, Offset(-1f, 0f)); host.settle()
                val after = host.canvas.viewport()
                assertEquals(before.zoom, after.zoom)
                assertEquals(before.y, after.y, 1e-5)
                assertTrue(after.x > before.x)
                assertEquals(shape, host.editor.shape); assertEquals(placement, host.editor.placement)
                assertEquals(selection, host.editor.selection)
            }
        }
    }
}
