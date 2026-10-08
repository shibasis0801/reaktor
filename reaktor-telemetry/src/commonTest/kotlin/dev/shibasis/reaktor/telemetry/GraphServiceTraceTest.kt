package dev.shibasis.reaktor.telemetry

import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.port.PortCapabilityImpl
import dev.shibasis.reaktor.portgraph.port.addInterceptor
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import dev.shibasis.reaktor.service.GetHandler
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.Service
import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.ServiceContract
import dev.shibasis.reaktor.service.SpanBuffer
import dev.shibasis.reaktor.service.SpanRecorder
import dev.shibasis.reaktor.service.SpanSink
import dev.shibasis.reaktor.service.TraceContext
import dev.shibasis.reaktor.service.TracePropagation
import dev.shibasis.reaktor.telemetry.export.ClientServiceSpanProcessor
import dev.shibasis.reaktor.telemetry.export.ResourceKeys
import io.opentelemetry.kotlin.createOpenTelemetry
import dev.shibasis.reaktor.telemetry.port.PortSemantics
import dev.shibasis.reaktor.telemetry.port.PortTelemetryInterceptor
import dev.shibasis.reaktor.telemetry.port.TelemetryFacet
import dev.shibasis.reaktor.telemetry.port.TracePolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.headersOf
import io.opentelemetry.kotlin.ExperimentalApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.time.TimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalApi::class)
class GraphServiceTraceTest {
    private val defaults = TelemetryFacet(TracePolicy.Spans, PortSemantics.Request)
    private val resources = mapOf(ServiceCall.SessionAttribute to "session-default",
        ServiceCall.EnvironmentAttribute to "dev", ServiceCall.BuildAttribute to "fixture.build")

    private fun wire(key: String, interceptor: PortTelemetryInterceptor): ConsumerPort<Sender> {
        val consumer = PortCapabilityImpl().registerConsumer<Sender>(key)
        val provider = PortCapabilityImpl().registerProvider<Sender>(key, Sender())
        connect(consumer, provider).getOrThrow()
        consumer.addInterceptor(interceptor)
        return consumer
    }

    @Test fun nestedSuspendingPortsPreserveTraceAndParentAcrossDispatcherSwitch() = runTest {
        val spans = SpanBuffer()
        val sdk = createOpenTelemetry { tracerProvider {
            resource(mapOf(ResourceKeys.ServiceName to "reaktor-client")); export { ClientServiceSpanProcessor(spans) }
        } }
        val interceptor = PortTelemetryInterceptor(sdk.tracerProvider.getTracer("test"), defaults = defaults,
            openTelemetry = sdk, callAttributes = { resources })
        val outer = wire("outer", interceptor)
        val inner = wire("inner", interceptor)
        val root = TraceContext.root()
        withContext(ServiceCall(root, null, mapOf(ServiceCall.SessionAttribute to "session-one"))) {
            outer.suspended { withContext(Dispatchers.Default) { inner.suspended { sendLater("hello") } } }
        }
        assertNull(currentCoroutineContext()[ServiceCall])
        val recorded = spans.snapshot().associateBy { it.operation }
        val parent = recorded.getValue("Sender.outer")
        val child = recorded.getValue("Sender.inner")
        assertEquals(root.traceId, parent.traceId)
        assertEquals(root.spanId, parent.parentId)
        assertEquals(parent.traceId, child.traceId)
        assertEquals(parent.spanId, child.parentId)
        assertTrue(recorded.values.all { it.applicationSession == "session-one" && it.build == "fixture.build" })
        assertTrue(recorded.values.all { it.node.isEmpty() && it.graphDigest.isEmpty() })
    }

    @Test fun nestedTypedServicePropagatesGraphTraceAndSessionToTransport() = runTest {
        val spans = SpanBuffer()
        var traceparent: String? = null
        var baggage: String? = null
        val http = HttpClient(MockEngine { request ->
            traceparent = request.headers[TraceContext.Header]
            baggage = request.headers["baggage"]
            respond("{}", headers = headersOf("Content-Type", "application/json"))
        })
        val service = object : Service("https://example.invalid", http) {
            override val contract = ServiceContract("test.service")
            val ping = GetHandler<GraphTraceRequest, GraphTraceResponse>("/ping")
            init { use(TracePropagation, SpanRecorder("reaktor-client", spans, resources)) }
        }
        val sdk = createOpenTelemetry { tracerProvider {
            resource(mapOf(ResourceKeys.ServiceName to "reaktor-client")); export { ClientServiceSpanProcessor(spans) }
        } }
        val port = wire("load", PortTelemetryInterceptor(sdk.tracerProvider.getTracer("test"), defaults = defaults,
            openTelemetry = sdk, callAttributes = { resources }))
        withContext(Dispatchers.Default) { port.suspended { service.ping(GraphTraceRequest()) } }
        val graph = spans.snapshot().single { it.contract == "Sender" }
        val request = spans.snapshot().single { it.contract == "test.service" }
        assertEquals(graph.traceId, request.traceId)
        assertEquals(graph.spanId, request.parentId)
        assertEquals(request.traceId, TraceContext.parse(traceparent)?.traceId)
        assertEquals(request.spanId, TraceContext.parse(traceparent)?.spanId)
        assertEquals("reaktor-session=session-default", baggage)
        assertEquals(graph.applicationSession, request.applicationSession)
        http.close()
    }

    @Test fun explicitTypedEndpointSpanOwnsTheNestedGraphParentInsideAnOlderSdkScope() = runTest {
        val spans = SpanBuffer()
        val sdk = createOpenTelemetry { tracerProvider {
            resource(mapOf(ResourceKeys.ServiceName to "reaktor-client")); export { ClientServiceSpanProcessor(spans) }
        } }
        val interceptor = PortTelemetryInterceptor(sdk.tracerProvider.getTracer("test"), defaults = defaults,
            openTelemetry = sdk, callAttributes = { resources })
        val outer = wire("entry", interceptor)
        val inner = wire("endpointChild", interceptor)
        var endpointSpan: String? = null
        val service = object : Service() {
            override val contract = ServiceContract("test.callback")
            val callback = GetHandler<GraphTraceRequest, GraphTraceResponse>("/callback", operation = "callback") {
                endpointSpan = currentCoroutineContext()[ServiceCall]?.trace?.spanId
                inner.suspended { sendLater("hello") }
                GraphTraceResponse()
            }
            init { use(SpanRecorder("reaktor-server", spans, resources)) }
        }
        outer.suspended { service.callback(GraphTraceRequest()) }
        val parent = spans.snapshot().single { it.operation == "Sender.entry" }
        val endpoint = spans.snapshot().single { it.contract == "test.callback" }
        val child = spans.snapshot().single { it.operation == "Sender.endpointChild" }
        assertEquals(parent.traceId, endpoint.traceId)
        assertEquals(parent.spanId, endpoint.parentId)
        assertEquals(endpointSpan, endpoint.spanId)
        assertEquals(endpoint.spanId, child.parentId)
        assertNotEquals(parent.spanId, child.parentId)
        assertEquals(endpoint.traceId, child.traceId)
        assertEquals(endpoint.applicationSession, child.applicationSession)
    }

    @Test fun throwingProjectionCannotChangeSuccessOrReplaceTheOriginalCallerFailure() = runTest {
        val processor = ClientServiceSpanProcessor(SpanSink { error("private sink failure") })
        val sdk = createOpenTelemetry { tracerProvider { export { processor } } }
        val port = wire("projection", PortTelemetryInterceptor(sdk.tracerProvider.getTracer("test"), defaults = defaults,
            openTelemetry = sdk, callAttributes = { resources }))
        assertEquals("sent:ok", port { send("ok") })
        val original = IllegalArgumentException("private caller failure")
        assertTrue(runCatching { port { throw original } }.exceptionOrNull() === original)
        assertEquals(2L, processor.failedSpans)
        assertTrue(!sdk.spanFactory.fromContext(sdk.contextFactory.implicitContext()).spanContext.isValid)
    }

    @Test fun concurrentSessionsDoNotBorrowEachOthersTraceOrIdentity() = runTest {
        val spans = SpanBuffer()
        val sdk = createOpenTelemetry { tracerProvider {
            resource(mapOf(ResourceKeys.ServiceName to "reaktor-client")); export { ClientServiceSpanProcessor(spans) }
        } }
        val port = wire("parallel", PortTelemetryInterceptor(sdk.tracerProvider.getTracer("test"), defaults = defaults,
            openTelemetry = sdk, callAttributes = { resources }))
        val roots = listOf(TraceContext.root(), TraceContext.root())
        roots.mapIndexed { index, root -> async {
            withContext(ServiceCall(root, null, mapOf(ServiceCall.SessionAttribute to "session-$index"))) {
                port.suspended { delay(if (index == 0) 20 else 1); sendLater("hello") }
            }
        } }.awaitAll()
        val recorded = spans.snapshot().associateBy { it.applicationSession }
        roots.forEachIndexed { index, root ->
            assertEquals(root.traceId, recorded.getValue("session-$index").traceId)
            assertEquals(root.spanId, recorded.getValue("session-$index").parentId)
        }
    }

    @Test fun synchronousScopeRestoresAfterFailureAndDoesNotCapturePrivateValues() = runTest {
        val spans = SpanBuffer()
        val sdk = createOpenTelemetry { tracerProvider {
            resource(mapOf(ResourceKeys.ServiceName to "reaktor-client")); export { ClientServiceSpanProcessor(spans) }
        } }
        val interceptor = PortTelemetryInterceptor(sdk.tracerProvider.getTracer("test"), defaults = defaults,
            openTelemetry = sdk, callAttributes = { resources + ("request.body" to "private@example.invalid") })
        val outer = wire("outer", interceptor)
        val inner = wire("inner", interceptor)
        val failure = runCatching { outer { inner { error("private@example.invalid") } } }.exceptionOrNull()
        assertEquals("private@example.invalid", failure?.message)
        outer { send("second") }
        val recorded = spans.snapshot()
        val child = recorded.single { it.operation == "Sender.inner" }
        val failedParent = recorded.single { it.operation == "Sender.outer" && it.status == 500 }
        val next = recorded.single { it.status == 200 }
        assertEquals(failedParent.traceId, child.traceId)
        assertEquals(failedParent.spanId, child.parentId)
        assertNotEquals(failedParent.traceId, next.traceId)
        assertTrue(recorded.none { it.toString().contains("private@example.invalid") })
    }

    @Test fun bareReferenceHandoffsAreUncoveredEvenWithRequestDefaults() = runTest {
        val spans = SpanBuffer()
        val sdk = createOpenTelemetry { tracerProvider {
            resource(mapOf(ResourceKeys.ServiceName to "reaktor-client")); export { ClientServiceSpanProcessor(spans) }
        } }
        val port = wire("reference", PortTelemetryInterceptor(sdk.tracerProvider.getTracer("test"), defaults = defaults,
            openTelemetry = sdk, callAttributes = { resources }))
        val target = port()
        assertEquals("sent:direct", target.send("direct"))
        assertEquals(0, spans.pending)
    }

    @Test fun graphContextOverheadHasABoundedDirectionalBurst() = runTest {
        val spans = SpanBuffer()
        val sdk = createOpenTelemetry { tracerProvider {
            resource(mapOf(ResourceKeys.ServiceName to "reaktor-client")); export { ClientServiceSpanProcessor(spans) }
        } }
        val tracer = sdk.tracerProvider.getTracer("test")
        val baseline = wire("baseline", PortTelemetryInterceptor(tracer,
            defaults = TelemetryFacet(TracePolicy.Off, PortSemantics.Request)))
        val observed = wire("observed", PortTelemetryInterceptor(tracer, defaults = defaults,
            openTelemetry = sdk, callAttributes = { resources }))
        repeat(2_000) { baseline.suspended { sendLater("warmup") }; observed.suspended { sendLater("warmup") } }
        val baselineMillis = mutableListOf<Double>()
        val observedMillis = mutableListOf<Double>()
        repeat(6) {
            var started = TimeSource.Monotonic.markNow()
            repeat(10_000) { baseline.suspended { sendLater("sample") } }
            baselineMillis += started.elapsedNow().inWholeMicroseconds / 1000.0
            started = TimeSource.Monotonic.markNow()
            repeat(10_000) { observed.suspended { sendLater("sample") } }
            observedMillis += started.elapsedNow().inWholeMicroseconds / 1000.0
            }
        assertEquals(62_000L, spans.pending.toLong() + spans.dropped)
        assertEquals(1_024, spans.pending)
        println("REAKTOR_GRAPH_SERVICE_BENCHMARK baseline_ms=$baselineMillis observed_ms=$observedMillis iterations_per_round=10000 accepted=62000 retained=${spans.pending} dropped=${spans.dropped}")
    }

    @Test fun cancellationKeepsTheFailureAndEndsTheGraphSpan() = runTest {
        val spans = SpanBuffer()
        val sdk = createOpenTelemetry { tracerProvider {
            resource(mapOf(ResourceKeys.ServiceName to "reaktor-client")); export { ClientServiceSpanProcessor(spans) }
        } }
        val port = wire("cancel", PortTelemetryInterceptor(sdk.tracerProvider.getTracer("test"), defaults = defaults,
            openTelemetry = sdk, callAttributes = { resources }))
        val failure = CancellationException("private cancellation detail")
        val thrown = runCatching { port.suspended { throw failure } }.exceptionOrNull()
        assertTrue(thrown === failure)
        assertEquals(499, spans.snapshot().single().status)
        assertTrue(spans.snapshot().single().durationMillis >= 0)
        assertNull(currentCoroutineContext()[ServiceCall])
    }
}

@Serializable
private class GraphTraceRequest : Request()

@Serializable
private class GraphTraceResponse : Response()
