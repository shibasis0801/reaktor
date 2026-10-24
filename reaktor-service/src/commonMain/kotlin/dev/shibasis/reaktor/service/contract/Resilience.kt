package dev.shibasis.reaktor.service.contract

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class Retry(
    val attempts: Int = 3,
    val backoff: List<Duration> = listOf(400.milliseconds, 1200.milliseconds),
    val retryAfterCap: Duration = 4.seconds,
    val budget: RetryBudget = RetryBudget(),
) : Interceptor {
    init {
        require(attempts >= 1 && backoff.isNotEmpty())
    }

    override val position: Int get() = Position.Retry
    override val sides: Set<Side> get() = ClientSide
    override val families: Set<Family> get() = CallsAndPublishes

    override suspend fun <In, R> intercept(exchange: Exchange<In, R>, next: Next<In, R>): R {
        while (true) {
            val failure = try {
                return next(exchange).also { budget.succeeded() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CallFailure) {
                failure
            }
            val safe = (failure is Unavailable && failure.status != 501) || (failure is OutcomeUnknown && exchange.retriable)
            if (!safe || exchange.attempt >= attempts) throw failure
            val wait = (failure as? Unavailable)?.retryAfter?.coerceAtMost(retryAfterCap)
                ?: (backoff[(exchange.attempt - 1).coerceAtMost(backoff.lastIndex)] * Random.nextDouble(0.7, 1.3))
            val remaining = exchange.remaining
            if (remaining != null && remaining <= wait) throw failure
            if (!budget.spend()) throw failure
            delay(wait)
            exchange.attempt += 1
        }
    }

    private companion object {
        val ClientSide = setOf(Side.Client)
        val CallsAndPublishes = setOf(Family.Call, Family.Publish)
    }
}

class RetryBudget(private val tokens: Int = 10, private val refillPerSuccess: Double = 0.1) {
    private val milli = atomic(tokens * 1000)
    private val ceiling = tokens * 1000
    private val refill = (refillPerSuccess * 1000).toInt()

    val available: Double get() = milli.value / 1000.0

    fun spend(): Boolean {
        while (true) {
            val current = milli.value
            if (current < 1000) return false
            if (milli.compareAndSet(current, current - 1000)) return true
        }
    }

    fun succeeded() {
        while (true) {
            val current = milli.value
            if (current >= ceiling) return
            if (milli.compareAndSet(current, minOf(ceiling, current + refill))) return
        }
    }
}
