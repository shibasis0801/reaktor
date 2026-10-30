package dev.shibasis.reaktor.conductor.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanScheduleTest {
    private fun plan(vararg items: PlanItem) = WorkPlan("hangar:test", "Schedule", items = items.toList(),
        createdBy = BoardActor(BoardActorKind.Person, "owner"), createdAt = 1, updatedAt = 1)

    @Test fun urgentDependentsRaiseEveryTransitiveBlocker() {
        val plan = plan(PlanItem("first", "First", priority = PlanPriority.Low),
            PlanItem("second", "Second", after = listOf("first")),
            PlanItem("urgent", "Urgent", after = listOf("second"), priority = PlanPriority.Urgent),
            PlanItem("other", "Other", priority = PlanPriority.High))
        val ranked = PlanSchedule.ready(plan)
        assertEquals(listOf("first", "other"), ranked.map { it.item.id })
        assertEquals(PlanPriority.Urgent, ranked.first().priority)
        assertEquals(2, ranked.first().unblocks)
        assertTrue(ranked.first().inherited)
    }

    @Test fun componentCompletionPassesDependentPriorityToItsParts() {
        val plan = plan(PlanItem("area", "Area", kind = PlanKind.Component),
            PlanItem("one", "One", parent = "area"), PlanItem("two", "Two", parent = "area"),
            PlanItem("release", "Release", after = listOf("area"), priority = PlanPriority.Urgent))
        assertEquals(listOf("one", "two"), PlanSchedule.ready(plan).map { it.item.id })
        assertTrue(PlanSchedule.ready(plan).all { it.priority == PlanPriority.Urgent && it.unblocks == 1 })
    }

    @Test fun parentDependenciesGateChildrenUntilDone() {
        val plan = plan(PlanItem("before", "Before"),
            PlanItem("area", "Area", kind = PlanKind.Component, after = listOf("before"), priority = PlanPriority.Urgent),
            PlanItem("child", "Child", parent = "area"))
        assertEquals(listOf("before"), plan.ready().map { it.id })
        val finished = plan.copy(items = plan.items.map { if (it.id == "before") it.copy(status = PlanStatus.Done) else it })
        assertEquals(listOf("child"), finished.ready().map { it.id })
        assertEquals(PlanPriority.Urgent, PlanSchedule.ready(finished).single().priority)
    }

    @Test fun droppedDependenciesDoNotCountAsCompleted() {
        val plan = plan(PlanItem("dropped", "Dropped", status = PlanStatus.Dropped),
            PlanItem("waiting", "Waiting", after = listOf("dropped"), priority = PlanPriority.Urgent))
        assertTrue(plan.ready().isEmpty())
    }

    @Test fun closedDependentsDoNotRaisePriority() {
        val plan = plan(PlanItem("first", "First", priority = PlanPriority.Low),
            PlanItem("closed", "Closed", after = listOf("first"), status = PlanStatus.Done, priority = PlanPriority.Urgent))
        assertEquals(PlanPriority.Low, PlanSchedule.ready(plan).single().priority)
        assertFalse(PlanSchedule.ready(plan).single().inherited)
    }

    @Test fun tiesPreferMoreWorkThenImmutableCreationTime() {
        val plan = plan(PlanItem("new", "New", createdAt = 20), PlanItem("old", "Old", createdAt = 10, changedAt = 100),
            PlanItem("busy", "Busy", createdAt = 30), PlanItem("after", "After", after = listOf("busy")))
        assertEquals(listOf("busy", "old", "new"), plan.ready().map { it.id })
        val raised = plan.copy(items = plan.items.map { if (it.id == "new") it.copy(priority = PlanPriority.Urgent) else it })
        assertEquals("new", raised.ready().first().id)
    }

    @Test fun finishedAndDroppedParentsHideUnfinishedChildren() {
        listOf(PlanStatus.Done, PlanStatus.Dropped).forEach { state ->
            val plan = plan(PlanItem("parent", "Parent", kind = PlanKind.Component, status = state),
                PlanItem("child", "Child", parent = "parent"))
            assertTrue(plan.ready().isEmpty())
        }
    }

    @Test fun anEmptyComponentIsNotExecutableWork() {
        assertTrue(plan(PlanItem("area", "Area", kind = PlanKind.Component)).ready().isEmpty())
    }
}
