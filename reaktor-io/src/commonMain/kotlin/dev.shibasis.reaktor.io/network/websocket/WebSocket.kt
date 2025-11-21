package dev.shibasis.reaktor.io.network.websocket

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapabilityImpl
import dev.shibasis.reaktor.core.framework.Async
import dev.shibasis.reaktor.io.network.http
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSocketException
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed class ConnectionState {
    data object Idle: ConnectionState()
    data class Connecting(val url: String): ConnectionState()
    data class Open(val session: DefaultClientWebSocketSession): ConnectionState()
    data class Closing(val reason: CloseReason): ConnectionState()
    data class Closed(val reason: CloseReason): ConnectionState()
    data class Failed(val exception: Exception): ConnectionState()
}

data class WebSocketOptions(
    val connectionTimeout: Duration = 4.seconds,
    val eager: Boolean = true,
    val receiverReplay: Int = 0
)

class WebSocket(
    var options: WebSocketOptions = WebSocketOptions(),
    var urlProvider: suspend () -> String, // round-robin if needed, or whatever logic you need.
    var reconnectionStrategy: ReconnectionStrategy = ExponentialBackoffStrategy(),
    // A single-threaded dispatcher is created from this.
    dispatcher: CoroutineDispatcher = Dispatchers.Async,
    val httpClient: HttpClient = http
): ConcurrencyCapability by ConcurrencyCapabilityImpl(dispatcher.limitedParallelism(1)) {
    private val connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = connection

    init {
        if (options.eager) launch { connect() }
    }

    val sender = Sender(this)
    val receiver = Receiver(this)

    suspend fun connect() = withContext {
        if (connection.value is ConnectionState.Open ||
            connection.value is ConnectionState.Connecting) {
            Logger.e("Invalid Connection State: ${connection.value}")
            return@withContext
        }

        val url = urlProvider()
        connection.value = ConnectionState.Connecting(url)
        try {
            connection.value = ConnectionState.Open(
                httpClient.webSocketSession(url) {
                    timeout {
                        requestTimeoutMillis = options.connectionTimeout.inWholeMilliseconds
                    }
                })
            reconnectionStrategy.reset()
        } catch (e: Exception) {
            connection.value = ConnectionState.Failed(e)
        }
    }

    suspend fun disconnect(
        reason: CloseReason = CloseReason(CloseReason.Codes.NORMAL, "")
    ) = withContext {
        if (connection.value is ConnectionState.Closed) return@withContext

        val previousState = connection.value
        connection.value = ConnectionState.Closing(reason)

        if (previousState is ConnectionState.Open) {
            try {
                previousState.session.close(reason)
            } catch (e: Exception) {
                Logger.w("Error during close", e)
            }
        }

        connection.value = ConnectionState.Closed(reason)
    }

    suspend fun reconnect(throwable: Throwable?, closeReason: CloseReason? = null) = withContext {
        if (connection.value is ConnectionState.Closed) return@withContext

        while (reconnectionStrategy.shouldReconnect(throwable, closeReason)) {
            reconnectionStrategy.wait()
            if (connection.value is ConnectionState.Closed) return@withContext

            connect()
            if (connection.value is ConnectionState.Open) return@withContext
        }

        if (connection.value !is ConnectionState.Closed) {
            if (closeReason != null) {
                connection.value = ConnectionState.Closed(closeReason)
            } else {
                connection.value = ConnectionState.Failed(RuntimeException(throwable?.message ?: "Reconnection Failure"))
            }
        }
    }
}
