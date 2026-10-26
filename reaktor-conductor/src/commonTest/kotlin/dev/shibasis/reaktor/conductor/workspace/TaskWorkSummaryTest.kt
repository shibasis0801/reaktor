package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.worklog.PlanState
import kotlin.test.*

class TaskWorkSummaryTest {
    private val actor = BoardActor(BoardActorKind.Agent, "Fixture")
    private val ref = ArtifactRef("diff", 100, "diff")
    private val candidate = AgentCandidate("c2", "/fixture", null, "source", 1, listOf("Parser.kt", "ParserTest.kt"), ref, true)
    private val task = BoardTask("T1", "Parse timestamps", createdBy = actor, createdAt = 1, updatedAt = 1, stageChangedAt = 1,
        graph = TaskPlan("G1", attempt = 2, runId = "run2", outcome = TaskOutcome(attempt = 2, candidateId = "c2", summary = "All checks passed")))
    private fun receipt(id: String, candidate: String, outcome: CheckOutcome) = AgentCandidateCheck(id, candidate,
        CheckResult(listOf("./gradlew", id), outcome, revision = candidate), "kernel")
    private fun finding(id: String, candidate: String) = AgentFinding(id, candidate, "review", "claude", id, "Detail", "high")

    @Test fun writerFinishRequiresExactCompleteKernelEvidenceOrSpecificRecordedGaps() {
        val outcome = requireNotNull(task.graph?.outcome)
        val pass = receipt("unit", "c2", CheckOutcome.Passed).let { it.copy(result = it.result.copy(coverage = CheckCoverage.Complete)) }
        fun summary(checks: List<AgentCandidateCheck>) = requireNotNull(TaskSummaries.of(task, AgentTaskEvidence("T1", listOf(candidate), checks = checks)))
        assertNull(outcome.finishRefusal(summary(listOf(pass))))
        listOf(emptyList(), listOf(pass.copy(source = "agent")), listOf(pass.copy(candidateId = "c1")),
            listOf(pass.copy(result = pass.result.copy(revision = "c1"))),
            listOf(pass.copy(result = pass.result.copy(outcome = CheckOutcome.Failed))),
            listOf(pass.copy(result = pass.result.copy(coverage = CheckCoverage.Unparsed("partial")))),
            listOf(pass, pass.copy(result = pass.result.copy(outcome = CheckOutcome.Failed)))).forEach {
            assertContains(requireNotNull(outcome.finishRefusal(summary(it))), "Finish gate")
        }
        val gap = outcome.copy(unverified = listOf("No physical device was attached; the device acceptance remains unverified."))
        assertNull(gap.finishRefusal(summary(emptyList())))
        assertNotNull(gap.finishRefusal(summary(emptyList()).copy(candidate = null)))
        assertNotNull(gap.finishRefusal(summary(emptyList()).copy(attempt = 1)))
        listOf(listOf(" "), List(11) { "Gap $it" }, listOf("x".repeat(1001))).forEach {
            assertNotNull(outcome.copy(unverified = it).finishRefusal(summary(emptyList())))
        }
        val withGap = task.copy(graph = task.graph!!.copy(outcome = gap))
        assertTrue(requireNotNull(TaskSummaries.of(withGap, AgentTaskEvidence("T1", listOf(candidate)))).limitations.any {
            "physical device" in it && "agent reported" in it })
        assertEquals(gap, ConductorJson.decodeFromString(TaskOutcome.serializer(), ConductorJson.encodeToString(TaskOutcome.serializer(), gap)))
        assertTrue(ConductorJson.decodeFromString(TaskOutcome.serializer(), """{"summary":"Legacy"}""").unverified.isEmpty())
    }

    @Test fun compilerWarningsRequireSpecificExplanationsForTheCurrentCandidateEvenAfterPassingChecks() {
        val outcome = requireNotNull(task.graph?.outcome)
        val warning = finding("warning-one", "c2").copy(producerRunId = "kernel:compiler-warning", participant = "compiler-warning",
            severity = "medium", sourcePath = "Parser.kt", line = 4)
        val pass = receipt("unit", "c2", CheckOutcome.Passed).let { it.copy(result = it.result.copy(coverage = CheckCoverage.Complete)) }
        fun summary(findings: List<AgentFinding>) = requireNotNull(TaskSummaries.of(task,
            AgentTaskEvidence("T1", listOf(candidate), findings = findings, checks = listOf(pass))))
        assertContains(requireNotNull(outcome.finishRefusal(summary(listOf(warning)))), "warning-one")
        assertNotNull(outcome.copy(unverified = listOf("Device checks unavailable")).finishRefusal(summary(listOf(warning))))
        assertNotNull(outcome.copy(unverified = listOf("warning-one: ")).finishRefusal(summary(listOf(warning))))
        assertNull(outcome.copy(unverified = listOf("warning-one: The API override must retain this unused argument.")).finishRefusal(summary(listOf(warning))))
        assertNull(outcome.finishRefusal(summary(listOf(warning.copy(candidateId = "c1")))))
        assertNotNull(outcome.copy(unverified = listOf("warning-one: Reviewed")).finishRefusal(summary(listOf(warning.copy(participant = "compiler-warning-coverage")))))
    }

    @Test fun nativePlanFormatsRetainStatesAndDoNotTreatAnOrdinaryToolAsAPlan() {
        val items = listOf(
            AgentActivityItem("p", ActivityKind.Plan, "Execution plan", output = """{"plan":[{"step":"Parse offsets","status":"completed"},{"step":"Verify","status":"in_progress"}]}"""),
            AgentActivityItem("p", ActivityKind.Tool, "TodoWrite", input = """{"todos":[{"content":"Parse offsets","status":"completed"},{"content":"Verify","status":"in_progress"}]}"""),
            AgentActivityItem("p", ActivityKind.Plan, "todo_list", input = """[{"text":"Parse offsets","completed":true},{"text":"Verify","status":"in_progress"}]"""),
        )
        items.forEach { item ->
            assertEquals(listOf(PlanState.Done, PlanState.Active), TaskSummaries.plan(item).map { it.state })
            assertEquals(listOf("Parse offsets", "Verify"), TaskSummaries.plan(item).map { it.text })
            assertTrue(TaskSummaries.plan(item.copy(parentId = "child")).isEmpty())
            assertTrue(TaskSummaries.plan(item.copy(status = ActivityStatus.Failed)).isEmpty())
        }
        assertTrue(TaskSummaries.plan(items.first().copy(kind = ActivityKind.Tool, title = "Read")).isEmpty())
        assertTrue(TaskSummaries.plan(items.first().copy(output = "broken")).isEmpty())
    }

    @Test fun summaryUsesTheCurrentCandidateLatestReceiptsAndOpenFindingsRatherThanTheFinalProse() {
        val facts = AgentTaskEvidence("T1", listOf(candidate), listOf(finding("old", "c1"), finding("open", "c2"),
            finding("closed", "c2").copy(resolvedByCandidate = "c2")),
            listOf(receipt("unit", "c1", CheckOutcome.Passed), receipt("unit", "c2", CheckOutcome.Passed),
                receipt("unit", "c2", CheckOutcome.Failed), receipt("lint", "c2", CheckOutcome.Unknown)), listOf("unit", "boundary"))
        val diff = AgentArtifactPage(ref, 0, "diff --git a/Parser.kt b/Parser.kt\n--- a/Parser.kt\n+++ b/Parser.kt\n@@ -1,2 +1,3 @@\n-old\n+new\n++++content\n unchanged\n", null, false)
        val summary = assertNotNull(TaskSummaries.of(task, facts, diff = diff))
        assertEquals(2, summary.added)
        assertEquals(1, summary.removed)
        assertEquals(listOf("open"), summary.findings.map { it.id })
        assertEquals(listOf("boundary"), summary.missingChecks)
        assertEquals(CheckOutcome.Failed, summary.checks.single { it.id == "unit" }.result.outcome)
        assertTrue("0 passed · 1 failed · 1 unknown · 1 missing" in summary.text())
        assertFalse("All checks passed" in summary.text())
    }

    @Test fun aRepairCannotInheritACandidateOrPlanFromThePreviousAttempt() {
        val activity = AgentActivityRecord(1, "old-run", "codex", 1, 1,
            AgentActivityItem("p", ActivityKind.Plan, "Execution plan", output = """{"plan":[{"step":"Done","status":"completed"}]}"""))
        val summary = assertNotNull(TaskSummaries.of(task.copy(graph = task.graph!!.copy(attempt = 3)),
            AgentTaskEvidence("T1", listOf(candidate), checks = listOf(receipt("unit", "c2", CheckOutcome.Passed))), activity))
        assertNull(summary.candidate)
        assertTrue(summary.plan.isEmpty())
        assertTrue(summary.checks.isEmpty())
        assertNull(summary.added)
        assertTrue("No candidate recorded for this attempt" in summary.limitations)
    }

    @Test fun partialAndMissingDiffsRemainExplicitAndUnrelatedArtifactsAreIgnored() {
        val facts = AgentTaskEvidence("T1", listOf(candidate))
        val partial = assertNotNull(TaskSummaries.of(task, facts, diff = AgentArtifactPage(ref, 0, "@@ -1 +1 @@\n+new\n", 20, true)))
        assertEquals(1, partial.added)
        assertTrue(partial.limitations.any { it.startsWith("Partial diff") })
        val unknown = assertNotNull(TaskSummaries.of(task, facts, diff = AgentArtifactPage(ref.copy(id = "other"), 0, "+new", null, false)))
        assertNull(unknown.added)
        assertTrue(unknown.limitations.any { it.contains("unknown") })
        assertNull(TaskSummaries.of(task.copy(graph = task.graph!!.copy(attempt = 0)), facts))
    }

    @Test fun summaryProtocolRoundTripsWithoutLosingUnknownFacts() {
        val summary = assertNotNull(TaskSummaries.of(task, AgentTaskEvidence("T1")))
        assertEquals(summary, ConductorJson.decodeFromString(TaskWorkSummary.serializer(),
            ConductorJson.encodeToString(TaskWorkSummary.serializer(), summary)))
        assertTrue("unknown additions" in summary.text())
    }
}
