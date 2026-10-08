package dev.shibasis.reaktor.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpanBufferTest {
    private fun span(id: Char) = ServiceSpan(
        traceId = "1".repeat(32), spanId = id.toString().repeat(16),
        phase = ServiceExecutionPhase.SERVER, island = "fixture", contract = "fixture",
        operation = "read", startedMillis = 1, durationMillis = 2.0, status = 200,
    )

    @Test fun failedDeliveryRemainsAvailableAndPressureIsCounted() {
        val buffer = SpanBuffer(2)
        buffer.record(span('2'))
        buffer.record(span('3'))
        val failed = buffer.snapshot()
        buffer.record(span('4'))
        assertEquals(failed, buffer.snapshot())
        assertEquals(2, buffer.pending)
        assertEquals(1L, buffer.dropped)
        buffer.acknowledge(failed.take(1))
        buffer.record(span('4'))
        assertEquals(listOf(span('3'), span('4')), buffer.snapshot())
    }

    @Test fun acknowledgmentNeverRemovesSpansRecordedDuringDelivery() {
        val buffer = SpanBuffer(3)
        buffer.record(span('2'))
        val sending = buffer.snapshot()
        buffer.record(span('3'))
        buffer.acknowledge(sending)
        buffer.acknowledge(sending)
        assertEquals(listOf(span('3')), buffer.snapshot())
        assertEquals(listOf(span('3')), buffer.drain())
        assertEquals(0, buffer.pending)
    }

    @Test fun nonPositiveCapacityIsRejected() {
        assertFailsWith<IllegalArgumentException> { SpanBuffer(0) }
        assertFailsWith<IllegalArgumentException> { SpanBuffer(-1) }
    }
}
