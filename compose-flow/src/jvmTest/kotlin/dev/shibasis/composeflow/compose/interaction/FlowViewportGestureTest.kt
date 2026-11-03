@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.composeflow.compose.interaction

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.shibasis.composeflow.runtime.ReactFlowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FlowViewportGestureTest {
    @Test
    fun initialPassConsumedDownShieldsTheWholeGesture() = consumedDown(PointerEventPass.Initial)

    @Test
    fun mainPassConsumedDownShieldsTheWholeGesture() = consumedDown(PointerEventPass.Main)

    @Test
    fun finalPassConsumedDownShieldsTheWholeGesture() = consumedDown(PointerEventPass.Final)

    @Test
    fun protectedPanStartUsesOriginalPhysicalDownAndNeverConsumesItAsAClick() = SwingUtilities.invokeAndWait { runBlocking {
        for (density in listOf(1f, 2f)) for (controller in listOf(false, true)) for (combined in listOf(false, true)) {
            val positions = mutableListOf<Offset>()
            val gate = FlowViewportPanStartGate { at, buttons ->
                positions += at
                assertEquals(true, buttons.isPrimaryPressed)
                at.x >= 160f * density
            }
            val host = PanGateHost(density, gate, controller, combined)
            try {
                host.frames()
                val before = host.state.viewport
                host.drag(host.at(40f, 40f), host.at(240f, 180f))
                host.frames()
                assertEquals(before, host.state.viewport)
                assertEquals(listOf(host.at(40f, 40f)), positions)
                assertEquals(0, host.paneClicks)
            } finally { host.scene.close() }
        }
    } }

    @Test
    fun admittedPanStartStaysLatchedWhenDraggingAcrossTheProtectedFrame() = SwingUtilities.invokeAndWait { runBlocking {
        for (density in listOf(1f, 2f)) {
            val positions = mutableListOf<Offset>()
            val host = PanGateHost(density, FlowViewportPanStartGate { at, _ ->
                positions += at
                at.x >= 160f * density
            })
            try {
                host.frames()
                val before = host.state.viewport
                host.drag(host.at(400f, 300f), host.at(40f, 40f))
                host.frames()
                assertEquals(listOf(host.at(400f, 300f)), positions)
                assertEquals(before.x - 350.0 * density, host.state.viewport.x)
                assertEquals(before.y - 250.0 * density, host.state.viewport.y)
                assertEquals(before.zoom, host.state.viewport.zoom)
                assertEquals(0, host.paneClicks)
            } finally { host.scene.close() }
        }
    } }

    @Test
    fun middleAndSecondaryPanKeepTheirExistingAuthorityOverTheGate() = SwingUtilities.invokeAndWait { runBlocking {
        for (density in listOf(1f, 2f)) for ((buttons, button) in listOf(
            PointerButtons(isTertiaryPressed = true) to PointerButton.Tertiary,
            PointerButtons(isSecondaryPressed = true) to PointerButton.Secondary,
        )) {
            var calls = 0
            val host = PanGateHost(density, FlowViewportPanStartGate { _, _ -> calls++; false })
            try {
                host.frames()
                val before = host.state.viewport
                host.drag(host.at(40f, 40f), host.at(80f, 70f), buttons, button)
                host.frames()
                assertEquals(before.x + 50.0 * density, host.state.viewport.x)
                assertEquals(before.y + 40.0 * density, host.state.viewport.y)
                assertEquals(before.zoom, host.state.viewport.zoom)
                assertEquals(0, calls)
            } finally { host.scene.close() }
        }
    } }

    @Test
    fun primaryHandButtonChordsBypassThePanStartGate() = SwingUtilities.invokeAndWait { runBlocking {
        for (density in listOf(1f, 2f)) for (buttons in listOf(
            PointerButtons(isPrimaryPressed = true, isTertiaryPressed = true),
            PointerButtons(isPrimaryPressed = true, isSecondaryPressed = true),
        )) {
            var calls = 0
            val host = PanGateHost(density, FlowViewportPanStartGate { _, _ -> calls++; false })
            try {
                host.frames()
                val before = host.state.viewport
                host.drag(host.at(40f, 40f), host.at(80f, 70f), buttons)
                host.frames()
                assertEquals(before.x + 50.0 * density, host.state.viewport.x)
                assertEquals(before.y + 40.0 * density, host.state.viewport.y)
                assertEquals(before.zoom, host.state.viewport.zoom)
                assertEquals(0, calls)
                assertEquals(0, host.paneClicks)
            } finally { host.scene.close() }
        }
    } }

    @Test
    fun refusedPrimaryPanCanPromoteToEitherHandButtonWithoutRecheckingTheGate() = SwingUtilities.invokeAndWait { runBlocking {
        for (density in listOf(1f, 2f)) for ((buttons, button) in listOf(
            PointerButtons(isPrimaryPressed = true, isTertiaryPressed = true) to PointerButton.Tertiary,
            PointerButtons(isPrimaryPressed = true, isSecondaryPressed = true) to PointerButton.Secondary,
        )) {
            val positions = mutableListOf<Offset>()
            val downButtons = mutableListOf<PointerButtons>()
            val host = PanGateHost(density, FlowViewportPanStartGate { at, originalButtons ->
                positions += at
                downButtons += originalButtons
                false
            })
            try {
                host.frames()
                val before = host.state.viewport
                val primary = PointerButtons(isPrimaryPressed = true)
                val from = host.at(40f, 40f)
                val refused = host.at(80f, 70f)
                val promoted = host.at(120f, 100f)
                val remaining = host.at(130f, 110f)
                host.scene.sendPointerEvent(PointerEventType.Press, from, buttons = primary, button = PointerButton.Primary)
                host.scene.sendPointerEvent(PointerEventType.Move, refused, buttons = primary)
                host.frames()
                assertEquals(before, host.state.viewport)
                assertEquals(listOf(from), positions)
                assertEquals(listOf(primary), downButtons)
                assertEquals(0, host.paneClicks)
                host.scene.sendPointerEvent(PointerEventType.Press, refused, buttons = buttons, button = button)
                host.scene.sendPointerEvent(PointerEventType.Move, promoted, buttons = buttons)
                host.frames()
                assertEquals(before.x + 40.0 * density, host.state.viewport.x)
                assertEquals(before.y + 30.0 * density, host.state.viewport.y)
                host.scene.sendPointerEvent(PointerEventType.Release, promoted, buttons = primary, button = button)
                host.scene.sendPointerEvent(PointerEventType.Move, remaining, buttons = primary)
                host.scene.sendPointerEvent(PointerEventType.Release, remaining, button = PointerButton.Primary)
                host.frames()
                assertEquals(before.x + 50.0 * density, host.state.viewport.x)
                assertEquals(before.y + 40.0 * density, host.state.viewport.y)
                assertEquals(before.zoom, host.state.viewport.zoom)
                assertEquals(listOf(from), positions)
                assertEquals(listOf(primary), downButtons)
                assertEquals(0, host.paneClicks)
            } finally { host.scene.close() }
        }
    } }

    @Test
    fun everyChildDownConsumptionPassPrecedesPanStart() = SwingUtilities.invokeAndWait { runBlocking {
        for (density in listOf(1f, 2f)) for (pass in listOf(PointerEventPass.Initial, PointerEventPass.Main, PointerEventPass.Final)) {
            var calls = 0
            val host = PanGateHost(density, FlowViewportPanStartGate { _, _ -> calls++; true }, claim = pass)
            try {
                host.frames()
                val before = host.state.viewport
                host.drag(host.at(40f, 40f), host.at(240f, 180f))
                host.frames()
                assertEquals(before, host.state.viewport)
                assertEquals(0, calls)
                assertEquals(0, host.paneClicks)
            } finally { host.scene.close() }
        }
    } }

    @Test
    fun wheelPanAndModifiedZoomAreIndependentOfPanStart() = SwingUtilities.invokeAndWait { runBlocking {
        for (density in listOf(1f, 2f)) {
            var calls = 0
            val host = PanGateHost(density, FlowViewportPanStartGate { _, _ -> calls++; false })
            try {
                host.frames()
                val before = host.state.viewport
                host.scene.sendPointerEvent(PointerEventType.Scroll, host.at(40f, 40f), scrollDelta = Offset(0f, 5f))
                host.frames()
                assertNotEquals(before.y, host.state.viewport.y)
                assertEquals(before.zoom, host.state.viewport.zoom)
                val afterPan = host.state.viewport
                host.scene.sendPointerEvent(PointerEventType.Scroll, host.at(40f, 40f), scrollDelta = Offset(0f, -1f),
                    keyboardModifiers = PointerKeyboardModifiers(isCtrlPressed = true))
                host.frames()
                assertNotEquals(afterPan.zoom, host.state.viewport.zoom)
                assertEquals(0, calls)
            } finally { host.scene.close() }
        }
    } }

    @Test
    fun aSecondContactPreservesPinchAndRemainingContactPanAfterAGateRefusal() = SwingUtilities.invokeAndWait { runBlocking {
        for (density in listOf(1f, 2f)) {
            var calls = 0
            val host = PanGateHost(density, FlowViewportPanStartGate { _, _ -> calls++; false })
            try {
                host.frames()
                host.touch(PointerEventType.Press, host.at(40f, 40f) to true)
                host.touch(PointerEventType.Move, host.at(100f, 80f) to true)
                assertEquals(1.0, host.state.viewport.zoom)
                assertEquals(0.0, host.state.viewport.x)
                assertEquals(1, calls)
                host.touch(PointerEventType.Press, host.at(100f, 80f) to true, host.at(200f, 80f) to true)
                host.touch(PointerEventType.Move, host.at(80f, 80f) to true, host.at(240f, 100f) to true)
                host.frames()
                assertEquals(true, host.state.viewport.zoom > 1.0)
                val beforeRemainingContact = host.state.viewport
                host.touch(PointerEventType.Release, host.at(80f, 80f) to true, host.at(240f, 100f) to false)
                host.touch(PointerEventType.Move, host.at(110f, 100f) to true, host.at(240f, 100f) to false)
                host.frames()
                assertNotEquals(beforeRemainingContact.x, host.state.viewport.x)
                assertEquals(beforeRemainingContact.zoom, host.state.viewport.zoom)
                assertEquals(1, calls)
                host.touch(PointerEventType.Release, host.at(110f, 100f) to false, host.at(240f, 100f) to false)
            } finally { host.scene.close() }
        }
    } }

    @Test
    fun originalTrailingLambdaPaneTapOverloadsKeepTheirMeaning() = SwingUtilities.invokeAndWait { runBlocking {
        for (controller in listOf(false, true)) for (combined in listOf(false, true)) {
            val host = PanGateHost(1f, null, controller, combined)
            try {
                host.frames()
                val at = host.at(400f, 300f)
                host.scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                host.scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
                host.frames()
                assertEquals(listOf(at), host.taps)
            } finally { host.scene.close() }
        }
    } }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private class PanGateHost(
        val density: Float,
        gate: FlowViewportPanStartGate?,
        controllerHost: Boolean = false,
        combined: Boolean = true,
        claim: PointerEventPass? = null,
    ) {
        val state = ReactFlowState()
        val interaction = FlowViewportInteractionState()
        val config = FlowViewportGestureConfig()
        var paneClicks = 0
        val taps = mutableListOf<Offset>()
        private var time = 0L
        private val controller = object : FlowViewportController {
            override fun panBy(dx: Double, dy: Double) = state.panBy(dx, dy)
            override fun zoomBy(factor: Double, anchor: Offset, config: FlowViewportGestureConfig) =
                state.zoomBy(factor, anchor.x.toDouble(), anchor.y.toDouble(), config.minZoom, config.maxZoom)
        }
        val scene = ImageComposeScene((640 * density).toInt(), (480 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
            val modifier = if (gate == null) {
                when {
                    combined && controllerHost -> Modifier.flowViewportGestures(controller, interaction, config) { taps += it; true }
                    combined -> Modifier.flowViewportGestures(state, interaction, config) { taps += it; true }
                    controllerHost -> Modifier.flowPointerViewportGestures(controller, interaction, config) { taps += it; true }
                    else -> Modifier.flowPointerViewportGestures(state, interaction, config) { taps += it; true }
                }
            } else {
                when {
                    combined && controllerHost -> Modifier.flowViewportGestures(controller, interaction, config, panStart = gate, onPaneClick = { paneClicks++ })
                    combined -> Modifier.flowViewportGestures(state, interaction, config, panStart = gate, onPaneClick = { paneClicks++ })
                    controllerHost -> Modifier.flowPointerViewportGestures(controller, interaction, config, panStart = gate, onPaneClick = { paneClicks++ })
                    else -> Modifier.flowPointerViewportGestures(state, interaction, config, panStart = gate, onPaneClick = { paneClicks++ })
                }
            }
            Box(Modifier.fillMaxSize().then(modifier)) {
                if (claim != null) Box(Modifier.size(160.dp).pointerInput(claim) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(claim)
                            event.changes.filter { it.pressed && !it.previousPressed }.forEach { it.consume() }
                        }
                    }
                })
            }
        }
        fun at(x: Float, y: Float) = Offset(x * density, y * density)
        suspend fun frames() {
            repeat(3) {
                Snapshot.sendApplyNotifications()
                time += 16_666_667L
                scene.render(time).close()
                delay(5)
            }
        }
        fun drag(from: Offset, to: Offset, buttons: PointerButtons = PointerButtons(isPrimaryPressed = true), button: PointerButton = PointerButton.Primary) {
            scene.sendPointerEvent(PointerEventType.Press, from, buttons = buttons, button = button)
            scene.sendPointerEvent(PointerEventType.Move, to, buttons = buttons)
            scene.sendPointerEvent(PointerEventType.Move, to + at(10f, 10f), buttons = buttons)
            scene.sendPointerEvent(PointerEventType.Release, to + at(10f, 10f), button = button)
        }
        fun touch(type: PointerEventType, vararg pointers: Pair<Offset, Boolean>) = scene.sendPointerEvent(type,
            pointers = pointers.mapIndexed { index, (at, pressed) -> ComposeScenePointer(PointerId(index.toLong()), at, pressed, PointerType.Touch) })
    }

    private fun consumedDown(pass: PointerEventPass) = SwingUtilities.invokeAndWait { runBlocking {
        val state = ReactFlowState()
        val interaction = FlowViewportInteractionState()
        val config = FlowViewportGestureConfig()
        var paneClicks = 0
        val scene = ImageComposeScene(640, 480, Density(1f), coroutineContext = Dispatchers.Unconfined) {
            Box(Modifier.fillMaxSize()
                .flowWheelAndTrackpadViewportGestures(state, interaction, config)
                .flowPointerViewportGestures(state, interaction, config, onPaneClick = { paneClicks++ })) {
                Box(Modifier.size(160.dp).pointerInput(pass) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(pass)
                            event.changes.filter { it.pressed && !it.previousPressed }.forEach { it.consume() }
                        }
                    }
                })
            }
        }
        var time = 0L
        suspend fun frames() {
            repeat(3) {
                Snapshot.sendApplyNotifications()
                time += 16_666_667L
                scene.render(time).close()
                delay(5)
            }
        }
        val primary = PointerButtons(isPrimaryPressed = true)
        fun drag(from: Offset, to: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, from, buttons = primary, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Move, to, buttons = primary)
            scene.sendPointerEvent(PointerEventType.Move, to + Offset(10f, 10f), buttons = primary)
            scene.sendPointerEvent(PointerEventType.Release, to + Offset(10f, 10f), button = PointerButton.Primary)
        }
        try {
            frames()
            val before = state.viewport
            drag(Offset(40f, 40f), Offset(240f, 180f))
            frames()
            assertEquals(before, state.viewport)
            assertEquals(0, paneClicks)
            drag(Offset(400f, 300f), Offset(440f, 330f))
            frames()
            assertEquals(before.x + 50.0, state.viewport.x)
            assertEquals(before.y + 40.0, state.viewport.y)
            assertEquals(before.zoom, state.viewport.zoom)
            val afterDrag = state.viewport
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(400f, 300f), scrollDelta = Offset(0f, 5f))
            frames()
            assertNotEquals(afterDrag.y, state.viewport.y)
            assertEquals(afterDrag.zoom, state.viewport.zoom)
        } finally {
            scene.close()
        }
    } }
}
