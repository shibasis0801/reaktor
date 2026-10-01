package dev.shibasis.reaktor.performance

actual object ReaktorMeasure {
    actual val sessionId: String? get() = null
    actual fun screenView(scope: ReaktorPerformanceScope) = Unit
    actual suspend fun <T> trace(name: String, scope: ReaktorPerformanceScope, block: suspend () -> T): T = block()
}
