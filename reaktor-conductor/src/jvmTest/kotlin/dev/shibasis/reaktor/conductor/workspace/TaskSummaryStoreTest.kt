package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class TaskSummaryStoreTest {
    private fun temporary(body: (Path) -> Unit) {
        val directory = Files.createTempDirectory("task-summary")
        try { body(directory) } finally { directory.toFile().deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    private fun plan(id: String = "plan") = AgentActivityItem(id, ActivityKind.Tool, "TodoWrite", ActivityStatus.Started,
        input = """{"todos":[{"content":"Verify offsets","status":"in_progress"}]}""")

    @Test fun aPlanSurvivesLongActivityLogsArchivingRestartAndInputlessCompletion() = temporary { directory ->
        val run = "a".repeat(64)
        val activity = AgentActivityStore(directory)
        activity.append(run, "claude", 1, plan())
        activity.append(run, "claude", 1, plan().copy(input = null, status = ActivityStatus.Completed))
        repeat(205) { activity.append(run, "claude", 1, AgentActivityItem("tool-$it", ActivityKind.Tool, "Read")) }
        assertEquals("Verify offsets", TaskSummaries.plan(assertNotNull(activity.plan(run)).item).single().text)
        activity.archive(run)
        val reopened = AgentActivityStore(directory)
        assertEquals(activity.plan(run), reopened.plan(run))
        reopened.append(run, "claude", 1, plan("new").copy(input = """{"todos":[{"content":"Finish","status":"completed"}]}"""))
        assertEquals("Finish", TaskSummaries.plan(assertNotNull(reopened.plan(run)).item).single().text)
    }

    @Test fun aNativeRetryAndFailedPlanCannotReuseTheEarlierPlan() = temporary { directory ->
        val run = "b".repeat(64)
        val activity = AgentActivityStore(directory)
        activity.append(run, "codex", 1, plan())
        assertNotNull(activity.plan(run))
        activity.append(run, "codex", 2, AgentActivityItem("start", ActivityKind.Agent, "Started"))
        assertNull(activity.plan(run))
        activity.append(run, "codex", 2, plan())
        assertNotNull(activity.plan(run))
        activity.append(run, "codex", 2, plan().copy(input = null, status = ActivityStatus.Failed))
        assertNull(activity.plan(run))
    }

    @Test fun newReceiptsRefreshTheProjectedHandoffWithoutRewritingHistoryOrAPersonsHandoff() = temporary { directory ->
        val actor = BoardActor(BoardActorKind.Agent, "Fixture", "codex:fixture")
        val candidate = AgentCandidate("c2", directory.toString(), null, "source", 1, listOf("Parser.kt"), null, true)
        var facts = AgentTaskEvidence("T1", candidates = listOf(candidate), requiredChecks = listOf("unit"))
        fun store() = AgentBoardStore(directory, summarize = { TaskSummaries.of(it, facts) })
        val board = store()
        val graph = board.propose(TaskGraphDraft("Summaries", tasks = listOf(TaskGraphOp.Add("Writer", seat = TaskSeat(RuntimeKind.Codex)))), actor).graph
        board.advance(graph.id, "T1") { task, plan -> task.copy(graph = plan.copy(attempt = 2, state = TaskState.Succeeded,
            outcome = TaskOutcome(attempt = 2, candidateId = "c2"))) }
        board.appendHandoff(TaskHandoff(graph.id, "T1", 2, "Verify offsets", "Snapshot", candidate = "c2", recordedSummary = true), actor)
        val before = board.view(graph.id)
        assertTrue("1 missing" in before.handoffs.getValue("T1").done)
        val revision = board.revision
        facts = facts.copy(checks = listOf(AgentCandidateCheck("unit", "c2", CheckResult(listOf("./gradlew", "test"), CheckOutcome.Failed, revision = "c2"), "kernel")),
            findings = listOf(AgentFinding("F1", "c2", "review", "claude", "Offset reversed", "Repair it", "high")))
        val refreshed = board.view(graph.id)
        val handoff = refreshed.handoffs.getValue("T1")
        assertEquals(refreshed.summaries.getValue("T1").text(), handoff.done)
        assertTrue("1 failed" in handoff.done)
        assertEquals(listOf("unit"), handoff.checkReceipts)
        assertEquals(listOf("Offset reversed"), handoff.openIssues)
        assertEquals(revision, board.revision)
        assertEquals(refreshed, store().view(graph.id))
        assertEquals("Snapshot", board.coordination(graph.id).handoffs.last().done)
        board.appendHandoff(TaskHandoff(graph.id, "T1", 2, "Person goal", "Person's words", candidate = "c2"), actor)
        assertEquals("Person's words", board.view(graph.id).handoffs.getValue("T1").done)
    }
}
