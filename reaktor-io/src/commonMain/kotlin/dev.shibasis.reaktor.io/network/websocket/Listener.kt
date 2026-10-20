package dev.shibasis.reaktor.io.network.websocket

import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapabilityImpl
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterIsInstance

sealed class Listener(
    val webSocket: WebSocket
): ConcurrencyCapability by ConcurrencyCapabilityImpl(
    context = socketFailuresAreLogged,
    coroutineDispatcher = webSocket.coroutineDispatcher,
) {
    init {
        launch {
            webSocket.state
                .filterIsInstance<ConnectionState.Open>()
                .collect { onConnect(it) }
        }
    }

    // must not be used directly
    var session: DefaultClientWebSocketSession? = null
    open suspend fun onConnect(state: ConnectionState.Open) {
        session = state.session

    }
    /**
     * Runs [fn] against the socket's open session, or fails with "No Session" if it has none — and
     * fails, rather than throws, when the session is there and its connection is not.
     *
     * A connection can die between the state saying `Open` and the frame going out: a server
     * restarted, a network dropped. The send then throws whatever the transport saw, an
     * `EOFException` from OkHttp, and a caller sending from a coroutine of its own — the heartbeat
     * did — had it thrown out of that coroutine and the process ended. The receiver notices the
     * same break and reconnects; the sender only has to report it.
     *
     * The session is read from the socket's state, not from [session]. That copy is set by this
     * listener's own collector, which runs on its own schedule — and the first thing anyone does
     * with a socket is watch the same state and send the moment it says `Open`. When the caller's
     * collector ran first, the hello found no session and was dropped; a server then had a
     * connection that never said who it was. Cairn's phone was connected and invisible after two
     * restarts out of two.
     */
    suspend inline fun<R> withSession(crossinline fn: suspend DefaultClientWebSocketSession.() -> R): Result<R> =
        withContext {
            val open = webSocket.state.value as? ConnectionState.Open
            val session = open?.session ?: return@withContext fail("No Session")
            try {
                succeed(fn(session))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
}