package dev.shibasis.reaktor.conductor.workspace

import kotlinx.serialization.Serializable

@Serializable
data class PlanLease(val by: BoardActor, val takenAt: Long, val expiresAt: Long) {
    fun until(activityAt: Long?): Long = maxOf(expiresAt, activityAt?.let { it + DurationMillis } ?: expiresAt)

    companion object { const val DurationMillis = 600_000L }
}
