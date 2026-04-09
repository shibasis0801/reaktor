package dev.shibasis.composeflow.compose.interaction

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import dev.shibasis.composeflow.runtime.ReactFlowState

// Platform bridges are intentionally narrow. compose-flow stays generic; desktop hosts can opt
// into native gesture support without leaking window/toolkit details into graph/editor code.
interface FlowViewportPlatformBridge {
    fun resolveScrollAnchor(
        event: PointerEvent,
        interactionState: FlowViewportInteractionState,
    ): Offset? = interactionState.lastPointerPosition

    fun installViewportGestures(
        state: ReactFlowState,
        interactionState: FlowViewportInteractionState,
        config: FlowViewportGestureConfig,
    ): FlowViewportPlatformGestureSubscription? = null
}

fun interface FlowViewportPlatformGestureSubscription {
    fun dispose()
}

val LocalFlowViewportPlatformBridge = staticCompositionLocalOf<FlowViewportPlatformBridge?> { null }

@Composable
fun ProvideFlowViewportPlatformBridge(
    bridge: FlowViewportPlatformBridge?,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalFlowViewportPlatformBridge provides bridge) {
        content()
    }
}
