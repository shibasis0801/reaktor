package dev.shibasis.reaktor.work.execution

import dev.shibasis.reaktor.work.definition.StepNode
import dev.shibasis.reaktor.work.run.StepRecord
import dev.shibasis.reaktor.work.run.StepState
import dev.shibasis.reaktor.work.run.WorkScope
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal const val MaxAttempts = 3
internal const val Parallelism = 4
internal val LeaseGrace = 30.seconds
internal val BlockedRecheck = 15.minutes

internal val StepNode<*>.leaseMillis: Long get() = (timeout + LeaseGrace).inWholeMilliseconds

internal fun backoff(attempt: Int): Long = (1_000L shl (attempt - 1).coerceIn(0, 6)).coerceAtMost(60_000)

internal fun StepNode<*>.interrupted(step: StepRecord, nowMillis: Long, reason: String): StepRecord {
    val released = step.copy(fence = step.fence + 1, leaseOwner = null, leaseUntilMillis = null, reason = reason)
    return when {
        step.attempt >= MaxAttempts && effect.retriable -> released.copy(state = StepState.Failed, endedAtMillis = nowMillis)
        step.attempt >= MaxAttempts -> released.copy(state = StepState.Unknown)
        effect.retriable -> released.copy(state = StepState.Pending, dueAtMillis = nowMillis + backoff(step.attempt))
        reconcile != null -> released.copy(state = StepState.Pending, reconcile = true, dueAtMillis = nowMillis + backoff(step.attempt))
        else -> released.copy(state = StepState.Unknown)
    }
}

internal fun undoInterrupted(step: StepRecord, nowMillis: Long, reason: String): StepRecord {
    val released = step.copy(fence = step.fence + 1, leaseOwner = null, leaseUntilMillis = null, reason = reason)
    return if (step.undoAttempt >= MaxAttempts) released.copy(state = StepState.Unknown)
    else released.copy(state = StepState.Succeeded, dueAtMillis = nowMillis + backoff(step.undoAttempt))
}

internal fun effectKey(scope: WorkScope, id: String, path: String): String =
    "reaktor-effect-v1\u0000${scope.key}\u0000$id\u0000$path".encodeUtf8().sha256().hex()
