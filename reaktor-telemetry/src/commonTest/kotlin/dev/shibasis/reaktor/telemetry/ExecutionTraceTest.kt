package dev.shibasis.reaktor.telemetry

import dev.shibasis.reaktor.service.ServiceExecutionPhase
import dev.shibasis.reaktor.service.ServiceSpan
import dev.shibasis.reaktor.telemetry.execution.ExecutionTrace
import dev.shibasis.reaktor.telemetry.execution.ParentCoverage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExecutionTraceTest {
    @Test fun concurrentChildrenAreUnionedAndCrossIslandWaitRemainsElapsedTime() {
        val root = span("root", "", 100, 100.0)
        val rows = listOf(root, span("a", "root", 110, 50.0), span("b", "root", 130, 50.0),
            span("receive", "b", 220, 10.0).copy(island = "recipient"), root)
        val trace = ExecutionTrace.retained(rows).single()
        assertEquals(130.0, trace.elapsedMillis)
        assertEquals(30.0, trace.steps.first().selfMillis)
        assertEquals(2, trace.steps.last().depth)
        assertEquals(1, trace.duplicateSpans)
        assertEquals(setOf("client", "recipient"), trace.islands)
    }

    @Test fun missingParentsCyclesAndCancellationRemainExplicit() {
        val trace = ExecutionTrace.retained(listOf(span("a", "b", 100, 5.0), span("b", "a", 100, 5.0),
            span("orphan", "outside", 100, 5.0).copy(status = 499), span("rejected", "", 100, 2.0).copy(status = 403))).single()
        assertEquals(2, trace.cycles)
        assertNull(trace.steps.first { it.span.spanId == "a" }.selfMillis)
        assertEquals(ParentCoverage.Missing, trace.steps.first { it.span.spanId == "orphan" }.parentCoverage)
        assertEquals(1, trace.missingParents)
        assertEquals(1, trace.cancellations)
        assertEquals(1, trace.failures)
    }

    @Test fun spanIdsAreScopedToTraceAndMalformedDurationsDoNotPoisonTimeline() {
        val traces = ExecutionTrace.retained(listOf(span("same", "", 100, 1.0),
            span("same", "", 200, 2.0).copy(traceId = "other"), span("bad", "", 0, Double.NaN)))
        assertEquals(listOf("other", "trace"), traces.map { it.id })
        assertEquals(listOf(2.0, 1.0), traces.map { it.elapsedMillis })
    }

    private fun span(id: String, parent: String, start: Long, duration: Double) = ServiceSpan(
        "trace", id, parent, ServiceExecutionPhase.CLIENT, "client", "messages", "send", start, duration, 200)
}
