package dev.shibasis.reaktor.work.execution

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.graph.step.StepFailed
import dev.shibasis.reaktor.graph.step.StepNode
import dev.shibasis.reaktor.graph.step.StepScope
import dev.shibasis.reaktor.graph.step.StepState
import dev.shibasis.reaktor.work.run.MaxPayloadBytes
import dev.shibasis.reaktor.work.run.RunRecord
import dev.shibasis.reaktor.work.run.StepRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface Outcome {
    @Serializable @SerialName("succeeded")
    data class Succeeded(val result: String) : Outcome

    @Serializable @SerialName("skipped")
    data object Skipped : Outcome

    @Serializable @SerialName("failed")
    data class Failed(val reason: String) : Outcome

    @Serializable @SerialName("interrupted")
    data class Interrupted(val reason: String) : Outcome

    @Serializable @SerialName("undone")
    data object Undone : Outcome
}

internal suspend fun <T : Any> StepNode<T>.perform(record: RunRecord, claim: StepRecord, context: StepScope): Outcome {
    val values = try {
        inputs.map { input -> json.decodeFromString(input.serializer, checkNotNull(record.step(input.name).result)) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (unreadable: Exception) {
        return Outcome.Failed("An input of '$name' could not be read: ${unreadable.message}")
    }
    return try {
        val produced = withTimeoutOrNull(timeout) {
            Produced((if (claim.reconcile) reconcile?.invoke(context, values) else null) ?: body(context, values))
        }
        val value = produced?.value
        if (produced == null) Outcome.Interrupted("The attempt timed out after $timeout")
        else if (value == null) Outcome.Skipped
        else json.encodeToString(serializer, value).let { result ->
            if (result.encodeToByteArray().size > MaxPayloadBytes) Outcome.Failed("The result of '$name' exceeds 64 KB; return a reference to it")
            else Outcome.Succeeded(result)
        }
    } catch (failed: StepFailed) {
        Outcome.Failed(failed.reason)
    } catch (cancelled: CancellationException) {
        currentCoroutineContext().ensureActive()
        Outcome.Interrupted("The attempt was cancelled: ${cancelled.message}")
    } catch (error: Throwable) {
        Outcome.Interrupted("The attempt failed: ${error.message ?: error::class.simpleName}")
    }
}

internal suspend fun StepNode<*>.revert(claim: StepRecord, context: StepScope): Outcome = try {
    if (withTimeoutOrNull(timeout) { reverse(context, checkNotNull(claim.result)) } != null) Outcome.Undone
    else Outcome.Interrupted("The undo timed out after $timeout")
} catch (cancelled: CancellationException) {
    currentCoroutineContext().ensureActive()
    Outcome.Interrupted("The undo was cancelled: ${cancelled.message}")
} catch (error: Throwable) {
    Outcome.Interrupted("The undo failed: ${error.message ?: error::class.simpleName}")
}

internal fun Outcome.applied(node: StepNode<*>, step: StepRecord, undoing: Boolean, nowMillis: Long): StepRecord = when (this) {
    is Outcome.Succeeded -> step.copy(state = StepState.Succeeded, result = result, reason = null, reconcile = false, dueAtMillis = null, endedAtMillis = nowMillis)
    Outcome.Skipped -> step.copy(state = StepState.Skipped, result = null, reason = null, reconcile = false, dueAtMillis = null, endedAtMillis = nowMillis)
    is Outcome.Failed -> step.copy(state = StepState.Failed, endedAtMillis = nowMillis, reason = reason)
    is Outcome.Interrupted -> if (undoing) undoInterrupted(step, nowMillis, reason) else node.interrupted(step, nowMillis, reason)
    Outcome.Undone -> step.copy(state = StepState.Undone, reason = null, dueAtMillis = null)
}.copy(leaseOwner = null, leaseUntilMillis = null)

private class Produced<T>(val value: T)
