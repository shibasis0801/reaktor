package dev.shibasis.reaktor.graph.step

import dev.shibasis.reaktor.graph.Workflow
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.service.contract.Effect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Refund(graph: Graph) : Workflow(graph, "test.refund", "Refund") {
    val order by input<String>()
    val charge by step(order, effect = Effect.ProductionReversibleWrite) { it.length }
    val apology by step(order, effect = Effect.ReadOnly, on = charge.failed) { "sorry about $it" }
    val receipt by step(charge, effect = Effect.ReadOnly) { "paid $it" }
    val answer by either(receipt, apology)
}

class Impossible(graph: Graph) : Workflow(graph, "test.impossible", "Impossible") {
    val order by input<String>()
    val charge by step(order, effect = Effect.ReadOnly) { it.length }
    val never by step(order, effect = Effect.ReadOnly, on = charge.succeeded and charge.failed) { it }
    val stray by step(order, effect = Effect.ReadOnly) { it }
}

class StepGraphTest {
    @Test fun stepsReadInDependencyOrderAndWaitOnConditionsToo() {
        val graph = Graph(label = "worker")
        val refund = graph.Node { Refund(it) }

        assertEquals(listOf("order", "charge", "apology", "receipt", "answer"), refund.steps().map { it.name })
        assertEquals(setOf(refund.order, refund.charge), refund.apology.waitsOn.toSet())
        assertEquals(emptyList(), refund.problems(refund.answer))
        graph.close()
    }

    @Test fun wiresCarryTheConditionOfTheStepTheyFeed() {
        val graph = Graph(label = "worker")
        graph.Node { Refund(it) }
        val shape = graph.shape()
        val ids = shape.nodes.associate { it.label to it.id }

        val guard = shape.wires.single { it.consumer == ids.getValue("apology") && it.provider == ids.getValue("charge") }
        assertEquals("failed", guard.condition)
        assertNull(shape.wires.single { it.consumer == ids.getValue("apology") && it.provider == ids.getValue("order") }.condition)
        assertEquals("charge failed", shape.nodes.single { it.label == "apology" }.attributes["on"])
        assertTrue(shape.wires.filter { it.consumer == ids.getValue("receipt") }.all { it.condition == null })
        graph.close()
    }

    @Test fun aRunLandsOnItsStepsInTheIslandAndInTheWorkflowsOwnShape() {
        val graph = Graph(label = "worker")
        val refund = graph.Node { Refund(it) }
        val steps = mapOf(
            "order" to StepShape(StepState.Succeeded, 1),
            "charge" to StepShape(StepState.Failed, 3, reason = "card declined"),
            "apology" to StepShape(StepState.Running, 1),
            "gone" to StepShape(StepState.Succeeded),
        )

        val island = graph.shape().run("run-1", refund.workflowId, "Running", steps)
        assertEquals(listOf(refund.order, refund.charge, refund.apology).map { it.id.toString() }.toSet(), island.steps.keys)
        assertEquals(StepState.Failed, island.steps.getValue(refund.charge.id.toString()).state)
        assertEquals(refund.apology.id.toString(), island.subject)

        val own = refund.childGraph.shape().run("run-1", refund.workflowId, "Running", steps)
        assertEquals(island, own)
        assertEquals(emptyMap(), graph.shape().run("run-1", "test.other", "Running", steps).steps)
        graph.close()
    }

    @Test fun checksNameConditionsThatCanNeverHoldAndResultsNobodyUses() {
        val graph = Graph(label = "worker")
        val impossible = graph.Node { Impossible(it) }

        val problems = impossible.problems(impossible.charge)
        assertTrue(problems.any { "'never'" in it && "can never hold" in it }, problems.joinToString())
        assertTrue(problems.any { "uses the result of 'stray'" in it }, problems.joinToString())
        graph.close()
    }
}
