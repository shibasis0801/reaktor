package dev.shibasis.reaktor.io.network.websocket

import io.ktor.websocket.CloseReason
import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

interface ReconnectionStrategy {
    suspend fun shouldReconnect(throwable: Throwable? = null, closeReason: CloseReason? = null): Boolean
    suspend fun wait()
    fun reset()
}

class ExponentialBackoffStrategy(
    val minDelay: Duration = 1.seconds,
    val maxDelay: Duration = 10.seconds,
    val growFactor: Double = 1.3,
    val maxRetries: Int = Int.MAX_VALUE,
    val jitter: Duration = 3.seconds,
) : ReconnectionStrategy {
    private var waitTime = minDelay
    private var retries = 0

    override suspend fun shouldReconnect(throwable: Throwable?, closeReason: CloseReason?): Boolean {
        if (closeReason?.knownReason == CloseReason.Codes.NORMAL) return false
        if (waitTime < maxDelay) {
            waitTime = (waitTime * growFactor).coerceAtMost(maxDelay)
        }
        retries++
        return retries < maxRetries
    }

    override suspend fun wait() {
        val spread = jitter.inWholeMilliseconds
        delay(waitTime + (if (spread > 0) Random.nextLong(spread) else 0).milliseconds)
    }

    override fun reset() {
        retries = 0
        waitTime = minDelay
    }
}
