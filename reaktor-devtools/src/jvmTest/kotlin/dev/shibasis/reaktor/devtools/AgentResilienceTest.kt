package dev.shibasis.reaktor.devtools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
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
