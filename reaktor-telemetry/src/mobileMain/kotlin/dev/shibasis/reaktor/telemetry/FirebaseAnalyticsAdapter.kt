package dev.shibasis.reaktor.telemetry

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.analytics.FirebaseAnalytics
import dev.gitlive.firebase.analytics.analytics

class FirebaseAnalyticsAdapter(
    analyticsProvider: () -> FirebaseAnalytics = { Firebase.analytics },
    private val extraSinks: List<AnalyticsSink> = emptyList(),
) : AnalyticsAdapter {
    val analytics by lazy { analyticsProvider() }

    override fun logEvent(name: String, parameters: Map<String, Any>?) {
        analytics.logEvent(name, parameters)
        fanOut { it.logEvent(name, parameters) }
    }

    override fun setUserProperty(name: String, value: String) {
        analytics.setUserProperty(name, value)
        fanOut { it.setUserProperty(name, value) }
    }

    override fun setUserId(userId: String?) {
        analytics.setUserId(userId)
        fanOut { it.setUserId(userId) }
    }

    override fun setAnalyticsCollectionEnabled(enabled: Boolean) {
        analytics.setAnalyticsCollectionEnabled(enabled)
        fanOut { it.setEnabled(enabled) }
    }

    private inline fun fanOut(block: (AnalyticsSink) -> Unit) {
        for (sink in extraSinks) {
            runCatching { block(sink) }
        }
    }
}
