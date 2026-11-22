package dev.shibasis.reaktor.conductor.workspace

import kotlin.test.*

class PlanBoardContinuityTest {
    private val owner = BoardActor(BoardActorKind.Person, "Owner")
    private val lease = PlanLease(BoardActor(BoardActorKind.Agent, "Codex", "codex:one-session"), 1, 100)
    private fun plan(items: List<PlanItem>) = WorkPlan("hangar:board-continuity", "Board", items = items,
        createdBy = owner, createdAt = 1, updatedAt = 1)

    @Test fun aSessionWithTwoDifferentAssigneesStaysUnboundOnBothCards() {
        val source = plan(listOf(
            PlanItem("a", "A", status = PlanStatus.Doing, refs = listOf("agent:builder-a"), lease = lease),
            PlanItem("b", "B", status = PlanStatus.Doing, refs = listOf("agent:builder-b"), lease = lease)))
        PlanBoard.project(listOf(source)).forEach { card ->
            assertEquals("Codex", card.people.first().actor.name)
            assertEquals("codex:one-session", card.people.first().actor.session)
            assertEquals(2, card.people.size)
        }
    }

    @Test fun aLocalPersonKeepsTheNearestAgentAncestorAndItsReviewerVisible() {
        val source = plan(listOf(
            PlanItem("area", "Area", kind = PlanKind.Component, refs = listOf("agent:builder", "agent:reviewer")),
            PlanItem("leaf", "Leaf", parent = "area", refs = listOf("person:owner"),
                status = PlanStatus.Doing, lease = lease)))
        val card = PlanBoard.project(listOf(source)).single()
        assertEquals(listOf("builder", "reviewer", "owner"), card.people.map { it.actor.name })
    }
    @Test fun rebuiltSnapshotsOfEightyFourPlansReuseAnalysisAndReturnTheirOwnItems() {
        val plans = List(84) { index ->
            WorkPlan("hangar:cache-$index", "Cache $index", items = listOf(PlanItem("a", "A"), PlanItem("b", "B", after = listOf("a"))),
                createdBy = owner, createdAt = 1, updatedAt = 1, revision = 1)
        }
        val reports = plans.map(PlanAnalysis::report)
        val rebuilt = plans.map { it.copy(items = it.items.map { item -> item.copy(detail = "Freshly hydrated") }) }
        rebuilt.forEachIndexed { index, plan ->
            val report = PlanAnalysis.report(plan)
            assertSame(reports[index].timings, report.timings)
            report.ready.forEach { assertSame(plan.item(it.item.id), it.item) }
            assertEquals(listOf("a"), report.ready.map { it.item.id })
        }
    }

}
