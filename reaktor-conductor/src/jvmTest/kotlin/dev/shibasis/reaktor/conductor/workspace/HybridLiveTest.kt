package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.cli.AntigravityRuntime
import dev.shibasis.reaktor.tooling.SupervisedProcessExecutor
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import org.junit.Assume.assumeTrue
import kotlin.test.*

/**
 * The whole ChatGPT + Gemini seat against the installed harness: two operator transfers around one
 * real Gemini execution.
 *
 * The parser and council tests use an in-process executor, so they prove the protocol and never the
 * harness. This one dispatches the real `agy` binary on the saved Google account, which is the only
 * way to know the seat works rather than that its state machine does. The planner and reviewer
 * halves are transferred by hand here exactly as they are in the pane; no ChatGPT call is made.
 */
class HybridLiveTest {
    @Test fun aRealGeminiTurnCarriesTheTransferredPlanAndReturnsEvidenceForReview() = runBlocking {
        assumeTrue(System.getenv("REAKTOR_GEMINI_LIVE") == "1")
        val root = source()
        val directory = Files.createTempDirectory("hybrid-live")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val executor = SupervisedProcessExecutor(scope = scope)
        val marker = File(root, "marker.txt").readText()
        try {
            AgentWorkspace(root, directory, mapOf(RuntimeKind.Gemini to AntigravityRuntime(executor)),
                discover = { ProviderCapability(it) }).use { workspace ->
                val run = workspace.submit(AgentSubmission("hybrid-live", RuntimeKind.ChatGptGemini,
                    "Report the exact contents of marker.txt in this workspace. Do not modify any file."))
                stopped(workspace, run.id)

                val planning = workspace.handoffs(run.id).single()
                assertEquals(HybridPhase.Planning, planning.phase, workspace.get(run.id).toString())
                assertTrue(planning.packet().contains("marker.txt"), "The packet carries the task an operator transfers")
                workspace.replyHandoff(run.id, HybridReply(planning.id, planning.revision, planning.phase,
                    "Read marker.txt with view_file and quote its exact contents. Change nothing.",
                    listOf("The answer quotes marker.txt verbatim")))

                workspace.resume(run.id)
                stopped(workspace, run.id, 300000)
                val reviewing = workspace.handoffs(run.id).single()
                val observation = requireNotNull(reviewing.observation) { workspace.get(run.id).toString() }
                assertEquals(HybridPhase.Reviewing, reviewing.phase)
                assertTrue(observation.ok, observation.failure)
                assertTrue(observation.result.contains(marker), "Gemini answered from the workspace: ${observation.result.take(400)}")
                assertNotNull(observation.session?.sessionId, "A real Gemini turn reports its own session")
                assertEquals("Antigravity", observation.attributes["executorHarness"])
                assertEquals(emptyList(), observation.changedPaths, "An inspect-only turn changed nothing")

                workspace.replyHandoff(run.id, HybridReply(reviewing.id, reviewing.revision, reviewing.phase,
                    "Gemini quoted marker.txt and touched no file; the receipt is its own session and candidate."))
                workspace.resume(run.id)
                stopped(workspace, run.id)
                val finished = workspace.get(run.id)
                assertEquals(AgentRunStatus.Completed, finished.status, finished.failure)
                assertTrue(finished.output.contains("operator-transferred ChatGPT review"), finished.output)
                val answer = workspace.transcript(finished.threadId).events.last { it.author == Author.Agent(AgentId("chatgptgemini")) }
                assertEquals("unknown", answer.attributes["chatgptUsage"], "ChatGPT usage is never claimed as observed")
                assertEquals("Gemini", answer.attributes["executor"])
                println("ChatGPT + Gemini seat qualified live: session=${observation.session?.sessionId}; usage=${observation.usage}")
            }
        } finally { scope.cancel(); executor.close(); root.deleteRecursively(); directory.toFile().deleteRecursively() }
        Unit
    }

    private fun git(root: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText(); check(process.waitFor() == 0) { output }
    }

    private fun source(): File = Files.createTempDirectory("hybrid-live-source").toFile().also {
        git(it, "init", "-q")
        File(it, "marker.txt").writeText("GEMINI_REAKTOR_" + java.util.UUID.randomUUID())
        git(it, "add", ".")
        git(it, "-c", "user.name=Test", "-c", "user.email=test@localhost", "commit", "-qm", "base")
    }

    private suspend fun stopped(workspace: AgentWorkspace, id: String, timeoutMillis: Long = 30000) =
        withTimeout(timeoutMillis) { while (workspace.get(id).status == AgentRunStatus.Running) delay(50) }
}
