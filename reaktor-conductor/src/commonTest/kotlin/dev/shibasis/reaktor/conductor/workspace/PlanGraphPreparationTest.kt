package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import kotlin.test.*

class PlanGraphPreparationTest {
    @Test fun aStaticPlanCaptureDoesNotStartADependencyContainer() {
        val plan = WorkPlan("plan:preparation", "Preparation", items = listOf(PlanItem("first", "First"),
            PlanItem("second", "Second", after = listOf("first"))),
            createdBy = BoardActor(BoardActorKind.Person, "Fixture"), createdAt = 1, updatedAt = 1)
        PlanGraph(listOf(plan), false).use { source ->
            assertFalse(source.graph.diAdapter is KoinDependencyAdapter)
            val shape = source.shape()
            assertEquals(2, shape.nodes.size)
            assertEquals(1, shape.wires.size)
            assertTrue(shape.nodes.all { it.ports.isNotEmpty() })
        }
    }
}
