package dev.shibasis.reaktor.conductor.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanDesignTest {
    private val person = BoardActor(BoardActorKind.Person, "ovd")
    private val plan = WorkPlan("claude:d", "Design", createdBy = person, createdAt = 1, updatedAt = 1, items = listOf(
        PlanItem("ui", "UI", kind = PlanKind.Component),
        PlanItem("api", "API", kind = PlanKind.Component),
        PlanItem("screen", "Screen", parent = "ui", after = listOf("route", "store"), relations = listOf(PlanRelation("route", "calls")), refs = listOf("file:modules/engine/Pane.kt")),
        PlanItem("route", "Route", parent = "api", status = PlanStatus.Done),
        PlanItem("store", "Store", parent = "api"),
        PlanItem("loose", "Loose task"),
    ))

    @Test fun componentsBecomeBoxesAndCrossingPrerequisitesFoldIntoOneCountedArrow() {
        val shape = PlanDesign.shape(listOf(plan))
        assertEquals(listOf("claude:d#ui", "claude:d#api"), shape.nodes.map { it.id })
        val wires = shape.wires.filter { it.consumer == "claude:d#ui" }
        assertEquals(setOf("waits on · 2 → api", "calls → api"), wires.map { it.consumerPort }.toSet())
        assertTrue(wires.all { it.provider == "claude:d#api" })
        assertEquals(2, PlanDesign.count("waits on · 2 → api"))
        assertEquals("1 of 2 done", shape.nodes.single { it.id == "claude:d#api" }.attributes["parts"])
    }

    @Test fun withCodeABoxTouchesTheModulesItsItemsLink() {
        val shape = PlanDesign.shape(listOf(plan), code = true) { ref -> if (ref.startsWith("file:modules/engine/")) listOf("module:bestbuds/modules/engine") else emptyList() }
        val module = shape.nodes.single { it.attributes["ref"] != null }
        assertEquals("claude:d ref module:bestbuds/modules/engine", module.id)
        assertTrue(shape.wires.any { it.consumer == "claude:d#ui" && it.provider == module.id })
    }

    @Test fun aSinglePlanWideComponentOpensOneLevelDown() {
        val wrapped = plan.copy(items = listOf(PlanItem("all", "All", kind = PlanKind.Component)) + plan.items.map { if (it.parent == null) it.copy(parent = "all") else it })
        assertEquals(listOf("claude:d#ui", "claude:d#api"), PlanDesign.shape(listOf(wrapped)).nodes.map { it.id })
    }
}
