package dev.shibasis.reaktor.telemetry

import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.port.PortCapabilityImpl
import dev.shibasis.reaktor.portgraph.port.addInterceptor
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import dev.shibasis.reaktor.telemetry.port.PortSemantics
import dev.shibasis.reaktor.telemetry.port.PortTelemetryInterceptor
import dev.shibasis.reaktor.telemetry.port.ReaktorAttributes
import dev.shibasis.reaktor.telemetry.port.TelemetryFacet
import dev.shibasis.reaktor.telemetry.port.TracePolicy
import dev.shibasis.reaktor.telemetry.port.telemetry
import io.opentelemetry.kotlin.ExperimentalApi
import io.opentelemetry.kotlin.tracing.StatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalApi::class)
class PortTelemetryTest {
    private val requestDefaults = TelemetryFacet(
        tracePolicy = TracePolicy.Spans,
        semantics = PortSemantics.Request,
    )

    private fun wire(): Pair<Sender, ConsumerPort<Sender>> {
        val impl = Sender()
        val consumerOwner = PortCapabilityImpl()
        val providerOwner = PortCapabilityImpl()
        val consumer = consumerOwner.registerConsumer<Sender>("sendMessage")
        val provider = providerOwner.registerProvider<Sender>("sendMessage", impl)
        connect(consumer, provider).getOrThrow()
        return impl to consumer
    }

    @Test
    fun onePortCallProducesOneCompletedSpan() {
        val tracer = RecordingTracer()
        val (_, consumer) = wire()
        consumer.addInterceptor(PortTelemetryInterceptor(tracer, "local/7", requestDefaults))

        consumer { send("hi") }

        assertEquals(1, tracer.spans.size)
        val span = tracer.spans.single()
        assertTrue(span.ended, "the span must be ended or no processor will ever export it")
        assertEquals(1, span.endCount)
        assertEquals(StatusCode.OK, span.status.statusCode)
    }

    @Test
    fun theSpanCarriesTheReaktorSemanticConvention() {
        val tracer = RecordingTracer()
        val (_, consumer) = wire()
        consumer.addInterceptor(PortTelemetryInterceptor(tracer, "local/7", requestDefaults))

        consumer { send("hi") }

        val attrs = tracer.spans.single().attributes
        assertEquals("sendMessage", attrs[ReaktorAttributes.PortId])
        assertEquals("Sender", attrs[ReaktorAttributes.ContractId])
        assertEquals("request", attrs[ReaktorAttributes.PortShape])
        assertEquals("consumer", attrs[ReaktorAttributes.PortDirection])
        assertEquals("local/7", attrs[ReaktorAttributes.ActivationId])
        assertEquals(consumer.edge?.id.toString(), attrs[ReaktorAttributes.EdgeId])
    }

    @Test
    fun theSpanNameIsDerivedFromDeclaredIdentityAndStaysLowCardinality() {
        val tracer = RecordingTracer()
        val (_, consumer) = wire()
        consumer.addInterceptor(PortTelemetryInterceptor(tracer, defaults = requestDefaults))

        consumer { send("first") }
        consumer { send("second") }

        assertEquals(listOf("Sender.sendMessage", "Sender.sendMessage"), tracer.spans.map { it.name })
    }

    @Test
    fun aReferenceShapedPortIsNeverTraced() {
        val tracer = RecordingTracer()
        val (impl, consumer) = wire()
        consumer.addInterceptor(
            PortTelemetryInterceptor(tracer, defaults = TelemetryFacet(TracePolicy.Spans, PortSemantics.Reference))
        )

        consumer { send("hi") }

        assertEquals(0, tracer.spans.size)
        assertEquals(1, impl.calls, "the call still runs; only the span is suppressed")
    }

    @Test
    fun policyOffSuppressesTheSpanAndStillRunsTheCall() {
        val tracer = RecordingTracer()
        val (impl, consumer) = wire()
        consumer.telemetry(TelemetryFacet(TracePolicy.Off, PortSemantics.Request))
        consumer.addInterceptor(PortTelemetryInterceptor(tracer, defaults = requestDefaults))

        consumer { send("hi") }

        assertEquals(0, tracer.spans.size)
        assertEquals(1, impl.calls)
    }

    @Test
    fun aPortPolicyOverridesTheInterceptorDefault() {
        val tracer = RecordingTracer()
        val (_, consumer) = wire()
        consumer.telemetry(TelemetryFacet(TracePolicy.Spans, PortSemantics.Mailbox))
        consumer.addInterceptor(PortTelemetryInterceptor(tracer, defaults = requestDefaults))

        consumer { send("hi") }

        assertEquals("mailbox", tracer.spans.single().attributes[ReaktorAttributes.PortShape])
    }

    @Test
    fun aFailingCallRecordsErrorStatusAndStillEndsTheSpan() {
        val tracer = RecordingTracer()
        val (_, consumer) = wire()
        consumer.addInterceptor(PortTelemetryInterceptor(tracer, defaults = requestDefaults))

        val outcome = runCatching { consumer { fail() } }

        assertTrue(outcome.isFailure)
        val span = tracer.spans.single()
        assertTrue(span.ended, "a throwing call must not leak an unended span")
        assertEquals(StatusCode.ERROR, span.status.statusCode)
    }

    @Test
    fun theSuspendingFormIsTracedToo() = runTest {
        val tracer = RecordingTracer()
        val (impl, consumer) = wire()
        consumer.addInterceptor(PortTelemetryInterceptor(tracer, defaults = requestDefaults))

        consumer.suspended { sendLater("hi") }

        assertEquals(1, tracer.spans.size)
        assertTrue(tracer.spans.single().ended)
        assertEquals(1, impl.calls)
    }
}
