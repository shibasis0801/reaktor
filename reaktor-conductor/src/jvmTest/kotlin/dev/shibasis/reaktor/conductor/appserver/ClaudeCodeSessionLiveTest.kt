package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import kotlin.test.*

/**
 * Opt-in, against the operator's installed `claude`. Set `REAKTOR_CLAUDE_SESSION_TEST=1`.
 *
 * Spends a real turn, which is why it is not in the default suite. It is what separates "the
 * adapter compiles" from "the CLI accepts what the adapter sends".
 */
class ClaudeCodeSessionLiveTest {
    @Test
    fun aSessionRunsATurnAndAcceptsAControlRequest() { runBlocking(Dispatchers.IO) {
        assumeTrue("Set REAKTOR_CLAUDE_SESSION_TEST=1 to spend a real Claude turn",
            System.getenv("REAKTOR_CLAUDE_SESSION_TEST") == "1")
        val workspace = Files.createTempDirectory("claude-session-live").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val runtime = ClaudeCodeSessionRuntime(scope = scope)
            val agent = AgentSpec(AgentId("probe"), "Probe", RuntimeKind.ClaudeCode, "Answer with the number only.")
            val session = runtime.open(AgentRequest(agent, "What is 17*23? Reply with only the number.", workspace.absolutePath))
            try {
                val events = withTimeout(180_000) { session.events.toList() }
                val finished = events.filterIsInstance<AgentEvent.Finished>().single().outcome
                assertTrue(finished.ok, "Live turn failed: ${finished.failure}")
                assertTrue(finished.text.contains("391"), "Unexpected answer: ${finished.text}")
                assertNotNull(finished.session, "The session id is the continuation cache")
                assertNotNull(finished.usage?.outputTokens)

                // Stale control is the ordinary case once a turn has ended, and must read as stale
                // rather than as a transport failure.
                val stale = session.steer("ignore this", expectedTurn = "turn-999")
                assertIs<CommandOutcome.Stale>(stale)
                assertIs<CommandOutcome.Stale>(session.interrupt())
            } finally { session.close() }
        } finally { scope.cancel(); workspace.deleteRecursively() }
    } }
}
