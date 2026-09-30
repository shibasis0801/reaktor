package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

class HybridCouncilTest {
    @Test fun singleHybridContinuesAnExistingTaskWithBothOtherPoolsPausedAndAttachesItsDiff() = runBlocking {
        val root = source(); val directory = Files.createTempDirectory("hybrid-quota")
        val calls = ConcurrentHashMap<RuntimeKind, Int>()
        val runtimes = listOf(RuntimeKind.Codex, RuntimeKind.ClaudeCode, RuntimeKind.Gemini).associateWith { provider -> object : AgentRuntime {
            override val kind = provider
            override fun run(request: AgentRequest) = flow {
                calls.merge(kind, 1, Int::plus)
                if (kind == RuntimeKind.Gemini) {
                    assertTrue(request.prompt.contains("prior graph finding"), "The new agent receives earlier task context")
                    File(request.workingDirectory, "graph.kt").appendText("\ngraph changed")
                }
                emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "prior graph finding", true)))
            }
        } }
        fun owner() = AgentWorkspace(root, directory, runtimes, discover = { ProviderCapability(it) })
        var workspace = owner()
        try {
            val prior = workspace.submit(AgentSubmission("prior", RuntimeKind.Codex, "Inspect graph"))
            stopped(workspace, prior.id)
            listOf(Entitlement.Codex, Entitlement.ClaudeSubscription).forEach { workspace.entitlements.set(it, true, "Quota exhausted", 0) }
            workspace.close(); workspace = owner()
            assertFailsWith<IllegalArgumentException> { workspace.submit(AgentSubmission("blocked", RuntimeKind.Codex, "Continue", threadId = prior.threadId)) }
            val run = workspace.submit(AgentSubmission("fallback", RuntimeKind.ChatGptGemini, "Continue implementing the graph", threadId = prior.threadId, allowWrites = true))
            assertEquals(AgentTransport.Batch, run.transport)
            stopped(workspace, run.id)
            val plan = workspace.handoffs(run.id).single()
            assertTrue(plan.prompt.contains("prior graph finding"))
            workspace.replyHandoff(run.id, HybridReply(plan.id, plan.revision, plan.phase, "Apply the graph change"))
            workspace.resume(run.id); stopped(workspace, run.id)
            val review = workspace.handoffs(run.id).single()
            val observation = requireNotNull(review.observation) { workspace.get(run.id).toString() }
            assertTrue(observation.diffExcerpt.orEmpty().contains("+graph changed"))
            assertTrue(workspace.handoffArtifact(run.id, requireNotNull(observation.diffArtifact).id).text.contains("+graph changed"))
            assertEquals(ChangeScope.Turn, observation.scope)
            assertTrue(workspace.evidence.graph(run.threadId).edges.any { it.to == observation.candidateId })
            workspace.close(); workspace = owner()
            workspace.replyHandoff(run.id, HybridReply(review.id, review.revision, review.phase, "Reviewed the graph diff"))
            workspace.resume(run.id); stopped(workspace, run.id)
            assertEquals(AgentRunStatus.Completed, workspace.get(run.id).status)
            assertEquals(1, calls[RuntimeKind.Codex]); assertNull(calls[RuntimeKind.ClaudeCode]); assertEquals(1, calls[RuntimeKind.Gemini])
            assertEquals(prior.threadId, workspace.get(run.id).threadId)
        } finally { workspace.close(); root.deleteRecursively(); directory.toFile().deleteRecursively() }
        Unit
    }
    @Test fun aLargeBinaryIsCoveredByLengthWithoutSpendingTheSnapshotsBudget() = runBlocking {
        val root = source(); val directory = Files.createTempDirectory("hybrid-partial")
        // A real workspace: a vendored binary or a build output sits beside the source, far larger
        // than every source file put together. Streaming it would spend the capture budget the code
        // then cannot have, so it goes into the revision by length instead.
        val large = File(root, "build-output.bin")
        java.io.RandomAccessFile(large, "rw").use { it.setLength(300_000_000L) }
        git(root, "add", "."); git(root, "-c", "user.name=T", "-c", "user.email=t@localhost", "commit", "-qm", "with output")
        val runtime = object : AgentRuntime {
            override val kind = RuntimeKind.Gemini
            override fun run(request: AgentRequest) = flow { emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "read the source", true))) }
        }
        AgentWorkspace(root, directory, mapOf(RuntimeKind.Gemini to runtime), discover = { ProviderCapability(it) }).use { workspace ->
            val run = workspace.submit(AgentSubmission("partial", RuntimeKind.ChatGptGemini, "Inspect the graph"))
            stopped(workspace, run.id)
            val handoff = workspace.handoffs(run.id).single()
            assertEquals(AgentRecovery.NeedsReview, workspace.get(run.id).recovery, workspace.get(run.id).failure)
            // Not partial: a binary carried by length is a narrower gap than a snapshot that ran
            // out of budget mid-source, and partial is what blocks an acceptance claim.
            assertFalse(handoff.partial, handoff.coverage.toString())
            assertTrue(handoff.coverage.any { it.contains("large binary files") }, handoff.coverage.toString())
            assertTrue(handoff.packet().contains("does not cover everything"), "The packet says what the binding cannot see")
            // The binding still holds: the digest covers every file name and every file it read, so
            // editing one of those after the transfer stops the execution rather than running it.
            workspace.replyHandoff(run.id, HybridReply(handoff.id, handoff.revision, handoff.phase, "Read it"))
            File(root, "graph.kt").writeText("moved")
            workspace.resume(run.id); stopped(workspace, run.id)
            assertEquals(AgentRunStatus.Failed, workspace.get(run.id).status)
            assertTrue(workspace.get(run.id).failure.orEmpty().contains("Source changed"), workspace.get(run.id).failure)
        }
        root.deleteRecursively(); directory.toFile().deleteRecursively()
        Unit
    }

    private fun git(root: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText(); check(process.waitFor() == 0) { output }
    }
    private fun source(): File = Files.createTempDirectory("hybrid-source").toFile().also {
        git(it, "init", "-q"); File(it, "graph.kt").writeText("graph base"); git(it, "add", ".")
        git(it, "-c", "user.name=Test", "-c", "user.email=test@localhost", "commit", "-qm", "base")
    }
    private suspend fun stopped(workspace: AgentWorkspace, id: String) = withTimeout(20000) {
        while (workspace.get(id).status == AgentRunStatus.Running) delay(10)
    }

    @Test fun threeSeatCouncilRetainsPeerWorkAndEveryHandoffAcrossOwnerRestarts() = runBlocking {
        val root = source(); val directory = Files.createTempDirectory("hybrid-owner")
        val calls = ConcurrentHashMap<RuntimeKind, Int>()
        val runtimes = listOf(RuntimeKind.Codex, RuntimeKind.ClaudeCode, RuntimeKind.Gemini).associateWith { provider ->
            object : AgentRuntime {
                override val kind = provider
                override fun run(request: AgentRequest) = flow {
                    calls.merge(kind, 1, Int::plus)
                    if (kind != RuntimeKind.Gemini) delay(80)
                    emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "${kind.name} evidence", true, usage = AgentUsage(11, 3))))
                }
            }
        }
        fun owner() = AgentWorkspace(root, directory, runtimes, discover = { ProviderCapability(it) }, background = true)
        var workspace = owner()
        try {
            val run = workspace.submit(AgentSubmission("council-three", RuntimeKind.Codex, "Review graph invariants",
                collaboration = AgentCollaboration.Council, partner = AgentPartner(RuntimeKind.ClaudeCode), councilHybrid = true,
                context = ContextPacket(workspaceId = root.path, principalId = "test", source = "test", entries = listOf(ContextEntry("graph:root", "graph-subject", "Root", "Ports", "selected")))))
            stopped(workspace, run.id)
            assertEquals(1, calls[RuntimeKind.Codex]); assertEquals(1, calls[RuntimeKind.ClaudeCode]); assertNull(calls[RuntimeKind.Gemini])
            assertTrue(workspace.checkpoints.list(run.id).first().inFlight.isEmpty())
            repeat(4) { index ->
                workspace.close(); workspace = owner(); workspace.recoverInBackground(); delay(50)
                val waiting = workspace.handoffs(run.id).single { it.phase in listOf(HybridPhase.Planning, HybridPhase.Reviewing) }
                assertEquals(if (index % 2 == 0) HybridPhase.Planning else HybridPhase.Reviewing, waiting.phase)
                assertEquals(listOf("graph:root"), waiting.subjectRefs)
                val reply = HybridReply(waiting.id, waiting.revision, waiting.phase, if (index % 2 == 0) "Inspect graph.kt and return evidence" else "Reviewed graph evidence and uncertainty")
                assertFailsWith<IllegalArgumentException> { workspace.replyHandoff(run.id, reply.copy(revision = 100)) }
                val imported = workspace.replyHandoff(run.id, reply)
                assertEquals(imported, workspace.replyHandoff(run.id, reply), "Exact import retries are idempotent")
                workspace.resume(run.id, workspace.get(run.id).revision); stopped(workspace, run.id)
            }
            assertEquals(AgentRunStatus.Completed, workspace.get(run.id).status)
            assertEquals(3, calls[RuntimeKind.Codex]); assertEquals(2, calls[RuntimeKind.ClaudeCode]); assertEquals(2, calls[RuntimeKind.Gemini])
            val events = workspace.transcript(run.threadId).events
            assertEquals(3, events.count { it.kind == EventKind.Proposal })
            assertEquals(3, events.count { it.kind == EventKind.Critique })
            assertEquals(3, events.single { it.kind == EventKind.Synthesis }.parents.size)
            assertTrue(events.filter { it.author == Author.Agent(AgentId("chatgptgemini")) }.all { it.attributes["chatgptUsage"] == "unknown" })
            assertEquals(77L, workspace.get(run.id).turnUsage?.input?.reported)
        } finally { workspace.close(); root.deleteRecursively(); directory.toFile().deleteRecursively() }
        Unit
    }

    @Test fun sourceChangesInvalidateHandoffAndFailedExecutorCannotBecomeASuccessfulVote() = runBlocking {
        val root = source(); val directory = Files.createTempDirectory("hybrid-failure")
        val runtime = object : AgentRuntime {
            override val kind = RuntimeKind.Gemini
            override fun run(request: AgentRequest) = flow { emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "", false, "Compiler failed"))) }
        }
        AgentWorkspace(root, directory, mapOf(RuntimeKind.Gemini to runtime), discover = { ProviderCapability(it) }).use { workspace ->
            val run = workspace.submit(AgentSubmission("hybrid-fail", RuntimeKind.ChatGptGemini, "Inspect graph"))
            stopped(workspace, run.id)
            val handoff = workspace.handoffs(run.id).single()
            val reply = HybridReply(handoff.id, handoff.revision, handoff.phase, "Inspect")
            File(root, "graph.kt").writeText("changed")
            assertFailsWith<IllegalArgumentException> { workspace.replyHandoff(run.id, reply) }
            File(root, "graph.kt").writeText("graph base")
            workspace.replyHandoff(run.id, reply); workspace.resume(run.id); stopped(workspace, run.id)
            val review = workspace.handoffs(run.id).single()
            workspace.replyHandoff(run.id, HybridReply(review.id, review.revision, review.phase, "Everything passed"))
            workspace.resume(run.id); stopped(workspace, run.id)
            assertEquals(AgentRunStatus.Failed, workspace.get(run.id).status)
            assertTrue(workspace.get(run.id).failure.orEmpty().contains("Compiler failed"), workspace.get(run.id).failure)
        }
        root.deleteRecursively(); directory.toFile().deleteRecursively()
        Unit
    }
}
