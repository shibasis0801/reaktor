package dev.shibasis.composeflow.compose.interaction

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

// References:
// - "Thinking in Compose": keep interaction state explicit instead of hiding it inside a large UI
//   function. The viewport auto-fit policy depends on whether the user has already taken control.
// - xyflow / React Flow fit-view behavior: initial framing should back off once the user starts
//   manipulating the viewport manually.
@Stable
class FlowViewportInteractionState internal constructor(
    initialUserModifiedViewport: Boolean = false,
) {
    var userModifiedViewport by mutableStateOf(initialUserModifiedViewport)
        private set

    var lastPointerPosition by mutableStateOf<Offset?>(null)
        private set

    var canvasOriginInWindow by mutableStateOf(Offset.Zero)
        private set

    var canvasBoundsInWindow by mutableStateOf(Rect.Zero)
        private set

    var canvasDensity by mutableStateOf(1f)
        private set

    fun markViewportAsUserModified() {
        userModifiedViewport = true
    }

    fun clearUserModifiedViewport() {
        userModifiedViewport = false
    }

    fun updatePointerPosition(position: Offset) {
        lastPointerPosition = position
    }

    fun updateCanvasOriginInWindow(origin: Offset) {
        canvasOriginInWindow = origin
    }

    fun updateCanvasGeometry(origin: Offset, visibleBounds: Rect, density: Float) {
        canvasOriginInWindow = origin
        canvasBoundsInWindow = visibleBounds
        canvasDensity = density
    }

    /** AWT uses logical content coordinates; Compose viewports use physical pixels. */
    fun canvasPositionFromWindow(position: Offset): Offset? {
        val physical = position * canvasDensity
        return if (canvasBoundsInWindow.contains(physical)) physical - canvasOriginInWindow else null
    }
}

@Composable
fun rememberFlowViewportInteractionState(
    initialUserModifiedViewport: Boolean = false,
): FlowViewportInteractionState = remember {
    FlowViewportInteractionState(initialUserModifiedViewport)
}
