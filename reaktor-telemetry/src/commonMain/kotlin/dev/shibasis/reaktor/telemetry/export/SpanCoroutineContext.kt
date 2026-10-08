package dev.shibasis.reaktor.telemetry.export

import io.opentelemetry.kotlin.ExperimentalApi
import io.opentelemetry.kotlin.context.Scope
import io.opentelemetry.kotlin.tracing.model.Span
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement

@OptIn(ExperimentalApi::class)
internal expect fun Span.asCoroutineContext(): CoroutineContext

@OptIn(ExperimentalApi::class)
internal expect fun Span.attachSpanScope(): Scope

@OptIn(ExperimentalApi::class)
internal expect fun currentPortSpan(): Span?

internal class SpanCoroutineParent(val spanId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SpanCoroutineParent>
}
