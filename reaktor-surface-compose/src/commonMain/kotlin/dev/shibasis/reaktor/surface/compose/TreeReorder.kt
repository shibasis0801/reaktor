package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import dev.shibasis.reaktor.surface.Edge

@Composable
internal fun CollectionReorderHandle(host: CollectionHost, key: String, modifier: Modifier, content: @Composable () -> Unit) {
    if (host.onMove == null) return
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var point by remember { mutableStateOf(Offset.Zero) }
    val drag = rememberDraggableState { delta -> point += Offset(0f, delta) }
    Box(
        modifier.onGloballyPositioned { coordinates = it }
            .semantics {
                contentDescription = "Reorder $key"
                customActions = listOf(
                    CustomAccessibilityAction("Move up") { host.moveAdjacent(key, Edge.First) },
                    CustomAccessibilityAction("Move down") { host.moveAdjacent(key, Edge.Last) },
                )
            }
            .draggable(
                drag,
                Orientation.Vertical,
                onDragStarted = { point = coordinates?.takeIf { it.isAttached }?.localToWindow(it) ?: Offset.Zero },
                onDragStopped = { host.moveAt(key, point) },
            ),
    ) { content() }
}
