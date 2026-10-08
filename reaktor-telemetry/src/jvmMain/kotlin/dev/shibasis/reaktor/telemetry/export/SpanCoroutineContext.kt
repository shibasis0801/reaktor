package dev.shibasis.reaktor.telemetry.export

import io.opentelemetry.kotlin.ExperimentalApi
import io.opentelemetry.kotlin.context.Scope
import io.opentelemetry.kotlin.tracing.model.Span
import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalApi::class)
private val activePortSpan = ThreadLocal<Span?>()

@OptIn(ExperimentalApi::class)
internal actual fun currentPortSpan(): Span? = activePortSpan.get()

@OptIn(ExperimentalApi::class)
internal actual fun Span.attachSpanScope(): Scope {
    val previous = activePortSpan.get()
    activePortSpan.set(this)
    return object : Scope {
        override fun detach() {
            if (previous == null) activePortSpan.remove() else activePortSpan.set(previous)
        }
    }
}

@OptIn(ExperimentalApi::class)
internal actual fun Span.asCoroutineContext(): CoroutineContext {
    val span = this
    return object : ThreadContextElement<Scope> {
        override val key: CoroutineContext.Key<*> = SpanCoroutineContextKey
        override fun updateThreadContext(context: CoroutineContext): Scope = span.attachSpanScope()
        override fun restoreThreadContext(context: CoroutineContext, oldState: Scope) = oldState.detach()
    }
}

@OptIn(ExperimentalApi::class)
private object SpanCoroutineContextKey : CoroutineContext.Key<ThreadContextElement<Scope>>
