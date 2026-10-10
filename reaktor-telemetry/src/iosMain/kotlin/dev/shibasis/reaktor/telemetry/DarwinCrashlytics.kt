package dev.shibasis.reaktor.telemetry

import dev.shibasis.reaktor.core.framework.AppleFirebaseRuntime
import platform.Foundation.NSError
import platform.Foundation.NSLocalizedDescriptionKey

interface AppleCrashReporter {
    fun recordException(error: NSError)
    fun log(message: String)
    fun setUserId(userId: String)
    fun setCustomKey(key: String, value: String)
}

object AppleCrashReporterRuntime {
    private var implementation: AppleCrashReporter? = null
    fun install(implementation: AppleCrashReporter) { this.implementation = implementation }
    fun current(): AppleCrashReporter = checkNotNull(implementation) {
        "Install the crash reporting SDK before enabling Crashlytics"
    }
}

class DarwinCrashlytics : CrashlyticsAdapter<Unit>(Unit) {
    private val reporter get() = AppleCrashReporterRuntime.current().also { AppleFirebaseRuntime.configure() }
    override fun recordException(throwable: Throwable) = reporter.recordException(NSError.errorWithDomain("Kotlin", 1,
        mapOf(NSLocalizedDescriptionKey to throwable.toString(), "stacktrace" to throwable.stackTraceToString())))
    override fun log(message: String) = reporter.log(message)
    override fun setUserId(userId: String) = reporter.setUserId(userId)
    override fun writeCustomKey(key: String, value: String) = reporter.setCustomKey(key, value)
}
