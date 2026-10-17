package dev.shibasis.reaktor.telemetry.export

import io.opentelemetry.kotlin.ExperimentalApi
import io.opentelemetry.kotlin.context.Scope
import io.opentelemetry.kotlin.tracing.model.Span
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

@kotlin.native.concurrent.ThreadLocal
@OptIn(ExperimentalApi::class)
private object ActivePortSpan { var current: Span? = null }

@OptIn(ExperimentalApi::class)
internal actual fun currentPortSpan(): Span? = ActivePortSpan.current

@OptIn(ExperimentalApi::class)
internal actual fun Span.attachSpanScope(): Scope {
    val previous = ActivePortSpan.current
    ActivePortSpan.current = this
    return object : Scope { override fun detach() { ActivePortSpan.current = previous } }
}

@OptIn(ExperimentalApi::class)
internal actual fun Span.asCoroutineContext(): CoroutineContext = EmptyCoroutineContext
