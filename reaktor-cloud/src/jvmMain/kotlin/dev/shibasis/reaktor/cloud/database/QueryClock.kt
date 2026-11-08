package dev.shibasis.reaktor.cloud.database

import java.util.Locale

class QueryClock {
    private val started = System.nanoTime()
    private var connectedAt = started
    private var executedAt = started
    fun connected() { connectedAt = System.nanoTime() }
    fun executed() { executedAt = System.nanoTime() }
    fun metrics(http: Boolean = false): List<QueryMetric> {
        val finished = System.nanoTime()
        fun metric(name: String, duration: Long) = QueryMetric(name,
            String.format(Locale.ROOT, "%.3f", duration.coerceAtLeast(0) / 1_000_000.0), "ms", QueryMetricSource.Client)
        return listOf(metric("Adapter elapsed", finished - started), metric("Connection and session", connectedAt - started),
            metric(if (http) "HTTP request and transfer" else "Execute and first batch", executedAt - connectedAt), metric(if (http) "Decode" else "Fetch and decode", finished - executedAt))
    }
}
