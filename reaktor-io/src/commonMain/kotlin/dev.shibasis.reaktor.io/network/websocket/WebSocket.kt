package dev.shibasis.reaktor.io.network.websocket

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.Frame.Close
import io.ktor.websocket.Frame.Text
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface Logger {
    operator fun invoke(message: String, error: Throwable? = null)
}

typealias UrlProvider = suspend () -> String

enum class Status {
    OPEN,
    CLOSING,
    CLOSED
}

open class WebSocket(
    val httpClient: HttpClient,
    protected var options: WebSocketOptions = WebSocketOptions(),
    protected var urlProvider: UrlProvider,
    protected var reconnectionStrategy: ReconnectionStrategy = ExponentialBackoffStrategy(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    val scope = CoroutineScope(dispatcher + SupervisorJob())

    var status = Status.CLOSED
        private set
    val frames = MutableSharedFlow<Frame>()

    // dont join with assignment, init is behaving weird
    private val connectingMutex: Mutex
    private val disconnectingMutex: Mutex

    init {
        connectingMutex = Mutex()
        disconnectingMutex = Mutex()
        if (!options.startClosed) {
            scope.launch {
                connect()
            }
        }
    }

    private var sessionDeferred = CompletableDeferred<DefaultClientWebSocketSession>()
    suspend fun<T> withSession(fn: suspend DefaultClientWebSocketSession.() -> T) =
        fn(sessionDeferred.await())


    private var sendChannel = Channel<Frame>(options.maxEnqueuedMessages)

    suspend fun connect(url: String? = null) {
        connectingMutex.withLock {
            if (status == Status.OPEN) return

            sessionDeferred = CompletableDeferred()
            try {
                val session = httpClient.webSocketSession(url ?: urlProvider()) {
                    timeout {
                        requestTimeoutMillis = options.connectionTimeout.inWholeMilliseconds
                    }
                }
                status = Status.OPEN
                sessionDeferred.complete(session)
                onConnect()
            } catch (e: Exception) {
                onClose(Result.failure(e))
            }

        }
    }

    protected suspend fun onConnect() {
        withSession {
            scope.launch {
                sendChannel.consumeEach {
                    send(it)
                }
            }
            scope.launch {
                for (frame in incoming)
                    if (status == Status.OPEN)
                        frames.emit(frame)
                onClose(Result.success(Unit))
            }
        }
    }


    protected suspend fun onClose(closeResult: Result<Unit>) {
        disconnectingMutex.withLock {
            if (status == Status.CLOSED) return

            status = Status.CLOSED

            // not withSession, because we call connect and it can cause a deadlock
            sessionDeferred.await().apply {
                val closeReason = if (closeResult.isSuccess) closeReason.await() else null
                if (false && reconnectionStrategy.shouldReconnect(closeResult, closeReason)) {
                    reconnectionStrategy.wait()
                    connect()
                }
            }
        }
    }

    suspend fun sendFrame(frame: Frame) {
        sendChannel.send(frame)
    }

    suspend fun close(reason: CloseReason = CloseReason(CloseReason.Codes.NORMAL, "")) {
        withSession {
            status = Status.CLOSING
            sendFrame(Close(reason))
        }
    }
}

suspend fun WebSocket.sendTextFrame(text: String) = sendFrame(Text(text))

suspend fun WebSocket.onTextFrame(fn: suspend (String) -> Unit) = withSession {
    scope.launch {
        frames
            .filter { it is Text }
            .map { (it as Text).readText() }
            .collect {
                fn(it)
            }
    }
}
