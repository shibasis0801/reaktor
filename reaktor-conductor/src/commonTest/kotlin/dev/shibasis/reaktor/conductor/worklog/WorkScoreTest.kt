package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.ArtifactRef
import dev.shibasis.reaktor.conductor.CheckOutcome
import dev.shibasis.reaktor.conductor.CheckResult
import dev.shibasis.reaktor.conductor.ConductorJson
import dev.shibasis.reaktor.conductor.workspace.BoardActor
import dev.shibasis.reaktor.conductor.workspace.BoardActorKind
import dev.shibasis.reaktor.conductor.workspace.BoardStage
import dev.shibasis.reaktor.conductor.workspace.BoardTask
import dev.shibasis.reaktor.conductor.workspace.TaskDependency
import dev.shibasis.reaktor.conductor.workspace.TaskGraph
import dev.shibasis.reaktor.conductor.workspace.TaskGraphView
import dev.shibasis.reaktor.conductor.workspace.TaskPlan
import dev.shibasis.reaktor.conductor.workspace.TaskState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkScoreTest {
    private val actor = BoardActor(BoardActorKind.Person, "Owner")

    @Test fun overlappingQuestionsAndIdleGapsAreCountedOnce() {
        val log = WorkLog(listOf(
            WorkTurn("1", "Build", 0, 100, listOf(
                WorkStep.Question("q1", 20, "Which?", "A", StepStatus.Done, 60),
                WorkStep.Question("q2", 30, "Confirm?", "Yes", StepStatus.Done, 70),
            ), WorkUsage(input = 100, output = 10, costUsd = 0.01)),
            WorkTurn("2", "Continue", 200, 250, emptyList(), WorkUsage(input = 200, output = 20, costUsd = 0.02)),
        ))
        val score = log.score()
        assertEquals(250L, score.wallMillis)
        assertEquals(100L, score.workingMillis)
        assertEquals(150L, score.personWaitMillis)
        assertEquals(0, score.unknownWaits)
        assertEquals(2, score.turns)
        assertEquals(1, score.personPromptsAfterFirst)
        assertEquals(2, score.toolCalls)
        assertEquals(300L, score.inputTokens)
        assertEquals(30L, score.outputTokens)
        assertNull(score.reasoningTokens)
        assertEquals(0.03, score.costUsd)
        assertEquals(0, score.unknownUsageTurns)
        assertEquals(0, score.unknownCostTurns)
        assertFalse(score.partial)
        assertEquals(WorkOutcome.Open, score.outcome)
    }

    @Test fun receiptsReplaceOneMatchingCommandAndRepeatedRunsRemainDistinct() {
        val command = "./gradlew :app:test"
        val log = WorkLog(listOf(WorkTurn("1", "Test", 0, 50, listOf(
            WorkStep.Command("c1", 1, command, exitCode = 1, status = StepStatus.Done),
            WorkStep.Command("c2", 2, command, exitCode = 0, status = StepStatus.Done),
            WorkStep.Command("c3", 3, "pnpm build", status = StepStatus.Failed),
            WorkStep.Subagent("a", 4, "Review", steps = listOf(WorkStep.Tool("t", 5, ToolKind.Read, "Read", status = StepStatus.Failed)), status = StepStatus.Done),
            WorkStep.Plan("p", 6, emptyList()),
        ))))
        val receipt = CheckResult(listOf("./gradlew", ":app:test"), CheckOutcome.Failed, log = ArtifactRef("r1", 10))
        val score = log.score(checks = listOf(receipt, receipt))
        assertEquals(3, score.builds)
        assertEquals(2, score.failedBuilds)
        assertEquals(2, score.tests)
        assertEquals(1, score.failedTests)
        assertEquals(5, score.toolCalls)
        assertEquals(3, score.failedTools)
        assertNull(score.inputTokens)
        assertNull(score.costUsd)
        assertEquals(1, score.unknownUsageTurns)
    }

    @Test fun incompleteWaitsAndOldUsageRemainUnknown() {
        val usage = ConductorJson.decodeFromString(WorkUsage.serializer(), """{"context":1000,"output":10}""")
        assertNull(usage.input)
        assertNull(usage.costUsd)
        val score = WorkLog(listOf(WorkTurn("1", "Choose", 0, 100, listOf(
            WorkStep.Question("q", 10, "Which?", "A", StepStatus.Done),
        ), usage)), earlier = true).score()
        assertEquals(100L, score.wallMillis)
        assertNull(score.workingMillis)
        assertNull(score.personWaitMillis)
        assertEquals(1, score.unknownWaits)
        assertEquals(1, score.unknownUsageTurns)
        assertEquals(10L, score.outputTokens)
        assertTrue(score.partial)
    }

    @Test fun liveTurnsUseTheReadTimeAndCompletionDoesNotProveLanding() {
        val log = WorkLog(listOf(WorkTurn("1", "Build", 100, 120, listOf(
            WorkStep.Question("q", 150, "Approve?"),
        ), open = true)))
        assertEquals(100L, log.score(now = 200).wallMillis)
        assertEquals(50L, log.score(now = 200).personWaitMillis)
        assertEquals(WorkOutcome.Accepted, log.score(task("T1").copy(stage = BoardStage.Done), now = 200).outcome)
        assertEquals(WorkOutcome.Abandoned, log.score(task("T1", state = TaskState.Cancelled), now = 200).outcome)
        assertEquals(WorkOutcome.Open, log.score(now = 200).outcome)
    }

    @Test fun graphSumsWorkButUsesTheLongestDependencyChain() {
        val tasks = listOf(task("T1"), task("T2", "T1"), task("T3", "T1"), task("T4", "T2", "T3"))
        val cards = mapOf("T1" to card(0, 10), "T2" to card(10, 30), "T3" to card(10, 40), "T4" to card(40, 45))
        val score = graph(tasks).score(cards)
        assertEquals(listOf("T1", "T3", "T4"), score.criticalPath)
        assertEquals(45L, score.criticalPathMillis)
        assertEquals(45L, score.total.wallMillis)
        assertEquals(65L, score.total.workingMillis)
        assertEquals(0L, score.total.personWaitMillis)
        assertEquals(4, score.total.turns)
        assertEquals(400L, score.total.inputTokens)
        assertEquals(40L, score.total.outputTokens)
        assertEquals(0.04, score.total.costUsd)
        assertEquals(WorkOutcome.Open, score.total.outcome)
        assertFalse(score.total.partial)
    }

    @Test fun missingOrUntimedTasksNeverProduceCompleteTotalsOrCriticalPaths() {
        val tasks = listOf(task("T1"), task("T2", "T1"))
        val missing = graph(tasks).score(mapOf("T1" to card(0, 10).copy(outcome = WorkOutcome.Accepted)))
        assertEquals(listOf("T2"), missing.missingTasks)
        assertTrue(missing.total.partial)
        assertNull(missing.criticalPathMillis)
        assertNull(missing.total.workingMillis)
        assertEquals(WorkOutcome.Open, missing.total.outcome)
        val unknown = graph(tasks).score(mapOf("T1" to card(0, 10), "T2" to card(10, 20).copy(workingMillis = null, personWaitMillis = null)))
        assertNull(unknown.total.workingMillis)
        assertNull(unknown.total.personWaitMillis)
        val cyclic = graph(listOf(task("T1", "T2"), task("T2", "T1"))).score(emptyMap())
        assertNull(cyclic.criticalPathMillis)
        assertTrue(cyclic.criticalPath.isEmpty())
    }

    private fun card(start: Long, end: Long) = WorkLog(listOf(WorkTurn("t", "Work", start, end, emptyList(), WorkUsage(input = 100, output = 10, costUsd = 0.01)))).score()

    private fun task(id: String, vararg dependencies: String, state: TaskState = TaskState.Succeeded) = BoardTask(id, id, createdBy = actor, createdAt = 0, updatedAt = 0, stageChangedAt = 0,
        graph = TaskPlan("G1", dependsOn = dependencies.map(::TaskDependency), state = state))

    private fun graph(tasks: List<BoardTask>) = TaskGraphView(TaskGraph("G1", "Plan", createdBy = actor, createdAt = 0, updatedAt = 0), tasks)
}
