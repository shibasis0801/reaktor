package dev.shibasis.composeflow.compose.interaction

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.shibasis.composeflow.runtime.ReactFlowState
import java.awt.Robot
import java.awt.Window
import javax.swing.JComponent
import javax.swing.SwingUtilities
import org.junit.Assume.assumeTrue
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FlowDesktopViewportPlatformBridgeTest {
    @Test
    fun nativePinchTargetsOnlyTheHoveredCanvasAndOldListenersAreRemovedOnEverySwitch() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("mac"))
        lateinit var window: ComposeWindow
        SwingUtilities.invokeAndWait {
            window = ComposeWindow().apply {
                setSize(640, 480)
                isAlwaysOnTop = true
                isVisible = true
            }
        }
        try {
            val bridge = FlowDesktopViewportPlatformBridge(window)
            val scale = window.graphicsConfiguration.defaultTransform.scaleX.toFloat()
            val left = FlowViewportInteractionState().apply {
                updateCanvasGeometry(Offset.Zero, Rect(0f, 0f, 300 * scale, 400 * scale), scale)
            }
            val right = FlowViewportInteractionState().apply {
                updateCanvasGeometry(Offset(300 * scale, 0f), Rect(300 * scale, 0f, 600 * scale, 400 * scale), scale)
            }
            val main = ReactFlowState()
            val compute = ReactFlowState()
            repeat(3) {
                lateinit var mainLease: FlowViewportPlatformGestureSubscription
                lateinit var computeLease: FlowViewportPlatformGestureSubscription
                SwingUtilities.invokeAndWait {
                    mainLease = assertNotNull(bridge.installViewportGestures(main, left, FlowViewportGestureConfig()))
                    computeLease = assertNotNull(bridge.installViewportGestures(compute, right, FlowViewportGestureConfig()))
                    assertEquals(2, listenerCount(window))
                }
                val mainBefore = main.viewport
                val computeBefore = compute.viewport
                pinch(window, 150, 160, 0.1)
                assertEquals(mainBefore.zoom * exp(0.2), main.viewport.zoom, 1e-10)
                assertEquals(computeBefore, compute.viewport, "The other mounted canvas must not receive this pinch")
                val expectedAnchorX = 150.0 * scale
                val beforeX = (expectedAnchorX - mainBefore.x) / mainBefore.zoom
                assertEquals(beforeX, main.screenToFlowPosition(expectedAnchorX, 160.0 * scale).x, 1e-7)
                SwingUtilities.invokeAndWait {
                    mainLease.dispose()
                    mainLease.dispose()
                    assertEquals(1, listenerCount(window), "Dispose must remove exactly this listener, including on repeated close")
                }
                val oldMain = main.viewport
                pinch(window, 420, 160, 0.1)
                assertEquals(computeBefore.zoom * exp(0.2), compute.viewport.zoom, 1e-10)
                assertEquals(oldMain, main.viewport, "A closed canvas must never steal the next canvas's gesture")
                val oldCompute = compute.viewport
                pinch(window, 620, 160, 0.1)
                assertEquals(oldCompute, compute.viewport, "Pinching chrome outside either canvas must not zoom")
                SwingUtilities.invokeAndWait {
                    computeLease.dispose()
                    assertEquals(0, listenerCount(window))
                }
            }
        } finally { SwingUtilities.invokeAndWait { window.dispose() } }
    }

    private fun listenerCount(window: ComposeWindow): Int {
        val handler = (window.contentPane as JComponent)
            .getClientProperty("com.apple.eawt.event.internalGestureHandler") ?: return 0
        return (handler.javaClass.getDeclaredField("magnifiers").apply { isAccessible = true }.get(handler) as List<*>).size
    }

    private fun pinch(window: ComposeWindow, x: Int, y: Int, magnification: Double) {
        val location = window.contentPane.locationOnScreen
        Robot().apply { mouseMove(location.x + x, location.y + y); waitForIdle() }
        val point = SwingUtilities.convertPoint(window.contentPane, x, y, window)
        val dispatch = Class.forName("com.apple.eawt.event.GestureHandler").getDeclaredMethod(
            "handleGestureFromNative", Window::class.java, Int::class.javaPrimitiveType,
            Double::class.javaPrimitiveType, Double::class.javaPrimitiveType,
            Double::class.javaPrimitiveType, Double::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        dispatch.invoke(null, window, 3, point.x.toDouble(), point.y.toDouble(), magnification, 0.0)
        SwingUtilities.invokeAndWait { }
    }
}
