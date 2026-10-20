package dev.shibasis.reaktor.io.network

import dev.shibasis.reaktor.io.network.websocket.ConnectionState
import dev.shibasis.reaktor.io.network.websocket.ExponentialBackoffStrategy
import dev.shibasis.reaktor.io.network.websocket.WebSocket
import dev.shibasis.reaktor.io.network.websocket.WebSocketOptions
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A server that goes away under an open socket.
 *
 * The state still says `Open` for a moment after the connection has died, and a send in that moment
 * threw the transport's `EOFException` out of `sender.send`. From a coroutine of the caller's own,
 * on Android, that ended the process; Cairn lost its app every time its local server restarted.
 * Every send has to come back as a `Result`, whatever the connection is doing.
 */
class DeadConnectionTest {
    private val server = MockWebServer()
    private val client = HttpClient(OkHttp) { install(WebSockets) }

    @AfterTest
    fun stop() {
        client.close()
        runCatching { server.shutdown() }
    }

    @Test
    fun sendingIntoAConnectionThatHasDiedFailsInsteadOfThrowing() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        val socket = WebSocket(
            options = WebSocketOptions(eager = false),
            urlProvider = { server.url("/ws").toString().replaceFirst("http", "ws") },
            reconnectionStrategy = ExponentialBackoffStrategy(minDelay = 50.milliseconds, maxDelay = 100.milliseconds, maxRetries = 1),
            httpClient = client,
        )
        socket.connect()
        withTimeout(5.seconds) { socket.state.filterIsInstance<ConnectionState.Open>().first() }

        server.shutdown()
        // Sent through the moment the transport notices, and after: none may throw.
        val results = (1..50).map { round -> socket.sender.send("after $round").also { delay(10) } }

        assertTrue(results.any { it.isFailure }, "a send into a dead connection reported success every time")
        socket.disconnect()
    }
}
