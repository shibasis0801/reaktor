package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.cli.AntigravityRuntime
import dev.shibasis.reaktor.conductor.cli.CodexRuntime
import dev.shibasis.reaktor.tooling.SupervisedProcessExecutor
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import org.junit.Assume.assumeTrue
import kotlin.test.*

/**
 * The unattended seat against both installed harnesses.
 *
 * [HybridLoopTest] proves the control flow with a scripted planner; this proves the thing that
 * cannot be scripted — that a real ChatGPT turn returns a decision this code can read, and that the
 * run reaches an answer without a person ever being asked for one.
 */
class HybridAutoLiveTest {
    @Test fun chatGptPlansGeminiExecutesAndTheRunFinishesWithoutAnyOperatorTransfer() = runBlocking {
        assumeTrue(System.getenv("REAKTOR_HYBRID_AUTO_LIVE") == "1")
        val root = source()
        val directory = Files.createTempDirectory("hybrid-auto-live")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val executor = SupervisedProcessExecutor(scope = scope)
        try {
            AgentWorkspace(root, directory,
                mapOf(RuntimeKind.Codex to CodexRuntime(executor), RuntimeKind.Gemini to AntigravityRuntime(executor)),
                discover = { ProviderCapability(it) }, plannerRuntime = RuntimeKind.Codex).use { workspace ->
                val run = workspace.submit(AgentSubmission("hybrid-auto-live", RuntimeKind.ChatGptGemini,
                    "Append one line to notes.md reading exactly REAKTOR_LOOP_OK, then read the file back and confirm the line is present.",
                    allowWrites = true))
                stopped(workspace, run.id, 900000)

                val record = workspace.get(run.id)
                assertEquals(AgentRecovery.None, record.recovery, "The loop stopped for a person: ${record.recoveryReason}")
                assertNull(record.pendingHandoff, "No operator transfer was required")
                assertEquals(AgentRunStatus.Completed, record.status, record.failure)

                val handoff = workspace.handoffs(run.id).single()
                assertEquals(HybridPhase.Completed, handoff.phase)
                assertTrue(handoff.completedCycles >= 1, "At least one plan/execute pass ran")
                assertNotNull(handoff.plan, "ChatGPT produced an instruction")
                assertNotNull(handoff.review, "ChatGPT produced a decision on the result")

                val answer = workspace.transcript(record.threadId).events.last { it.author == Author.Agent(AgentId("chatgptgemini")) }
                assertEquals("automatic-chatgpt-agent", answer.attributes["plannerTransport"])
                assertEquals("Gemini", answer.attributes["executor"])

                assertTrue(File(root, "notes.md").readText().contains("REAKTOR_LOOP_OK"),
                    "Gemini actually changed the workspace: ${File(root, "notes.md").readText().take(300)}")
                println("Unattended hybrid loop qualified: ${handoff.completedCycles} cycle(s); " +
                    "plan=${handoff.plan?.text?.take(160)}; review=${handoff.review?.text?.take(200)}")
            }
        } finally { scope.cancel(); executor.close(); root.deleteRecursively(); directory.toFile().deleteRecursively() }
        Unit
    }

    /**
     * The loop against the real executor, with the planner scripted.
     *
     * Separate from the test above because the two unknowns are different: that one asks whether a
     * real ChatGPT turn returns a decision this code can read, this one asks whether two scripted
     * cycles really drive `agy`, carry its conversation forward, and land both edits. Splitting them
     * means a ChatGPT outage cannot hide a broken executor path.
     */
    @Test fun twoScriptedCyclesDriveTheRealGeminiHarnessAndCarryItsConversationForward() = runBlocking {
        assumeTrue(System.getenv("REAKTOR_GEMINI_LIVE") == "1")
        val root = source()
        val directory = Files.createTempDirectory("hybrid-gemini-loop")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val executor = SupervisedProcessExecutor(scope = scope)
        val plans = java.util.concurrent.atomic.AtomicInteger()
        val planner = object : AgentRuntime {
            override val kind = RuntimeKind.Codex
            override fun run(request: AgentRequest) = kotlinx.coroutines.flow.flow {
                val template = requireNotNull(extractReply(request.prompt))
                val answer = when (plans.incrementAndGet()) {
                    1 -> template.copy(text = "Append a line reading exactly LOOP_ONE to notes.md. Change nothing else.",
                        acceptanceCriteria = listOf("notes.md contains LOOP_ONE"), next = HybridNext.Execute)
                    2 -> template.copy(text = "Append a second line reading exactly LOOP_TWO to notes.md. Change nothing else.",
                        acceptanceCriteria = listOf("notes.md contains LOOP_TWO"), next = HybridNext.Execute)
                    else -> template.copy(text = "Both lines are present.", next = HybridNext.Finish)
                }
                emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id,
                    ConductorJson.encodeToString(HybridReply.serializer(), answer), true)))
            }
        }
        try {
            AgentWorkspace(root, directory,
                mapOf(RuntimeKind.Codex to planner, RuntimeKind.Gemini to AntigravityRuntime(executor)),
                discover = { ProviderCapability(it) }, plannerRuntime = RuntimeKind.Codex).use { workspace ->
                val run = workspace.submit(AgentSubmission("gemini-loop", RuntimeKind.ChatGptGemini,
                    "Record two markers in notes.md.", allowWrites = true,
                    model = System.getenv("REAKTOR_GEMINI_MODEL")))
                stopped(workspace, run.id, 900000)

                val record = workspace.get(run.id)
                assertEquals(AgentRecovery.None, record.recovery, record.recoveryReason)
                assertEquals(AgentRunStatus.Completed, record.status, record.failure)

                val handoff = workspace.handoffs(run.id).single()
                assertEquals(2, handoff.completedCycles)
                val notes = File(root, "notes.md").readText()
                assertTrue(notes.contains("LOOP_ONE"), notes.take(400))
                assertTrue(notes.contains("LOOP_TWO"), "The second cycle actually ran against the workspace: ${notes.take(400)}")

                val first = requireNotNull(handoff.cycles.single().observation)
                val second = requireNotNull(handoff.observation)
                assertNotNull(first.session?.sessionId, "A real Gemini turn reports its own conversation")
                assertEquals(first.session?.sessionId, second.session?.sessionId,
                    "Cycle two continued cycle one's conversation rather than starting a new one")
                println("Real Gemini loop qualified: conversation=${first.session?.sessionId}; " +
                    "cycle1=${first.result.take(120)}; cycle2=${second.result.take(120)}")
            }
        } finally { scope.cancel(); executor.close(); root.deleteRecursively(); directory.toFile().deleteRecursively() }
        Unit
    }

    private fun git(root: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText(); check(process.waitFor() == 0) { output }
    }

    private fun source(): File = Files.createTempDirectory("hybrid-auto-source").toFile().also {
        git(it, "init", "-q")
        File(it, "notes.md").writeText("# Notes\n")
        git(it, "add", ".")
        git(it, "-c", "user.name=Test", "-c", "user.email=test@localhost", "commit", "-qm", "base")
    }

    private suspend fun stopped(workspace: AgentWorkspace, id: String, timeoutMillis: Long = 30000) =
        withTimeout(timeoutMillis) { while (workspace.get(id).status == AgentRunStatus.Running) delay(200) }
}
