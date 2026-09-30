package dev.shibasis.reaktor.telemetry

import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.PortCapabilityImpl
import dev.shibasis.reaktor.portgraph.port.addInterceptor
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import dev.shibasis.reaktor.telemetry.export.OtlpJsonSpans
import dev.shibasis.reaktor.telemetry.export.OtlpSpanExporter
import dev.shibasis.reaktor.telemetry.export.ReaktorSpanProcessor
import dev.shibasis.reaktor.telemetry.export.RecordingTransport
import dev.shibasis.reaktor.telemetry.export.ResourceKeys
import dev.shibasis.reaktor.telemetry.export.createReaktorOpenTelemetry
import dev.shibasis.reaktor.telemetry.port.PortSemantics
import dev.shibasis.reaktor.telemetry.port.PortTelemetryInterceptor
import dev.shibasis.reaktor.telemetry.port.ReaktorAttributes
import dev.shibasis.reaktor.telemetry.port.TelemetryFacet
import dev.shibasis.reaktor.telemetry.port.TracePolicy
import io.opentelemetry.kotlin.ExperimentalApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalApi::class)
class SpanPipelineTest {
    private val requestDefaults =
        TelemetryFacet(tracePolicy = TracePolicy.Spans, semantics = PortSemantics.Request)

    /**
     * P0.1 — the vertical slice. A real port call, through K1, a real SDK, the batching
     * processor and the OTLP encoder, arriving at a transport where it can be read back.
     */
    @Test
    fun aPortCallArrivesAtTheTransportAsOtlpJsonCarryingTheReaktorConvention() = runTest {
        val transport = RecordingTransport()
        val installation = createReaktorOpenTelemetry(
            serviceName = "bestbuds",
            transport = transport,
            scope = backgroundScope,
            environment = "dev",
            snapshotId = "git:a41302",
            activationId = "local/7",
        )
        val tracer = installation.openTelemetry.tracerProvider.getTracer("reaktor", "1.0.0")

        val consumerOwner = PortCapabilityImpl()
        val providerOwner = PortCapabilityImpl()
        val consumer = consumerOwner.registerConsumer<Sender>("sendMessage")
        val provider = providerOwner.registerProvider<Sender>("sendMessage", Sender())
        connect(consumer, provider).getOrThrow()
        consumer.addInterceptor(PortTelemetryInterceptor(tracer, "local/7", requestDefaults))

        consumer { send("hi") }
        installation.flush()

        assertEquals(1, transport.batches.size, "the call must produce exactly one exported batch")
        val payload = Json.parseToJsonElement(transport.batches.single()).jsonObject
        val resourceSpans = payload["resourceSpans"]!!.jsonArray.single().jsonObject

        val resource = resourceSpans["resource"]!!.jsonObject["attributes"]!!.jsonArray
            .associate { entry ->
                entry.jsonObject["key"]!!.jsonPrimitive.content to
                    entry.jsonObject["value"]!!.jsonObject.values.first().jsonPrimitive.content
            }
        assertEquals("bestbuds", resource[ResourceKeys.ServiceName])
        assertEquals("git:a41302", resource[ResourceKeys.ReaktorSnapshotId])

        val scopeSpans = resourceSpans["scopeSpans"]!!.jsonArray.single().jsonObject
        assertEquals("reaktor", scopeSpans["scope"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        val span = scopeSpans["spans"]!!.jsonArray.single().jsonObject
        assertEquals("Sender.sendMessage", span["name"]!!.jsonPrimitive.content)
        assertEquals(1, span["kind"]!!.jsonPrimitive.content.toInt(), "INTERNAL is OTLP kind 1")
        assertEquals(1, span["status"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        assertTrue(span.containsKey("endTimeUnixNano"), "an unended span is never exported")

        val attributes = span["attributes"]!!.jsonArray.associate { entry ->
            entry.jsonObject["key"]!!.jsonPrimitive.content to
                entry.jsonObject["value"]!!.jsonObject.values.first().jsonPrimitive.content
        }
        assertEquals("sendMessage", attributes[ReaktorAttributes.PortId])
        assertEquals("Sender", attributes[ReaktorAttributes.ContractId])
        assertEquals("request", attributes[ReaktorAttributes.PortShape])
        assertEquals("consumer", attributes[ReaktorAttributes.PortDirection])
    }

    @Test
    fun theHealthReportShowsWhatActuallyLeft() = runTest {
        val transport = RecordingTransport()
        val installation = createReaktorOpenTelemetry("svc", transport, backgroundScope)
        val tracer = installation.openTelemetry.tracerProvider.getTracer("reaktor", "1.0.0")

        repeat(3) { tracer.startSpan("op").end() }
        installation.flush()

        val health = installation.health
        assertEquals(3, health.accepted)
        assertEquals(3, health.exported)
        assertEquals(0, health.dropped)
        assertTrue(health.healthy)
    }

    @Test
    fun aRejectedBatchIsCountedAsFailedRatherThanSilentlyLost() = runTest {
        val installation = createReaktorOpenTelemetry("svc", RecordingTransport(accept = false), backgroundScope)
        val tracer = installation.openTelemetry.tracerProvider.getTracer("reaktor", "1.0.0")

        tracer.startSpan("op").end()
        installation.flush()

        val health = installation.health
        assertEquals(1, health.failed)
        assertTrue(!health.healthy, "a pipeline that is dropping must not report healthy")
    }

    @Test
    fun aFullQueueDropsAndCountsRatherThanBlockingTheTracedCall() = runTest {
        val installation = createReaktorOpenTelemetry(
            serviceName = "svc",
            exporter = OtlpSpanExporter(RecordingTransport()),
            scope = backgroundScope,
            maxBatchSize = 2,
            maxQueuedSpans = 2,
        )
        val tracer = installation.openTelemetry.tracerProvider.getTracer("reaktor", "1.0.0")

        // No suspension point between these, so the drain worker cannot run and the queue fills.
        repeat(32) { tracer.startSpan("op$it").end() }

        val health = installation.health
        assertEquals(32, health.accepted, "every span is accounted for")
        assertTrue(health.dropped > 0, "a bounded queue must drop rather than grow without limit")
        assertEquals(32, health.dropped + health.queued.toLong(), "no span vanishes unrecorded")
    }

    @Test
    fun batchesAreCappedAtTheConfiguredSize() = runTest {
        val transport = RecordingTransport()
        val installation = createReaktorOpenTelemetry(
            serviceName = "svc",
            exporter = OtlpSpanExporter(transport),
            scope = backgroundScope,
            maxBatchSize = 2,
            maxQueuedSpans = 64,
        )
        val tracer = installation.openTelemetry.tracerProvider.getTracer("reaktor", "1.0.0")

        repeat(5) { tracer.startSpan("op$it").end() }
        installation.flush()

        val spansPerBatch = transport.batches.map { body ->
            Json.parseToJsonElement(body).jsonObject["resourceSpans"]!!.jsonArray
                .sumOf { rs ->
                    rs.jsonObject["scopeSpans"]!!.jsonArray
                        .sumOf { it.jsonObject["spans"]!!.jsonArray.size }
                }
        }
        assertEquals(5, spansPerBatch.sum())
        assertTrue(spansPerBatch.all { it <= 2 }, "batch cap not honoured: $spansPerBatch")
    }

    @Test
    fun shutdownFlushesWhatIsQueuedAndClosesTheTransport() = runTest {
        val transport = RecordingTransport()
        val installation = createReaktorOpenTelemetry("svc", transport, backgroundScope)
        val tracer = installation.openTelemetry.tracerProvider.getTracer("reaktor", "1.0.0")

        tracer.startSpan("op").end()
        installation.shutdown()

        assertEquals(1, installation.health.exported)
        assertTrue(transport.closed)
    }

    /**
     * Regression: the workbench probe accepted 35 spans and exported 1, because flush drained
     * the queue without waiting for the batch the worker already held.
     */
    @Test
    fun flushWaitsForBatchesTheWorkerIsAlreadyExporting() = runTest {
        val transport = RecordingTransport()
        val installation = createReaktorOpenTelemetry(
            serviceName = "svc",
            exporter = OtlpSpanExporter(transport),
            scope = backgroundScope,
            maxBatchSize = 4,
            maxQueuedSpans = 512,
        )
        val tracer = installation.openTelemetry.tracerProvider.getTracer("reaktor", "1.0.0")

        repeat(35) { tracer.startSpan("op$it").end() }
        installation.flush()

        val health = installation.health
        assertEquals(35, health.accepted)
        assertEquals(35, health.exported, "every accepted span must be exported or counted as dropped")
        assertEquals(0, health.queued, "nothing may still be in flight after a flush returns")
    }

    @Test
    fun anEmptyBatchEncodesToAnEmptyDocumentRatherThanFailing() {
        assertEquals("""{"resourceSpans":[]}""", OtlpJsonSpans.encode(emptyList()))
    }
}
