package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import kotlin.test.*

/**
 * Opt-in, against the operator's installed `codex app-server`.
 *
 * Set `REAKTOR_CODEX_APPSERVER_TEST=1` to run it. It spends a real provider turn, which is why it
 * is not part of the default suite, and it is the only thing that proves the adapter talks to the
 * binary rather than to a fixture of what the binary was believed to do.
 */
class CodexAppServerLiveTest {
    @Test
    fun aRealTurnReportsItsEffectiveEffortAndStreamsBeforeItCompletes() { runBlocking(Dispatchers.IO) {
        assumeTrue("Set REAKTOR_CODEX_APPSERVER_TEST=1 to spend a real Codex turn",
            System.getenv("REAKTOR_CODEX_APPSERVER_TEST") == "1")
        val workspace = Files.createTempDirectory("codex-appserver-live").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val runtime = CodexAppServerRuntime(scope = scope)
            val agent = AgentSpec(AgentId("probe"), "Probe", RuntimeKind.Codex,
                "Answer with the number only.", effort = NativeEffort("low"))
            val events = withTimeout(180_000) {
                runtime.run(AgentRequest(agent, "What is 17*23? Reply with only the number.", workspace.absolutePath)).toList()
            }

            val started = events.filterIsInstance<AgentEvent.Started>().single()
            assertNotNull(started.session, "A thread id is the continuation cache; without it nothing can resume")

            // exec --json can never answer this. The App Server reports the thread's real effort.
            val finished = events.filterIsInstance<AgentEvent.Finished>().single().outcome
            assertTrue(finished.ok, "Live turn failed: ${finished.failure}")
            assertNotNull(finished.effort.observed, "thread/start reports reasoningEffort; it must reach the outcome")
            assertFalse(finished.effort.unknownEffective)
            assertTrue(finished.text.contains("391"), "Unexpected answer: ${finished.text}")

            // Token-level streaming is the other thing the batch transport does not do.
            assertTrue(events.filterIsInstance<AgentEvent.Delta>().isNotEmpty(), "No streamed deltas arrived")
            assertNotNull(finished.usage?.inputTokens, "thread/tokenUsage/updated should have been seen")
        } finally {
            scope.cancel(); workspace.deleteRecursively()
        }
    } }
}
