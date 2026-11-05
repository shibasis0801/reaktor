package dev.shibasis.reaktor.conductor.workspace

import kotlin.test.*

class PlanGraphShapeTest {
    @Test fun stableIdsAndPortKeysStayLiteral() {
        val plan = WorkPlan("fixture", "Plan", createdBy = BoardActor(BoardActorKind.Person, "fixture"), createdAt = 1, updatedAt = 1, items = listOf(
            PlanItem("box", "Box", kind = PlanKind.Component),
            PlanItem("a", "A", parent = "box"),
            PlanItem("b", "B", parent = "box"),
            PlanItem("task", "Task", after = listOf("a"), replacement = "b", relations = listOf(PlanRelation("a", "calls"), PlanRelation("b", "calls"), PlanRelation("a", "after a")), refs = listOf("file:fixture.kt")),
        ))
        val shape = PlanGraph.shape(listOf(plan), code = true) { if (it == "file:fixture.kt") listOf("module:fixture") else emptyList() }
        assertEquals(listOf("plans", "fixture", "fixture parts box", "fixture code"), shape.scopes.map { it.id })
        assertEquals(listOf("fixture#box", "fixture#a", "fixture#b", "fixture#task", "fixture ref file:fixture.kt", "fixture ref module:fixture"), shape.nodes.map { it.id })
        assertEquals(listOf("after a", "replaced by", "calls a", "calls b", "after a → a", "file:fixture.kt"), shape.nodes.single { it.id == "fixture#task" }.ports.filterNot { it.provides }.map { it.key })
        assertEquals(listOf("in module:fixture"), shape.nodes.single { it.id == "fixture ref file:fixture.kt" }.ports.filterNot { it.provides }.map { it.key })
    }
}
