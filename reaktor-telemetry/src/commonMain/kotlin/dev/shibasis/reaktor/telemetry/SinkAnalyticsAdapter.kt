package dev.shibasis.reaktor.telemetry

class SinkAnalyticsAdapter(
    private val sinks: List<AnalyticsSink>,
) : AnalyticsAdapter {
    override fun logEvent(name: String, parameters: Map<String, Any>?) = each { it.logEvent(name, parameters) }

    override fun setUserProperty(name: String, value: String) = each { it.setUserProperty(name, value) }

    override fun setUserId(userId: String?) = each { it.setUserId(userId) }

    override fun setAnalyticsCollectionEnabled(enabled: Boolean) = each { it.setEnabled(enabled) }

    private inline fun each(block: (AnalyticsSink) -> Unit) {
        for (sink in sinks) {
            runCatching { block(sink) }
        }
    }
}
