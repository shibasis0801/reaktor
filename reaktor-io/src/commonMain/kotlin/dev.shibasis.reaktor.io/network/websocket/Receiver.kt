package dev.shibasis.reaktor.io.network.websocket

import io.ktor.websocket.Frame
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

class Receiver(
    webSocket: WebSocket
): Listener(webSocket) {
    val textFrames = MutableSharedFlow<Frame.Text>(webSocket.options.receiverReplay)
    val binaryFrames = MutableSharedFlow<Frame.Binary>(webSocket.options.receiverReplay)

    override suspend fun onConnect(state: ConnectionState.Open) {
        super.onConnect(state)
        val session = state.session
        launch {
            val cause = try {
                for (frame in session.incoming) {
                    when (frame) {
                        is Frame.Text -> textFrames.emit(frame)
                        is Frame.Binary -> binaryFrames.emit(frame)
                        else -> Unit
                    }
                }
                null
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                e
            }
            val reason = withTimeoutOrNull(1.seconds) { runCatching { session.closeReason.await() }.getOrNull() }
            webSocket.dropped(session, cause, reason)
        }
    }
}
