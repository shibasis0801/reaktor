package dev.shibasis.reaktor.telemetry

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature

abstract class CrashlyticsAdapter<Controller>(controller: Controller): Adapter<Controller>(controller) {
    abstract fun recordException(throwable: Throwable)
    abstract fun log(message: String)
    abstract fun setUserId(userId: String)
}


var Feature.Crashlytics by CreateSlot<CrashlyticsAdapter<*>>()
