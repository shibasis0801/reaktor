package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.SpanBuffer
import dev.shibasis.reaktor.service.TraceContext
import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.service.contract.Spans
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkflowTest {
    @Test fun activitiesExecuteThroughStablePortsInAnOwnedChildGraph() = runTest {
        Reaktor.web()
        val graph = Graph(label = "worker")
        val spans = SpanBuffer()
        graph.intercept(Spans("worker", spans))
        val flow = graph.Node { Workflow(it, "matching", "Matching") }
        val plan = flow.activity<Int, Int>("plan", Effect.ReadOnly) { it + 1 }
        val commit = flow.activity<Int, Int>("commit", Effect.ProductionReversibleWrite) { it * 2 }
        val initial = graph.shape()
        val parent = ServiceCall(TraceContext.root(), null, emptyMap())

        withContext(parent) { assertEquals(8, commit(plan(3))) }
        withContext(parent) { assertEquals(10, commit(plan(4))) }

        assertEquals(initial, graph.shape())
        val scope = initial.scopes.single { it.container == flow.id.toString() }
        assertEquals(2, initial.nodes.count { it.scope == scope.id && it.kind == NodeKind.Interactor })
        val recorded = spans.snapshot()
        assertEquals(4, recorded.size)
        recorded.forEach { span ->
            assertEquals(parent.trace.spanId, span.parentId)
            assertEquals(parent.trace.traceId, span.traceId)
            assertEquals(initial.definitionDigest, span.graphDigest)
            assertTrue(initial.nodes.any { node -> node.definitionId == span.node && node.ports.any { it.definitionId == span.port } })
            assertTrue(initial.wires.any { it.definitionId == span.edge })
        }
        val equivalent = Graph(label = "worker")
        val other = equivalent.Node { Workflow(it, "matching", "Matching") }
        other.activity<Int, Int>("plan", Effect.ReadOnly) { it + 1 }
        other.activity<Int, Int>("commit", Effect.ProductionReversibleWrite) { it * 2 }
        assertEquals(initial.definitionDigest, equivalent.shape().definitionDigest)
        graph.close()
        equivalent.close()
    }

    @Test fun flowsShareTheOriginalProviderAndRecordTheCallingWire() = runTest {
        Reaktor.web()
        val graph = Graph(label = "worker")
        val spans = SpanBuffer()
        graph.intercept(Spans("worker", spans))
        val onboarding = graph.Node { Workflow(it, "onboarding", "Onboarding") }
        val formation = graph.Node { Workflow(it, "formation", "Formation") }
        val eligibility = onboarding.stage<Int, Int>("eligibility", Effect.LiveRead)
        val read = formation.call("eligibility", eligibility)
        val shape = graph.shape()
        assertEquals(8, read(4) { it * 2 })
        val span = spans.snapshot().single()
        val provider = shape.nodes.single { it.attributes["activity"] == "onboarding/eligibility" }
        assertEquals(provider.definitionId, span.node)
        val wire = shape.wires.single { it.definitionId == span.edge }
        assertEquals(formation.id.toString(), wire.consumer)
        assertEquals(provider.id, wire.provider)
        assertEquals(shape, graph.shape())
        assertFailsWith<IllegalArgumentException> { formation.call("eligibility", eligibility) }
        graph.close()
    }

    @Test fun activityFailuresPropagateAndProduceFailureSpans() = runTest {
        Reaktor.web()
        val graph = Graph(label = "worker")
        val spans = SpanBuffer()
        graph.intercept(Spans("worker", spans))
        val flow = graph.Node { Workflow(it, "matching", "Matching") }
        val step = flow.activity<Unit, Unit>("commit", Effect.ProductionReversibleWrite) { error("conflict") }
        assertFailsWith<IllegalStateException> { step(Unit) }
        assertEquals(500, spans.snapshot().single().status)
        assertFailsWith<IllegalArgumentException> { flow.activity<Unit, Unit>("commit", Effect.ReadOnly) {} }
        graph.close()
    }
}
