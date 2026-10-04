package dev.shibasis.reaktor.performance

import dev.shibasis.reaktor.service.Response
import sh.measure.kmp.Measure
import sh.measure.kmp.attributes.StringAttr
import sh.measure.kmp.tracing.SpanStatus

actual object ReaktorMeasure {
    actual val sessionId: String? get() = Measure.getSessionId()

    actual fun screenView(scope: ReaktorPerformanceScope) {
        scope.route?.let { Measure.trackScreenView(it.take(64), scope.measureAttributes().mapValues { StringAttr(it.value) }) }
    }

    actual suspend fun <T> trace(name: String, scope: ReaktorPerformanceScope, block: suspend () -> T): T {
        require(name.isNotBlank() && name.length <= 64) { "Measure span names need 1 to 64 characters" }
        val span = Measure.startSpan(name)
        scope.measureAttributes().forEach { (key, value) -> span.setAttribute(key, value) }
        try {
            return block().also { span.setStatus(if (it is Response && !it.isSuccess) SpanStatus.Error else SpanStatus.Ok) }
        } catch (failure: Throwable) {
            span.setStatus(SpanStatus.Error)
            throw failure
        } finally {
            span.end()
        }
    }
}
