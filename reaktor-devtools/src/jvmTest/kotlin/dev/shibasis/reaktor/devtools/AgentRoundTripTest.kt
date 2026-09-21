package dev.shibasis.reaktor.devtools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs an agent and a workbench in one process, over a real socket.
 *
 * This is the protocol's only honest test: serialising a descriptor in a unit test proves the
 * serializer works, not that the two halves agree. Here the same declarations are exercised from
 * both ends across an actual connection, which is the thing that breaks in practice.
 */
class AgentRoundTripTest {

    @Test
    fun describesCommandsAndStreams() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val port = 47_931
        val agent = DevToolsAgent(
            applicationId = "ai.bestbuds.test",
            displayName = "Round trip",
            revision = AgentRevision("test", "debug", "run-1", "digest-1"),
        )
        val overrides = OverrideStore()
        agent.register(overrides.handler())
        agent.register(agent.logLevelHandler())
        agent.start()

        val host = DevToolsHost(agent, TcpAgentTransport(port), scope)
        host.start()
        delay(300)

        val attachment = AgentAttachment("127.0.0.1", port, scope)
        try {
            val descriptor = attachment.attach()
            assertEquals("ai.bestbuds.test", descriptor.applicationId)
            assertEquals("digest-1", descriptor.revision.graphDigest)
            assertTrue(descriptor.writable, "a debug agent should accept writes")

            // Capabilities are derived from what is wired, so an absent provider must say so
            // rather than disappear.
            val semantics = descriptor.capability(AgentCapability.Semantics)
            assertTrue(semantics != null && !semantics.available, "semantics should report unavailable")
            assertTrue(
                semantics?.unavailableReason?.contains("provider") == true,
                "the refusal should name what is missing: ${semantics?.unavailableReason}",
            )

            // `logs` both streams and accepts a level change. Declared as two entries it resolved
            // to whichever was added first, and the control half was unreachable — a workbench
            // asking whether it could set the level was told no by a descriptor that could.
            val logs = descriptor.capabilities.filter { it.name == AgentCapability.Logs }
            assertEquals(1, logs.size, "one name, one entry: $logs")
            assertEquals(Fidelity.Interactive, logs.single().fidelity, "the control half must survive the merge")
            assertTrue(logs.single().available, "a read-only facet keeps the capability reachable")

            val accepted = attachment.execute(
                AgentCapability.Overrides,
                "set",
                mapOf("key" to "theme", "value" to "dark"),
            )
            assertTrue(accepted.accepted, accepted.detail)
            assertEquals("dark", overrides["theme"])

            val refused = attachment.execute("nonsense", "go")
            assertTrue(!refused.accepted, "an unknown capability must be refused, not ignored")

            attachment.subscribe(AgentCapability.Logs)
            delay(200)
            agent.log.info("test", "hello from the app")

            val streamed = withTimeout(4_000) {
                var seen: AgentFact.Log? = null
                while (seen == null) {
                    val page = agent.logs.latest()
                    seen = page.facts.filterIsInstance<AgentFact.Log>()
                        .firstOrNull { it.message == "hello from the app" }
                    if (seen == null) delay(50)
                }
                seen
            }
            assertEquals(LogLevel.Info, streamed.level)

            val read = attachment.logs()
            assertTrue(
                read.entries.any { it.message == "hello from the app" },
                "the bounded log read should return what the sink recorded",
            )
        } finally {
            attachment.detach()
            host.stop()
            agent.stop()
        }
    }
}
