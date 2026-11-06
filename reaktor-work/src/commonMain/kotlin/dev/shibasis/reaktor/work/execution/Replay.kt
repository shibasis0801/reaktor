package dev.shibasis.reaktor.work.execution

import dev.shibasis.reaktor.work.definition.SignalNode
import dev.shibasis.reaktor.work.definition.StepNode
import dev.shibasis.reaktor.work.definition.StepScope
import dev.shibasis.reaktor.work.definition.WorkflowPlan
import dev.shibasis.reaktor.work.run.RunRecord
import dev.shibasis.reaktor.work.run.RunState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.time.Clock

internal interface RecordedSteps {
    suspend fun <T> record(name: String, serializer: KSerializer<T>, limitMillis: Long, produce: suspend () -> T): T
    suspend fun delivery(name: String, untilMillis: Long): Delivery?
}

@Serializable
internal sealed interface Delivery {
    @Serializable @SerialName("signal")
    data class Signal(val name: String, val value: String) : Delivery

    @Serializable @SerialName("cancel")
    data object Cancel : Delivery
}

internal class Replay(
    private val plan: WorkflowPlan,
    private val steps: RecordedSteps,
    private val clock: Clock,
    private val mirror: suspend (RunRecord) -> Unit,
    start: RunRecord,
) {
    private var record = start
    private var now = start.createdAtMillis
    private var waits = 0

    suspend fun run(): RunRecord {
        if (record.digest != plan.digest) {
            return change(record.copy(state = RunState.Quarantined, nextWakeAtMillis = null, reason = "Workflow '${plan.id}' changed after this run started"))
        }
        while (true) {
            val advance = plan.advance(record, now, ReplayOwner, Parallelism)
            change(advance.record)
            if (record.state.finished) return record
            check(advance.spawn.isEmpty() && advance.cancel.isEmpty()) { "Workflow '${plan.id}' runs child workflows, which this host does not run" }
            if (advance.claims > 0) settle(advance) else wait()
        }
    }

    private suspend fun settle(advance: Advance) {
        val claimed = record
        val attempts = advance.claimed.map { it to false } + listOfNotNull(advance.undoing).map { it to true }
        val settled = coroutineScope {
            attempts.map { (node, undoing) -> async { if (undoing) undo(claimed, node) else attempt(claimed, node) } }.awaitAll()
        }
        val claimedAt = now
        now = maxOf(now, settled.maxOf { it.atMillis })
        change(record.copy(steps = record.steps + attempts.zip(settled).map { (attempt, done) ->
            val (node, undoing) = attempt
            node.name to done.outcome.applied(node, record.step(node.name), undoing, maxOf(claimedAt, done.atMillis))
        }))
    }

    private suspend fun attempt(claimed: RunRecord, node: StepNode<*>): Settled {
        val claim = claimed.step(node.name)
        val scope = StepScope(effectKey(claimed.scope, claimed.id, node.name), claim.attempt, claim.checkpoint) {
            throw UnsupportedOperationException("Checkpoints are not durable on this host")
        }
        return steps.record("${node.name}#${claim.attempt}", Settled.serializer(), node.leaseMillis) {
            Settled(node.perform(claimed, claim, scope), currentMillis())
        }
    }

    private suspend fun undo(claimed: RunRecord, node: StepNode<*>): Settled {
        val claim = claimed.step(node.name)
        val scope = StepScope(effectKey(claimed.scope, claimed.id, node.name), claim.undoAttempt, claim.checkpoint) { error("An undo cannot checkpoint") }
        return steps.record("${node.name}~undo#${claim.undoAttempt}", Settled.serializer(), node.leaseMillis) {
            Settled(node.revert(claim, scope), currentMillis())
        }
    }

    private suspend fun wait() {
        val due = record.nextWakeAtMillis
        check(due == null || due > now) { "Run '${record.id}' stopped making progress" }
        val wait = waits++
        val delivery = steps.delivery("wait#$wait", minOf(due ?: Long.MAX_VALUE, now + LongestWaitMillis))
        now = maxOf(now, steps.record("clock#$wait", Long.serializer(), ClockReadMillis) { currentMillis() })
        when (delivery) {
            null -> Unit
            is Delivery.Signal -> if (plan.node(delivery.name) is SignalNode<*> && delivery.name !in record.signals) {
                change(record.copy(signals = record.signals + (delivery.name to delivery.value)))
            }
            Delivery.Cancel -> if (!record.cancelRequested) change(record.copy(cancelRequested = true))
        }
    }

    private suspend fun change(next: RunRecord): RunRecord {
        if (next != record) {
            record = next.after(record, now)
            mirror(record)
        }
        return record
    }

    private fun currentMillis() = clock.now().toEpochMilliseconds()
}

@Serializable
private class Settled(val outcome: Outcome, val atMillis: Long)

internal const val LongestWaitMillis = 30L * 24 * 60 * 60 * 1_000
private const val ClockReadMillis = 10_000L
private const val ReplayOwner = "replay"
