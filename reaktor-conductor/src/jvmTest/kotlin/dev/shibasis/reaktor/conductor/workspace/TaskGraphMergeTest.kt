package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class TaskGraphMergeTest {
    private val person = BoardActor(BoardActorKind.Person, "fixture")
    private val coordinator = BoardActor(BoardActorKind.Schedule, "fixture coordinator")

    private class Native(override val kind: RuntimeKind, private val reply: (AgentRequest) -> String) : AgentRuntime {
        val requests = CopyOnWriteArrayList<AgentRequest>()
        override fun run(request: AgentRequest) = flow {
            requests += request
            emit(AgentEvent.Started(request.agent.id, ProviderSession(kind, "${kind.name}-${requests.size}")))
            emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, reply(request), true)))
        }
    }

    private fun git(root: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
        return output.trim()
    }

    private fun fixture(body: suspend (File, File, AgentWorktrees) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("task-merge-").toFile().canonicalFile
        val root = File(directory, "project").apply { mkdir() }
        val data = File(directory, "data").apply { mkdir() }
        try {
            git(root, "init", "--quiet")
            File(root, "A.kt").writeText("val a = 0\n")
            File(root, "B.kt").writeText("val b = 0\n")
            git(root, "add", ".")
            git(root, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "--quiet", "-m", "Base")
            body(root, data, AgentWorktrees(root, File(data, "worktrees").toPath(), LocalAgentArtifacts(File(data, "artifacts").toPath())))
        } finally { directory.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    private fun draft() = TaskGraphDraft("Integration", tasks = listOf(
        TaskGraphOp.Add("First", key = "first", scope = listOf("A.kt"), seat = TaskSeat(RuntimeKind.Codex, access = TaskAccess.Write)),
        TaskGraphOp.Add("Second", key = "second", scope = listOf("B.kt"), seat = TaskSeat(RuntimeKind.ClaudeCode, access = TaskAccess.Write)),
        TaskGraphOp.Add("Integrate", key = "merge", kind = TaskKind.Merge, scope = listOf("A.kt", "B.kt"),
            dependsOn = listOf(TaskDependency("first"), TaskDependency("second"))),
        TaskGraphOp.Add("Check combined source", key = "check", kind = TaskKind.Check, check = "fixture",
            dependsOn = listOf(TaskDependency("merge"))),
        TaskGraphOp.Add("Review combined source", key = "review", contract = WorkflowContract.Decision, seat = TaskSeat(RuntimeKind.ClaudeCode),
            dependsOn = listOf(TaskDependency("check")))
    ), envelope = TaskEnvelope(agents = listOf(RuntimeKind.Codex, RuntimeKind.ClaudeCode), writes = listOf("*"), maxTasks = 8))

    private fun candidate(request: AgentRequest) =
        requireNotNull(Regex("""["]candidate["]:\s*["]([a-f0-9]{64})["]""").find(request.prompt)?.groupValues?.get(1))

    private fun review(request: AgentRequest, verdict: String = "pass") =
        """{"verdict":"$verdict","summary":"Combined source reviewed","candidate":"${candidate(request)}","findings":[]}"""

    private suspend fun AgentWorkspaceConnection.until(id: String, condition: (TaskGraphView) -> Boolean): TaskGraphView = withTimeout(30000) {
        var view = taskGraph(id)
        while (!condition(view)) { delay(25); view = taskGraph(id) }
        view
    }

    @Test fun cleanMergeChecksAndReviewsOneCombinedCandidateBeforePersonAcceptance() = fixture { root, data, _ ->
        val codex = Native(RuntimeKind.Codex) { request ->
            File(request.workingDirectory, "A.kt").writeText("val a = 1\n")
            "First implemented"
        }
        val claude = Native(RuntimeKind.ClaudeCode) { request ->
            if (request.agent.tools.allowWrites) {
                File(request.workingDirectory, "B.kt").writeText("val b = 2\n")
                "Second implemented"
            } else {
                assertEquals("val a = 1\n", File(request.workingDirectory, "A.kt").readText())
                assertEquals("val b = 2\n", File(request.workingDirectory, "B.kt").readText())
                review(request)
            }
        }
        val checked = CopyOnWriteArrayList<String>()
        lateinit var owner: AgentWorkspace
        val index = File(root, ".git/index").readBytes()
        AgentWorkspaceConnection.open(root, data.toPath(), mapOf(codex.kind to codex, claude.kind to claude),
            discover = { ProviderCapability(it) }, extraTools = { owner = it; emptyList() },
            workflowCheck = { task, _, stage ->
                assertEquals(TaskKind.Merge, owner.board.get(task).graph?.kind)
                val current = owner.captureCandidate(task)
                assertEquals(current.id, stage.candidateId)
                assertEquals(setOf("A.kt", "B.kt"), current.changedFiles.toSet())
                checked += current.id
                owner.evidence.check(task, AgentCandidateCheck("fixture", current.id,
                    CheckResult(listOf("fixture"), CheckOutcome.Passed, 0, current.id), "kernel"))
                WorkflowCheckResult(true, "Combined source checked", "fixture-receipt")
            }).use { connection ->
            val proposed = connection.proposeTaskGraph(draft(), person)
            connection.startTaskGraph(proposed.graph.id, proposed.graph.revision, person)
            val done = connection.until(proposed.graph.id) { it.graph.status != TaskGraphStatus.Running }
            assertEquals(TaskGraphStatus.Finished, done.graph.status, done.tasks.toString())
            val merged = done.tasks.single { it.graph?.kind == TaskKind.Merge }
            val output = requireNotNull(merged.graph?.outcome)
            val inputs = done.tasks.filter { it.title in listOf("First", "Second") }.associate { it.id to it.graph?.outcome?.candidateId }
            assertEquals(inputs, output.inputCandidates)
            assertEquals(listOf(output.candidateId), checked.toList())
            assertEquals(output.candidateId, done.tasks.single { it.title == "Review combined source" }.graph?.outcome?.candidateId)
            assertEquals(1, codex.requests.size)
            assertEquals(2, claude.requests.size)
            assertContentEquals(index, File(root, ".git/index").readBytes())
            assertEquals("val a = 0\n", File(root, "A.kt").readText())
            assertEquals("val b = 0\n", File(root, "B.kt").readText())
            assertFailsWith<IllegalArgumentException> { owner.acceptTaskCandidate(done.graph.id, merged.id, requireNotNull(output.candidateId),
                done.graph.revision, BoardActor(BoardActorKind.Agent, "codex")) }
            owner.acceptTaskCandidate(done.graph.id, merged.id, requireNotNull(output.candidateId), done.graph.revision, person)
            assertEquals(output.candidateId, owner.taskEvidence(merged.id).acceptedCandidate)
            assertTrue(owner.taskEvidence(merged.id).approverVerified)
        }
    }

    @Test fun conflictedMergeCreatesAResolutionWriterAndReusesItsExactCheckout() = fixture { root, data, _ ->
        val resolved = CopyOnWriteArrayList<String>()
        val codex = Native(RuntimeKind.Codex) { request ->
            val file = File(request.workingDirectory, "A.kt")
            if (request.prompt.contains("Your task: Resolve ")) {
                assertTrue(file.readText().contains("<<<<<<< "))
                resolved += request.workingDirectory
                file.writeText("val a = 3\n")
            } else file.writeText("val a = 1\n")
            "Implemented"
        }
        val claude = Native(RuntimeKind.ClaudeCode) { request ->
            if (request.agent.tools.allowWrites) {
                File(request.workingDirectory, "A.kt").writeText("val a = 2\n")
                "Second implemented"
            } else {
                assertEquals("val a = 3\n", File(request.workingDirectory, "A.kt").readText())
                assertEquals(resolved.single(), request.workingDirectory)
                review(request)
            }
        }
        AgentWorkspaceConnection.open(root, data.toPath(), mapOf(codex.kind to codex, claude.kind to claude),
            discover = { ProviderCapability(it) }, workflowCheck = { _, _, _ -> WorkflowCheckResult(true, "Checked", "fixture") }).use { connection ->
            val value = draft().let { it.copy(tasks = it.tasks.map { task -> if (task.key == "second") task.copy(scope = listOf("A.kt")) else task }) }
            val proposed = connection.proposeTaskGraph(value, person)
            connection.startTaskGraph(proposed.graph.id, proposed.graph.revision, person)
            val done = connection.until(proposed.graph.id) { it.graph.status != TaskGraphStatus.Running }
            assertEquals(TaskGraphStatus.Finished, done.graph.status, done.tasks.toString())
            val merged = done.tasks.single { it.graph?.kind == TaskKind.Merge }
            val resolution = done.tasks.single { it.graph?.repairOf == merged.id }
            assertEquals(2, merged.graph?.attempt)
            assertEquals(merged.graph?.worktreeId, resolution.graph?.worktreeId)
            assertEquals(mapOf(resolution.id to assertNotNull(resolution.graph?.outcome?.candidateId)), merged.graph?.outcome?.inputCandidates)
            assertEquals(BoardActorKind.Schedule, resolution.createdBy.kind)
            assertEquals(listOf(TaskDependency(resolution.id)), merged.graph?.dependsOn)
            assertEquals(1, resolved.size)
            assertEquals("val a = 0\n", File(root, "A.kt").readText())
        }
    }

    @Test fun conflictResolutionCannotExpandTheApprovedTaskBudget() = fixture { root, data, _ ->
        val codex = Native(RuntimeKind.Codex) { request -> File(request.workingDirectory, "A.kt").writeText("val a = 1\n"); "First" }
        val claude = Native(RuntimeKind.ClaudeCode) { request -> File(request.workingDirectory, "A.kt").writeText("val a = 2\n"); "Second" }
        AgentWorkspaceConnection.open(root, data.toPath(), mapOf(codex.kind to codex, claude.kind to claude),
            discover = { ProviderCapability(it) }).use { connection ->
            val value = draft().let { it.copy(envelope = it.envelope.copy(maxTasks = 5), tasks = it.tasks.map { task ->
                if (task.key == "second") task.copy(scope = listOf("A.kt")) else task
            }) }
            val proposed = connection.proposeTaskGraph(value, person)
            connection.startTaskGraph(proposed.graph.id, proposed.graph.revision, person)
            val held = connection.until(proposed.graph.id) { view -> view.tasks.any { it.graph?.kind == TaskKind.Merge && it.graph.state == TaskState.Waiting } }
            assertEquals(5, held.tasks.size)
            val merged = held.tasks.single { it.graph?.kind == TaskKind.Merge }
            assertTrue(merged.graph?.outcome?.summary.orEmpty().contains("envelope"))
            assertTrue(merged.graph?.outcome?.files.orEmpty().contains("A.kt"))
            assertEquals(1, codex.requests.size)
            assertEquals(1, claude.requests.size)
            assertTrue(held.tasks.none { it.graph?.repairOf != null })
            assertEquals("val a = 0\n", File(root, "A.kt").readText())
        }
    }

    @Test fun integratedReviewRepairRunsInTheCombinedCheckoutAndRefreshesItsCheck() = fixture { root, data, _ ->
        val checks = CopyOnWriteArrayList<String>()
        var reviews = 0
        val codex = Native(RuntimeKind.Codex) { request ->
            if (request.prompt.contains("Your task: Resolve ")) assertEquals("val b = 2\n", File(request.workingDirectory, "B.kt").readText())
            File(request.workingDirectory, "A.kt").writeText(if (request.prompt.contains("Your task: Resolve ")) "val a = 3\n" else "val a = 1\n")
            "Implemented"
        }
        val claude = Native(RuntimeKind.ClaudeCode) { request ->
            if (request.agent.tools.allowWrites) {
                File(request.workingDirectory, "B.kt").writeText("val b = 2\n")
                "Second"
            } else {
                reviews++
                review(request, if (reviews == 1) "repair" else "pass")
            }
        }
        AgentWorkspaceConnection.open(root, data.toPath(), mapOf(codex.kind to codex, claude.kind to claude), discover = { ProviderCapability(it) },
            workflowCheck = { _, _, stage -> checks += requireNotNull(stage.candidateId); WorkflowCheckResult(true, "Checked", "fixture") }).use { connection ->
            val proposed = connection.proposeTaskGraph(draft(), person)
            connection.startTaskGraph(proposed.graph.id, proposed.graph.revision, person)
            val done = connection.until(proposed.graph.id) { it.graph.status != TaskGraphStatus.Running }
            assertEquals(TaskGraphStatus.Finished, done.graph.status, done.tasks.toString())
            assertEquals(2, reviews)
            assertEquals(2, checks.distinct().size)
            val merged = done.tasks.single { it.graph?.kind == TaskKind.Merge }
            assertEquals(checks.last(), merged.graph?.outcome?.candidateId)
            assertEquals(2, merged.graph?.attempt)
            assertEquals("val a = 0\n", File(root, "A.kt").readText())
        }
    }

    @Test fun conflictedTreesSurviveRestartAndRejectMarkersAndForgedResolutionActors() = fixture { root, data, store ->
        val base = store.freeze("G1")
        val first = store.prepareFrom("first", "workspace", base, emptyList())
        val second = store.prepareFrom("second", "workspace", base, emptyList())
        File(first.roots.single().path, "A.kt").writeText("val a = 1\n")
        File(second.roots.single().path, "A.kt").writeText("val a = 2\n")
        val inputs = listOf(AgentWorktreeInput("T1", first.id, store.capture(first.id).id),
            AgentWorktreeInput("T2", second.id, store.capture(second.id).id))
        val merged = store.prepareFrom("merge", "workspace", base, inputs, keepConflicts = true)
        assertEquals(listOf("A.kt"), merged.conflicts)
        val reopened = AgentWorktrees(root, File(data, "worktrees").toPath(), LocalAgentArtifacts(File(data, "artifacts").toPath()))
        assertEquals(merged, reopened.prepareFrom("merge", "workspace", base, inputs, keepConflicts = true))
        assertFailsWith<IllegalArgumentException> { reopened.requireResolved(merged.id) }
        val board = AgentBoardStore(File(data, "board").toPath())
        val proposed = board.propose(draft(), person)
        board.start(proposed.graph.id, person, proposed.graph.revision)
        val task = proposed.tasks.single { it.graph?.kind == TaskKind.Merge }
        board.advance(proposed.graph.id, task.id) { value, plan -> value.copy(graph = plan.copy(state = TaskState.Running,
            attempt = 1, worktreeId = merged.id)) }
        assertFailsWith<IllegalArgumentException> { board.resolveIntegration(task.id, 1, merged, merged.conflicts, "conflict", person) }
        assertFailsWith<IllegalArgumentException> { board.resolveIntegration(task.id, 2, merged, merged.conflicts, "stale", coordinator) }
        assertEquals(5, board.members(proposed.graph.id).size)
        val resolution = board.resolveIntegration(task.id, 1, merged, merged.conflicts, "conflict", coordinator)
        val restarted = AgentBoardStore(File(data, "board").toPath())
        assertEquals(merged.id, restarted.get(resolution.id).graph?.worktreeId)
        assertEquals(listOf(TaskDependency(resolution.id)), restarted.get(task.id).graph?.dependsOn)
        File(merged.roots.single().path, "A.kt").writeText("val a = 3\n")
        reopened.requireResolved(merged.id)
        assertEquals("val a = 0\n", File(root, "A.kt").readText())
    }
}
