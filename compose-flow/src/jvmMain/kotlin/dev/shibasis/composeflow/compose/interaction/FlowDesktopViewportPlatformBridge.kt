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
        val mouseWheelEvent = event.nativeEvent as? MouseWheelEvent ?: return interactionState.lastPointerPosition
        val content = window.contentPane as? JComponent ?: return interactionState.lastPointerPosition
        val pointInContent = SwingUtilities.convertPoint(
            mouseWheelEvent.component ?: content,
            mouseWheelEvent.point,
            content,
        )
        return Offset(
            x = pointInContent.x.toFloat() - interactionState.canvasOriginInWindow.x,
            y = pointInContent.y.toFloat() - interactionState.canvasOriginInWindow.y,
        )
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

        val listener = Proxy.newProxyInstance(
            magnificationListenerClass.classLoader,
            arrayOf(magnificationListenerClass),
        ) { _, method, args ->
            if (method.name != "magnify") {
                return@newProxyInstance null
            }

            val gestureEvent = args?.firstOrNull() ?: return@newProxyInstance null
            val magnification = runCatching {
                gestureEvent.javaClass.getMethod("getMagnification").invoke(gestureEvent) as Double
            }.getOrNull() ?: return@newProxyInstance null

            if (abs(magnification) < 0.0001) {
                return@newProxyInstance null
            }

            val anchor = pointerPositionInContent(content)?.let { point ->
                Offset(
                    x = point.x - interactionState.canvasOriginInWindow.x,
                    y = point.y - interactionState.canvasOriginInWindow.y,
                )
            } ?: return@newProxyInstance null

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
            runCatching {
                removeMethod.invoke(null, content, listener)
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
