package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class TaskContextTest {
    private val person = BoardActor(BoardActorKind.Person, "owner")

    private class Runtime(override val kind: RuntimeKind) : AgentRuntime {
        val requests = CopyOnWriteArrayList<AgentRequest>()
        var onRun: suspend (AgentRequest) -> Unit = {}
        override fun run(request: AgentRequest) = flow {
            requests += request
            emit(AgentEvent.Started(request.agent.id, ProviderSession(kind, "fixture-${requests.size}")))
            onRun(request)
            emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "Completed", true)))
        }
    }

    private fun fixture(test: suspend (AgentWorkspaceConnection, AgentWorkspace, File, java.nio.file.Path, Runtime) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("task-context-source").toFile()
        val data = Files.createTempDirectory("task-context-data")
        try {
            File(root, "source.txt").writeText("baseline")
            listOf(listOf("init", "-q"), listOf("add", "."), listOf("-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "-qm", "base")).forEach { args ->
                val process = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                check(process.waitFor() == 0) { output }
            }
            val runtime = Runtime(RuntimeKind.Codex)
            lateinit var owner: AgentWorkspace
            AgentWorkspaceConnection.open(root, data, mapOf(runtime.kind to runtime, RuntimeKind.ClaudeCode to Runtime(RuntimeKind.ClaudeCode)),
                extraTools = { owner = it; emptyList() }, discover = { ProviderCapability(it, defaultModel = "fixture") }).use {
                test(it, owner, root, data, runtime)
            }
        } finally {
            root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
            data.toFile().deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }

    private fun seed(owner: AgentWorkspace, root: File, data: java.nio.file.Path, number: Int, scope: AgentMemoryScope,
        target: AgentTaskTarget? = null, age: Long = 0): AgentMemory {
        val source = SourceCandidates(root, owner.evidence.artifacts).capture()
        val value = AgentMemory(number.toString(16).padStart(64, '0'), "Memory $number", "Retained evidence $number", "run-$number", "event-$number", "codex",
            source.sourceDigest, emptyList(), System.currentTimeMillis() - age, scope, target)
        Files.writeString(data.resolve("memory/${value.id}.json"), ConductorJson.encodeToString(AgentMemory.serializer(), value))
        return value
    }

    @Test fun memoryScopesSourceAndExpiryAreEnforcedAndLegacyValuesDecode() = fixture { _, owner, root, data, _ ->
        val workspace = seed(owner, root, data, 1, AgentMemoryScope.Workspace, AgentTaskTarget("G1", "T1", 2))
        seed(owner, root, data, 2, AgentMemoryScope.Graph, AgentTaskTarget("G1", "T1", 2))
        seed(owner, root, data, 3, AgentMemoryScope.Task, AgentTaskTarget("G1", "T1", 2))
        seed(owner, root, data, 4, AgentMemoryScope.Graph, AgentTaskTarget("G2", "T3", 1))
        seed(owner, root, data, 5, AgentMemoryScope.Task, AgentTaskTarget("G1", "T2", 1))
        seed(owner, root, data, 6, AgentMemoryScope.Workspace, AgentTaskTarget("G1", "T1", 1), 31 * 86400000L)
        fun refs(graph: String, task: String) = owner.memory.search("", null, false, graph, task).entries.map { it.title }.toSet()
        assertEquals(setOf("Memory 1", "Memory 2", "Memory 3"), refs("G1", "T1"))
        assertEquals(setOf("Memory 1", "Memory 2", "Memory 5"), refs("G1", "T2"))
        assertEquals(setOf("Memory 1", "Memory 4"), refs("G2", "T3"))
        val encoded = ConductorJson.encodeToJsonElement(AgentMemory.serializer(), workspace).jsonObject
        val legacy = ConductorJson.decodeFromJsonElement(AgentMemory.serializer(), JsonObject(encoded - "scope" - "origin"))
        assertEquals(AgentMemoryScope.Workspace, legacy.scope)
        assertNull(legacy.origin)
        Files.writeString(data.resolve("memory/${workspace.id}.json"), JsonObject(encoded - "scope" - "origin").toString())
        assertTrue(owner.memory.search("", null, false, "G1", "T1").entries.single { it.title == "Memory 1" }.reason.contains("unknown legacy origin"))
        assertTrue(owner.memory.search("", null, true, "G1", "T1").entries.any { it.title == "Memory 6" && "STALE" in it.reason })
        File(root, "source.txt").writeText("changed")
        assertTrue(owner.memory.search("", null, false, "G1", "T1").entries.isEmpty())
    }

    @Test fun previewAndDispatchShareDecisionsAndPersonExclusionsWithoutDroppingRequiredInstructions() = fixture { connection, owner, root, data, _ ->
        val view = connection.proposeTaskGraph(TaskGraphDraft("Context", "Preserve the required goal", listOf(
            TaskGraphOp.Add("Inspect source", intent = "Required task instruction", acceptance = listOf("Explain the evidence"), seat = TaskSeat(RuntimeKind.Codex)),
        )), person)
        val task = view.tasks.single()
        val memory = seed(owner, root, data, 1, AgentMemoryScope.Task, AgentTaskTarget("G1", task.id, 1))
        val first = owner.board.appendDecision(TaskDecision("G1", "Old choice", "Initial evidence", listOf(task.id)), person)
        val current = owner.board.appendDecision(TaskDecision("G1", "Current choice", "Revised evidence", listOf(task.id), supersedes = first.id, personAccepted = true), person)
        fun preview() = requireNotNull(owner.graphs).context("G1", task.id)
        val before = preview()
        assertTrue(before.prompt.contains("Required task instruction"))
        assertEquals(setOf("memory:${memory.id}", "decision:${current.id}"), before.context!!.entries.map { it.ref }.toSet())
        assertEquals(2, connection.taskGraph("G1").decisions.size)
        val excluded = "memory:${memory.id}"
        val patch = TaskGraphPatch(listOf(TaskGraphOp.Update(task.id, excludedContext = listOf(excluded))), "Drop optional memory", owner.board.graph("G1").revision)
        assertFailsWith<IllegalArgumentException> { owner.board.patch("G1", patch, TaskGraphCaller(BoardActor(BoardActorKind.Agent, "lead", "codex:lead"), lead = true)) }
        connection.patchTaskGraph("G1", patch, person)
        val selected = preview()
        assertEquals(before.prompt, selected.prompt)
        assertEquals(excluded, selected.excluded.single().ref)
        assertTrue(selected.context!!.entries.none { it.ref == excluded })
        assertFailsWith<Exception> { connection.patchTaskGraph("G1", TaskGraphPatch(listOf(TaskGraphOp.Update(task.id, excludedContext = listOf("required:prompt"))), "Cannot drop mandatory inputs"), person) }
        connection.startTaskGraph("G1", owner.board.graph("G1").revision, person)
        withTimeout(10000) { while (owner.board.get(task.id).graph?.runId == null) delay(20) }
        val saved = preview()
        val request = owner.submission(requireNotNull(owner.board.get(task.id).graph?.runId))
        assertTrue(saved.dispatched)
        assertEquals(selected.prompt, request.prompt)
        assertEquals(selected.context.entries, request.context!!.entries)
        assertEquals(request.context, saved.context)
        assertFailsWith<Exception> { connection.patchTaskGraph("G1", TaskGraphPatch(listOf(TaskGraphOp.Update(task.id, excludedContext = emptyList())), "Cannot alter dispatched inputs"), person) }
    }

    @Test fun optionalPreviewUsesTheDispatchEntryBudget() = fixture { connection, owner, _, _, _ ->
        val view = connection.proposeTaskGraph(TaskGraphDraft("Bounded", tasks = listOf(
            TaskGraphOp.Add("Inspect", seat = TaskSeat(RuntimeKind.Codex)),
        )), person)
        val task = view.tasks.single()
        repeat(25) { owner.board.appendDecision(TaskDecision(view.graph.id, "Claim $it", "Evidence", listOf(task.id)), person) }
        val preview = requireNotNull(owner.graphs).context(view.graph.id, task.id)
        assertEquals(20, preview.context!!.entries.size)
        assertEquals(5, preview.excluded.size)
        connection.startTaskGraph(view.graph.id, owner.board.graph(view.graph.id).revision, person)
        withTimeout(10000) { while (owner.board.get(task.id).graph?.runId == null) delay(20) }
        val saved = owner.submission(requireNotNull(owner.board.get(task.id).graph?.runId))
        assertEquals(preview.context.entries, saved.context!!.entries)
    }

    @Test fun privateReadsStayClosedDuringIndependentCompareAndOpenAfterSubmission() = fixture { _, owner, root, _, runtime ->
        val prior = owner.submit(AgentSubmission("prior-private", RuntimeKind.Codex, "Inspect the source"))
        withTimeout(10000) { while (owner.get(prior.id).status == AgentRunStatus.Running) delay(20) }
        File(root, "source.txt").writeText("candidate change")
        val candidate = owner.captureCandidate(prior.threadId)
        owner.evidence.finding(prior.threadId, AgentFinding("private-finding", candidate.id, prior.id, "codex",
            "Prior private finding", "Peer claim must stay private", "medium", sourcePath = "source.txt", line = 1))
        val started = kotlinx.coroutines.CompletableDeferred<String>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        runtime.onRun = { request ->
            assertFalse("Prior private finding" in request.prompt)
            assertFalse("Peer claim must stay private" in request.prompt)
            started.complete(requireNotNull(request.executionId).substringBefore(':'))
            release.await()
        }
        val run = owner.submit(AgentSubmission("compare-private", RuntimeKind.Codex, "Independent assessment",
            threadId = prior.threadId, collaboration = AgentCollaboration.Compare, partner = AgentPartner(RuntimeKind.ClaudeCode)))
        assertEquals(run.id, withTimeout(10000) { started.await() })
        assertFailsWith<IllegalArgumentException> { owner.requirePrivateRead(run.id) }
        release.complete(Unit)
        withTimeout(10000) { while (owner.get(run.id).status == AgentRunStatus.Running) delay(20) }
        assertEquals(AgentRunStatus.Completed, owner.get(run.id).status)
        owner.requirePrivateRead(run.id)
    }

    @Test fun retainedTaskResultsSaveTheirOriginAndScope() = fixture { connection, owner, _, _, _ ->
        val view = connection.proposeTaskGraph(TaskGraphDraft("Retain", tasks = listOf(TaskGraphOp.Add("Inspect", seat = TaskSeat(RuntimeKind.Codex)))), person)
        connection.startTaskGraph(view.graph.id, view.graph.revision, person)
        val task = view.tasks.single()
        withTimeout(10000) { while (owner.board.get(task.id).graph?.state != TaskState.Succeeded) delay(20) }
        val run = requireNotNull(owner.board.get(task.id).graph?.runId)
        val event = owner.checkpoint(run)!!.completed.values.first { it.author is Author.Agent }
        val memory = owner.remember(run, event.id.value, AgentMemoryScope.Task)
        assertEquals(AgentMemoryScope.Task, memory.scope)
        assertEquals(AgentTaskTarget(view.graph.id, task.id, 1), memory.origin)
        assertEquals(memory, owner.remember(run, event.id.value, AgentMemoryScope.Task))
        assertFailsWith<IllegalArgumentException> { owner.remember(run, event.id.value, AgentMemoryScope.Workspace) }
        assertTrue(owner.memory.search("", null, false, view.graph.id, task.id).entries.single().reason.contains("attempt-1"))
    }

    @Test fun blindContextDoesNotExposeMemoryOrDecisions() = fixture { connection, owner, root, data, _ ->
        val view = connection.proposeTaskGraph(TaskGraphDraft("Blind", tasks = listOf(TaskGraphOp.Add("Inspect source", seat = TaskSeat(RuntimeKind.Codex)))), person)
        val task = view.tasks.single()
        seed(owner, root, data, 1, AgentMemoryScope.Task, AgentTaskTarget("G1", task.id, 1))
        owner.board.appendDecision(TaskDecision("G1", "Peer verdict", "Private evidence", listOf(task.id)), person)
        val context = owner.taskContext(task, "Required independent instructions", blind = true)
        assertNull(context.context)
        assertTrue(context.excluded.isEmpty())
        assertEquals("Required independent instructions", context.prompt)
    }
}
