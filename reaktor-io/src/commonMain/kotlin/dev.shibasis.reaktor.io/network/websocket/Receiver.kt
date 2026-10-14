package dev.shibasis.reaktor.io.network.websocket

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readReason
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.MutableSharedFlow

class Receiver(
    webSocket: WebSocket
): Listener(webSocket) {
    val textFrames = MutableSharedFlow<Frame.Text>(webSocket.options.receiverReplay)
    val binaryFrames = MutableSharedFlow<Frame.Binary>(webSocket.options.receiverReplay)

    override suspend fun onConnect(state: ConnectionState.Open) {
        super.onConnect(state)
        withSession {
            launch {
                try {
                    for (frame in incoming) {
                        webSocket.heard()
                        when (frame) {
                            is Frame.Text -> textFrames.emit(frame)
                            is Frame.Binary -> binaryFrames.emit(frame)
                            is Frame.Close -> onClose(frame)
                            else -> Unit
                        }
                    }
                } catch (e: ClosedReceiveChannelException) {
                    // Only if this is still the connection in use. One the heartbeat dropped has
                    // already been replaced, and reconnecting from here as well would race it.
                    if (webSocket.state.value === state) webSocket.reconnect(e)
                } catch (e: Throwable) {
                    if (webSocket.state.value === state) webSocket.reconnect(e, CloseReason(
                        CloseReason.Codes.INTERNAL_ERROR,
                        e.message ?: "throw from Connection:Receiver"
                    ))
                }
            }
        }
    }

    private fun onClose(frame: Frame.Close) {
        val reason = frame.readReason() ?: return
        launch { webSocket.reconnect(null, reason) }
    }
}