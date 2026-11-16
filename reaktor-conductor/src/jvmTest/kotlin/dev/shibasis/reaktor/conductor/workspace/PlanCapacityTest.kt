package dev.shibasis.reaktor.conductor.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanCapacityTest {
    private val person = BoardActor(BoardActorKind.Person, "Shibasis", "hangar:capacity")

    private fun plan(open: Int) = WorkPlan("hangar:capacity", "Launch", items =
        List(open) { PlanItem("open-$it", "Open $it", status = if (it % 2 == 0) PlanStatus.Todo else PlanStatus.Doing) } +
            List(400) { PlanItem("closed-$it", "Closed $it", status = if (it % 2 == 0) PlanStatus.Done else PlanStatus.Dropped, reason = "Kept in history") },
        createdBy = person, createdAt = 1, updatedAt = 1)

    private fun apply(plan: WorkPlan, vararg ops: PlanOp) = PlanRules.apply(plan, PlanPatch(ops.toList()), person, 2)

    @Test fun todoBacklogAndClosedHistoryDoNotConsumeDoingSlots() {
        val initial = plan(199)
        val changed = apply(initial, PlanOp.Add("Next", key = "next"), PlanOp.Add("Over capacity", key = "extra"))
        assertTrue(changed.refused.isEmpty())
        assertEquals(601, changed.plan.items.size)
        assertEquals(initial.items, changed.plan.items.dropLast(2))
        assertEquals(PlanStatus.Todo, changed.plan.item("next")?.status)
    }

    @Test fun closedItemsCanBeRecordedAtCapacity() {
        val changed = apply(plan(200), PlanOp.Add("Finished", key = "finished", status = PlanStatus.Done),
            PlanOp.Add("Replaced", key = "replaced", status = PlanStatus.Dropped, reason = "A better route"))
        assertTrue(changed.refused.isEmpty())
        assertEquals(602, changed.plan.items.size)
    }

    @Test fun reopeningUsesTheSlotFreedByClosingInTheSamePatch() {
        val initial = plan(200).let { value -> value.copy(items = value.items.map { item ->
            if (item.id in listOf("open-1", "open-3", "open-5", "open-7", "open-9")) item.copy(by = person) else item
        }) }
        val refused = apply(initial, PlanOp.Status("closed-0", PlanStatus.Doing))
        assertEquals(1, refused.refused.size)
        assertEquals(initial, refused.plan)
        val changed = apply(initial, PlanOp.Status("open-1", PlanStatus.Done), PlanOp.Status("open-0", PlanStatus.Doing),
            PlanOp.Status("closed-0", PlanStatus.Todo))
        assertTrue(changed.refused.isEmpty())
        assertEquals(PlanStatus.Doing, changed.plan.item("open-0")?.status)
        assertEquals(PlanStatus.Done, changed.plan.item("open-1")?.status)
        assertEquals(PlanStatus.Todo, changed.plan.item("closed-0")?.status)
        assertEquals(initial.items.size, changed.plan.items.size)
    }

    @Test fun olderOverCapacityPlansCanBeEditedAndClosed() {
        val initial = plan(201)
        val changed = apply(initial, PlanOp.Update("open-0", title = "Still editable"), PlanOp.Status("open-0", PlanStatus.Doing),
            PlanOp.Status("open-1", PlanStatus.Done), PlanOp.Status("open-2", PlanStatus.Dropped, "No longer needed"),
            PlanOp.Add("Next", key = "next"))
        assertTrue(changed.refused.isEmpty())
        assertEquals("Still editable", changed.plan.item("open-0")?.title)
        assertEquals(200, changed.plan.items.count { it.status == PlanStatus.Todo || it.status == PlanStatus.Doing })
        assertEquals(initial.items.size + 1, changed.plan.items.size)
        assertEquals(initial.items.filter { it.id.startsWith("closed-") }, changed.plan.items.filter { it.id.startsWith("closed-") })
    }
}
