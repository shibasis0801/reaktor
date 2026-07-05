package dev.shibasis.reaktor.notification

internal object Clock {
    fun nowEpochMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}
