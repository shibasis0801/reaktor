package dev.shibasis.reaktor.work.execution

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthDecision
import dev.shibasis.reaktor.auth.kernel.LocalAuthorizer
import dev.shibasis.reaktor.graph.step.Output
import dev.shibasis.reaktor.graph.step.StepNode
import dev.shibasis.reaktor.graph.step.StepScope
import dev.shibasis.reaktor.graph.step.StepState
import dev.shibasis.reaktor.work.definition.Spawns
import dev.shibasis.reaktor.work.definition.WorkflowPlan
import dev.shibasis.reaktor.work.run.ChildRecord
import dev.shibasis.reaktor.work.run.MaxPayloadBytes
import dev.shibasis.reaktor.work.run.RunRecord
import dev.shibasis.reaktor.work.run.RunState
import dev.shibasis.reaktor.work.run.StepRecord
import dev.shibasis.reaktor.work.run.WorkScope
import dev.shibasis.reaktor.work.store.Admission
import dev.shibasis.reaktor.work.store.WorkStore
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

data class Drain(val runs: Int, val steps: Int)

internal class Executor(
    private val scope: WorkScope,
    private val store: WorkStore,
    private val plans: Map<String, WorkflowPlan>,
    private val authority: suspend (WorkScope) -> AuthContext?,
    private val owner: String,
    private val clock: Clock,
    private val armed: suspend () -> Unit,
) {
    private val permits = Semaphore(Parallelism)
    private val mutex = Mutex()
    private val decisions = mutableMapOf<String, Boolean>()
    private val troubled = mutableSetOf<String>()
    private var executed = 0

    private val now: Long get() = clock.now().toEpochMilliseconds()

    suspend fun drain(): Drain {
        var runs = 0
        repeat(MaxRounds) {
            val skipped = mutex.withLock { troubled.toSet() }
            val due = store.due(scope, now, DueBatch).filter { it.id !in skipped }
            if (due.isEmpty()) return Drain(runs, mutex.withLock { executed })
            runs += due.size
            supervisorScope { due.forEach { record -> launch { guard(record.id) { visit(record) } } } }
        }
        return Drain(runs, mutex.withLock { executed })
    }

    suspend fun drive(id: String) = supervisorScope {
        store.get(scope, id)?.let { record -> launch { guard(id) { visit(record) } } }
    }

    private suspend fun CoroutineScope.visit(record: RunRecord) {
        if (record.state.finished) return notify(record)
        advance(record) { it }?.let { follow(it) }
    }

    private suspend fun CoroutineScope.follow(advance: Advance) {
        val record = advance.record
        if (advance.claims > 0) armed()
        advance.claimed.forEach { node -> launch { guard(record.id) { attempt(record, node) } } }
        advance.undoing?.let { node -> launch { guard(record.id) { undo(record, node) } } }
        if (advance.spawn.isNotEmpty()) admit(record, advance.spawn)
        advance.cancel.forEach { id -> cancelChild(id) }
        if (record.state.finished) notify(record)
    }

    private suspend fun advance(initial: RunRecord, change: (RunRecord) -> RunRecord?): Advance? {
        var stored = initial
        repeat(MaxConflicts) {
            val modified = change(stored) ?: return null
            val held = hold()
            val planned = try { plan(modified, held) } catch (failure: Throwable) {
                release(held)
                throw failure
            }
            val next = if (planned.record == stored) stored else planned.record.after(stored, now)
            if (next === stored || store.replace(stored, next)) {
                release(held - planned.claims)
                return Advance(next, planned.claimed, planned.undoing, planned.spawn, planned.cancel)
            }
            release(held)
            stored = store.get(scope, initial.id) ?: return null
        }
        error("Run '${initial.id}' kept changing while this host advanced it")
    }

    private suspend fun plan(record: RunRecord, capacity: Int): Advance {
        if (record.state.finished || record.state == RunState.Quarantined) return Advance(record)
        val plan = plans[record.workflow]
            ?: return Advance(record.quarantined("Workflow '${record.workflow}' is not installed in this host"))
        if (plan.digest != record.digest) return Advance(record.quarantined("Workflow '${record.workflow}' changed after this run started"))
        if (!authorized(plan)) return Advance(record.copy(state = RunState.Blocked,
            nextWakeAtMillis = now + BlockedRecheck.inWholeMilliseconds, reason = "The authority of this scope is unavailable or denied"))
        return plan.advance(record, now, owner, capacity)
    }

    private suspend fun CoroutineScope.attempt(record: RunRecord, node: StepNode<*>) {
        val claim = record.step(node.name)
        val context = StepScope(effectKey(scope, record.id, node.name), claim.attempt, claim.checkpoint) { value -> checkpoint(record.id, node, claim.fence, value) }
        val outcome = try { node.perform(record, claim, context) } finally { permits.release() }
        commit(record.id, node, claim.fence) { step -> outcome.applied(node, step, undoing = false, now) }
    }

    private suspend fun CoroutineScope.undo(record: RunRecord, node: StepNode<*>) {
        val claim = record.step(node.name)
        val context = StepScope(effectKey(scope, record.id, node.name), claim.undoAttempt, claim.checkpoint) { error("An undo cannot checkpoint") }
        val outcome = try { node.revert(claim, context) } finally { permits.release() }
        commit(record.id, node, claim.fence) { step -> outcome.applied(node, step, undoing = true, now) }
    }

    private suspend fun CoroutineScope.commit(id: String, node: StepNode<*>, fence: Long, change: (StepRecord) -> StepRecord) {
        val stored = store.get(scope, id) ?: return
        val advance = advance(stored) { record ->
            val step = record.step(node.name)
            if (step.fence != fence || (step.state != StepState.Running && step.state != StepState.Undoing)) null
            else record.copy(steps = record.steps + (node.name to change(step).copy(leaseOwner = null, leaseUntilMillis = null)))
        } ?: return
        mutex.withLock { executed++ }
        follow(advance)
    }

    private suspend fun checkpoint(id: String, node: StepNode<*>, fence: Long, value: String) {
        require(value.encodeToByteArray().size <= MaxPayloadBytes) { "A checkpoint is limited to 64 KB" }
        repeat(MaxConflicts) {
            val stored = store.get(scope, id) ?: throw ClaimLost()
            val step = stored.step(node.name)
            if (step.fence != fence || step.state != StepState.Running) throw ClaimLost()
            val renewed = step.copy(checkpoint = value, leaseUntilMillis = now + node.leaseMillis)
            if (store.replace(stored, stored.copy(steps = stored.steps + (node.name to renewed)).after(stored, now))) return
        }
        throw ClaimLost()
    }

    private suspend fun CoroutineScope.admit(parent: RunRecord, spawn: List<Pair<Output<*>, ChildRecord>>) {
        val outcomes = spawn.associate { (node, child) -> child.id to admitChild(parent, node, child) }
        val advance = advance(parent) { record ->
            record.copy(steps = record.steps.mapValues { (_, step) ->
                if (step.children.none { it.id in outcomes }) step
                else step.copy(children = step.children.map { child -> if (child.admitted) child else outcomes[child.id] ?: child })
            })
        } ?: return
        follow(advance)
        outcomes.filterValues { it.state == null }.keys.forEach { id ->
            launch { guard(id) { store.get(scope, id)?.let { visit(it) } } }
        }
    }

    private suspend fun admitChild(parent: RunRecord, node: Output<*>, child: ChildRecord): ChildRecord {
        val target = checkNotNull((node as? Spawns)?.target) { "Node '${node.name}' runs no child workflow" }
        val plan = plans[target.workflowId] ?: return child.copy(admitted = true, state = RunState.Failed)
        val record = RunRecord(scope, child.id, plan.id, plan.digest, child.input, createdAtMillis = now, parent = parent.id, links = parent.links,
            steps = mapOf(target.input.name to StepRecord(StepState.Succeeded, result = child.input, startedAtMillis = now, endedAtMillis = now)))
        return when (val admission = store.admit(record)) {
            is Admission.Conflict -> child.copy(admitted = true, state = RunState.Failed)
            is Admission.Accepted -> if (!admission.record.state.finished) child.copy(admitted = true)
                else child.copy(admitted = true, state = admission.record.state, result = output(admission.record))
        }
    }

    private fun output(record: RunRecord): String? =
        if (record.state != RunState.Succeeded) null else plans[record.workflow]?.workflow?.output?.let { record.step(it.name).result }

    private suspend fun CoroutineScope.cancelChild(id: String) {
        val stored = store.get(scope, id) ?: return
        if (stored.state.finished || stored.cancelRequested) return
        advance(stored) { record -> if (record.state.finished || record.cancelRequested) null else record.copy(cancelRequested = true) }
            ?.let { follow(it) }
    }

    private suspend fun CoroutineScope.notify(child: RunRecord) {
        val parentId = child.parent ?: return
        if (child.parentNotified) return
        val result = output(child)
        store.get(scope, parentId)?.let { stored ->
            advance(stored) { parent ->
                parent.copy(steps = parent.steps.mapValues { (_, step) ->
                    if (step.children.none { it.id == child.id }) step
                    else step.copy(children = step.children.map { if (it.id == child.id) it.copy(admitted = true, state = child.state, result = result) else it })
                })
            }?.let { follow(it) }
        }
        advance(child) { record -> if (record.parentNotified) null else record.copy(parentNotified = true, nextWakeAtMillis = null) }
    }

    private suspend fun authorized(plan: WorkflowPlan): Boolean {
        mutex.withLock { decisions[plan.id] }?.let { return it }
        val auth = authority(scope)
        val allowed = auth != null && scope.matches(auth) && LocalAuthorizer.authorize(auth, plan.workflow.requirement) is AuthDecision.Allow
        mutex.withLock { decisions[plan.id] = allowed }
        return allowed
    }

    private suspend fun guard(id: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            mutex.withLock { troubled += id }
            Logger.w("ReaktorWork") { "Run '$id' could not advance: ${failure.message}" }
        }
    }

    private fun hold(): Int {
        var held = 0
        while (held < Parallelism && permits.tryAcquire()) held++
        return held
    }

    private fun release(count: Int) = repeat(count) { permits.release() }

    private fun RunRecord.quarantined(reason: String) = copy(state = RunState.Quarantined, nextWakeAtMillis = null, reason = reason)
}

private class ClaimLost : CancellationException("This attempt's claim was lost")

private const val MaxRounds = 16
private const val DueBatch = 32
private const val MaxConflicts = 64
