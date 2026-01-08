package dev.shibasis.reaktor.io.network.websocket

import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapabilityImpl
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import kotlinx.coroutines.flow.filterIsInstance

sealed class Listener(
    val webSocket: WebSocket
): ConcurrencyCapability by ConcurrencyCapabilityImpl(webSocket.coroutineDispatcher) {
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
    suspend inline fun<R> withSession(crossinline fn: suspend DefaultClientWebSocketSession.() -> R): Result<R> =
        withContext {
            session?.run { succeed(fn(this)) } ?: fail("No Session")
        }
}