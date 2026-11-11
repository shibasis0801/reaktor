package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.gestures.draggable2D
import androidx.compose.foundation.gestures.rememberDraggable2DState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics

data class DragAction(val label: String, val run: () -> Unit)

@Composable
fun DragSource(
    label: String,
    onMove: (Offset) -> Unit,
    onDrop: (Offset) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    actions: List<DragAction> = emptyList(),
    content: @Composable () -> Unit,
) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var point by remember { mutableStateOf(Offset.Zero) }
    val move by rememberUpdatedState(onMove)
    val drop by rememberUpdatedState(onDrop)
    val state = rememberDraggable2DState { delta ->
        point += delta
        move(point)
    }
    Box(
        modifier.onGloballyPositioned { coordinates = it }
            .semantics {
                contentDescription = label
                if (actions.isNotEmpty()) customActions = actions.map { action -> CustomAccessibilityAction(action.label) { action.run(); true } }
            }
            .pointerHoverIcon(PointerIcon.Hand)
            .draggable2D(
                state,
                enabled = enabled,
                onDragStarted = { start ->
                    point = coordinates?.takeIf { it.isAttached }?.localToRoot(start) ?: start
                    move(point)
                },
                onDragStopped = { drop(point) },
            ),
    ) { content() }
}
