package dev.shibasis.composeflow.compose.primitives

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.shibasis.composeflow.model.Connection
import dev.shibasis.composeflow.model.Edge
import dev.shibasis.composeflow.model.HandleType
import dev.shibasis.composeflow.runtime.ConnectionController

@Composable
internal fun EdgeReconnectHandle(
    edge: Edge,
    anchor: FlowAnchor,
    isSource: Boolean,
    connectionController: ConnectionController?,
    onReconnect: ((oldEdge: Edge, newConnection: Connection) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (!edge.reconnectable || connectionController == null || onReconnect == null) return

    val handleSize = 12.dp
    Box(
        modifier = modifier
            .offset(
                x = with(anchor) { point.x.dp - handleSize / 2 },
                y = with(anchor) { point.y.dp - handleSize / 2 },
            )
            .size(handleSize)
            .pointerInput(edge.id, isSource) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val nodeId = if (isSource) edge.source else edge.target
                    val handleId = (if (isSource) edge.sourceHandle else edge.targetHandle) ?: ""
                    val handleType = if (isSource) HandleType.Source else HandleType.Target

                    connectionController.startConnection(
                        nodeId = nodeId,
                        handleId = handleId,
                        type = handleType,
                        startPosition = down.position,
                    )
                    down.consume()

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            connectionController.completeConnection(null) { newConn ->
                                onReconnect(edge, newConn)
                            }
                            break
                        }
                        connectionController.updateConnectionEnd(change.position)
                        change.consume()
                    }
                }
            },
    )
}
