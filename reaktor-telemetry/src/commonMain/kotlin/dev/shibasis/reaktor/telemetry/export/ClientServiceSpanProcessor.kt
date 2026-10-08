package dev.shibasis.reaktor.telemetry.export

import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.ServiceExecutionPhase
import dev.shibasis.reaktor.service.ServiceSpan
import dev.shibasis.reaktor.service.SpanSink
import dev.shibasis.reaktor.telemetry.port.ReaktorAttributes
import io.opentelemetry.kotlin.ExperimentalApi
import io.opentelemetry.kotlin.OpenTelemetry
import io.opentelemetry.kotlin.createOpenTelemetry
import io.opentelemetry.kotlin.export.OperationResultCode
import io.opentelemetry.kotlin.tracing.StatusCode
import io.opentelemetry.kotlin.tracing.data.SpanData
import io.opentelemetry.kotlin.tracing.export.SpanProcessor
import io.opentelemetry.kotlin.tracing.model.ReadWriteSpan
import io.opentelemetry.kotlin.tracing.model.ReadableSpan
import io.opentelemetry.kotlin.context.Context
import kotlinx.atomicfu.atomic

@OptIn(ExperimentalApi::class)
internal class ClientServiceSpanProcessor(private val sink: SpanSink) : SpanProcessor {
    private val failedCount = atomic(0L)
    internal val failedSpans: Long get() = failedCount.value

    override fun isStartRequired() = false
    override fun isEndRequired() = true
    override fun onStart(span: ReadWriteSpan, parentContext: Context) = Unit
    override fun onEnding(span: ReadWriteSpan) = Unit
    override fun onEnd(span: ReadableSpan) {
        try { record(span.toSpanData()) } catch (_: Throwable) { failedCount.incrementAndGet() }
    }

    private fun record(span: SpanData) {
        val ended = span.endTimestamp ?: return
        val attributes = span.attributes
        val resources = span.resource.attributes
        sink.record(ServiceSpan(
            traceId = span.spanContext.traceId,
            spanId = span.spanContext.spanId,
            parentId = span.parent?.takeIf { it.isValid }?.spanId.orEmpty(),
            phase = ServiceExecutionPhase.CLIENT,
            island = resources[ResourceKeys.ServiceName] as? String ?: "reaktor-client",
            contract = attributes[ReaktorAttributes.ContractId] as? String ?: return,
            operation = span.name,
            startedMillis = span.startTimestamp / 1_000_000,
            durationMillis = ((attributes[ReaktorAttributes.DurationNanos] as? Long)
                ?: (ended - span.startTimestamp)).coerceAtLeast(0) / 1_000_000.0,
            status = if (span.status.statusCode != StatusCode.ERROR) 200
                else if (span.status.description == "CancellationException") 499 else 500,
            applicationSession = attributes[ServiceCall.SessionAttribute] as? String ?: "",
            environment = attributes[ServiceCall.EnvironmentAttribute] as? String ?: "",
            build = attributes[ServiceCall.BuildAttribute] as? String ?: "",
            node = attributes[ReaktorAttributes.NodeId] as? String ?: "",
            graphDigest = attributes[ServiceCall.GraphAttribute] as? String ?: "",
        ))
    }

    override suspend fun forceFlush(): OperationResultCode = OperationResultCode.Success
    override suspend fun shutdown(): OperationResultCode = OperationResultCode.Success
}

@OptIn(ExperimentalApi::class)
fun createClientServiceTelemetry(serviceName: String, sink: SpanSink): OpenTelemetry {
    require(serviceName.isNotBlank())
    return createOpenTelemetry { tracerProvider {
        resource(mapOf(ResourceKeys.ServiceName to serviceName))
        export { ClientServiceSpanProcessor(sink) }
    } }
}
