package dev.shibasis.reaktor.work.execution

import dev.shibasis.reaktor.work.run.WorkScope

fun interface WorkScheduler {
    suspend fun arm(scope: WorkScope, nextWakeAtMillis: Long?)
}
