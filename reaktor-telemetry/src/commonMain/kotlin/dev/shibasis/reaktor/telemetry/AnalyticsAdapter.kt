package dev.shibasis.reaktor.telemetry

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.analytics.FirebaseAnalytics
import dev.gitlive.firebase.analytics.analytics
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature

/**
 * Cross-platform analytics using the GitLive Firebase Kotlin SDK.
 *
 * Wraps [FirebaseAnalytics] from `dev.gitlive:firebase-analytics`, which provides KMP
 * implementations for Android, iOS, JS, JVM, macOS, and tvOS.
 *
 * ## Usage
 * ```kotlin
 * // At startup (Firebase must be initialized first)
 * Feature.Analytics = FirebaseAnalyticsAdapter()
 *
 * // With extra sinks (e.g. custom HTTP ingest for ML pipelines)
 * Feature.Analytics = FirebaseAnalyticsAdapter(extraSinks = listOf(myHttpSink))
 *
 * // Anywhere
 * Feature.Analytics?.logEvent("screen_view", mapOf("screen_name" to "/home"))
 * ```
 */
open class FirebaseAnalyticsAdapter(
        analyticsProvider: () -> FirebaseAnalytics = { Firebase.analytics },
        private val extraSinks: List<AnalyticsSink> = emptyList(),
) {
    val analytics by lazy { analyticsProvider() }

    open fun logEvent(name: String, parameters: Map<String, Any>? = null) {
        analytics.logEvent(name, parameters)
        fanOut { it.logEvent(name, parameters) }
    }

    open fun setUserProperty(name: String, value: String) {
        analytics.setUserProperty(name, value)
        fanOut { it.setUserProperty(name, value) }
    }

    open fun setUserId(userId: String?) {
        analytics.setUserId(userId)
        fanOut { it.setUserId(userId) }
    }

    open fun setAnalyticsCollectionEnabled(enabled: Boolean) {
        analytics.setAnalyticsCollectionEnabled(enabled)
        fanOut { it.setEnabled(enabled) }
    }

    private inline fun fanOut(block: (AnalyticsSink) -> Unit) {
        for (sink in extraSinks) {
            runCatching { block(sink) }
        }
    }
}

var Feature.Analytics by CreateSlot<FirebaseAnalyticsAdapter>()
