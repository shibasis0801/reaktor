package dev.shibasis.reaktor.work

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthDecision
import dev.shibasis.reaktor.auth.kernel.AuthRequirement
import dev.shibasis.reaktor.auth.kernel.LocalAuthorizer
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.traversal.Members
import dev.shibasis.reaktor.portgraph.traversal.Step
import dev.shibasis.reaktor.portgraph.traversal.depthFirst
import dev.shibasis.reaktor.work.definition.Durable
import dev.shibasis.reaktor.work.definition.Output
import dev.shibasis.reaktor.work.definition.SignalNode
import dev.shibasis.reaktor.work.definition.StepNode
import dev.shibasis.reaktor.work.definition.WorkflowPlan
import dev.shibasis.reaktor.work.execution.Drain
import dev.shibasis.reaktor.work.execution.Executor
import dev.shibasis.reaktor.work.execution.RequestStore
import dev.shibasis.reaktor.work.execution.WorkScheduler
import dev.shibasis.reaktor.work.run.MaxPayloadBytes
import dev.shibasis.reaktor.work.run.RunLinks
import dev.shibasis.reaktor.work.run.RunRecord
import dev.shibasis.reaktor.work.run.RunState
import dev.shibasis.reaktor.work.run.StepRecord
import dev.shibasis.reaktor.work.run.StepState
import dev.shibasis.reaktor.work.run.WorkScope
import dev.shibasis.reaktor.work.store.Admission
import dev.shibasis.reaktor.work.store.WorkStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.Clock

var Feature.Work by CreateSlot<Work>()

class Work(
    graph: Graph,
    val scope: WorkScope,
    val store: WorkStore,
    private val scheduler: WorkScheduler,
    private val authority: suspend (WorkScope) -> AuthContext?,
    private val owner: String,
    private val clock: Clock = Clock.System,
) : BasicNode(graph) {
    init { require(owner.isNotBlank() && owner.length <= 256) { "A Work host needs an owner name of 1 to 256 characters" } }

    private val draining = Mutex()

    @Volatile
    private var planned: Map<Durable<*, *>, WorkflowPlan> = emptyMap()

    private val now: Long get() = clock.now().toEpochMilliseconds()

    suspend fun <I : Any, O : Any> start(workflow: Durable<I, O>, input: I, key: String, links: RunLinks = RunLinks()): Run<O> {
        admit(workflow, input, key, links, awake = true)
        rearmQuietly()
        return Run(this, key, workflow.output)
    }

    internal suspend fun <I : Any> admit(workflow: Durable<I, *>, input: I, key: String, links: RunLinks, awake: Boolean): RunRecord =
        when (val admission = store.admit(prepare(workflow, input, key, links, awake))) {
            is Admission.Conflict -> throw RunConflict(key)
            is Admission.Accepted -> admission.record
        }

    private suspend fun <I : Any> prepare(workflow: Durable<I, *>, input: I, key: String, links: RunLinks, awake: Boolean): RunRecord {
        require(key.isNotBlank() && key.length <= 512 && key.none { it == '/' || it == '#' }) {
            "A run key needs 1 to 512 characters and no '/' or '#'"
        }
        val plan = plans()[workflow.workflowId]?.takeIf { it.workflow === workflow }
            ?: throw IllegalArgumentException("Workflow '${workflow.workflowId}' is not part of this Work node's graph")
        authorize(workflow.requirement)
        val encoded = json.encodeToString(workflow.input.serializer, input)
        require(encoded.encodeToByteArray().size <= MaxPayloadBytes) { "A run input is limited to 64 KB; pass a reference to larger data" }
        val createdAt = now
        return RunRecord(scope, key, plan.id, plan.digest, encoded, createdAtMillis = createdAt, links = links,
            nextWakeAtMillis = createdAt.takeIf { awake },
            steps = mapOf(workflow.input.name to StepRecord(StepState.Succeeded, result = encoded, startedAtMillis = createdAt, endedAtMillis = createdAt)))
    }

    internal fun authorizeSignal(wait: SignalNode<*>, sender: AuthContext?) {
        wait.sender?.let { requirement ->
            require(sender != null && LocalAuthorizer.authorize(sender, requirement) is AuthDecision.Allow) {
                "This sender may not deliver signal '${wait.name}'"
            }
        }
    }

    suspend fun <I : Any, O : Any> execute(workflow: Durable<I, O>, input: I, key: String, links: RunLinks = RunLinks()): Run<O> {
        if (store.get(scope, key) == null) {
            val plans = plans()
            val record = prepare(workflow, input, key, links, awake = true)
            val request = RequestStore(store, plans.getValue(record.workflow), record)
            Executor(scope, request, plans, authority, owner, clock) { if (request.stored) rearmQuietly() }.drive(key)
            if (!request.persist()) throw RunConflict(key)
        } else {
            admit(workflow, input, key, links, awake = true)
            executor().drive(key)
        }
        rearmQuietly()
        return Run(this, key, workflow.output)
    }

    fun <O : Any> run(workflow: Durable<*, O>, key: String): Run<O> = Run(this, key, workflow.output)

    suspend fun drain(): Drain = draining.withLock { executor().drain().also { rearm() } }

    suspend fun rearm() = scheduler.arm(scope, store.nextWake(scope))

    fun workflows(): List<Durable<*, *>> = graph.depthFirst(Members)
        .filterIsInstance<Step.Enter>()
        .map { it.element }
        .filterIsInstance<Durable<*, *>>()
        .toList()

    internal fun plans(): Map<String, WorkflowPlan> {
        val workflows = workflows()
        val repeated = workflows.groupBy { it.workflowId }.filterValues { it.size > 1 }.keys
        require(repeated.isEmpty()) { "Workflow ids must be unique in one Work graph: $repeated" }
        return workflows.associate { workflow ->
            workflow.workflowId to (planned[workflow] ?: WorkflowPlan(workflow).also { planned = planned + (workflow to it) })
        }
    }

    internal suspend fun <T : Any> signal(id: String, wait: SignalNode<T>, value: T, sender: AuthContext?): Boolean {
        authorizeSignal(wait, sender)
        val encoded = json.encodeToString(wait.serializer, value)
        require(encoded.encodeToByteArray().size <= MaxPayloadBytes) { "A signal is limited to 64 KB" }
        val delivered = update(id) { record ->
            require(record.workflow == wait.workflowId) { "Signal '${wait.name}' belongs to workflow '${wait.workflowId}'" }
            if (record.state.finished || wait.name in record.signals) null
            else record.copy(signals = record.signals + (wait.name to encoded), nextWakeAtMillis = now)
        }
        rearmQuietly()
        return delivered != null
    }

    internal suspend fun cancel(id: String, expectedRevision: Long? = null): RunRecord? = update(id) { record ->
        if (expectedRevision != null && record.revision != expectedRevision) null
        else if (record.state.finished || record.cancelRequested) null
        else record.copy(cancelRequested = true, nextWakeAtMillis = now)
    }.also { rearmQuietly() }

    internal suspend fun retry(id: String, expectedRevision: Long, path: String?): RunRecord? {
        val stored = store.get(scope, id) ?: return null
        val plan = plans()[stored.workflow] ?: return null
        authorize(plan.workflow.operatorRetry ?: throw IllegalStateException("Workflow '${plan.id}' does not allow operator retries"))
        if (stored.revision != expectedRevision) return null
        if (path == null) stored.steps.values.filter { it.state != StepState.Succeeded }.flatMap { it.children }
            .filter { it.state != null && it.state != RunState.Succeeded }
            .forEach { child -> store.get(scope, child.id)?.takeIf { it.state.finished }?.let { retry(it.id, it.revision, null) } }
        val retried = update(id) { record ->
            when {
                record.revision != expectedRevision -> null
                path != null -> record.steps[path]?.takeIf { it.state in Retriable }?.let { step ->
                    record.copy(steps = record.steps + (path to step.reset()), state = RunState.Running, nextWakeAtMillis = now, reason = null)
                }
                record.state == RunState.Failed || record.state == RunState.Blocked ||
                    (record.state == RunState.Quarantined && record.digest == plan.digest) -> record.copy(
                    steps = record.steps.mapValues { (_, step) -> if (step.state == StepState.Succeeded) step else step.reset() },
                    state = RunState.Running, cancelRequested = false, parentNotified = false, nextWakeAtMillis = now, reason = null)
                else -> null
            }
        }
        rearmQuietly()
        return retried
    }

    internal suspend fun reconcile(id: String, expectedRevision: Long, path: String): RunRecord? {
        val stored = store.get(scope, id) ?: return null
        val node = plans()[stored.workflow]?.node(path) as? StepNode<*>
        check(node?.reconcile != null) { "Step '$path' has no reconcile function" }
        return update(id) { record ->
            val step = record.steps[path]
            if (record.revision != expectedRevision || step?.state != StepState.Unknown) null
            else record.copy(steps = record.steps + (path to step.copy(state = StepState.Pending, attempt = 0, reconcile = true,
                fence = step.fence + 1, dueAtMillis = null, reason = "Operator reconcile")), state = RunState.Running, nextWakeAtMillis = now, reason = null)
        }.also { rearmQuietly() }
    }

    private suspend fun update(id: String, change: (RunRecord) -> RunRecord?): RunRecord? {
        repeat(MaxUpdates) {
            val stored = store.get(scope, id) ?: return null
            val next = change(stored)?.after(stored, now) ?: return null
            if (store.replace(stored, next)) return next
        }
        error("Run '$id' kept changing; try again")
    }

    private suspend fun authorize(requirement: AuthRequirement) {
        val auth = authority(scope)
        require(auth != null && scope.matches(auth)) { "The current authority does not match this Work scope" }
        val decision = LocalAuthorizer.authorize(auth, requirement)
        require(decision is AuthDecision.Allow) { (decision as AuthDecision.Deny).safeMessage }
    }

    private fun executor() = Executor(scope, store, plans(), authority, owner, clock, ::rearmQuietly)

    private suspend fun rearmQuietly() {
        try {
            rearm()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Logger.w("ReaktorWork") { "The run is stored; arming the next wake failed: ${failure.message}" }
        }
    }
}

class Run<O : Any> internal constructor(private val work: Work, val id: String, private val output: Output<O>) {
    val updates: Flow<RunRecord?> get() = work.store.observe(work.scope, id)

    suspend fun record(): RunRecord? = work.store.get(work.scope, id)

    suspend fun result(): O? = record()?.takeIf { it.state == RunState.Succeeded }?.step(output.name)?.result
        ?.let { json.decodeFromString(output.serializer, it) }

    suspend fun <T : Any> signal(wait: SignalNode<T>, value: T, sender: AuthContext? = null): Boolean = work.signal(id, wait, value, sender)

    suspend fun cancel(): Boolean = work.cancel(id) != null
}

class RunConflict(val id: String) : IllegalStateException("Run '$id' already holds different work")

private fun StepRecord.reset() = copy(state = StepState.Pending, attempt = 0, fence = fence + 1, reconcile = false, undoAttempt = 0,
    leaseOwner = null, leaseUntilMillis = null, dueAtMillis = null, result = null, children = emptyList(), endedAtMillis = null, reason = "Operator retry")

private val Retriable = setOf(StepState.Failed, StepState.TimedOut, StepState.Unknown, StepState.Cancelled, StepState.Undone, StepState.Skipped)
private const val MaxUpdates = 64
