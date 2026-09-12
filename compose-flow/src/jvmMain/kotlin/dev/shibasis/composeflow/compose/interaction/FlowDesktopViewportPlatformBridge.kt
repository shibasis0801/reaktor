package dev.shibasis.composeflow.compose.interaction

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import dev.shibasis.composeflow.compose.theme.FlowSizing
import dev.shibasis.composeflow.runtime.ReactFlowState
import java.awt.MouseInfo
import java.awt.Point
import java.awt.event.MouseWheelEvent
import java.lang.reflect.Proxy
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.exp

class FlowDesktopViewportPlatformBridge internal constructor(
    private val window: ComposeWindow,
) : FlowViewportPlatformBridge {
    override fun resolveScrollAnchor(
        event: PointerEvent,
        interactionState: FlowViewportInteractionState,
    ): Offset? {
        // Compose has already converted this event to the receiving canvas's pixel space.
        return event.changes.firstOrNull()?.position
    }

    override fun resolveScrollPan(
        event: PointerEvent,
        interactionState: FlowViewportInteractionState,
    ): Offset? {
        if (!isMacOs()) return null
        val wheel = event.nativeEvent as? MouseWheelEvent
        val delta = event.changes.fold(Offset.Zero) { total, change -> total + change.scrollDelta }
        val amount = wheel?.scrollAmount?.toFloat() ?: 1f
        // Matches Compose Foundation's MacOSCocoaConfig (native deltas already accelerate).
        return if (wheel?.scrollType == MouseWheelEvent.WHEEL_BLOCK_SCROLL) {
            Offset(delta.x * interactionState.canvasBoundsInWindow.width,
                delta.y * interactionState.canvasBoundsInWindow.height) * -amount
        } else delta * (-FlowSizing.macWheelPanFactor * interactionState.canvasDensity * amount).toFloat()
    }

    override fun installViewportGestures(
        state: ReactFlowState,
        interactionState: FlowViewportInteractionState,
        config: FlowViewportGestureConfig,
    ): FlowViewportPlatformGestureSubscription? {
        if (!isMacOs()) return null
        val content = window.contentPane as? JComponent ?: return null
        val gestureUtilitiesClass = runCatching { Class.forName("com.apple.eawt.event.GestureUtilities") }.getOrNull() ?: return null
        val gestureListenerClass = runCatching { Class.forName("com.apple.eawt.event.GestureListener") }.getOrNull() ?: return null
        val magnificationListenerClass = runCatching { Class.forName("com.apple.eawt.event.MagnificationListener") }.getOrNull() ?: return null
        val addMethod = runCatching {
            gestureUtilitiesClass.getMethod("addGestureListenerTo", JComponent::class.java, gestureListenerClass)
        }.getOrNull() ?: return null
        val removeMethod = runCatching {
            gestureUtilitiesClass.getMethod("removeGestureListenerFrom", JComponent::class.java, gestureListenerClass)
        }.getOrNull() ?: return null

        var disposed = false
        val listener = Proxy.newProxyInstance(
            magnificationListenerClass.classLoader,
            arrayOf(magnificationListenerClass),
        ) { proxy, method, args ->
            // GestureHandler stores these proxies in Lists and removes them with equals().
            // Returning null here left disposed canvases consuming the next canvas's pinch.
            when (method.name) {
                "equals" -> return@newProxyInstance proxy === args?.firstOrNull()
                "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                "toString" -> return@newProxyInstance "FlowMagnificationListener"
            }
            if (disposed) return@newProxyInstance null
            if (method.name != "magnify") {
                return@newProxyInstance null
            }

            val gestureEvent = args?.firstOrNull() ?: return@newProxyInstance null
            val magnification = runCatching {
                gestureEvent.javaClass.getMethod("getMagnification").invoke(gestureEvent) as Double
            }.getOrNull() ?: return@newProxyInstance null

            if (!magnification.isFinite() || abs(magnification) < 0.0001) {
                return@newProxyInstance null
            }

            val anchor = pointerPositionInContent(content)
                ?.let(interactionState::canvasPositionFromWindow) ?: return@newProxyInstance null

            val factor = exp(magnification * FlowSizing.pinchZoomSensitivity)
            interactionState.markViewportAsUserModified()
            state.zoomBy(
                factor = factor,
                anchorX = anchor.x.toDouble(),
                anchorY = anchor.y.toDouble(),
                minZoom = config.minZoom,
                maxZoom = config.maxZoom,
            )
            runCatching {
                gestureEvent.javaClass.getMethod("consume").invoke(gestureEvent)
            }
            null
        }

        val installed = runCatching {
            addMethod.invoke(null, content, listener)
        }.isSuccess
        if (!installed) return null

        return FlowViewportPlatformGestureSubscription {
            if (!disposed) {
                disposed = true
                runCatching { removeMethod.invoke(null, content, listener) }
            }
        }
    }

    private fun pointerPositionInContent(content: JComponent): Offset? {
        val location = MouseInfo.getPointerInfo()?.location ?: return null
        val localPoint = Point(location)
        SwingUtilities.convertPointFromScreen(localPoint, content)
        return Offset(localPoint.x.toFloat(), localPoint.y.toFloat())
    }

    private fun isMacOs(): Boolean =
        System.getProperty("os.name").lowercase().contains("mac")
}

@Composable
fun rememberFlowDesktopViewportPlatformBridge(
    window: ComposeWindow?,
): FlowViewportPlatformBridge? = remember(window) {
    window?.let(::FlowDesktopViewportPlatformBridge)
}
