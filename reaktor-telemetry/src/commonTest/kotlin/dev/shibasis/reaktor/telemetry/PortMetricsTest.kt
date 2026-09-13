package dev.shibasis.reaktor.telemetry

import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.port.PortCapabilityImpl
import dev.shibasis.reaktor.portgraph.port.addInterceptor
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import dev.shibasis.reaktor.telemetry.port.PortMeasurement
import dev.shibasis.reaktor.telemetry.port.PortMetrics
import dev.shibasis.reaktor.telemetry.port.PortSemantics
import dev.shibasis.reaktor.telemetry.port.PortSeries
import dev.shibasis.reaktor.telemetry.port.PortTelemetryInterceptor
import dev.shibasis.reaktor.telemetry.port.TelemetryFacet
import dev.shibasis.reaktor.telemetry.port.TracePolicy
import dev.shibasis.reaktor.telemetry.port.telemetry
import dev.shibasis.reaktor.telemetry.export.RecordingTransport
import dev.shibasis.reaktor.telemetry.export.createReaktorOpenTelemetry
import io.opentelemetry.kotlin.ExperimentalApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalApi::class)
class PortMetricsTest {

    private interface Repository {
        fun load(id: String): String
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun tracer() = createReaktorOpenTelemetry(
        serviceName = "metrics-test", transport = RecordingTransport(), scope = scope,
    ).openTelemetry.tracerProvider.getTracer("reaktor", "1.0.0")

    /** One consumer wired to a provider, with the interceptor attached to the consumer. */
    private fun wired(metrics: PortMetrics, failing: Boolean = false): ConsumerPort<Repository> {
        val consumerOwner = PortCapabilityImpl()
        val providerOwner = PortCapabilityImpl()
        val impl = object : Repository {
            override fun load(id: String): String = if (failing) error("no such record $id") else "loaded:$id"
        }
        val consumer = consumerOwner.registerConsumer<Repository>("")
        val provider = providerOwner.registerProvider<Repository>("", impl)
        connect(consumer, provider).getOrThrow()
        consumer.addInterceptor(
            PortTelemetryInterceptor(
                tracer(), activationId = "test/1",
                defaults = TelemetryFacet(TracePolicy.Spans, PortSemantics.Request),
                metrics = metrics,
            ),
        )
        return consumer
    }

    @Test
    fun everyCallIsCountedAndTimed() {
        val metrics = PortMetrics()
        val port = wired(metrics)

        repeat(7) { port { load("row-$it") } }

        val measured = metrics.snapshot().single()
        assertEquals(7, measured.calls)
        assertEquals(0, measured.errors)
        assertTrue(measured.totalNanos > 0, "a call takes some time")
        assertTrue(measured.maxNanos >= measured.meanNanos)
        assertEquals("Repository.default", measured.series.operation)
        assertEquals("consumer", measured.series.direction)
        assertEquals("request", measured.series.shape)
    }

    @Test
    fun aThrowingCallIsCountedAsAnErrorAndStillMeasured() {
        val metrics = PortMetrics()
        val port = wired(metrics, failing = true)

        repeat(3) { assertFailsWith<IllegalStateException> { port { load("missing") } } }

        val measured = metrics.snapshot().single()
        assertEquals(3, measured.calls, "a failed call still crossed the port")
        assertEquals(3, measured.errors)
        assertEquals(1.0, measured.errorRate)
        assertTrue(measured.totalNanos > 0, "a failure has a latency too")
    }

    /**
     * `TracePolicy.Metrics` is documented as "aggregate only — the interceptor runs but emits no
     * span", and before there was anything to aggregate into it did nothing at all: the
     * interceptor returned `proceed()` without recording either.
     */
    @Test
    fun metricsPolicyRecordsMeasuresWithoutSpans() {
        val metrics = PortMetrics()
        val port = wired(metrics)
        port.telemetry(TelemetryFacet(TracePolicy.Metrics, PortSemantics.Request))

        repeat(4) { port { load("row") } }

        assertEquals(4, metrics.snapshot().single().calls, "metrics-only must still count the call")
    }

    @Test
    fun offRecordsNothingAndReferencePortsAreNeverMeasured() {
        val metrics = PortMetrics()
        val port = wired(metrics)

        port.telemetry(TelemetryFacet(TracePolicy.Off, PortSemantics.Request))
        repeat(3) { port { load("row") } }
        assertTrue(metrics.isEmpty, "Off means off")

        // A reference handoff is not a call: the work happens on the object the port returned, so
        // measuring the handoff would report traffic that did not occur.
        port.telemetry(TelemetryFacet(TracePolicy.Spans, PortSemantics.Reference))
        repeat(3) { port { load("row") } }
        assertTrue(metrics.isEmpty, "a reference handoff is not a call")
    }

    @Test
    fun quantilesAreBucketUpperBoundsAndAreAbsentWithoutCalls() {
        val empty = PortMeasurement(PortSeries("n", "p", "c", "consumer", "request"))
        assertNull(empty.quantileNanos(0.95), "no calls means no quantile, not zero")

        var measured = empty
        repeat(99) { measured = measured.plus(2_000, failed = false) }
        measured = measured.plus(2_000_000_000, failed = false)

        assertEquals(100, measured.calls)
        // An upper bound, not the value: 2µs calls fall in the (1µs, 2.5µs] bucket, so the honest
        // reading of p50 is "at most 2.5µs".
        assertEquals(2_500L, measured.quantileNanos(0.5))
        // And p99 of ninety-nine fast calls is still fast — one outlier in a hundred sits above
        // the 99th percentile, not at it. This is exactly why max is reported beside the
        // quantiles: the tail bound and the true peak answer different questions.
        assertEquals(2_500L, measured.quantileNanos(0.99))
        assertEquals(2_000_000_000L, measured.maxNanos)
        assertEquals(2_000L, measured.minNanos)
    }

    @Test
    fun aTailHeavyEnoughToMatterMovesTheQuantile() {
        var measured = PortMeasurement(PortSeries("n", "p", "c", "consumer", "request"))
        // Five percent slow is the shape a histogram exists to expose: the mean stays small while
        // p95 lands in the slow bucket.
        repeat(95) { measured = measured.plus(2_000, failed = false) }
        repeat(5) { measured = measured.plus(2_000_000_000, failed = false) }

        assertEquals(2_500L, measured.quantileNanos(0.5))
        assertEquals(10_000_000_000L, measured.quantileNanos(0.99),
            "the slow calls have to be visible at the tail")
        assertTrue(measured.meanNanos < 200_000_000L, "the mean alone would hide them")
    }

    @Test
    fun seriesAreKeyedOnStableIdentityOnly() {
        val metrics = PortMetrics()
        // Two separate owners with the same label are the same architectural series; a per-process
        // identity in the key would make every restart — and every instance — a new one.
        listOf(1, 2).forEach {
            wired(metrics).invoke { load("row") }
        }
        val series = metrics.snapshot().map { it.series }.distinct()
        assertEquals(1, series.size, "same node label, port, contract and direction is one series")
    }
}
