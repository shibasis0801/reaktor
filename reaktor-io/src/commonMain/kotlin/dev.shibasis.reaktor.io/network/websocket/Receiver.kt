package dev.shibasis.reaktor.io.network.websocket

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class Receiver(
    webSocket: WebSocket
): Listener(webSocket) {
    val textFrames = MutableSharedFlow<Frame.Text>(webSocket.options.receiverReplay)
    val binaryFrames = MutableSharedFlow<Frame.Binary>(webSocket.options.receiverReplay)

    override suspend fun onConnect(state: ConnectionState.Open) {
        super.onConnect(state)
        val session = state.session
        val beat = webSocket.options.heartbeat
        var heard = TimeSource.Monotonic.markNow()
        val pulse = beat?.let { launch { pulse(session, it) { heard } } }
        launch {
            val cause = try {
                for (frame in session.incoming) {
                    heard = TimeSource.Monotonic.markNow()
                    when (frame) {
                        is Frame.Text -> if (beat == null || frame.readText() != beat.pong) textFrames.emit(frame)
                        is Frame.Binary -> binaryFrames.emit(frame)
                        else -> Unit
                    }
                }
                null
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                e
            }
            pulse?.cancel()
            val reason = withTimeoutOrNull(1.seconds) { runCatching { session.closeReason.await() }.getOrNull() }
            webSocket.dropped(session, cause, reason)
        }
    }

    private suspend fun pulse(session: DefaultClientWebSocketSession, beat: Heartbeat, heard: () -> TimeSource.Monotonic.ValueTimeMark) {
        while (session.isActive) {
            delay(beat.every)
            val asked = TimeSource.Monotonic.markNow()
            if (runCatching { session.send(Frame.Text(beat.ping)) }.isFailure) return
            delay(beat.wait)
            if (heard() < asked) {
                session.cancel(CancellationException("No ${beat.pong} within ${beat.wait}"))
                return
            }
        }
    }
}
