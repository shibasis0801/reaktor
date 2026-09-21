package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * The unattended ChatGPT + Gemini loop.
 *
 * These drive the seat through a scripted planner rather than a real one, because what is under
 * test is the control flow — how many passes run, what each half is shown, where it stops — and a
 * real planner would make every one of those a coin flip.
 */
class HybridLoopTest {

    @Test fun theSeatPlansExecutesAndRePlansUntilChatGptSaysItIsDoneWithNoHumanInTheLoop() = runBlocking {
        val root = source(); val directory = Files.createTempDirectory("hybrid-loop")
        val plans = AtomicInteger()
        val executions = AtomicInteger()
        val packets = mutableListOf<String>()
        val executorPrompts = mutableListOf<String>()

        // Two instructions, then done. The third reply is what ends the loop; nothing else can.
        val planner = scripted { packet ->
            packets += packet
            when (plans.incrementAndGet()) {
                1 -> reply(packet, "Add the first marker", HybridNext.Execute, listOf("marker one present"))
                2 -> reply(packet, "Now add the second marker", HybridNext.Execute, listOf("marker two present"))
                else -> reply(packet, "Both markers are present and verified.", HybridNext.Finish)
            }
        }
        val gemini = object : AgentRuntime {
            override val kind = RuntimeKind.Gemini
            override fun run(request: AgentRequest) = flow {
                executorPrompts += request.prompt
                val index = executions.incrementAndGet()
                File(request.workingDirectory, "graph.kt").appendText("\nmarker $index")
                emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id,
                    "wrote marker $index", true, session = ProviderSession(RuntimeKind.Gemini, "gemini-conversation"))))
            }
        }

        AgentWorkspace(root, directory, mapOf(RuntimeKind.Codex to planner, RuntimeKind.Gemini to gemini),
            discover = { ProviderCapability(it) }, plannerRuntime = RuntimeKind.Codex).use { workspace ->
            val run = workspace.submit(AgentSubmission("loop", RuntimeKind.ChatGptGemini, "Add two markers", allowWrites = true))
            stopped(workspace, run.id)

            val record = workspace.get(run.id)
            assertEquals(AgentRunStatus.Completed, record.status, record.failure)
            // The whole point: it ran to completion without ever stopping for a person.
            assertEquals(AgentRecovery.None, record.recovery)
            assertNull(record.pendingHandoff, "No handoff is left waiting for an operator")

            assertEquals(3, plans.get(), "Plan, review-and-replan, review-and-finish")
            assertEquals(2, executions.get(), "One execution per instruction")

            val handoff = workspace.handoffs(run.id).single()
            assertEquals(HybridPhase.Completed, handoff.phase)
            assertEquals(2, handoff.completedCycles)
            assertEquals(1, handoff.cycles.size, "The pass a Finish closes stays open rather than being duplicated")

            // Cycle two's planner sees cycle one's instruction and what Gemini reported for it.
            assertTrue(packets[1].contains("Add the first marker"), packets[1].takeLast(600))
            assertTrue(packets[1].contains("wrote marker 1"), packets[1].takeLast(600))
            assertTrue(packets[2].contains("Now add the second marker"), packets[2].takeLast(600))

            // Cycle two's executor continues the conversation cycle one left behind.
            assertTrue(executorPrompts[1].contains("This is cycle 2"), executorPrompts[1])
            assertTrue(executorPrompts[1].contains("Now add the second marker"))

            assertEquals(File(root, "graph.kt").readText().lines().count { it.startsWith("marker") }, 2)
        }
        removeTree(root); removeTree(directory.toFile())
        Unit
    }

    @Test fun aPlannerThatNeverFinishesIsStoppedByTheCycleCapAndSaysSo() = runBlocking {
        val root = source(); val directory = Files.createTempDirectory("hybrid-cap")
        val plans = AtomicInteger()
        val executions = AtomicInteger()
        val planner = scripted { packet -> plans.incrementAndGet(); reply(packet, "Keep going", HybridNext.Execute, listOf("more")) }
        val gemini = object : AgentRuntime {
            override val kind = RuntimeKind.Gemini
            override fun run(request: AgentRequest) = flow {
                val index = executions.incrementAndGet()
                File(request.workingDirectory, "graph.kt").appendText("\npass $index")
                emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "pass $index", true)))
            }
        }
        AgentWorkspace(root, directory, mapOf(RuntimeKind.Codex to planner, RuntimeKind.Gemini to gemini),
            discover = { ProviderCapability(it) }, plannerRuntime = RuntimeKind.Codex).use { workspace ->
            val run = workspace.submit(AgentSubmission("cap", RuntimeKind.ChatGptGemini, "Never satisfied", allowWrites = true))
            stopped(workspace, run.id)
            val handoff = workspace.handoffs(run.id).single()
            assertEquals(HybridPhase.Completed, handoff.phase)
            assertEquals(handoff.maxCycles, handoff.completedCycles, "The cap, not the planner, ended it")
            assertEquals(handoff.maxCycles, executions.get())
            val record = workspace.get(run.id)
            assertEquals(AgentRunStatus.Failed, record.status, "A capped loop did not finish its work and must not read as success")
            assertTrue(record.failure.orEmpty().contains("Cycle cap"), record.failure.orEmpty())
        }
        removeTree(root); removeTree(directory.toFile())
        Unit
    }

    @Test fun aPausedCodexPoolFallsBackToTheOperatorRelayRatherThanSpendingIt() = runBlocking {
        val root = source(); val directory = Files.createTempDirectory("hybrid-paused")
        val plans = AtomicInteger()
        val planner = scripted { packet -> plans.incrementAndGet(); reply(packet, "Should never run", HybridNext.Execute) }
        val gemini = object : AgentRuntime {
            override val kind = RuntimeKind.Gemini
            override fun run(request: AgentRequest) = flow {
                emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "done", true)))
            }
        }
        AgentWorkspace(root, directory, mapOf(RuntimeKind.Codex to planner, RuntimeKind.Gemini to gemini),
            discover = { ProviderCapability(it) }, plannerRuntime = RuntimeKind.Codex).use { workspace ->
            workspace.entitlements.set(Entitlement.Codex, true, "Quota exhausted", 0)
            val run = workspace.submit(AgentSubmission("paused", RuntimeKind.ChatGptGemini, "Inspect the graph"))
            stopped(workspace, run.id)
            assertEquals(0, plans.get(), "A paused pool is not spent behind the operator's back")
            assertEquals(AgentRecovery.NeedsReview, workspace.get(run.id).recovery)
            assertNotNull(workspace.get(run.id).pendingHandoff, "It waits for a person instead")
        }
        removeTree(root); removeTree(directory.toFile())
        Unit
    }

    @Test fun anExhaustedPlannerHandsTheTaskBackToTheOperatorInsteadOfLosingIt() = runBlocking {
        val root = source(); val directory = Files.createTempDirectory("hybrid-exhausted")
        val executions = AtomicInteger()
        val plans = AtomicInteger()
        // Plans once, then hits its usage limit — exactly what a real pool does mid-task.
        val planner = object : AgentRuntime {
            override val kind = RuntimeKind.Codex
            override fun run(request: AgentRequest) = flow {
                val answer = if (plans.incrementAndGet() == 1)
                    AgentOutcome(request.agent.id, ConductorJson.encodeToString(HybridReply.serializer(),
                        reply(request.prompt, "Add a marker", HybridNext.Execute)), true)
                else AgentOutcome(request.agent.id, "", false, failure = "You've hit your usage limit.")
                emit(AgentEvent.Finished(request.agent.id, answer))
            }
        }
        val gemini = object : AgentRuntime {
            override val kind = RuntimeKind.Gemini
            override fun run(request: AgentRequest) = flow {
                executions.incrementAndGet()
                File(request.workingDirectory, "graph.kt").appendText("\nmarker")
                emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "wrote the marker", true)))
            }
        }
        AgentWorkspace(root, directory, mapOf(RuntimeKind.Codex to planner, RuntimeKind.Gemini to gemini),
            discover = { ProviderCapability(it) }, plannerRuntime = RuntimeKind.Codex).use { workspace ->
            val run = workspace.submit(AgentSubmission("exhausted", RuntimeKind.ChatGptGemini, "Add a marker", allowWrites = true))
            stopped(workspace, run.id)
            val record = workspace.get(run.id)
            assertEquals(AgentRecovery.NeedsReview, record.recovery, record.failure)
            assertNotNull(record.pendingHandoff, "The operator can finish what ChatGPT could not")
            assertEquals(1, executions.get(), "The cycle that was already paid for is not thrown away")
            val handoff = workspace.handoffs(run.id).single()
            assertEquals(HybridPhase.Reviewing, handoff.phase, "It stopped exactly where the planner was needed")
            assertNotNull(handoff.observation, "Gemini's result is durable and waiting to be reviewed")
        }
        removeTree(root); removeTree(directory.toFile())
        Unit
    }

    /**
     * The report names what the turn touched, not what the checkout happens to be carrying.
     *
     * This is the defect that mattered most in practice: on a repository with other uncommitted
     * work the planner was told the agent had changed a hundred files it never opened.
     */
    @Test fun theReportNamesOnlyWhatThisTurnChanged() = runBlocking {
        val root = source(); val directory = Files.createTempDirectory("hybrid-manifest")
        // Uncommitted work that has nothing to do with the agent, exactly as a real checkout has.
        File(root, "unrelated.kt").writeText("someone else was here")
        File(root, "graph.kt").appendText("\nalso edited by hand")
        val passes = AtomicInteger()
        val planner = scripted { packet ->
            if (passes.incrementAndGet() == 1) reply(packet, "Touch only the marker", HybridNext.Execute)
            else reply(packet, "Done", HybridNext.Finish)
        }
        val gemini = object : AgentRuntime {
            override val kind = RuntimeKind.Gemini
            override fun run(request: AgentRequest) = flow {
                File(request.workingDirectory, "agent-made.kt").writeText("val x = 1")
                emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "wrote agent-made.kt", true)))
            }
        }
        AgentWorkspace(root, directory, mapOf(RuntimeKind.Codex to planner, RuntimeKind.Gemini to gemini),
            discover = { ProviderCapability(it) }, plannerRuntime = RuntimeKind.Codex).use { workspace ->
            val run = workspace.submit(AgentSubmission("manifest", RuntimeKind.ChatGptGemini, "Add a marker", allowWrites = true))
            stopped(workspace, run.id)
            val handoff = workspace.handoffs(run.id).single()
            val changed = requireNotNull(handoff.observation ?: handoff.cycles.lastOrNull()?.observation).changedPaths
            assertEquals(listOf("agent-made.kt"), changed,
                "only the file this turn wrote; the operator's own dirty files are not the agent's doing")
        }
        removeTree(root); removeTree(directory.toFile())
        Unit
    }

    @Test fun aReplyBuriedInProseAndFencesIsStillRead() {
        val json = """{"handoffId":"x","revision":3,"phase":"Reviewing","text":"Ship it","next":"finish"}"""
        assertEquals("Ship it", extractReply("Here is my answer.\n```json\n$json\n```\nLet me know.")?.text)
        assertEquals(HybridNext.Finish, extractReply("prose { not json } then $json trailing")?.next)
        // A brace inside a string must not close the object early.
        assertEquals("a}b", extractReply("""{"handoffId":"x","revision":1,"phase":"Planning","text":"a}b"}""")?.text)
        assertNull(extractReply("no structured answer at all"))
    }

    /** A planner whose answer is scripted, wrapped in the prose a real harness would add. */
    private fun scripted(answer: (String) -> HybridReply) = object : AgentRuntime {
        override val kind = RuntimeKind.Codex
        override fun run(request: AgentRequest) = flow {
            require(!request.agent.tools.allowWrites) { "The planner must never be granted writes" }
            val text = ConductorJson.encodeToString(HybridReply.serializer(), answer(request.prompt))
            emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id,
                "Understood. Here is the decision:\n```json\n$text\n```", true)))
        }
    }

    /** Builds a reply bound to whatever handoff identity the packet is carrying. */
    private fun reply(packet: String, text: String, next: HybridNext, acceptance: List<String> = emptyList()): HybridReply {
        val template = requireNotNull(extractReply(packet)) { "The packet must show the planner the reply shape" }
        return template.copy(text = text, acceptanceCriteria = acceptance, next = next)
    }

    /** Deletes without following symlinks; see HybridConnectorTest for why that matters. */
    private fun removeTree(root: File) {
        if (!root.exists() && !java.nio.file.Files.isSymbolicLink(root.toPath())) return
        java.nio.file.Files.walkFileTree(root.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes) =
                java.nio.file.FileVisitResult.CONTINUE.also { java.nio.file.Files.deleteIfExists(file) }
            override fun postVisitDirectory(dir: java.nio.file.Path, failure: java.io.IOException?) =
                java.nio.file.FileVisitResult.CONTINUE.also { java.nio.file.Files.deleteIfExists(dir) }
        })
    }

    private fun git(root: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText(); check(process.waitFor() == 0) { output }
    }
    private fun source(): File = Files.createTempDirectory("hybrid-loop-source").toFile().also {
        git(it, "init", "-q"); File(it, "graph.kt").writeText("graph base"); git(it, "add", ".")
        git(it, "-c", "user.name=Test", "-c", "user.email=test@localhost", "commit", "-qm", "base")
    }
    private suspend fun stopped(workspace: AgentWorkspace, id: String) = withTimeout(60000) {
        while (workspace.get(id).status == AgentRunStatus.Running) delay(10)
    }
}
