package dev.shibasis.reaktor.conductor.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanConnectOrderTest {
    private val owner = BoardActor(BoardActorKind.Person, "Owner")
    @Test fun connectingBelowTheChosenOrderMovesTheWaiterAndItsDependentsTogether() {
        val source = WorkPlan("hangar:ordered", "Order", items = listOf(
            PlanItem("s2", "Second"), PlanItem("s1", "First"), PlanItem("s3", "Third"), PlanItem("s4", "Fourth", after = listOf("s1"))),
            order = listOf("s2", "s1", "s3", "s4"), createdBy = owner, createdAt = 1, updatedAt = 1)
        val connected = PlanRules.apply(source, PlanPatch(base = 0, ops = listOf(PlanOp.Connect("s3", "s1"))), owner, 2)
        assertTrue(connected.refused.isEmpty())
        assertEquals(listOf("s2", "s3", "s1", "s4"), connected.plan.order)
        assertTrue(connected.plan.changes.last().summary.any { it.contains("Moved s1, s4") })
        val unrelated = PlanRules.apply(connected.plan, PlanPatch(base = connected.plan.revision,
            ops = listOf(PlanOp.Update("s2", orderBefore = "s3"))), owner, 3)
        assertTrue(unrelated.refused.isEmpty(), unrelated.refused.joinToString { it.reason })
    }
}
