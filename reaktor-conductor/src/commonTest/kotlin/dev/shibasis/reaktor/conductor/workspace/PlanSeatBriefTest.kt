package dev.shibasis.reaktor.conductor.workspace

import kotlin.test.*

class PlanSeatBriefTest {
    private val owner = BoardActor(BoardActorKind.Person, "Owner")
    private val seat = PlanSeat("s1", "worker", listOf("area", "area"), owner, 1)
    private fun plan() = WorkPlan("hangar:launch", "Launch", items = listOf(
        PlanItem("area", "Data", kind = PlanKind.Component),
        PlanItem("source", "Read source", "Complete source requirements", parent = "area", status = PlanStatus.Done,
            refs = listOf("file:src/data.kt", "symbol:DataSource")),
        PlanItem("view", "Render data", "Complete rendering requirements", parent = "area", after = listOf("source", "external"),
            refs = listOf("flow:F-DATA-01"), priority = PlanPriority.High),
        PlanItem("proof", "Check data", "Check the actual data", parent = "view", kind = PlanKind.Check),
        PlanItem("external", "External", detail = "Unrelated private detail".repeat(12000), status = PlanStatus.Done),
    ), revision = 42, createdBy = owner, createdAt = 1, updatedAt = 2)

    @Test fun aSeatReceivesCompleteDescendantsWithoutUnrelatedPlanBodies() {
        val brief = PlanBrief.seat(plan(), seat, "Worker", "Owner note")
        assertTrue("Complete source requirements" in brief)
        assertTrue("Complete rendering requirements" in brief)
        assertTrue("Check the actual data" in brief)
        assertTrue("file:src/data.kt, symbol:DataSource" in brief)
        assertTrue("Waits on: source (Done), external (Done)" in brief)
        assertTrue("Parent: view" in brief)
        assertTrue("(Check, Todo, Normal)" in brief)
        assertTrue("Owner note" in brief)
        assertFalse("Unrelated private detail" in brief)
        assertEquals(1, Regex("- area ").findAll(brief).count())
        assertTrue(brief.length < 8000)
    }

    @Test fun newsStartsAtTheBriefRevisionInsteadOfAnInitialFullRead() {
        val brief = PlanBrief.seat(plan(), seat, "Worker", "")
        assertTrue("Plan revision: 42" in brief)
        assertTrue("arguments: {thread: \"hangar:launch\", since: 42}" in brief)
        assertTrue("update since to the revision each read returns" in brief)
        assertFalse("arguments: {thread: \"hangar:launch\"}}" in brief)
        assertTrue("Ready, highest priority first: proof (Normal)" in brief)
    }

    @Test fun hangarInstructionsNameTheFlowTaskExclusionsAndReceipts() {
        val brief = PlanBrief.seat(plan(), seat, "Worker", "")
        assertTrue("./gradlew :reaktorDesktop:hangarFlows -Dhangar.flows=<ids> -x :exportBuildModel -x :reaktorDesktop:test" in brief)
        assertTrue("targets/reaktorDesktop/build/hangar-shards" in brief)
        assertTrue("A filtered :reaktorDesktop:test run does not run Hangar flows" in brief)
    }
}
