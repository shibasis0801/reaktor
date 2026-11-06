package dev.shibasis.reaktor.work.host

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.work.execution.WorkScheduler
import dev.shibasis.reaktor.work.run.WorkScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

class LiveScheduler(
    private val lifetime: CoroutineScope,
    private val host: WorkHost,
    private val clock: Clock = Clock.System,
) : WorkScheduler {
    private val mutex = Mutex()
    private val timers = mutableMapOf<WorkScope, Job>()
    private val started = mutableMapOf<WorkScope, Long>()

    override suspend fun arm(scope: WorkScope, nextWakeAtMillis: Long?): Unit = mutex.withLock {
        timers.remove(scope)?.cancel()
        if (nextWakeAtMillis == null) return@withLock
        val at = maxOf(nextWakeAtMillis, (started[scope] ?: 0) + Spacing)
        lateinit var timer: Job
        timer = lifetime.launch(start = CoroutineStart.LAZY) {
            delay(at - clock.now().toEpochMilliseconds())
            mutex.withLock {
                if (timers[scope] === timer) timers.remove(scope)
                started[scope] = clock.now().toEpochMilliseconds()
            }
            try {
                host.drain(scope)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Logger.w("ReaktorWork") { "A live drain failed; trying again shortly: ${failure.message}" }
                arm(scope, clock.now().toEpochMilliseconds() + RetryAfterFailure)
            }
        }
        timers[scope] = timer
        timer.start()
    }
}

private const val Spacing = 1_000L
private const val RetryAfterFailure = 30_000L
