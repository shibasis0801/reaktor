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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class TaskGraphRuntimeTest {
    private class Scripted(override val kind: RuntimeKind, private val replies: List<String>) : AgentRuntime {
        val requests = CopyOnWriteArrayList<AgentRequest>()
        val failing = AtomicBoolean(false)
        override fun run(request: AgentRequest) = flow {
            requests += request
            val session = ProviderSession(kind, "${kind.name}-${requests.size}")
            emit(AgentEvent.Started(request.agent.id, session))
            val failed = failing.get()
            val text = if (failed) "" else replies[minOf(requests.size, replies.size) - 1]
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

    private fun workspace(codex: Scripted, claude: Scripted, body: suspend (AgentWorkspaceConnection) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("taskgraph-root").toFile()
        val data = Files.createTempDirectory("taskgraph-data")
        try { AgentWorkspaceConnection.open(root, data, mapOf(codex.kind to codex, claude.kind to claude)).use { body(it) } }
        finally { root.deleteTreeSafely(within = temporary); data.toFile().deleteTreeSafely(within = temporary) }
    }

    @Test fun aReviewThatAsksForARepairAddsARepairRoundAndTheGateWaitsForAPerson() {
        val codex = Scripted(RuntimeKind.Codex, listOf("Built the parser in Parser.kt", "Handled empty input"))
        val claude = Scripted(RuntimeKind.ClaudeCode, listOf("Empty input crashes.\n{\"verdict\": \"repair\", \"summary\": \"Empty input crashes the parser\"}",
            "Looks right now.\n{\"verdict\":\"pass\",\"summary\":\"ok\"}"))
        workspace(codex, claude) { connection ->
            val proposed = connection.proposeTaskGraph(draft(), person)
            assertEquals(TaskGraphStatus.Draft, proposed.graph.status)
            connection.startTaskGraph("G1", proposed.graph.revision, person)
            val waiting = connection.until("G1") { view -> view.task("Land it").graph?.state == TaskState.Waiting }
            assertEquals(TaskGraphStatus.Running, waiting.graph.status)
            assertEquals(5, waiting.tasks.size)
            val review = waiting.task("Review the parser").graph!!
            assertEquals(TaskState.Succeeded, review.state)
            assertEquals("repair", review.outcome?.verdict)
            val repair = waiting.task("Repair: Build the parser")
            assertEquals(TaskState.Succeeded, repair.graph?.state)
            assertEquals("T2", repair.graph?.repairOf)
            assertEquals("T1", repair.parent)
            val rereview = waiting.task("Review the repair of T1")
            assertEquals("pass", rereview.graph?.outcome?.verdict)
            assertTrue(TaskDependency(rereview.id, TaskCondition.Pass) in waiting.task("Land it").graph!!.dependsOn)
            assertEquals(BoardStage.Review, waiting.task("Build the parser").stage)
            assertEquals(480, waiting.graph.tokensUsed)
            assertEquals(2, codex.requests.size)
            assertEquals(2, claude.requests.size)
            assertTrue("Empty input crashes the parser" in codex.requests[1].prompt)
            assertTrue("Handled empty input" in claude.requests[1].prompt)
            assertTrue("Built the parser in Parser.kt" in claude.requests[0].prompt)
            val runs = connection.list().map { it.id }.toSet()
            assertTrue((codex.requests + claude.requests).all { it.environment[AgentRequest.RunVariable] in runs })
            assertTrue(waiting.task("Build the parser").work.single().startsWith(BoardRefs.Hangar))

            val shape = connection.taskGraphShape("G1")
            assertEquals(setOf("T1", "T2", "T3", "T4", "T5"), shape.nodes.mapNotNull { it.attributes["task"] }.toSet())
            assertEquals("coordinator", shape.nodes.single { it.attributes["role"] != null }.attributes["role"])
            assertEquals(setOf("T1", "T2", "T4", "T5"), shape.wires.map { it.consumerPort }.toSet())

            assertFailsWith<Exception> { connection.reportTaskGraph("G1", "T3", TaskReport.Succeeded, TaskOutcome(summary = "Landed"),
                BoardActor(BoardActorKind.Agent, "claude", "claude:lead-session")) }
            connection.reportTaskGraph("G1", "T3", TaskReport.Succeeded, TaskOutcome(summary = "Landed"), person)
            val finished = connection.until("G1") { it.graph.status != TaskGraphStatus.Running }
            assertEquals(TaskGraphStatus.Finished, finished.graph.status)
            assertEquals("accepted", finished.task("Land it").graph?.outcome?.verdict)
        }
    }

    @Test fun aFailedTaskPausesTheGraphUntilAPersonRetriesIt() {
        val codex = Scripted(RuntimeKind.Codex, listOf("Built it"))
        val claude = Scripted(RuntimeKind.ClaudeCode, listOf("{\"verdict\":\"pass\",\"summary\":\"ok\"}"))
        codex.failing.set(true)
        workspace(codex, claude) { connection ->
            val proposed = connection.proposeTaskGraph(draft(), person)
            connection.startTaskGraph("G1", proposed.graph.revision, person)
            val paused = connection.until("G1") { it.graph.status == TaskGraphStatus.Paused }
            assertTrue(paused.graph.reason!!.startsWith("T1 failed"))
            assertEquals(1, codex.requests.size)
            val build = paused.task("Build the parser").graph!!
            assertEquals(TaskState.Failed, build.state)
            assertTrue(build.held)
            assertEquals(1, build.attempt)
            assertEquals(TaskState.Pending, paused.task("Review the parser").graph?.state)
            assertTrue(claude.requests.isEmpty())

            codex.failing.set(false)
            connection.patchTaskGraph("G1", TaskGraphPatch(listOf(TaskGraphOp.Retry("T1")), "Fixed the toolchain"), person)
            connection.startTaskGraph("G1", null, person)
            val waiting = connection.until("G1") { view -> view.task("Land it").graph?.state == TaskState.Waiting }
            assertEquals(2, waiting.task("Build the parser").graph?.attempt)
            assertEquals(TaskState.Succeeded, waiting.task("Build the parser").graph?.state)
            assertEquals("pass", waiting.task("Review the parser").graph?.outcome?.verdict)
            assertTrue(waiting.graph.changes.any { it.reason == "Resumed" })
        }
    }
}
