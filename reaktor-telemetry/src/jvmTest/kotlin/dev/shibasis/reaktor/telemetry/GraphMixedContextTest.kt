package dev.shibasis.reaktor.telemetry

import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.port.PortCapabilityImpl
import dev.shibasis.reaktor.portgraph.port.addInterceptor
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.SpanBuffer
import dev.shibasis.reaktor.service.TraceContext
import dev.shibasis.reaktor.telemetry.export.ClientServiceSpanProcessor
import dev.shibasis.reaktor.telemetry.export.SpanCoroutineParent
import dev.shibasis.reaktor.telemetry.export.currentPortSpan
import dev.shibasis.reaktor.telemetry.export.ResourceKeys
import dev.shibasis.reaktor.telemetry.port.PortSemantics
import dev.shibasis.reaktor.telemetry.port.PortTelemetryInterceptor
import dev.shibasis.reaktor.telemetry.port.TelemetryFacet
import dev.shibasis.reaktor.telemetry.port.TracePolicy
import io.opentelemetry.kotlin.ExperimentalApi
import io.opentelemetry.kotlin.createOpenTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalApi::class)
class GraphMixedContextTest {
    private fun wire(key: String, interceptor: PortTelemetryInterceptor): ConsumerPort<Sender> {
        val consumer = PortCapabilityImpl().registerConsumer<Sender>(key)
        val provider = PortCapabilityImpl().registerProvider<Sender>(key, Sender())
        connect(consumer, provider).getOrThrow()
        consumer.addInterceptor(interceptor)
        return consumer
    }

    @Test fun suspendToSyncKeepsParentsAcrossDispatcherSwitchesAndConcurrentSessions() = runTest {
        val spans = SpanBuffer()
        val sdk = createOpenTelemetry { tracerProvider {
            resource(mapOf(ResourceKeys.ServiceName to "reaktor-client")); export { ClientServiceSpanProcessor(spans) }
        } }
        val interceptor = PortTelemetryInterceptor(sdk.tracerProvider.getTracer("test"),
            defaults = TelemetryFacet(TracePolicy.Spans, PortSemantics.Request), openTelemetry = sdk)
        val parent = wire("parent", interceptor)
        val child = wire("child", interceptor)
        val roots = List(8) { TraceContext.root() }
        roots.mapIndexed { index, root -> async {
            withContext(ServiceCall(root, null, mapOf(ServiceCall.SessionAttribute to "session-$index"))) {
                parent.suspended { withContext(Dispatchers.Default) { delay(2); child { send("hello") } } }
            }
        } }.awaitAll()
        assertNull(currentCoroutineContext()[ServiceCall])
        assertNull(currentPortSpan())
        roots.forEachIndexed { index, root ->
            val recorded = spans.snapshot().filter { it.traceId == root.traceId }
            assertEquals(2, recorded.size)
            val outer = recorded.single { it.operation == "Sender.parent" }
            val inner = recorded.single { it.operation == "Sender.child" }
            assertEquals(outer.spanId, inner.parentId)
            assertTrue(recorded.all { it.applicationSession == "session-$index" })
        }
    }

    @Test fun suspendedChildFollowsTheNestedSyncSpanWhileServiceCallStillBelongsToTheOuterPort() = runTest {
        val spans = SpanBuffer()
        val sdk = createOpenTelemetry { tracerProvider { export { ClientServiceSpanProcessor(spans) } } }
        val interceptor = PortTelemetryInterceptor(sdk.tracerProvider.getTracer("test"),
            defaults = TelemetryFacet(TracePolicy.Spans, PortSemantics.Request), openTelemetry = sdk,
            callAttributes = { mapOf(ServiceCall.SessionAttribute to "session-one") })
        val outer = wire("outer", interceptor)
        val sync = wire("sync", interceptor)
        val child = wire("child", interceptor)
        outer.suspended { withContext(Dispatchers.Default) {
            val caller = checkNotNull(currentCoroutineContext()[ServiceCall])
            val origin = checkNotNull(currentCoroutineContext()[SpanCoroutineParent])
            sync { runBlocking(caller + origin) { child.suspended { delay(1); send("hello") } } }
        } }
        val recorded = spans.snapshot()
        val first = recorded.single { it.operation == "Sender.outer" }
        val middle = recorded.single { it.operation == "Sender.sync" }
        val last = recorded.single { it.operation == "Sender.child" }
        assertEquals(first.spanId, middle.parentId)
        assertEquals(middle.spanId, last.parentId)
        assertTrue(recorded.all { it.traceId == first.traceId && it.applicationSession == "session-one" })
        assertNull(currentCoroutineContext()[ServiceCall])
        assertNull(currentPortSpan())
    }

    @Test fun syncToSuspendUsesTheActiveSyncSpanAndRestoresAfterFailure() = runTest {
        val spans = SpanBuffer()
        val sdk = createOpenTelemetry { tracerProvider {
            resource(mapOf(ResourceKeys.ServiceName to "reaktor-client")); export { ClientServiceSpanProcessor(spans) }
        } }
        val interceptor = PortTelemetryInterceptor(sdk.tracerProvider.getTracer("test"),
            defaults = TelemetryFacet(TracePolicy.Spans, PortSemantics.Request), openTelemetry = sdk,
            callAttributes = { mapOf(ServiceCall.SessionAttribute to "session-one") })
        val sync = wire("sync", interceptor)
        val suspended = wire("suspended", interceptor)
        val failure = withContext(Dispatchers.Default) { runCatching {
            sync { runBlocking { suspended.suspended { delay(1); error("private failure detail") } } }
        }.exceptionOrNull() }
        assertEquals("private failure detail", failure?.message)
        val recorded = spans.snapshot()
        val parent = recorded.single { it.operation == "Sender.sync" }
        val child = recorded.single { it.operation == "Sender.suspended" }
        assertEquals(parent.traceId, child.traceId)
        assertEquals(parent.spanId, child.parentId)
        assertTrue(recorded.all { it.status == 500 && it.applicationSession == "session-one" })
        assertTrue(!sdk.spanFactory.fromContext(sdk.contextFactory.implicitContext()).spanContext.isValid)
    }
}
