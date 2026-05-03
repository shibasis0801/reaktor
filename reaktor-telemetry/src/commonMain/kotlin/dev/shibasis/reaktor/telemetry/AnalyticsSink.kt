package dev.shibasis.reaktor.telemetry

/**
 * Secondary sink for analytics events. Plug additional destinations alongside
 * the primary Firebase pipeline (e.g. a custom HTTP ingest used to drive
 * embeddings / recommendation pipelines).
 *
 * Implementations must be non-blocking; long-running work belongs on a
 * background coroutine owned by the implementation.
 */
interface AnalyticsSink {
    fun logEvent(name: String, parameters: Map<String, Any>?)
    fun setUserProperty(name: String, value: String) {}
    fun setUserId(userId: String?) {}
    fun setEnabled(enabled: Boolean) {}
}
