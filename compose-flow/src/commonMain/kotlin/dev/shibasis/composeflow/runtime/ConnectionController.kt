package dev.shibasis.composeflow.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.shibasis.composeflow.model.Connection
import dev.shibasis.composeflow.model.HandleType
import dev.shibasis.composeflow.model.XYPosition

class ConnectionController {
    var isConnecting by mutableStateOf(false)
        private set

    var sourceNodeId by mutableStateOf<String?>(null)
        private set

    var sourceHandleId by mutableStateOf<String?>(null)
        private set

    var sourceHandleType by mutableStateOf<HandleType?>(null)
        private set

    var connectionLineEnd by mutableStateOf(Offset.Zero)
        private set

    var snapTargetNodeId by mutableStateOf<String?>(null)
        private set

    var snapTargetHandleId by mutableStateOf<String?>(null)
        private set

    private val handleBounds = mutableMapOf<HandleKey, Rect>()

    fun registerHandle(nodeId: String, handleId: String, type: HandleType, bounds: Rect) {
        handleBounds[HandleKey(nodeId, handleId, type)] = bounds
    }

    fun unregisterHandle(nodeId: String, handleId: String, type: HandleType) {
        handleBounds.remove(HandleKey(nodeId, handleId, type))
    }

    fun startConnection(nodeId: String, handleId: String, type: HandleType, startPosition: Offset) {
        isConnecting = true
        sourceNodeId = nodeId
        sourceHandleId = handleId
        sourceHandleType = type
        connectionLineEnd = startPosition
        snapTargetNodeId = null
        snapTargetHandleId = null
    }

    fun updateConnectionEnd(position: Offset) {
        connectionLineEnd = position
        val targetType = if (sourceHandleType == HandleType.Source) HandleType.Target else HandleType.Source
        val hit = findClosestHandle(position, targetType)
        snapTargetNodeId = hit?.first?.nodeId
        snapTargetHandleId = hit?.first?.handleId
    }

    fun completeConnection(
        isValidConnection: ((Connection) -> Boolean)?,
        onConnect: ((Connection) -> Unit)?,
    ) {
        if (!isConnecting) return

        val targetNodeId = snapTargetNodeId
        val targetHandleId = snapTargetHandleId

        if (targetNodeId != null && sourceNodeId != null) {
            val connection = if (sourceHandleType == HandleType.Source) {
                Connection(
                    source = sourceNodeId,
                    target = targetNodeId,
                    sourceHandle = sourceHandleId,
                    targetHandle = targetHandleId,
                )
            } else {
                Connection(
                    source = targetNodeId,
                    target = sourceNodeId,
                    sourceHandle = targetHandleId,
                    targetHandle = sourceHandleId,
                )
            }

            if (isValidConnection == null || isValidConnection(connection)) {
                onConnect?.invoke(connection)
            }
        }

        cancelConnection()
    }

    fun cancelConnection() {
        isConnecting = false
        sourceNodeId = null
        sourceHandleId = null
        sourceHandleType = null
        connectionLineEnd = Offset.Zero
        snapTargetNodeId = null
        snapTargetHandleId = null
    }

    private fun findClosestHandle(position: Offset, targetType: HandleType): Pair<HandleKey, Float>? {
        val snapRadius = 30f
        var closest: Pair<HandleKey, Float>? = null
        for ((key, bounds) in handleBounds) {
            if (key.type != targetType) continue
            if (key.nodeId == sourceNodeId) continue
            val center = bounds.center
            val dx = position.x - center.x
            val dy = position.y - center.y
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (dist <= snapRadius && (closest == null || dist < closest.second)) {
                closest = key to dist
            }
        }
        return closest
    }

    internal data class HandleKey(
        val nodeId: String,
        val handleId: String,
        val type: HandleType,
    )
}

val LocalConnectionController = compositionLocalOf<ConnectionController?> { null }

@Composable
fun rememberConnectionController(): ConnectionController = remember { ConnectionController() }
