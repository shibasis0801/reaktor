package dev.shibasis.reaktor.io.network.websocket

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapabilityImpl
import dev.shibasis.reaktor.core.framework.Async
import dev.shibasis.reaktor.io.network.http
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

sealed class ConnectionState {
    data object Idle: ConnectionState()
    data class Connecting(val url: String): ConnectionState()
    data class Open(val session: DefaultClientWebSocketSession): ConnectionState()
    data class Closing(val reason: CloseReason): ConnectionState()
    data class Closed(val reason: CloseReason): ConnectionState()
    data class Failed(val exception: Exception): ConnectionState()
}

/**
 * How a socket finds out that it has stopped working.
 *
 * A connection can die without closing. A NAT that forgets an idle mapping, a phone that changed
 * networks, an emulator's address translation: the far end is gone, nothing arrives to say so, and
 * the socket goes on reporting `Open`. Everything sent into it is lost and nothing comes out, for
 * as long as the app runs.
 *
 * With a heartbeat the socket sends [message] every [interval] while it is open, and if it has
 * received nothing at all — the reply, or anything else — for [silence], it drops the connection
 * and reconnects through its [ReconnectionStrategy]. What [message] is, and what the server answers
 * it with, is the app's protocol; the socket only needs some frame to come back.
 */
data class Heartbeat(
    val interval: Duration,
    val silence: Duration,
    val message: suspend () -> String,
)

data class WebSocketOptions(
    val connectionTimeout: Duration = 4.seconds,
    val eager: Boolean = true,
    val receiverReplay: Int = 0,
    /** Off by default. A socket meant to stay open for a long time wants one; see [Heartbeat]. */
    val heartbeat: Heartbeat? = null,
)

/**
 * The last line under a socket's own coroutines. A transport failure that reached one of them was
 * an uncaught exception, and on Android that ends the app; a socket is there to survive the
 * network, so it is logged here and the socket goes on.
 */
internal val socketFailuresAreLogged = CoroutineExceptionHandler { _, error ->
    Logger.w(error) { "Reaktor:WebSocket — a failure reached the socket's scope: ${error::class.simpleName}" }
}

/** Why a socket that never closed was dropped. */
class SilentConnection(silence: Duration) : RuntimeException("Nothing heard for $silence")
typealias UrlProvider = suspend () -> String

open class WebSocket(
    var options: WebSocketOptions = WebSocketOptions(),
    var urlProvider: UrlProvider, // round-robin if needed, or whatever logic you need.
    var reconnectionStrategy: ReconnectionStrategy = ExponentialBackoffStrategy(),
    // A single-threaded dispatcher is created from this.
    dispatcher: CoroutineDispatcher = Dispatchers.Async,
    val httpClient: HttpClient = http
): ConcurrencyCapability by ConcurrencyCapabilityImpl(
    context = socketFailuresAreLogged,
    // Named. The first parameter is a context, and a dispatcher passed there positionally is
    // overridden by the default one — which is how every socket ran on the whole default pool, with
    // connect, close and both listeners free to interleave, while this said it was single-threaded.
    coroutineDispatcher = dispatcher.limitedParallelism(1),
) {
    private val connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = connection

    init {
        if (options.eager) launch { connect() }
    }

    val sender = Sender(this)
    val receiver = Receiver(this)

    private var lastHeard = TimeSource.Monotonic.markNow()

    /** Called for every frame that arrives. Anything at all counts as the far end being there. */
    internal fun heard() {
        lastHeard = TimeSource.Monotonic.markNow()
    }

    init {
        options.heartbeat?.let { beat -> launch { keepAlive(beat) } }
    }

    private suspend fun keepAlive(beat: Heartbeat) {
        while (true) {
            delay(beat.interval)
            val open = connection.value as? ConnectionState.Open ?: continue
            // Nothing in one beat may end the heartbeat: a loop that died here stopped the very
            // thing that notices a dead connection, and took the process with it.
            try {
                if (lastHeard.elapsedNow() > beat.silence) {
                    Logger.w { "Reaktor:WebSocket — nothing heard for ${beat.silence}; dropping the connection." }
                    drop(open, SilentConnection(beat.silence))
                } else {
                    sender.send(beat.message())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Logger.w(error) { "Reaktor:WebSocket — a heartbeat failed; the next one tries again." }
            }
        }
    }

    /**
     * Abandons [open] and reconnects, if it is still the current connection.
     *
     * Not [disconnect]: that is a decision to stop, and leaves the socket `Closed`. And not a polite
     * close either — on a connection that has silently died, a close frame waits for an answer that
     * will never come.
     */
    private suspend fun drop(open: ConnectionState.Open, why: Exception) = withContext {
        if (connection.value !== open) return@withContext
        connection.value = ConnectionState.Failed(why)
        runCatching { open.session.cancel() }
        reconnect(why)
    }

    suspend fun connect() = withContext {
        if (connection.value is ConnectionState.Open ||
            connection.value is ConnectionState.Connecting) {
            Logger.e("Invalid Connection State: ${connection.value}")
            return@withContext
        }

        val url = urlProvider()
        connection.value = ConnectionState.Connecting(url)
        try {
            val session = httpClient.webSocketSession(url) {
                timeout {
                    requestTimeoutMillis = options.connectionTimeout.inWholeMilliseconds
                }
            }
            // Before the state says Open, so a heartbeat that looks at once does not see the
            // silence of the previous connection and drop this one.
            heard()
            connection.value = ConnectionState.Open(session)
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
