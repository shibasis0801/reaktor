package dev.shibasis.reaktor.telemetry

import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature

interface AnalyticsAdapter {
    fun logEvent(name: String, parameters: Map<String, Any>? = null)
    fun setUserProperty(name: String, value: String)
    fun setUserId(userId: String?)
    fun setAnalyticsCollectionEnabled(enabled: Boolean)
}

var Feature.Analytics by CreateSlot<AnalyticsAdapter>()
