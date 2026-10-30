package dev.shibasis.reaktor.conductor.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanReplanningTest {
    private val owner = BoardActor(BoardActorKind.Person, "owner")

    @Test fun droppingWithAReplacementFlagsEveryBlockedDependentForReview() {
        val original = WorkPlan("hangar:test", "Replan", createdBy = owner, createdAt = 1, updatedAt = 1)
        val drawn = PlanRules.apply(original, PlanPatch(ops = listOf(
            PlanOp.Add("Old", key = "old"), PlanOp.Add("Replacement", key = "new"),
            PlanOp.Add("First dependent", key = "first", after = listOf("old")),
            PlanOp.Add("Later dependent", key = "later", after = listOf("first")),
        )), owner, 2).plan
        val dropped = PlanRules.apply(drawn, PlanPatch(ops = listOf(
            PlanOp.Status("old", PlanStatus.Dropped, "Use the existing service", "new"),
        )), owner, 3).plan
        val report = PlanAnalysis.report(dropped)
        assertEquals(setOf("first", "later"), report.review.map { it.item }.toSet())
        assertTrue(report.review.all { it.dependency == "old" && it.reason == "Use the existing service" && it.replacement == "new" })
        assertEquals(listOf("new"), report.ready.map { it.item.id })
        assertEquals(listOf("old"), dropped.item("first")?.after)
        assertNull(report.steps)
        val reviewed = PlanRules.apply(dropped, PlanPatch(ops = listOf(
            PlanOp.Disconnect("old", "first"), PlanOp.Connect("new", "first"),
        )), owner, 4).plan
        assertTrue(PlanAnalysis.report(reviewed).review.isEmpty())
        assertEquals(3, PlanAnalysis.report(reviewed).steps)
    }

    @Test fun aDroppedAncestorGateFlagsItsChildrenAndRetainsLegacyUnknownReasons() {
        val plan = WorkPlan("hangar:test", "Replan", items = listOf(
            PlanItem("old", "Old", status = PlanStatus.Dropped),
            PlanItem("area", "Area", kind = PlanKind.Component, after = listOf("old")),
            PlanItem("child", "Child", parent = "area"),
        ), createdBy = owner, createdAt = 1, updatedAt = 1)
        val review = PlanAnalysis.report(plan).review.single()
        assertEquals("child", review.item)
        assertEquals("old", review.dependency)
        assertEquals("Dropped prerequisite; reason was not recorded", review.reason)
        assertNull(review.replacement)
        assertTrue(PlanAnalysis.report(plan).ready.isEmpty())
    }
}
