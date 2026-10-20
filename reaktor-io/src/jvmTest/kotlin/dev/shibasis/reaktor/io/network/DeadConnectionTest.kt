package dev.shibasis.reaktor.io.network

import dev.shibasis.reaktor.io.network.websocket.ConnectionState
import dev.shibasis.reaktor.io.network.websocket.ExponentialBackoffStrategy
import dev.shibasis.reaktor.io.network.websocket.Heartbeat
import dev.shibasis.reaktor.io.network.websocket.WebSocket
import dev.shibasis.reaktor.io.network.websocket.WebSocketOptions
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * A server that goes away under an open socket takes nothing down with it.
 *
 * It took the app. Cairn's phone was connected to a local server that was restarted; the next
 * heartbeat was sent into a session whose connection had died, the send threw, the heartbeat's
 * coroutine had nothing to catch it, and Android ended the process — "FATAL EXCEPTION ...
 * java.io.EOFException" from the socket's own dispatcher.
 */
class DeadConnectionTest {
    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun server(port: Int): EmbeddedServer<*, *> = embeddedServer(CIO, port = port) {
        install(WebSockets)
        routing {
            webSocket("/answers") {
                for (frame in incoming) if (frame is Frame.Text) send(Frame.Text("pong"))
            }
        }
    }.start(wait = false)

    private fun socket(port: Int) = WebSocket(
        options = WebSocketOptions(
            heartbeat = Heartbeat(
                interval = Duration.parse("100ms"),
                silence = Duration.parse("2s"),
                message = { "ping" },
            ),
        ),
        urlProvider = { "ws://127.0.0.1:$port/answers" },
        reconnectionStrategy = ExponentialBackoffStrategy(
            minDelay = Duration.parse("50ms"),
            maxDelay = Duration.parse("200ms"),
            maxRetries = Int.MAX_VALUE,
        ),
        dispatcher = Dispatchers.Default,
    )

    @Test
    fun aServerThatVanishesDoesNotThrowOutOfTheSocket() = runBlocking {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught += error }
        val port = freePort()
        var first: EmbeddedServer<*, *>? = server(port)
        var second: EmbeddedServer<*, *>? = null
        val socket = socket(port)
        try {
            withTimeout(5_000) { socket.state.filterIsInstance<ConnectionState.Open>().first() }

            // Gone at once, no close frame: what a restarted dev server or a dropped network does.
            first?.stop(0, 0)
            first = null
            Thread.sleep(1_500)
            assertTrue(uncaught.isEmpty(), "escaped the socket: ${uncaught.map { it::class.simpleName + ": " + it.message }}")

            // And it is still trying: a server back on the same port gets the socket back.
            second = server(port)
            withTimeout(10_000) {
                socket.state.filterIsInstance<ConnectionState.Open>().first()
            }
            assertTrue(uncaught.isEmpty(), "escaped the socket: ${uncaught.map { it::class.simpleName + ": " + it.message }}")
        } finally {
            socket.disconnect()
            first?.stop(0, 0)
            second?.stop(0, 0)
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun sendingIntoADeadConnectionIsAFailureNotAThrow() = runBlocking {
        val port = freePort()
        val server = server(port)
        val socket = WebSocket(urlProvider = { "ws://127.0.0.1:$port/answers" }, dispatcher = Dispatchers.Default)
        try {
            withTimeout(5_000) { socket.state.filterIsInstance<ConnectionState.Open>().first() }
            server.stop(0, 0)
            Thread.sleep(300)
            // Several, because the first may still be buffered before the break is noticed.
            val results = (1..20).map { runCatching { socket.sender.send("after the server went") } }
            assertTrue(results.all { it.isSuccess }, "send threw: ${results.firstOrNull { it.isFailure }?.exceptionOrNull()}")
        } finally {
            socket.disconnect()
        }
    }
}
