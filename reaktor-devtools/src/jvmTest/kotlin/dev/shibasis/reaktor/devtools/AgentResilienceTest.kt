package dev.shibasis.reaktor.devtools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentResilienceTest {

    @Test
    fun listensAgainAfterTheListenerDies() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val port = 47_933
        val agent = DevToolsAgent(
            applicationId = "ai.bestbuds.relisten",
            displayName = "Relisten",
            revision = AgentRevision("test", "debug", "run-1", "digest-1"),
        )
        agent.start()
        val transport = TcpAgentTransport(port)
        val host = DevToolsHost(agent, transport, scope)
        host.start()
        delay(300)
        try {
            repeat(3) { round ->
                val attachment = AgentAttachment("127.0.0.1", port, scope)
                try {
                    assertEquals("ai.bestbuds.relisten", attachment.attach().applicationId, "round $round")
                } finally {
                    attachment.detach()
                }
                transport.relisten()
                delay(600)
            }
        } finally {
            host.stop()
            agent.stop()
        }
    }

    @Test
    fun aReplacedClientIsToldSoBeforeItsConnectionCloses() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val port = 47_935
        val agent = DevToolsAgent(
            applicationId = "ai.bestbuds.replaced",
            displayName = "Replaced",
            revision = AgentRevision("test", "debug", "run-1", "digest-1"),
        )
        agent.start()
        val host = DevToolsHost(agent, TcpAgentTransport(port), scope)
        host.start()
        delay(300)
        val first = AgentAttachment("127.0.0.1", port, scope)
        val second = AgentAttachment("127.0.0.1", port, scope)
        try {
            first.attach()
            second.attach()
            withTimeout(4_000) { first.connected.first { !it } }
            assertEquals(CarrierFrame.Goodbye.Replaced, first.farewell.value)
            assertEquals(null, second.farewell.value)
            assertEquals("ai.bestbuds.replaced", second.describe().applicationId)
            second.detach()
            val third = AgentAttachment("127.0.0.1", port, scope)
            try {
                third.attach()
                delay(300)
                assertEquals(null, third.farewell.value)
            } finally {
                third.detach()
            }
            delay(300)
            val warnings = agent.logs.latest().facts.filterIsInstance<AgentFact.Log>().filter { it.level >= LogLevel.Warn }
            assertTrue(warnings.isEmpty(), "a client leaving is routine, not a warning: ${warnings.joinToString { it.message }}")
        } finally {
            first.detach()
            host.stop()
            agent.stop()
        }
    }

    @Test
    fun aLinkThatStopsCarryingBytesFailsItsPing() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val port = 47_937
        val agent = DevToolsAgent(
            applicationId = "ai.bestbuds.stalled",
            displayName = "Stalled",
            revision = AgentRevision("test", "debug", "run-1", "digest-1"),
        )
        agent.start()
        val host = DevToolsHost(agent, TcpAgentTransport(port), scope)
        host.start()
        delay(300)
        val relay = StallingRelay(port)
        val attachment = AgentAttachment("127.0.0.1", relay.port, scope)
        try {
            attachment.attach()
            assertTrue(attachment.ping(2_000))
            relay.stall()
            assertFalse(attachment.ping(1_000))
            assertTrue(attachment.connected.value)
        } finally {
            attachment.detach()
            relay.close()
            host.stop()
            agent.stop()
        }
    }

    @Test
    fun aClientSeesTheAppStopAndReachesItsNextRunOnTheSamePort() = runBlocking {
        val port = 47_939
        val desktop = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repeat(5) { run ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val agent = DevToolsAgent(
                applicationId = "ai.bestbuds.restarted",
                displayName = "Restarted",
                revision = AgentRevision("test", "debug", "run-$run", "digest-1"),
            )
            agent.start()
            val host = DevToolsHost(agent, TcpAgentTransport(port), scope)
            host.start()
            val attachment = reach(port, desktop)
            assertEquals("run-$run", attachment.descriptor.value?.revision?.activation)
            host.stop()
            agent.stop()
            scope.cancel()
            withTimeout(1_000) { attachment.connected.first { !it } }
            attachment.detach()
        }
        desktop.cancel()
    }

    private suspend fun reach(port: Int, scope: CoroutineScope): AgentAttachment {
        repeat(100) {
            val attachment = AgentAttachment("127.0.0.1", port, scope)
            if (runCatching { attachment.attach() }.isSuccess) return attachment
            attachment.detach()
            delay(50)
        }
        error("Nothing answered on port $port")
    }

    @Test
    fun aFailureInsideTheAgentIsLoggedInsteadOfThrown() = runBlocking {
        val agent = DevToolsAgent(
            applicationId = "ai.bestbuds.failure",
            displayName = "Failure",
            revision = AgentRevision("test", "debug", "run-1", "digest-1"),
        )
        agent.start()
        try {
            agent.scope().launch { error("connection reset by peer") }.join()
            val logged = agent.logs.latest().facts.filterIsInstance<AgentFact.Log>()
            assertTrue(logged.any { it.level == LogLevel.Warn && "connection reset by peer" in it.message }, logged.joinToString { it.message })
        } finally {
            agent.stop()
        }
    }
}

private class StallingRelay(target: Int) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val sockets = CopyOnWriteArrayList<Socket>()
    @Volatile private var stalled = false
    val port: Int = server.localPort

    init {
        thread(isDaemon = true) {
            val inbound = runCatching { server.accept() }.getOrNull() ?: return@thread
            val outbound = Socket(InetAddress.getLoopbackAddress(), target)
            sockets += inbound
            sockets += outbound
            pump(inbound, outbound)
            pump(outbound, inbound)
        }
    }

    private fun pump(from: Socket, to: Socket) = thread(isDaemon = true) {
        val buffer = ByteArray(8_192)
        runCatching {
            while (true) {
                val read = from.getInputStream().read(buffer)
                if (read < 0) break
                if (!stalled) to.getOutputStream().apply { write(buffer, 0, read); flush() }
            }
        }
    }

    fun stall() {
        stalled = true
    }

    override fun close() {
        sockets.forEach { runCatching { it.close() } }
        server.close()
    }
}
