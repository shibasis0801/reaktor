package dev.shibasis.reaktor.work

import dev.shibasis.reaktor.graph.Workflow
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.graph.step.input
import dev.shibasis.reaktor.graph.step.step
import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.work.definition.Durable
import dev.shibasis.reaktor.work.definition.WorkflowPlan
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class Handle(val id: Int)

private class Holding(graph: Graph) : Workflow(graph, "test.holding", "Holding"), Durable<String, String> {
    override val input by input<String>()
    val handle by step(input, effect = Effect.ReadOnly) { Handle(it.length) }
    override val output by step(handle, effect = Effect.ReadOnly) { "handle ${it.id}" }
}

class WorkflowPlanTest {
    @Test fun aDurablePlanRefusesAStepWhoseResultCannotBeStored() {
        val koin = koinApplication {}
        val graph = Graph(dependencyAdapter = KoinDependencyAdapter(koin))
        val holding = graph.Node { Holding(it) }

        val refused = assertFailsWith<IllegalArgumentException> { WorkflowPlan(holding) }
        assertTrue("'handle' in workflow 'test.holding'" in refused.message.orEmpty(), refused.message)
        assertTrue("cannot run durably" in refused.message.orEmpty(), refused.message)
        graph.close()
        koin.close()
    }
}
