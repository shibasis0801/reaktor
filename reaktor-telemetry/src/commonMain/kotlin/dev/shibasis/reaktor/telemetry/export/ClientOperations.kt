package dev.shibasis.reaktor.telemetry.export

import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.TraceContext
import dev.shibasis.reaktor.telemetry.port.ReaktorAttributes
import io.opentelemetry.kotlin.ExperimentalApi
import io.opentelemetry.kotlin.OpenTelemetry
import io.opentelemetry.kotlin.tracing.data.StatusData
import io.opentelemetry.kotlin.tracing.model.Span
import io.opentelemetry.kotlin.tracing.model.SpanKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

@OptIn(ExperimentalApi::class)
class ClientOperations(
    private val telemetry: OpenTelemetry,
    private val attributes: () -> Map<String, String>,
    private val enabled: () -> Boolean = { true },
) {
    fun <T> trace(name: String, subject: Map<String, String> = emptyMap(), block: () -> T): T {
        if (!enabled()) return block()
        val operation = start(name, subject, captureClientOperation())
        val scope = operation.span.attachSpanScope()
        return try {
            block()
        } catch (failure: Throwable) {
            operation.fail(failure)
            throw failure
        } finally {
            try { operation.end() } finally { scope.detach() }
        }
    }

    suspend fun <T> traceAsync(name: String, subject: Map<String, String> = emptyMap(), block: suspend () -> T): T {
        if (!enabled()) return block()
        val parent = currentCoroutineContext()[ClientOperationContext] ?: captureClientOperation()
        val operation = start(name, subject, parent)
        return try {
            withContext(operation.context) { block() }
        } catch (failure: Throwable) {
            operation.fail(failure)
            throw failure
        } finally {
            operation.end()
        }
    }

    private fun start(name: String, subject: Map<String, String>, parent: ClientOperationContext?): Operation {
        val inherited = parent?.span?.attributes.orEmpty().mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
        val values = inherited + attributes() + subject
        val span = telemetry.tracerProvider.getTracer("reaktor.client.operations", "1.0.0").startSpan(
            name, spanKind = SpanKind.INTERNAL,
            parentContext = parent?.span?.let { telemetry.contextFactory.storeSpan(telemetry.contextFactory.root(), it) },
        ) { values.forEach { (key, value) -> if (value.isNotEmpty()) setStringAttribute(key, value) } }
        span.status = StatusData.Ok
        return Operation(span, values)
    }

    private class Operation(val span: Span, attributes: Map<String, String>) {
        private val started = TimeSource.Monotonic.markNow()
        val context = span.asCoroutineContext() + SpanCoroutineParent(span.spanContext.spanId) + ClientOperationContext(span) + ServiceCall(
            TraceContext(span.spanContext.traceId, span.spanContext.spanId, span.spanContext.traceFlags.isSampled),
            span.parent?.spanId, attributes,
        )

        fun fail(failure: Throwable) {
            if (failure is CancellationException) span.setBooleanAttribute(ReaktorAttributes.Cancelled, true)
            else span.status = StatusData.Error(failure::class.simpleName ?: "Throwable")
        }

        fun end() {
            span.setLongAttribute(ReaktorAttributes.DurationNanos, started.elapsedNow().inWholeNanoseconds)
            span.end()
        }
    }
}

@OptIn(ExperimentalApi::class)
class ClientOperationContext internal constructor(internal val span: Span) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ClientOperationContext>
    val context: CoroutineContext get() = this + span.asCoroutineContext() + SpanCoroutineParent(span.spanContext.spanId) + ServiceCall(
        TraceContext(span.spanContext.traceId, span.spanContext.spanId, span.spanContext.traceFlags.isSampled),
        span.parent?.spanId, span.attributes.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap(),
    )
}

@OptIn(ExperimentalApi::class)
fun captureClientOperation(): ClientOperationContext? = currentPortSpan()?.takeIf { it.spanContext.isValid }?.let(::ClientOperationContext)
