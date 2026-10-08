package dev.shibasis.reaktor.telemetry

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

abstract class CrashlyticsAdapter<Controller>(controller: Controller): Adapter<Controller>(controller) {
    private val contextLock = SynchronizedObject()

    abstract fun recordException(throwable: Throwable)
    abstract fun log(message: String)
    abstract fun setUserId(userId: String)
    protected abstract fun writeCustomKey(key: String, value: String)

    fun setContext(values: Map<String, String?>) {
        require(values.keys.all { it in ContextKeys })
        require(values.values.all { it == null || it.length <= 128 && ContextValue.matches(it) })
        synchronized(contextLock) {
            values.forEach { (key, value) -> writeCustomKey(key, value.orEmpty()) }
        }
    }

    fun clearContext() = synchronized(contextLock) {
        ContextKeys.forEach { writeCustomKey(it, "") }
        setUserId("")
    }

    companion object {
        val ContextKeys: Set<String> = setOf(
            "reaktor.source_revision", "reaktor.artifact", "reaktor.configuration",
            "reaktor.environment", "reaktor.platform", "reaktor.application_session",
            "reaktor.route", "reaktor.graph_digest",
        )
        private val ContextValue = Regex("[A-Za-z0-9._:/ ()-]*")
    }
}


var Feature.Crashlytics by CreateSlot<CrashlyticsAdapter<*>>()
