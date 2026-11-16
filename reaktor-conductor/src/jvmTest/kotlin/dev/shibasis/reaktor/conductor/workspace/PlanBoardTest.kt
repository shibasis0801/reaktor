package dev.shibasis.reaktor.conductor.workspace

import kotlin.test.*

class PlanBoardTest {
    private val owner = BoardActor(BoardActorKind.Person, "Fixture owner")
    private fun plan(items: List<PlanItem>) = WorkPlan("hangar:board", "Board", createdBy = owner, createdAt = 1, updatedAt = 1, revision = 2, items = items)

    @Test fun oneNamedHolderKeepsItsLeaseProvenanceAndFramePath() {
        val actor = BoardActor(BoardActorKind.Agent, "Codex", "codex:seat")
        val plan = plan(listOf(PlanItem("frame", "Frame", kind = PlanKind.Component, refs = listOf("agent:builder")),
            PlanItem("doing", "Doing", parent = "frame", status = PlanStatus.Doing, lease = PlanLease(actor, 1, 100))))
        val card = PlanBoard.project(listOf(plan)).single()
        assertEquals(listOf("frame"), card.path.map { it.id })
        assertEquals(1, card.people.size)
        assertEquals("builder", card.people.single().actor.name)
        assertEquals("agent:builder", card.people.single().key)
        assertEquals("codex:seat", card.people.single().actor.session)
    }

    @Test fun allLanesAndInheritedPriorityComeFromTheCompletePlan() {
        val plan = plan(listOf(PlanItem("frame", "Frame", kind = PlanKind.Component),
            PlanItem("todo", "To do", parent = "frame"),
            PlanItem("urgent", "Urgent", after = listOf("todo"), priority = PlanPriority.Urgent),
            PlanItem("doing", "Doing", status = PlanStatus.Doing),
            PlanItem("done", "Done", status = PlanStatus.Done),
            PlanItem("dropped", "Won't do", status = PlanStatus.Dropped, reason = "Replaced")))
        val cards = PlanBoard.project(listOf(plan)).associateBy { it.item.id }
        assertEquals(PlanStatus.entries.toSet(), cards.values.map { it.state }.toSet())
        assertFalse("frame" in cards)
        assertEquals(PlanPriority.Urgent, cards.getValue("todo").priority)
        assertTrue(cards.getValue("todo").inherited)
        assertEquals("Replaced", cards.getValue("dropped").item.reason)
        assertEquals(6, PlanBoard.project(listOf(plan), selected = "${plan.thread}#frame").size)
    }
}
