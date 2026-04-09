package dev.shibasis.composeflow.compose.interaction

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

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

    fun markViewportAsUserModified() {
        userModifiedViewport = true
    }

    fun clearUserModifiedViewport() {
        userModifiedViewport = false
    }
}

@Composable
fun rememberFlowViewportInteractionState(
    initialUserModifiedViewport: Boolean = false,
): FlowViewportInteractionState = remember {
    FlowViewportInteractionState(initialUserModifiedViewport)
}
