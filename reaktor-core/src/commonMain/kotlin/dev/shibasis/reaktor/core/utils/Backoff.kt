package dev.shibasis.reaktor.core.utils

import kotlinx.coroutines.delay
import kotlin.time.Duration

class Backoff(private val first: Duration, private val cap: Duration, private val factor: Double = 2.0) {
    private var wait = first

    fun next(): Duration = wait.also { wait = (wait * factor).coerceAtMost(cap) }

    suspend fun pause() = delay(next())

    fun reset() {
        wait = first
    }
}
