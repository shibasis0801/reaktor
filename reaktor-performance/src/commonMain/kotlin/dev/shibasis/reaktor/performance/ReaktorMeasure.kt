package dev.shibasis.reaktor.performance

expect object ReaktorMeasure {
    val sessionId: String?
    fun screenView(scope: ReaktorPerformanceScope)
    suspend fun <T> trace(name: String, scope: ReaktorPerformanceScope, block: suspend () -> T): T
}
