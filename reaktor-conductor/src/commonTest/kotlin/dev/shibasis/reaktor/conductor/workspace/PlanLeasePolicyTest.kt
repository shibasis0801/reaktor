package dev.shibasis.reaktor.conductor.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanLeasePolicyTest {
    private val actor = BoardActor(BoardActorKind.Agent, "Worker", "codex:one", "agent:codex:one")

    @Test fun leaseUsesTheLaterOfItsInitialDeadlineAndFreshActivity() {
        val lease = PlanLease(actor, 10, 10 + PlanLease.DurationMillis)
        assertEquals(10 + PlanLease.DurationMillis, lease.until(null))
        assertEquals(10 + PlanLease.DurationMillis, lease.until(0))
        assertEquals(50 + PlanLease.DurationMillis, lease.until(50))
    }

    @Test fun fileScopesOverlapOnlyAtPathBoundaries() {
        assertTrue(PlanSchedule.conflicts(listOf("file:src/A.kt"), listOf("file:src/A.kt")))
        assertTrue(PlanSchedule.conflicts(listOf("file:src/main"), listOf("file:src/main/A.kt")))
        assertFalse(PlanSchedule.conflicts(listOf("file:src/main"), listOf("file:src/mainland/A.kt")))
        assertFalse(PlanSchedule.conflicts(listOf("file:src/A.kt", "module:engine"), listOf("file:src/B.kt", "module:engine")))
        assertTrue(PlanSchedule.conflicts(listOf("module:engine"), listOf("module:engine", "file:src/B.kt")))
        assertFalse(PlanSchedule.conflicts(listOf("symbol:Example"), listOf("symbol:Example")))
    }

    @Test fun ownedLeaseSurvivesPriorityEditsAndClearsWhenParked() {
        val item = PlanItem("build", "Build", status = PlanStatus.Doing, by = actor, lease = PlanLease(actor, 1, 100))
        val plan = WorkPlan("codex:one", "Work", items = listOf(item), createdBy = actor, createdAt = 0, updatedAt = 0)
        val person = BoardActor(BoardActorKind.Person, "Owner")
        val changed = PlanRules.apply(plan, PlanPatch(ops = listOf(PlanOp.Update("build", priority = PlanPriority.Urgent))), person, 2).plan
        assertEquals(actor, changed.item("build")?.by)
        assertEquals(person, changed.changes.single().by)
        val parked = PlanRules.apply(changed, PlanPatch(ops = listOf(PlanOp.Status("build", PlanStatus.Todo))), actor, 3).plan
        assertEquals(null, parked.item("build")?.lease)
    }
}
