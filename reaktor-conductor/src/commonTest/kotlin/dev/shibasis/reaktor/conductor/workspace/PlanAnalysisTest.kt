package dev.shibasis.reaktor.conductor.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanAnalysisTest {
    private fun report(vararg items: PlanItem) = PlanAnalysis.report(WorkPlan("hangar:test", "Schedule", items = items.toList(),
        revision = 7, createdBy = BoardActor(BoardActorKind.Person, "owner"), createdAt = 1, updatedAt = 1))

    @Test fun branchesExposeCriticalPathAndSlackWithoutInventingDurations() {
        val report = report(PlanItem("first", "First"), PlanItem("second", "Second", after = listOf("first")),
            PlanItem("last", "Last", after = listOf("second")), PlanItem("short", "Short", after = listOf("first")))
        assertEquals(3, report.steps)
        assertEquals(2, report.width)
        assertEquals(listOf("first", "second", "last"), report.criticalPath)
        assertEquals(1, report.timings.single { it.item == "short" }.slack)
        assertTrue(report.timings.filter { it.item != "short" }.all { it.critical })
        assertTrue("no duration estimates" in report.unit)
        assertEquals(7L, report.revision)
    }

    @Test fun widthIsTheMaximumAntichainRatherThanTheLargestLayer() {
        val report = report(PlanItem("a", "A"), PlanItem("b", "B"), PlanItem("c", "C", after = listOf("a")),
            PlanItem("d", "D", after = listOf("a")), PlanItem("e", "E", after = listOf("b", "c", "d")))
        assertEquals(3, report.width)
        assertEquals(2, report.ready.size)
    }

    @Test fun componentCompletionAndAncestorGatesBecomeLeafDependencies() {
        val report = report(PlanItem("before", "Before"),
            PlanItem("area", "Area", kind = PlanKind.Component, after = listOf("before")),
            PlanItem("one", "One", parent = "area"), PlanItem("two", "Two", parent = "area"),
            PlanItem("last", "Last", after = listOf("area")))
        assertEquals(4, report.remaining)
        assertEquals(3, report.steps)
        assertEquals(listOf("before"), report.ready.map { it.item.id })
        assertEquals(setOf("one", "two"), report.timings.single { it.item == "last" }.after.toSet())
    }

    @Test fun droppedMissingAndEmptyDependenciesStayVisibleAndPropagate() {
        val report = report(PlanItem("drop", "Drop", status = PlanStatus.Dropped),
            PlanItem("empty", "Empty", kind = PlanKind.Component),
            PlanItem("blocked", "Blocked", after = listOf("drop", "missing", "empty")),
            PlanItem("later", "Later", after = listOf("blocked")), PlanItem("free", "Free"))
        assertNull(report.steps)
        assertTrue(report.criticalPath.isEmpty())
        assertEquals(setOf("drop", "missing", "empty"), report.timings.single { it.item == "later" }.blockedBy.toSet())
        assertEquals(listOf("free"), report.ready.map { it.item.id })
        assertTrue(report.timings.all { it.slack == null && !it.critical })
        assertEquals(1, report.width)
    }

    @Test fun cyclesAndTheirFollowersDoNotProduceAFabricatedSchedule() {
        val report = report(PlanItem("a", "A", after = listOf("b")), PlanItem("b", "B", after = listOf("a")),
            PlanItem("later", "Later", after = listOf("a")), PlanItem("free", "Free"))
        assertNull(report.steps)
        assertTrue(report.timings.filter { it.item != "free" }.all { it.blockedBy.isNotEmpty() && it.earliest == null })
        assertEquals(1, report.width)
    }

    @Test fun finishedWorkAndClosedParentsDoNotInflateTheRemainingPath() {
        val report = report(PlanItem("done", "Done", status = PlanStatus.Done),
            PlanItem("area", "Area", kind = PlanKind.Component, status = PlanStatus.Done), PlanItem("hidden", "Hidden", parent = "area"),
            PlanItem("held", "Held", status = PlanStatus.Doing, after = listOf("done")), PlanItem("next", "Next", after = listOf("held")))
        assertEquals(2, report.remaining)
        assertEquals(2, report.steps)
        assertTrue(report.ready.isEmpty())
        assertFalse(report.timings.any { it.item == "hidden" })
    }

    @Test fun anEmptyPlanHasNoRemainingWork() {
        val report = report()
        assertEquals(0, report.steps)
        assertEquals(0, report.width)
        assertTrue(report.timings.isEmpty())
    }
}
