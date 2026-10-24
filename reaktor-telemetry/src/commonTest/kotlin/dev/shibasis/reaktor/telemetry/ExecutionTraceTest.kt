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

    @Test fun selectedEndpointsRequireCausalityAndDoNotSumOverlappingWork() {
        val rows = listOf(span("start", "", 100, 20.0).copy(node = "ui"),
            span("work", "start", 110, 80.0).copy(node = "worker"),
            span("end", "work", 170, 10.0).copy(node = "screen", island = "recipient"),
            span("sibling", "start", 120, 300.0).copy(node = "other"),
            span("orphan", "outside", 170, 10.0).copy(node = "screen"))
        val trace = ExecutionTrace.retained(rows).single()
        val selected = trace.between("ui", "screen").single()
        assertEquals(80.0, selected.elapsedMillis)
        assertEquals(listOf("start", "work", "end"), selected.spans.map { it.spanId })
        assertEquals(true, selected.crossIsland)
        assertEquals(false, selected.failed)
        assertEquals(emptyList(), trace.between("screen", "ui"))
        assertEquals(emptyList(), trace.between("other", "screen"))
        assertEquals(20.0, trace.between("ui", "ui").single().elapsedMillis)
        val failed = ExecutionTrace.retained(rows.map { if (it.spanId == "work") it.copy(status = 503) else it }).single()
        assertEquals(true, failed.between("ui", "screen").single().failed)
        val cancelled = ExecutionTrace.retained(rows.map { if (it.spanId == "end") it.copy(status = 499) else it }).single()
        assertEquals(true, cancelled.between("ui", "screen").single().cancelled)
        val skewed = ExecutionTrace.retained(rows.map { if (it.spanId == "end") it.copy(startedMillis = 0) else it }).single()
        assertEquals(emptyList(), skewed.between("ui", "screen"))
    }

    private fun span(id: String, parent: String, start: Long, duration: Double) = ServiceSpan(
        "trace", id, parent, ServiceExecutionPhase.CLIENT, "client", "messages", "send", start, duration, 200)
}
