package dev.shibasis.reaktor.work.execution

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.work.definition.CallNode
import dev.shibasis.reaktor.work.definition.EachNode
import dev.shibasis.reaktor.work.definition.EitherNode
import dev.shibasis.reaktor.work.definition.InputNode
import dev.shibasis.reaktor.work.definition.LoopNode
import dev.shibasis.reaktor.work.definition.MaxFanOut
import dev.shibasis.reaktor.work.definition.Outcomes
import dev.shibasis.reaktor.work.definition.Output
import dev.shibasis.reaktor.work.definition.SignalNode
import dev.shibasis.reaktor.work.definition.StepNode
import dev.shibasis.reaktor.work.definition.TimerNode
import dev.shibasis.reaktor.work.definition.WorkflowPlan
import dev.shibasis.reaktor.work.run.ChildRecord
import dev.shibasis.reaktor.work.run.MaxPayloadBytes
import dev.shibasis.reaktor.work.run.MaxRunIdLength
import dev.shibasis.reaktor.work.run.RunRecord
import dev.shibasis.reaktor.work.run.RunState
import dev.shibasis.reaktor.work.run.StepRecord
import dev.shibasis.reaktor.work.run.StepState
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.jsonArray

internal class Advance(
    val record: RunRecord,
    val claimed: List<StepNode<*>> = emptyList(),
    val undoing: StepNode<*>? = null,
    val spawn: List<Pair<Output<*>, ChildRecord>> = emptyList(),
    val cancel: List<String> = emptyList(),
) {
    val claims: Int get() = claimed.size + if (undoing == null) 0 else 1
}

internal fun WorkflowPlan.advance(record: RunRecord, nowMillis: Long, owner: String, capacity: Int): Advance =
    if (record.state.finished || record.state == RunState.Quarantined) Advance(record)
    else Pass(this, record, nowMillis, owner, capacity).advance()

private class Pass(
    private val plan: WorkflowPlan,
    private val before: RunRecord,
    private val now: Long,
    private val owner: String,
    private var capacity: Int,
) : Outcomes {
    private val steps = before.steps.toMutableMap()
    private val claimed = mutableListOf<StepNode<*>>()
    private var undoing: StepNode<*>? = null
    private val spawn = mutableListOf<Pair<Output<*>, ChildRecord>>()
    private val cancel = mutableListOf<String>()
    private var wake: Long? = null
    private var deferred = false

    override fun state(node: Output<*>): StepState = step(node).state

    override fun <T : Any> value(node: Output<T>): T? = step(node).result?.let { json.decodeFromString(node.serializer, it) }

    fun advance(): Advance {
        expire()
        val failure = plan.nodes.firstOrNull { state(it) in Failing && it.name !in plan.handled }
        val state = if (before.cancelRequested || failure != null || before.state == RunState.Compensating) compensate() else forward()
        if (state.finished && before.parent != null && !before.parentNotified) wakeAt(now)
        val after = before.copy(steps = steps.toMap(), state = state, nextWakeAtMillis = wake, reason = reason(state, failure))
        return Advance(after, claimed, undoing, spawn, cancel)
    }

    private fun forward(): RunState {
        plan.nodes.forEach(::evaluate)
        val states = plan.nodes.map(::state)
        return when {
            states.all { it.settled } ->
                if (state(plan.workflow.output) == StepState.Succeeded) RunState.Succeeded else RunState.Failed
            claimed.isNotEmpty() || deferred || StepState.Running in states -> RunState.Running
            StepState.Unknown in states -> RunState.Blocked
            else -> RunState.Waiting
        }
    }

    private fun evaluate(node: Output<*>) {
        val step = step(node)
        try {
            when (step.state) {
                StepState.Running -> if (node is StepNode<*>) step.leaseUntilMillis?.let(::wakeAt) else settle(node, step)
                StepState.Waiting -> open(node, step)
                StepState.Pending -> when (readiness(node)) {
                    null -> Unit
                    false -> put(node, step.copy(state = StepState.Skipped, dueAtMillis = null, endedAtMillis = now))
                    true -> open(node, step)
                }
                else -> Unit
            }
        } catch (failure: Throwable) {
            put(node, step.copy(state = StepState.Failed, endedAtMillis = now, reason = "Node '${node.name}' could not be evaluated: ${failure.message}"))
        }
    }

    private fun open(node: Output<*>, step: StepRecord) {
        when (node) {
            is InputNode<*> -> put(node, step.copy(state = StepState.Succeeded, result = before.input,
                startedAtMillis = before.createdAtMillis, endedAtMillis = before.createdAtMillis))
            is StepNode<*> -> claim(node, step)
            is SignalNode<*> -> signal(node, step)
            is TimerNode -> timer(node, step)
            is EitherNode<*> -> put(node, step.copy(state = StepState.Succeeded, startedAtMillis = now, endedAtMillis = now,
                result = step(node.options.first { state(it) == StepState.Succeeded }).result))
            is CallNode<*, *>, is EachNode<*, *>, is LoopNode<*, *> -> children(node, step)
        }
    }

    private fun readiness(node: Output<*>): Boolean? {
        val condition = node.on.evaluate(this)
        val inputs = if (node is EitherNode<*>) oneSucceeded(node.options) else allSucceeded(node.inputs)
        return when {
            condition == false || inputs == false -> false
            condition == true && inputs == true -> true
            else -> null
        }
    }

    private fun allSucceeded(inputs: List<Output<*>>): Boolean? = when {
        inputs.any { state(it).settled && state(it) != StepState.Succeeded } -> false
        inputs.all { state(it) == StepState.Succeeded } -> true
        else -> null
    }

    private fun oneSucceeded(options: List<Output<*>>): Boolean? =
        if (options.any { !state(it).settled }) null else options.any { state(it) == StepState.Succeeded }

    private fun claim(node: StepNode<*>, step: StepRecord) {
        val due = step.dueAtMillis
        when {
            due != null && due > now -> wakeAt(due)
            capacity == 0 -> {
                deferred = true
                wakeAt(now)
            }
            else -> {
                capacity--
                val lease = now + node.leaseMillis
                put(node, step.copy(state = StepState.Running, attempt = step.attempt + 1, fence = step.fence + 1,
                    leaseOwner = owner, leaseUntilMillis = lease, dueAtMillis = null, startedAtMillis = step.startedAtMillis ?: now))
                claimed += node
                wakeAt(lease)
            }
        }
    }

    private fun signal(node: SignalNode<*>, step: StepRecord) {
        val waiting = if (step.state == StepState.Pending) step.copy(state = StepState.Waiting, startedAtMillis = now,
            dueAtMillis = node.timeout?.let { now + it.inWholeMilliseconds }) else step
        val delivered = before.signals[node.name]
        val due = waiting.dueAtMillis
        when {
            delivered != null -> put(node, waiting.copy(state = StepState.Succeeded, result = delivered, dueAtMillis = null, endedAtMillis = now))
            due != null && due <= now -> put(node, waiting.copy(state = StepState.TimedOut, dueAtMillis = null, endedAtMillis = now,
                reason = "No signal arrived before the timeout"))
            else -> {
                put(node, waiting)
                due?.let(::wakeAt)
            }
        }
    }

    private fun timer(node: TimerNode, step: StepRecord) {
        val waiting = if (step.state == StepState.Pending) step.copy(state = StepState.Waiting, startedAtMillis = now,
            dueAtMillis = now + node.after.inWholeMilliseconds) else step
        val due = waiting.dueAtMillis ?: now
        if (due <= now) put(node, waiting.copy(state = StepState.Succeeded, result = UnitResult, dueAtMillis = null, endedAtMillis = now))
        else {
            put(node, waiting)
            wakeAt(due)
        }
    }

    private fun children(node: Output<*>, step: StepRecord) {
        val source = checkNotNull(step(node.inputs.single()).result) { "Input '${node.inputs.single().name}' has no result" }
        val children = when (node) {
            is CallNode<*, *> -> listOf(ChildRecord(childId(node, ""), source))
            is LoopNode<*, *> -> listOf(ChildRecord(childId(node, "#0"), source))
            is EachNode<*, *> -> json.parseToJsonElement(source).jsonArray
                .also { require(it.size <= MaxFanOut) { "Each '${node.name}' received ${it.size} items, more than $MaxFanOut" } }
                .mapIndexed { index, item -> ChildRecord(childId(node, "/$index"), item.toString()) }
            else -> error("Node '${node.name}' runs no child workflows")
        }
        settle(node, step.copy(state = StepState.Running, startedAtMillis = now, children = children))
    }

    private fun settle(node: Output<*>, step: StepRecord) {
        val unadmitted = step.children.filter { !it.admitted && it.state == null }
        if (unadmitted.isNotEmpty()) {
            spawn += unadmitted.map { node to it }
            wakeAt(now)
        }
        if (step.children.any { it.state?.finished != true }) return put(node, step)
        val ended = step.children.firstOrNull { it.state != RunState.Succeeded }
        when {
            ended != null -> put(node, step.copy(state = StepState.Failed, endedAtMillis = now,
                reason = "Child run '${ended.id}' ended ${ended.state?.name?.lowercase()}"))
            node is LoopNode<*, *> -> loop(node, step)
            node is EachNode<*, *> -> {
                val result = step.children.joinToString(",", "[", "]") { it.result.orEmpty() }
                put(node, if (result.encodeToByteArray().size > MaxPayloadBytes)
                    step.copy(state = StepState.Failed, endedAtMillis = now, reason = "The results of '${node.name}' exceed 64 KB")
                else step.copy(state = StepState.Succeeded, result = result, endedAtMillis = now))
            }
            else -> put(node, step.copy(state = StepState.Succeeded, result = step.children.single().result, endedAtMillis = now))
        }
    }

    private fun loop(node: LoopNode<*, *>, step: StepRecord) {
        val last = step.children.last()
        val following = node.following(checkNotNull(last.result) { "Iteration '${last.id}' has no result" })
        when {
            following == null -> put(node, step.copy(state = StepState.Succeeded, result = last.result, endedAtMillis = now))
            step.children.size >= node.limit -> put(node, step.copy(state = StepState.Failed, endedAtMillis = now,
                reason = "Loop '${node.name}' reached its limit of ${node.limit} iterations"))
            else -> {
                val child = ChildRecord(childId(node, "#${step.children.size}"), following)
                put(node, step.copy(children = step.children + child))
                spawn += node to child
                wakeAt(now)
            }
        }
    }

    private fun compensate(): RunState {
        for (node in plan.nodes) {
            val step = step(node)
            when (step.state) {
                StepState.Pending, StepState.Waiting -> put(node, step.copy(state = StepState.Cancelled, dueAtMillis = null, endedAtMillis = now))
                StepState.Running -> if (node is StepNode<*>) step.leaseUntilMillis?.let(::wakeAt) else stop(node, step)
                StepState.Undoing -> step.leaseUntilMillis?.let(::wakeAt)
                else -> Unit
            }
        }
        if (plan.nodes.any { state(it) == StepState.Running || state(it) == StepState.Undoing }) return RunState.Compensating
        if (plan.nodes.any { state(it) == StepState.Unknown && step(it).undoAttempt > 0 }) return RunState.Blocked
        val next = plan.nodes.filterIsInstance<StepNode<*>>()
            .filter { it.undo != null && state(it) == StepState.Succeeded }
            .maxWithOrNull(compareBy({ step(it).endedAtMillis ?: 0 }, { plan.nodes.indexOf(it) }))
            ?: return if (before.cancelRequested) RunState.Cancelled else RunState.Failed
        val step = step(next)
        val due = step.dueAtMillis
        when {
            due != null && due > now -> wakeAt(due)
            capacity == 0 -> wakeAt(now)
            else -> {
                capacity--
                val lease = now + next.leaseMillis
                put(next, step.copy(state = StepState.Undoing, undoAttempt = step.undoAttempt + 1, fence = step.fence + 1,
                    leaseOwner = owner, leaseUntilMillis = lease, dueAtMillis = null))
                undoing = next
                wakeAt(lease)
            }
        }
        return RunState.Compensating
    }

    private fun stop(node: Output<*>, step: StepRecord) {
        val children = step.children.map { if (!it.admitted && it.state == null) it.copy(admitted = true, state = RunState.Cancelled) else it }
        val open = children.filter { it.state?.finished != true }
        cancel += open.map { it.id }
        put(node, if (open.isEmpty()) step.copy(state = StepState.Cancelled, children = children, endedAtMillis = now) else step.copy(children = children))
    }

    private fun expire() {
        for (node in plan.nodes) {
            if (node !is StepNode<*>) continue
            val step = step(node)
            val lease = step.leaseUntilMillis ?: continue
            if (lease > now) continue
            when (step.state) {
                StepState.Running -> put(node, node.interrupted(step, now, "The attempt's lease expired"))
                StepState.Undoing -> put(node, undoInterrupted(step, now, "The undo's lease expired"))
                else -> Unit
            }
        }
    }

    private fun reason(state: RunState, failure: Output<*>?): String? = when (state) {
        RunState.Cancelled -> "Cancelled"
        RunState.Failed, RunState.Compensating -> when {
            failure != null -> "Step '${failure.name}' ${if (state(failure) == StepState.TimedOut) "timed out" else "failed"}: ${step(failure).reason.orEmpty()}"
            before.cancelRequested -> "Cancelling"
            else -> "The output '${plan.workflow.output.name}' was ${state(plan.workflow.output).name.lowercase()}"
        }
        RunState.Blocked -> plan.nodes.firstOrNull { state(it) == StepState.Unknown }
            ?.let { "Step '${it.name}' needs attention: ${step(it).reason.orEmpty()}" }
        else -> null
    }

    private fun childId(node: Output<*>, suffix: String): String = "${before.id}/${node.name}$suffix".also {
        require(it.length <= MaxRunIdLength) { "Child runs nest too deep under '${before.id}'" }
    }

    private fun step(node: Output<*>): StepRecord = steps[node.name] ?: StepRecord()

    private fun put(node: Output<*>, record: StepRecord) {
        if (step(node) != record) steps[node.name] = record
    }

    private fun wakeAt(at: Long) {
        wake = minOf(wake ?: at, at)
    }
}

private val Failing = setOf(StepState.Failed, StepState.TimedOut)
private val UnitResult = json.encodeToString(Unit.serializer(), Unit)
