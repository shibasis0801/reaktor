package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class AgentWorkflowTest {
    private val worker = AgentSpec(AgentId("builder"), "Builder", RuntimeKind.Echo, "")
    private val reviewer = worker.copy(id = AgentId("reviewer"), name = "Reviewer")
    private fun definition() = WorkflowDefinition("repair", "Build and review", listOf(worker, reviewer), listOf(
        WorkflowStage("build", "Build", worker.id, "BUILD"),
        WorkflowStage("review", "Review", reviewer.id, "REVIEW", contract = WorkflowContract.Decision),
        WorkflowStage("repair", "Repair", worker.id, "REPAIR"),
        WorkflowStage("approve", "Approve", action = WorkflowAction.Gate),
    ), listOf(WorkflowEdge("build", "review"), WorkflowEdge("review", "repair", WorkflowCondition.Repair),
        WorkflowEdge("review", "approve", WorkflowCondition.Pass), WorkflowEdge("repair", "approve")))

    @Test fun anIndependentSuccessCannotHideAnUnhandledFailedBranch() {
        val graph = WorkflowDefinition("branch", "Independent branches", listOf(worker), listOf(
            WorkflowStage("failed", "Failed", worker.id), WorkflowStage("blocked", "Blocked", worker.id), WorkflowStage("ok", "OK", worker.id)),
            listOf(WorkflowEdge("failed", "blocked")))
        assertTrue(graph.failed(WorkflowProgress(mapOf("failed" to WorkflowStageResult(WorkflowStageStatus.Failed),
            "blocked" to WorkflowStageResult(WorkflowStageStatus.Skipped), "ok" to WorkflowStageResult(WorkflowStageStatus.Completed)))))
        assertFalse(graph.copy(edges = listOf(WorkflowEdge("failed", "blocked", WorkflowCondition.Failed))).failed(WorkflowProgress(mapOf(
            "failed" to WorkflowStageResult(WorkflowStageStatus.Failed), "blocked" to WorkflowStageResult(WorkflowStageStatus.Completed), "ok" to WorkflowStageResult(WorkflowStageStatus.Completed)))))
    }

    @Test fun conditionalDecisionsPauseAndResumeWithoutReplayingCompletedStages() = runBlocking {
        var checkpoint: ProtocolCheckpoint? = null
        val calls = mutableListOf<String>()
        val conductor = Conductor(mapOf(RuntimeKind.Echo to EchoRuntime { request ->
            calls += request.agent.id.value
            if (request.agent.id == reviewer.id) "{\"verdict\":\"pass\",\"summary\":\"checked\"}" else "built"
        }))
        val graph = definition()
        val thread = ThreadDocument(ThreadId("workflow"), "Test", graph.participants)
        assertFailsWith<WorkflowPaused> { conductor.run(thread, "task", Protocol.Graph(graph), ".", onProgress = { checkpoint = it }) }
        assertEquals(WorkflowStageStatus.Skipped, checkpoint!!.workflow.stages["repair"]?.status)
        assertEquals(listOf("builder", "reviewer"), calls)
        val resume = checkpoint!!.copy(workflow = checkpoint!!.workflow.copy(approvedGates = setOf("approve")))
        conductor.run(thread, "task", Protocol.Graph(graph), ".", resumeFrom = resume, onProgress = { checkpoint = it })
        assertEquals(listOf("builder", "reviewer"), calls)
        assertEquals(WorkflowStageStatus.Completed, checkpoint!!.workflow.stages["approve"]?.status)
        Unit
    }

    @Test fun invalidDecisionNeverEntersAPassBranchAndCyclesAreRejected() = runBlocking {
        val graph = definition()
        var checkpoint: ProtocolCheckpoint? = null
        Conductor(mapOf(RuntimeKind.Echo to EchoRuntime { "looks fine" })).run(
            ThreadDocument(ThreadId("workflow"), "Test", graph.participants), "task", Protocol.Graph(graph), ".", onProgress = { checkpoint = it })
        assertEquals(WorkflowStageStatus.Failed, checkpoint!!.workflow.stages["review"]?.status)
        assertEquals(WorkflowStageStatus.Skipped, checkpoint!!.workflow.stages["approve"]?.status)
        assertFailsWith<IllegalArgumentException> { graph.copy(edges = graph.edges + WorkflowEdge("approve", "build")).validate() }
        Unit
    }

    @Test fun restartRetainsGateAndForkRejectsStaleRetainedEvidence() = runBlocking {
        val root = Files.createTempDirectory("workflow-source").toFile()
        val data = Files.createTempDirectory("workflow-data")
        git(root, "init", "-q"); File(root, "source.txt").writeText("one"); git(root, "add", ".")
        git(root, "-c", "user.name=Test", "-c", "user.email=test@localhost", "commit", "-qm", "base")
        var calls = 0
        val runtime = EchoRuntime { request -> calls++; if (request.agent.id == reviewer.id) "{\"verdict\":\"pass\",\"summary\":\"ok\"}" else "built" }
        suspend fun stopped(workspace: AgentWorkspace, id: String) { withTimeout(10000) { while (workspace.get(id).status == AgentRunStatus.Running) delay(20) } }
        try {
            val id = AgentWorkspace(root, data, mapOf(RuntimeKind.Echo to runtime), background = true).use { workspace ->
                val run = workspace.submit(AgentSubmission("workflow", RuntimeKind.Echo, "work", workflow = definition()))
                stopped(workspace, run.id)
                assertEquals(AgentRecovery.NeedsReview, workspace.get(run.id).recovery)
                run.id
            }
            AgentWorkspace(root, data, mapOf(RuntimeKind.Echo to runtime), background = true).use { workspace ->
                workspace.recoverInBackground(); delay(30)
                assertEquals(2, calls, "A durable gate never auto-approves")
                val checkpoint = workspace.checkpoints.list(id).first { it.workflow.stages["approve"]?.status == WorkflowStageStatus.Waiting }
                File(root, "source.txt").writeText("changed by human")
                assertFailsWith<IllegalArgumentException> { workspace.resume(id) }
                assertFailsWith<IllegalArgumentException> { workspace.fork(id, checkpoint.id, "partial-fork", "review") }
                val fresh = workspace.fork(id, checkpoint.id, "fresh-fork")
                stopped(workspace, fresh.id)
                assertNotEquals(workspace.get(id).threadId, fresh.threadId)
                assertEquals(4, calls)
                val reviewed = workspace.get(fresh.id).revision
                workspace.resume(fresh.id, reviewed); stopped(workspace, fresh.id)
                assertFailsWith<IllegalArgumentException> { workspace.resume(fresh.id, reviewed) }
                assertEquals(AgentRunStatus.Completed, workspace.get(fresh.id).status)
                assertEquals(4, calls)
                val eventId = workspace.get(fresh.id).workflowProgress!!.stages.getValue("build").eventId!!.value
                val memory = workspace.remember(fresh.id, eventId)
                assertEquals(memory, workspace.remember(fresh.id, eventId))
                assertEquals(1, workspace.memory.search("built", null, false).entries.size)
                File(root, "source.txt").writeText("another source revision")
                assertTrue(workspace.memory.search("built", null, false).entries.isEmpty())
                assertTrue(workspace.memory.search("built", null, true).entries.single().reason.contains("STALE"))
                workspace.memory.forget(memory.id)
                assertTrue(workspace.memory.search("built", null, true).entries.isEmpty())
            }
        } finally { root.deleteRecursively(); data.toFile().deleteRecursively() }
        Unit
    }

    @Test fun worktreesPreserveDirtyBaselineAndOnlyApplyTheirOwnPatch() {
        val root = Files.createTempDirectory("isolation-source").toFile()
        val data = Files.createTempDirectory("isolation-data")
        git(root, "init", "-q"); File(root, "source.txt").writeText("base\n"); git(root, "add", ".")
        git(root, "-c", "user.name=Test", "-c", "user.email=test@localhost", "commit", "-qm", "base")
        try {
            File(root, "source.txt").writeText("user change\n")
            File(root, "untracked.txt").writeText("user new file\n")
            val index = git(root, "diff", "--cached")
            val store = AgentWorktrees(root, data.resolve("worktrees"), LocalAgentArtifacts(data.resolve("artifacts")))
            val isolated = store.prepare("a".repeat(64), "builder")
            val checkout = File(isolated.roots.single().path)
            assertEquals("user change\n", File(checkout, "source.txt").readText())
            assertEquals("user new file\n", File(checkout, "untracked.txt").readText())
            File(checkout, "agent.txt").writeText("agent addition\n")
            val review = store.review(isolated.id)
            assertTrue(review.conflicts.isEmpty())
            assertTrue(review.changedFiles.single().endsWith("agent.txt"))
            assertFailsWith<IllegalArgumentException> { store.apply(isolated.id, "bad", review.sourceRevision) }
            store.apply(isolated.id, review.patchDigest, review.sourceRevision)
            assertEquals("agent addition\n", File(root, "agent.txt").readText())
            assertEquals("user change\n", File(root, "source.txt").readText())
            assertEquals(index, git(root, "diff", "--cached"))
            store.apply(isolated.id, review.patchDigest, review.sourceRevision) // idempotent receipt
        } finally { data.toFile().deleteRecursively(); root.deleteRecursively() }
    }
    private fun git(root: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { text }
        return text
    }
}
