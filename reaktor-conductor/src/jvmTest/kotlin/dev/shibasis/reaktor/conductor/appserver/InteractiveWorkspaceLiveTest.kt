package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.workspace.*
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import kotlin.test.*

/**
 * The interactive transports driven through the shared workspace, not standalone.
 *
 * Standalone adapter tests prove the wire format. This proves the thing a person actually uses:
 * the workspace's continuation, persistence and capability reporting on top of it. Opt-in behind
 * `REAKTOR_INTERACTIVE_WORKSPACE_TEST=1`; it spends real turns.
 */
class InteractiveWorkspaceLiveTest {
    @Test
    fun aWorkspaceOnInteractiveTransportsObservesEffortAndContinuesItsThread() { runBlocking(Dispatchers.IO) {
        assumeTrue("Set REAKTOR_INTERACTIVE_WORKSPACE_TEST=1 to spend real turns",
            System.getenv("REAKTOR_INTERACTIVE_WORKSPACE_TEST") == "1")
        val root = Files.createTempDirectory("interactive-ws-root").toFile()
        val data = Files.createTempDirectory("interactive-ws-data")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            AgentWorkspaceConnection.open(root, data, AgentRuntimes.interactive(scope)).use { owner ->
                // The capability record must say these hold sessions; the batch pair does not.
                val codex = owner.info().capability(RuntimeKind.Codex)
                assertNotNull(codex)
                assertTrue(codex.session.usable, "Interactive runtimes must report a usable session")

                val first = terminal(owner, owner.submit(AgentSubmission(
                    "interactive-1", RuntimeKind.Codex, "Reply with only the number: 17*23.",
                    effort = NativeEffort("low"))))
                assertEquals(AgentRunStatus.Completed, first.status, "failure=${first.failure}")
                assertTrue(first.output.contains("391"), "Unexpected answer: ${first.output}")
                assertNotNull(first.effort.observed, "thread/start reports the effective effort")
                assertNotNull(first.session, "A thread id has to reach the record for continuation")
                assertNotNull(first.usage?.inputTokens)

                // Continuation on the same thread: the workspace must reuse the native session.
                val second = terminal(owner, owner.submit(AgentSubmission(
                    "interactive-2", RuntimeKind.Codex, "Now add 9 to it. Reply with only the number.",
                    threadId = first.threadId, effort = NativeEffort("low"))))
                assertEquals(AgentRunStatus.Completed, second.status, "failure=${second.failure}")
                assertTrue(second.output.contains("400"), "Continuation lost its context: ${second.output}")
                assertEquals(first.session?.sessionId, second.session?.sessionId, "Same thread, same native session")
            }
        } finally { scope.cancel(); root.deleteRecursively(); data.toFile().deleteRecursively() }
    } }

    private suspend fun terminal(connection: AgentWorkspaceConnection, initial: AgentRunRecord): AgentRunRecord =
        withTimeout(300_000) {
            var record = initial
            while (record.status == AgentRunStatus.Running) record = connection.wait(record.id, record.revision, 10_000)
            record
        }
}
