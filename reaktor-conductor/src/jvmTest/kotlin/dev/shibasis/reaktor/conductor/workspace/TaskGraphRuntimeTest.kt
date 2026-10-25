package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import org.koin.dsl.koinApplication
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class TaskGraphRuntimeTest {
    private class Scripted(override val kind: RuntimeKind, private val replies: List<String>) : AgentRuntime {
        val requests = CopyOnWriteArrayList<AgentRequest>()
        val failing = AtomicBoolean(false)
        var onRun: suspend (AgentRequest, Int) -> Unit = { request, number ->
            if (request.agent.tools.allowWrites) File(request.workingDirectory, "Source.kt").writeText("val attempt = $number\n")
        }
        override fun run(request: AgentRequest) = flow {
            requests += request
            val session = ProviderSession(kind, "${kind.name}-${requests.size}")
            emit(AgentEvent.Started(request.agent.id, session))
            val failed = failing.get()
            if (!failed) onRun(request, requests.size)
            val candidate = Regex("\"candidate\":\\s*\"([a-f0-9]{64})\"").find(request.prompt)?.groupValues?.get(1).orEmpty()
            val text = if (failed) "" else replies[minOf(requests.size, replies.size) - 1].replace("{{candidate}}", candidate)
            emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, text, !failed, failure = if (failed) "The build broke" else null,
                session = session, usage = AgentUsage(inputTokens = 100, outputTokens = 20))))
        }
    }

    private val person = BoardActor(BoardActorKind.Person, "owner")
    private val temporary = File(System.getProperty("java.io.tmpdir"))

    private fun draft() = TaskGraphDraft("Parser", "Parse the sample", listOf(
        TaskGraphOp.Add("Build the parser", key = "build", scope = listOf(":engine"), seat = TaskSeat(RuntimeKind.Codex, access = TaskAccess.Write)),
        TaskGraphOp.Add("Review the parser", key = "review", seat = TaskSeat(RuntimeKind.ClaudeCode), contract = WorkflowContract.Decision, dependsOn = listOf(TaskDependency("build"))),
        TaskGraphOp.Add("Land it", kind = TaskKind.Gate, dependsOn = listOf(TaskDependency("review", TaskCondition.Pass))),
    ))

    private suspend fun AgentWorkspaceConnection.until(id: String, test: (TaskGraphView) -> Boolean): TaskGraphView = withTimeout(30_000) {
        var view = taskGraph(id)
        while (!test(view)) { delay(25); view = taskGraph(id) }
        view
    }

    private fun TaskGraphView.task(title: String) = tasks.single { it.title == title }

    private fun workspace(codex: Scripted, claude: Scripted,
        workflowCheck: (suspend (AgentWorkspace, String, WorkflowStage) -> WorkflowCheckResult)? = null,
        body: suspend (AgentWorkspaceConnection, File, AgentWorktrees) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("taskgraph-root").toFile()
        val data = Files.createTempDirectory("taskgraph-data")
        try {
            File(root, "Source.kt").writeText("val baseline = 1\n")
            listOf(listOf("init", "--quiet"), listOf("add", "."),
                listOf("-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "--quiet", "-m", "Baseline")).forEach { args ->
                val process = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                check(process.waitFor() == 0) { output }
            }
            lateinit var workspace: AgentWorkspace
            val check: (suspend (String, String, WorkflowStage) -> WorkflowCheckResult)? = workflowCheck?.let { callback ->
                { task, _, stage -> callback(workspace, task, stage) }
            }
            AgentWorkspaceConnection.open(root, data, mapOf(codex.kind to codex, claude.kind to claude),
                extraTools = { workspace = it; emptyList() }, workflowCheck = check).use {
                body(it, root, AgentWorktrees(root, data.resolve("worktrees"), LocalAgentArtifacts(data.resolve("artifacts"))))
            }
        }
        finally { root.deleteTreeSafely(within = temporary); data.toFile().deleteTreeSafely(within = temporary) }
    }


    @Test fun anAttemptPinsTheDiscoveredProfileAndOnlyARetryUsesItsRevision() = runBlocking {
        val root = Files.createTempDirectory("taskgraph-profile-root").toFile()
        val data = Files.createTempDirectory("taskgraph-profile-data")
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val requests = CopyOnWriteArrayList<AgentRequest>()
        val runtime = object : AgentRuntime {
            override val kind = RuntimeKind.Codex
            override fun run(request: AgentRequest) = flow {
                requests += request
                val first = requests.size == 1
                val session = ProviderSession(kind, "profile-${requests.size}")
                emit(AgentEvent.Started(request.agent.id, session))
                if (first) release.await()
                emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "attempt ${requests.size}", !first,
                    failure = if (first) "Try the next revision" else null, session = session)))
            }
        }
        try {
            AgentWorkspaceConnection.open(root, data, mapOf(runtime.kind to runtime),
                discover = { ProviderCapability(it, defaultModel = "model-a",
                    effort = EffortSupport(listOf(NativeEffort("low"), NativeEffort("high")), source = "fixture")) }).use { connection ->
                suspend fun save(profile: AgentProfile, revision: Long? = null): AgentProfile {
                    val result = connection.call("agent_profile_save", buildJsonObject {
                        put("profile", AgentWorkspaceJson.encodeToJsonElement(AgentProfile.serializer(), profile))
                        put("by", AgentWorkspaceJson.encodeToJsonElement(BoardActor.serializer(), person))
                        revision?.let { put("expectedRevision", it) }
                    })
                    return AgentWorkspaceJson.decodeFromJsonElement(AgentProfile.serializer(), result)
                }
                val first = save(AgentProfile("builder", "Builder", RuntimeKind.Codex, "model-a", "high", "Find the smallest fix.",
                    tools = ToolPolicy(allow = listOf("Read"), deny = listOf("Network"))))
                val seat = TaskSeat(profile = first.id)
                val discovered = connection.call("agent_taskgraph_seats", buildJsonObject {
                    put("seat", AgentWorkspaceJson.encodeToJsonElement(TaskSeat.serializer(), seat))
                }).jsonObject.getValue("resolved").let { AgentWorkspaceJson.decodeFromJsonElement(ResolvedTaskSeat.serializer(), it) }
                val proposed = connection.proposeTaskGraph(TaskGraphDraft("Profile", tasks = listOf(
                    TaskGraphOp.Add("Build it", key = "build", seat = seat),
                    TaskGraphOp.Add("Accept it", kind = TaskKind.Gate, dependsOn = listOf(TaskDependency("build"))),
                )), person)
                connection.startTaskGraph("G1", proposed.graph.revision, person)
                withTimeout(10_000) { while (requests.isEmpty()) delay(10) }
                val pinned = connection.taskGraph("G1").task("Build it").graph!!.resolvedSeat
                assertEquals(discovered, pinned)
                assertEquals("Builder", requests.single().agent.name)
                assertEquals("model-a", requests.single().agent.model)
                assertEquals(NativeEffort("high"), requests.single().agent.effort)
                assertTrue("Find the smallest fix." in requests.single().agent.instructions)
                assertEquals(listOf("Network"), requests.single().agent.tools.deny)
                assertFalse(requests.single().agent.tools.allowWrites)
                save(first.copy(model = "model-b", effort = "low", instructions = "Test the boundary cases."), first.revision)
                assertEquals(pinned, connection.taskGraph("G1").task("Build it").graph!!.resolvedSeat)
                connection.pauseTaskGraph("G1", "Inspect the first attempt", person)
                release.complete(Unit)
                connection.until("G1") { it.task("Build it").graph?.state == TaskState.Failed }
                connection.patchTaskGraph("G1", TaskGraphPatch(listOf(TaskGraphOp.Retry("T1")), "Use the updated profile"), person)
                connection.startTaskGraph("G1", null, person)
                val done = connection.until("G1") { it.task("Accept it").graph?.state == TaskState.Waiting }.task("Build it").graph!!
                assertEquals(2, done.attempt)
                assertEquals(2L, done.resolvedSeat?.profileRevision)
                assertEquals("model-b", requests[1].agent.model)
                assertEquals(NativeEffort("low"), requests[1].agent.effort)
                assertTrue("Test the boundary cases." in requests[1].agent.instructions)
                assertEquals(1L, pinned?.profileRevision)
            }
        } finally {
            release.complete(Unit)
            root.deleteTreeSafely(within = temporary)
            data.toFile().deleteTreeSafely(within = temporary)
        }
    }

    @Test fun aLateSettlementCannotChangeTheNextAttempt() = runBlocking {
        val root = Files.createTempDirectory("taskgraph-late-root").toFile()
        val data = Files.createTempDirectory("taskgraph-late-data")
        val dependencies = koinApplication {}
        val owner = Graph(dependencyAdapter = KoinDependencyAdapter(dependencies))
        try {
            AgentWorkspace(root, data, emptyMap()).use { workspace ->
                workspace.board.propose(draft(), person)
                workspace.board.advance("G1", "T1") { task, plan ->
                    task.copy(graph = plan.copy(state = TaskState.Running, attempt = 2))
                }
                val scope = TaskGraphScope(owner, workspace, "G1")
                try {
                    val before = workspace.board.view("G1")
                    val revision = workspace.board.revision
                    scope.coordinator.send(CoordinatorMessage.Settled("T1", 1, TaskState.Failed,
                        TaskOutcome(summary = "Late failure", tokens = 100, files = listOf("wrong.kt"), log = "hangar:old")))
                    scope.coordinator.send(CoordinatorMessage.Settled("T1", 2, TaskState.Succeeded, TaskOutcome(summary = "Current")))
                    withTimeout(5_000) {
                        while (workspace.board.get("T1").graph?.outcome?.summary != "Current") delay(10)
                    }
                    val settled = workspace.board.get("T1").graph!!
                    assertEquals(revision + 2, workspace.board.revision)
                    assertEquals(before.graph.revision + 2, workspace.board.graph("G1").revision)
                    assertEquals(2, workspace.board.coordination("G1").handoffs.single().attempt)
                    assertEquals(2, settled.outcome?.attempt)
                    assertEquals(TaskState.Succeeded, settled.state)
                    assertTrue(settled.outcome?.files.orEmpty().isEmpty())
                    assertNull(settled.outcome?.tokens)
                    assertNull(settled.outcome?.log)
                } finally { scope.close() }
            }
        } finally {
            owner.close()
            dependencies.close()
            root.deleteTreeSafely(within = temporary)
            data.toFile().deleteTreeSafely(within = temporary)
        }
    }

    @Test fun graphChecksBindTheWritingCandidateAndRunAgainAfterRepair() {
        val codex = Scripted(RuntimeKind.Codex, listOf("Built", "Repaired"))
        val claude = Scripted(RuntimeKind.ClaudeCode, listOf(
            "{\"verdict\":\"repair\",\"summary\":\"Handle empty input\",\"candidate\":\"{{candidate}}\",\"findings\":[]}",
            "{\"verdict\":\"pass\",\"summary\":\"Verified\",\"candidate\":\"{{candidate}}\",\"findings\":[]}"))
        val candidates = mutableListOf<String>()
        workspace(codex, claude, workflowCheck = { owner, task, stage ->
            assertEquals("T1", task)
            assertEquals(listOf("verify-parser"), owner.taskEvidence(task).requiredChecks)
            val candidate = requireNotNull(stage.candidateId)
            assertEquals(candidate, owner.captureCandidate(task).id)
            if (candidates.isNotEmpty()) {
                assertNotEquals(candidates.last(), candidate)
                assertTrue(assertFails { owner.evidence.accept(task, candidate) }.message.orEmpty().contains("Required kernel checks"))
            }
            candidates += candidate
            owner.evidence.check(task, AgentCandidateCheck("verify-parser", candidate,
                CheckResult(listOf("verify-parser"), CheckOutcome.Passed, 0, revision = candidate, coverage = CheckCoverage.Complete), "kernel"))
            WorkflowCheckResult(true, "Verified $candidate", "receipt-${candidates.size}")
        }) { connection, _, _ ->
            val proposed = connection.proposeTaskGraph(TaskGraphDraft("Checked repair", tasks = listOf(
                TaskGraphOp.Add("Build", key = "build", seat = TaskSeat(RuntimeKind.Codex, access = TaskAccess.Write)),
                TaskGraphOp.Add("Check", key = "check", kind = TaskKind.Check, check = "verify-parser", dependsOn = listOf(TaskDependency("build"))),
                TaskGraphOp.Add("Review", key = "review", seat = TaskSeat(RuntimeKind.ClaudeCode), contract = WorkflowContract.Decision,
                    dependsOn = listOf(TaskDependency("check"))),
                TaskGraphOp.Add("Accept", kind = TaskKind.Gate, dependsOn = listOf(TaskDependency("review", TaskCondition.Pass))),
            )), person)
            connection.startTaskGraph("G1", proposed.graph.revision, person)
            val view = connection.until("G1") { it.task("Accept").graph?.state == TaskState.Waiting }
            assertEquals(2, candidates.size)
            assertEquals(2, view.task("Check").graph?.attempt)
            assertEquals(candidates.last(), view.task("Check").graph?.outcome?.candidateId)
            assertEquals(candidates.last(), view.task("Build").graph?.outcome?.candidateId)
            assertEquals(listOf("receipt-2"), view.task("Check").graph?.outcome?.checks?.map { it.receipt })
        }
    }

    @Test fun repairReusesTheWritingTaskAndCandidateCheckoutBeforeThePersonGate() {
        val codex = Scripted(RuntimeKind.Codex, listOf("Built the parser in Source.kt", "Handled empty input"))
        val claude = Scripted(RuntimeKind.ClaudeCode, listOf(
            "{\"verdict\":\"repair\",\"summary\":\"Empty input crashes the parser\",\"candidate\":\"{{candidate}}\",\"findings\":[{\"title\":\"Empty input\",\"detail\":\"Handle empty input\",\"severity\":\"high\",\"sourcePath\":\"Source.kt\",\"line\":1}]}",
            "{\"verdict\":\"pass\",\"summary\":\"ok\",\"candidate\":\"{{candidate}}\",\"findings\":[]}"))
        workspace(codex, claude) { connection, root, worktrees ->
            suspend fun evidence() = connection.call("agent_task_graph", buildJsonObject { put("taskId", "T1") })
                .jsonObject.getValue("task").let { AgentWorkspaceJson.decodeFromJsonElement(AgentTaskEvidence.serializer(), it) }
            claude.onRun = { request, number ->
                assertEquals("val attempt = $number\n", File(request.workingDirectory, "Source.kt").readText())
                if (number == 1) {
                    val snapshots = evidence().candidates
                    val candidate = snapshots.last()
                    assertFailsWith<Exception> {
                        connection.call("agent_finding", buildJsonObject {
                            put("taskId", "T1")
                            put("finding", AgentWorkspaceJson.encodeToJsonElement(AgentFinding.serializer(), AgentFinding("wrong-candidate",
                                snapshots.first().id, request.environment.getValue(AgentRequest.RunVariable), "claudecode",
                                "Wrong checkout", "This was not assigned to the reviewer", "high")))
                        })
                    }
                    assertEquals(candidate.id, request.outputSchema!!.getValue("properties").jsonObject.getValue("candidate").jsonObject.getValue("enum").jsonArray.single().jsonPrimitive.content)
                }
            }
            codex.onRun = { request, number ->
                File(request.workingDirectory, "Source.kt").writeText("val attempt = $number\n")
                if (number == 2) {
                    val captured = connection.call("agent_candidate_capture", buildJsonObject { put("taskId", "T1") })
                        .let { AgentWorkspaceJson.decodeFromJsonElement(AgentCandidate.serializer(), it) }
                    connection.call("agent_finding_resolve", buildJsonObject {
                        put("taskId", "T1"); put("findingId", evidence().findings.single().id); put("candidateId", captured.id)
                        put("reason", "The second attempt handles empty input")
                    })
                }
            }
            val proposed = connection.proposeTaskGraph(draft(), person)
            connection.startTaskGraph("G1", proposed.graph.revision, person)
            val waiting = connection.until("G1") { it.task("Land it").graph?.state == TaskState.Waiting }
            assertEquals(3, waiting.tasks.size)
            val build = waiting.task("Build the parser")
            val review = waiting.task("Review the parser")
            assertEquals(2, build.graph?.attempt)
            assertEquals(2, review.graph?.attempt)
            assertEquals("pass", review.graph?.outcome?.verdict)
            assertEquals("T2", build.graph?.repairOf)
            assertEquals(BoardStage.Review, build.stage)
            assertEquals(480, waiting.graph.tokensUsed)
            assertEquals(2, codex.requests.size)
            assertEquals(2, claude.requests.size)
            assertTrue("Empty input crashes the parser" in codex.requests[1].prompt)
            assertTrue("Handled empty input" in claude.requests[1].prompt)
            assertTrue("Built the parser in Source.kt" in claude.requests[0].prompt)
            assertTrue(claude.requests.all { listOf("Handoff G1-H", "Changed paths:", "Checks:", "Open questions:", "Decisions:", "Session logs:").all(it.prompt::contains) })
            assertTrue(claude.requests.all { !it.agent.tools.allowWrites })
            val path = File(codex.requests.first().workingDirectory).canonicalFile
            assertNotEquals(root.canonicalFile, path)
            assertTrue((codex.requests + claude.requests).all { File(it.workingDirectory).canonicalFile == path })
            val owned = worktrees.read(requireNotNull(build.graph?.worktreeId))
            assertEquals(path, File(owned.roots.single().path).canonicalFile)
            codex.requests.forEach { assertEquals(owned.id, worktrees.list(it.environment.getValue(AgentRequest.RunVariable)).single().id) }
            assertEquals("val baseline = 1\n", File(root, "Source.kt").readText())
            val candidate = requireNotNull(build.graph.outcome?.candidateId)
            val record = evidence()
            assertTrue(record.candidates.size >= 3)
            assertEquals(candidate, record.findings.single().resolvedByCandidate)
            assertEquals(candidate, review.graph?.outcome?.candidateId)
            assertEquals(candidate, waiting.handoffs.getValue("T1").candidate)
            assertEquals(2, waiting.handoffs.getValue("T1").attempt)
            assertTrue(claude.requests.last().prompt.contains(candidate))
            assertNotEquals(record.findings.single().candidateId, candidate)
            assertEquals(setOf(1, 2), build.graph.workAttempts.values.toSet())
            assertFailsWith<Exception> {
                connection.call("agent_finding", buildJsonObject {
                    put("taskId", "T1")
                    put("finding", AgentWorkspaceJson.encodeToJsonElement(AgentFinding.serializer(), AgentFinding("old-attempt",
                        candidate, claude.requests.first().environment.getValue(AgentRequest.RunVariable), "claudecode",
                        "Stale review", "An earlier attempt cannot review the repair", "high")))
                })
            }
            val data = path.parentFile.parentFile.parentFile
            (codex.requests + claude.requests).forEach { request ->
                val saved = File(data, "requests/${request.environment.getValue(AgentRequest.RunVariable)}.json")
                val submitted = ConductorJson.decodeFromString(AgentSubmission.serializer(), saved.readText())
                assertTrue(submitted.prompt.length <= 8000)
            }
            val shape = connection.taskGraphShape("G1")
            assertEquals(setOf("T1", "T2", "T3"), shape.nodes.mapNotNull { it.attributes["task"] }.toSet())
            assertEquals(setOf("T1", "T2"), shape.wires.map { it.consumerPort }.toSet())
            assertFailsWith<Exception> { connection.reportTaskGraph("G1", "T3", TaskReport.Succeeded, TaskOutcome(summary = "Landed"),
                BoardActor(BoardActorKind.Agent, "claude", "claude:lead-session")) }
            connection.reportTaskGraph("G1", "T3", TaskReport.Succeeded, TaskOutcome(summary = "Landed"), person)
            val finished = connection.until("G1") { it.graph.status != TaskGraphStatus.Running }
            assertEquals(TaskGraphStatus.Finished, finished.graph.status)
            assertEquals("accepted", finished.task("Land it").graph?.outcome?.verdict)
        }
    }

    @Test fun aStaleDecisionContinuesTheSameReviewAttemptUntilItNamesTheCandidate() {
        val codex = Scripted(RuntimeKind.Codex, listOf("Built it"))
        val claude = Scripted(RuntimeKind.ClaudeCode, listOf(
            "{\"verdict\":\"pass\",\"summary\":\"Old checkout\",\"candidate\":\"stale\",\"findings\":[]}",
            "{\"verdict\":\"pass\",\"summary\":\"Reviewed the assigned candidate\",\"candidate\":\"{{candidate}}\",\"findings\":[]}"))
        workspace(codex, claude) { connection, _, _ ->
            claude.onRun = { _, number ->
                if (number == 2) {
                    val current = connection.taskGraph("G1")
                    assertEquals(TaskState.Running, current.task("Review the parser").graph?.state)
                    assertEquals(1, current.task("Review the parser").graph?.attempt)
                    assertEquals(TaskState.Pending, current.task("Land it").graph?.state)
                    assertTrue(current.task("Review the parser").graph?.note.orEmpty().contains("Stale Decision"))
                }
            }
            val proposed = connection.proposeTaskGraph(draft(), person)
            connection.startTaskGraph("G1", proposed.graph.revision, person)
            val waiting = connection.until("G1") { it.task("Land it").graph?.state == TaskState.Waiting }
            assertEquals(2, claude.requests.size)
            assertEquals(1, waiting.task("Review the parser").graph?.attempt)
            assertEquals("Reviewed the assigned candidate", waiting.task("Review the parser").graph?.outcome?.summary)
            assertEquals(waiting.task("Build the parser").graph?.outcome?.candidateId, waiting.task("Review the parser").graph?.outcome?.candidateId)
            assertEquals(360, waiting.graph.tokensUsed)
            assertEquals(claude.requests.first().workingDirectory, claude.requests.last().workingDirectory)
        }
    }

    @Test fun malformedAndInvalidStructuredReviewsHoldThePersonGate() {
        val valid = """{"title":"Defect","detail":"Fix the value","severity":"high","sourcePath":"Source.kt","line":1}"""
        val invalid = valid.replace("\"line\":1", "\"line\":999")
        val reviews = listOf(
            """{"verdict":"pass","summary":"Missing findings","candidate":"{{candidate}}"}""" to 0,
            """{"verdict":"repair","summary":"Invalid location","candidate":"{{candidate}}","findings":[$valid,$invalid]}""" to 0,
            """{"verdict":"pass","summary":"Contradicts findings","candidate":"{{candidate}}","findings":[$valid]}""" to 1,
        )
        reviews.forEach { (reply, retained) ->
            val codex = Scripted(RuntimeKind.Codex, listOf("Built it"))
            val claude = Scripted(RuntimeKind.ClaudeCode, listOf(reply))
            workspace(codex, claude) { connection, _, _ ->
                val proposed = connection.proposeTaskGraph(draft(), person)
                connection.startTaskGraph("G1", proposed.graph.revision, person)
                val stopped = connection.until("G1") { it.task("Review the parser").graph?.state == TaskState.Failed }
                assertEquals(TaskState.Pending, stopped.task("Land it").graph?.state)
                assertTrue(stopped.task("Review the parser").graph?.outcome?.summary.orEmpty().startsWith("Invalid structured review:"))
                val evidence = connection.call("agent_task_graph", buildJsonObject { put("taskId", "T1") })
                    .jsonObject.getValue("task").let { AgentWorkspaceJson.decodeFromJsonElement(AgentTaskEvidence.serializer(), it) }
                assertEquals(retained, evidence.findings.size)
                evidence.findings.forEach { assertEquals(claude.requests.single().environment.getValue(AgentRequest.RunVariable), it.producerRunId) }
            }
        }
    }

    @Test fun aReviewCannotChangeItsCandidateDuringTheTurn() {
        val codex = Scripted(RuntimeKind.Codex, listOf("Built it"))
        val claude = Scripted(RuntimeKind.ClaudeCode, listOf("""{"verdict":"pass","summary":"Changed it","candidate":"{{candidate}}","findings":[]}"""))
        claude.onRun = { request, _ -> File(request.workingDirectory, "Source.kt").writeText("val changedByReviewer = 1\n") }
        workspace(codex, claude) { connection, root, _ ->
            val proposed = connection.proposeTaskGraph(draft(), person)
            connection.startTaskGraph("G1", proposed.graph.revision, person)
            val stopped = connection.until("G1") { it.task("Review the parser").graph?.state == TaskState.Failed }
            val reason = stopped.task("Review the parser").graph?.outcome?.summary.orEmpty()
            assertEquals("The review handoff candidate is stale or incomplete", reason)
            assertEquals(1, claude.requests.size)
            assertTrue(connection.list(50).any { it.failure == "The review candidate changed during the turn" })
            assertEquals(TaskState.Pending, stopped.task("Land it").graph?.state)
            assertEquals("val baseline = 1\n", File(root, "Source.kt").readText())
        }
    }

    @Test fun aFailedTaskPausesTheGraphUntilAPersonRetriesIt() {
        val codex = Scripted(RuntimeKind.Codex, listOf("Built it"))
        val claude = Scripted(RuntimeKind.ClaudeCode, listOf("{\"verdict\":\"pass\",\"summary\":\"ok\",\"candidate\":\"{{candidate}}\",\"findings\":[]}"))
        codex.failing.set(true)
        workspace(codex, claude) { connection, _, _ ->
            val proposed = connection.proposeTaskGraph(draft(), person)
            connection.startTaskGraph("G1", proposed.graph.revision, person)
            val paused = connection.until("G1") { it.graph.status == TaskGraphStatus.Paused }
            assertTrue(paused.graph.reason!!.contains("T2 requires T1 Succeeded, but T1 is Failed"))
            assertEquals(1, codex.requests.size)
            val build = paused.task("Build the parser").graph!!
            assertEquals(TaskState.Failed, build.state)
            assertEquals(1, build.attempt)
            assertEquals(TaskState.Pending, paused.task("Review the parser").graph?.state)
            assertTrue(claude.requests.isEmpty())

            assertEquals(listOf("T2 requires T1 Succeeded, but T1 is Failed"), paused.blocked["T2"])
            connection.startTaskGraph("G1", null, person)
            val stillHeld = connection.until("G1") { it.graph.status == TaskGraphStatus.Paused }
            assertEquals(TaskState.Pending, stillHeld.task("Review the parser").graph?.state)
            assertTrue(claude.requests.isEmpty())

            codex.failing.set(false)
            connection.patchTaskGraph("G1", TaskGraphPatch(listOf(TaskGraphOp.Retry("T1")), "Fixed the toolchain"), person)
            connection.startTaskGraph("G1", null, person)
            val waiting = connection.until("G1") { view -> view.task("Land it").graph?.state == TaskState.Waiting }
            assertEquals(2, waiting.task("Build the parser").graph?.attempt)
            assertEquals(TaskState.Succeeded, waiting.task("Build the parser").graph?.state)
            assertEquals("pass", waiting.task("Review the parser").graph?.outcome?.verdict)
            assertEquals(2, waiting.task("Build the parser").graph?.outcome?.attempt)
            assertNotEquals(build.runId, waiting.task("Build the parser").graph?.runId)
            assertNotNull(waiting.task("Build the parser").graph?.runId)
            assertEquals(setOf(1, 2), waiting.task("Build the parser").graph?.workAttempts?.values?.toSet())
            val beforeReport = connection.taskGraph("G1")
            val oldSession = BoardActor(BoardActorKind.Agent, "codex", build.run)
            val refused = assertFailsWith<Exception> {
                connection.reportTaskGraph("G1", "T1", TaskReport.Failed, TaskOutcome(summary = "Late first run"), oldSession)
            }.message.orEmpty()
            assertTrue("current attempt 2" in refused && "caller attempt 1" in refused, refused)
            assertEquals(beforeReport, connection.taskGraph("G1"))
            val currentSession = oldSession.copy(session = waiting.task("Build the parser").graph?.run)
            assertEquals(2, connection.reportTaskGraph("G1", "T1", TaskReport.Review,
                TaskOutcome(summary = "Current run confirmed", attempt = 2), currentSession).graph?.outcome?.attempt)
            assertTrue(waiting.graph.changes.any { it.reason == "Resumed" })
        }
    }
}
