package dev.shibasis.reaktor.io.network.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.websocket.CloseReason
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WebSocketReconnectTest {
    private val server = MockWebServer()
    private val client = HttpClient(OkHttp) { install(WebSockets) }

    @AfterTest
    fun stop() {
        client.close()
        server.shutdown()
    }

    private fun answer(greeting: String, close: CloseReason? = null) {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: Response) {
                webSocket.send(greeting)
                close?.let { webSocket.close(it.code.toInt(), it.message) }
            }

            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }))
    }

    private fun socket(retries: Int = 10) = WebSocket(
        options = WebSocketOptions(eager = false),
        urlProvider = { server.url("/room").toString().replaceFirst("http", "ws") },
        reconnectionStrategy = ExponentialBackoffStrategy(minDelay = 50.milliseconds, maxDelay = 100.milliseconds, maxRetries = retries),
        httpClient = client,
    )

    @Test
    fun aServerCloseReconnectsAndTheSameReceiverKeepsDelivering() = runBlocking {
        answer("first", CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
        answer("second")
        val socket = socket()
        val states = mutableListOf<ConnectionState>()
        val watching = launch { socket.state.collect { states += it } }
        val frames = withTimeout(10.seconds) {
            socket.receiver.textFrames.onSubscription { socket.connect() }.map { it.readText() }.take(2).toList()
        }
        assertEquals(listOf("first", "second"), frames)
        assertEquals(2, server.requestCount)
        val dropped = states.filterIsInstance<ConnectionState.Failed>().map { it.exception }.filterIsInstance<DroppedConnection>().single()
        assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, dropped.reason?.code)
        assertIs<ConnectionState.Open>(socket.state.value)
        watching.cancel()
        socket.disconnect()
    }

    @Test
    fun whenRetriesRunOutTheSocketEndsClosedWithTheServersReason() = runBlocking {
        answer("only", CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
        val socket = socket(retries = 1)
        socket.connect()
        val closed = withTimeout(10.seconds) { socket.state.filterIsInstance<ConnectionState.Closed>().first() }
        assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closed.reason.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun disconnectingNeverReconnects() = runBlocking {
        answer("hello")
        answer("unexpected")
        val socket = socket()
        withTimeout(10.seconds) { socket.receiver.textFrames.onSubscription { socket.connect() }.first() }
        socket.disconnect()
        delay(500.milliseconds)
        assertEquals(1, server.requestCount)
        assertTrue(socket.state.value is ConnectionState.Closed)
    }
}
