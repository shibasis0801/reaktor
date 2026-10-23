package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ActivityKind
import dev.shibasis.reaktor.conductor.NativeEffort
import dev.shibasis.reaktor.conductor.WorkflowAction
import dev.shibasis.reaktor.conductor.WorkflowContract
import dev.shibasis.reaktor.conductor.WorkflowStage
import dev.shibasis.reaktor.conductor.workflowDecision
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.ShapeAttributes
import dev.shibasis.reaktor.graph.core.node.ActorNode
import dev.shibasis.reaktor.graph.core.node.ActorKey
import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.Type
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

interface TaskOutcomes {
    val task: BoardTask
}

sealed interface CoordinatorMessage {
    data object Tick : CoordinatorMessage
    data class Settled(val task: String, val attempt: Int, val state: TaskState, val outcome: TaskOutcome, val transient: Boolean = false) : CoordinatorMessage
}

sealed interface TaskMessage {
    data object Start : TaskMessage
    data object Stop : TaskMessage
}

internal class TaskGraphRuntime(private val workspace: AgentWorkspace, private val owner: Graph) : AutoCloseable {
    private val scopes = ConcurrentHashMap<String, TaskGraphScope>()
    private val watch: Job = owner.coroutineScope.launch(Dispatchers.IO) {
        workspace.revisions().conflate().collect {
            try { sync() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {}
            delay(150)
        }
    }

    @Synchronized fun sync() {
        val graphs = workspace.board.graphs()
        val live = graphs.filter { graph ->
            graph.status == TaskGraphStatus.Running || scopes[graph.id]?.busy() == true ||
                graph.status == TaskGraphStatus.Paused && workspace.board.members(graph.id).any { it.graph?.state == TaskState.Running }
        }.map { it.id }.toSet()
        scopes.keys.filter { it !in live }.forEach { id -> scopes.remove(id)?.close() }
        live.forEach { id ->
            val scope = scopes.getOrPut(id) { owner.Graph { TaskGraphScope(it, workspace, id) } }
            scope.coordinator.trySend(CoordinatorMessage.Tick)
        }
    }

    fun shape(id: String): GraphShape {
        workspace.board.graph(id)
        scopes[id]?.let { return it.shape() }
        val scope = owner.Graph { TaskGraphScope(it, workspace, id, live = false) }
        return try { scope.shape() } finally { scope.close() }
    }

    override fun close() {
        watch.cancel()
        scopes.values.forEach { it.close() }
        scopes.clear()
    }
}

internal class TaskGraphScope(owner: Graph, val workspace: AgentWorkspace, val graphId: String, val live: Boolean = true) : Graph(
    parentGraph = owner,
    id = if (live) ActorKey("scope:taskgraph/$graphId", CoordinatorMessage::class).nodeId else Uuid.random(),
    label = "Task graph $graphId",
) {
    val coordinator = TaskCoordinator(this)
    private val actors = ConcurrentHashMap<String, TaskActor>()
    val by = BoardActor(BoardActorKind.Schedule, "Task graph $graphId")

    init {
        attach(coordinator)
        rebuild(workspace.board.members(graphId).associateBy { it.id })
    }

    fun actor(id: String): TaskActor? = actors[id]

    fun busy(): Boolean = actors.values.any { it.busy }

    fun rebuild(members: Map<String, BoardTask>) {
        actors.keys.filter { it !in members }.forEach { id -> actors.remove(id)?.let(::detach) }
        members.values.forEach { task -> actors.getOrPut(task.id) { TaskActor(this, task).also(::attach) }.task = task }
        actors.values.forEach { it.wire(actors) }
    }
}

internal class TaskCoordinator(private val scope: TaskGraphScope) : ActorNode<CoordinatorMessage>(scope, ActorKey("taskgraph/${scope.graphId}", CoordinatorMessage::class)), ShapeAttributes {
    private val store get() = scope.workspace.board
    private val graphId get() = scope.graphId

    override val shapeAttributes: Map<String, String>
        get() = runCatching { store.graph(graphId) }.getOrNull()?.let { graph ->
            buildMap {
                put("role", "coordinator"); put("graph", graph.id); put("title", graph.title); put("status", graph.status.name)
                put("revision", graph.revision.toString()); put("tokens", graph.tokensUsed.toString())
                graph.envelope.tokens?.let { put("budget", it.toString()) }
                graph.reason?.let { put("reason", it) }
            }
        }.orEmpty()

    override suspend fun receive(message: CoordinatorMessage) {
        if (!scope.live) return
        when (message) {
            CoordinatorMessage.Tick -> evaluate()
            is CoordinatorMessage.Settled -> { settle(message); evaluate() }
        }
    }

    private fun members() = store.members(graphId).associateBy { it.id }

    private fun evaluate() {
        var members = members()
        scope.rebuild(members)
        val graph = store.graph(graphId)
        members.values.filter { it.graph?.state == TaskState.Cancelled || graph.status == TaskGraphStatus.Cancelled }.forEach { task ->
            scope.actor(task.id)?.takeIf { it.busy }?.trySend(TaskMessage.Stop)
        }
        if (graph.status != TaskGraphStatus.Running) return
        members.values.filter { it.graph?.state == TaskState.Running && it.graph.seat?.lead != true && it.graph.kind in Dispatched }.forEach { task ->
            scope.actor(task.id)?.takeIf { !it.busy }?.trySend(TaskMessage.Start)
        }
        graph.envelope.tokens?.let { budget ->
            if (graph.tokensUsed >= budget) { store.hold(graphId, TaskGraphStatus.Paused, "The token budget is used: ${graph.tokensUsed} of $budget", scope.by); return }
        }
        members.values.firstOrNull { TaskGraphRules.unhandledFailure(it, members) }?.let { failed ->
            store.advance(graphId, failed.id) { task, plan -> task.copy(graph = plan.copy(held = true)) }
            store.hold(graphId, TaskGraphStatus.Paused, "${failed.id} failed: ${failed.graph?.outcome?.summary?.take(200).orEmpty()}. Retry it, or resume to go on without it", scope.by)
            return
        }
        while (true) {
            val skipped = members.values.filter { TaskGraphRules.readiness(it, members) == TaskGraphRules.Readiness.Skip }
            if (skipped.isEmpty()) break
            skipped.forEach { task -> store.advance(graphId, task.id) { value, plan -> value.copy(graph = plan.copy(state = TaskState.Skipped, note = "None of the tasks it depends on led here")) } }
            members = members()
        }
        TaskGraphRules.finished(members)?.let { status ->
            val failed = members.values.filter { it.graph?.state == TaskState.Failed }.joinToString { it.id }
            store.hold(graphId, status, if (status == TaskGraphStatus.Finished) "Every task has settled" else "Failed tasks: $failed", scope.by)
            return
        }
        var free = scope.workspace.freeSlots()
        for (task in TaskGraphRules.ready(members)) {
            val plan = requireNotNull(task.graph)
            if (plan.readySince == null) store.advance(graphId, task.id) { value, current -> value.copy(graph = current.copy(readySince = System.currentTimeMillis())) }
            when {
                plan.kind == TaskKind.Gate -> store.advance(graphId, task.id) { value, current ->
                    value.copy(graph = current.copy(state = TaskState.Waiting, note = "Waiting for a person to pass this gate")) }
                plan.kind == TaskKind.Merge -> store.advance(graphId, task.id) { value, current ->
                    value.copy(graph = current.copy(state = TaskState.Succeeded, outcome = merged(task, members))) }
                plan.seat?.lead == true -> Unit
                plan.kind == TaskKind.Agent && plan.seat?.agent == null -> store.advance(graphId, task.id) { value, current ->
                    value.copy(graph = current.copy(state = TaskState.Waiting, note = "Choose who does this task, then retry it")) }
                plan.kind == TaskKind.Agent && free <= 0 -> Unit
                fits(task, members) -> {
                    if (plan.kind == TaskKind.Agent) free--
                    val started = store.advance(graphId, task.id) { value, current ->
                        value.copy(graph = current.copy(state = TaskState.Running, attempt = current.attempt + 1, note = null, outcome = null, held = false, run = null))
                    }
                    members = members + (started.id to started)
                    scope.actor(task.id)?.let { actor -> actor.task = started; actor.trySend(TaskMessage.Start) }
                }
            }
        }
    }

    private fun fits(task: BoardTask, members: Map<String, BoardTask>): Boolean {
        val plan = requireNotNull(task.graph)
        val running = members.values.filter { it.graph?.state == TaskState.Running }
        val agent = plan.seat?.agent
        if (plan.kind == TaskKind.Agent && agent != null && running.count { it.graph?.kind == TaskKind.Agent && it.graph.seat?.agent == agent } >= store.graph(graphId).envelope.perAgent) return false
        if (plan.seat?.access == TaskAccess.Write && running.any { other -> other.graph?.seat?.access == TaskAccess.Write && TaskGraphRules.overlaps(other.scope, task.scope) }) return false
        return true
    }

    private fun merged(task: BoardTask, members: Map<String, BoardTask>): TaskOutcome {
        val inputs = task.graph?.dependsOn.orEmpty().mapNotNull { members[it.task] }.filter { it.graph?.state == TaskState.Succeeded }
        return TaskOutcome(
            summary = ("Joined ${inputs.joinToString { it.id }}. The tasks share this checkout, so their changes are already together.\n\n" +
                inputs.joinToString("\n\n") { "${it.id} ${it.title}: ${it.graph?.outcome?.summary.orEmpty().take(1_500)}" }).take(TaskGraphRules.OutcomeChars),
            files = inputs.flatMap { it.graph?.outcome?.files.orEmpty() }.distinct(),
            at = System.currentTimeMillis(),
        )
    }

    private fun settle(message: CoordinatorMessage.Settled) {
        val task = runCatching { store.get(message.task) }.getOrNull() ?: return
        val plan = task.graph ?: return
        if (plan.attempt != message.attempt) return
        if (plan.state != TaskState.Running) {
            store.advance(graphId, task.id) { value, current -> value.copy(graph = current.copy(outcome = (current.outcome ?: message.outcome).copy(
                log = current.outcome?.log ?: message.outcome.log, tokens = current.outcome?.tokens ?: message.outcome.tokens,
                files = current.outcome?.files?.ifEmpty { null } ?: message.outcome.files))) }
            return
        }
        if (message.transient && plan.attempt < MaxAttempts) {
            store.advance(graphId, task.id) { value, current -> value.copy(graph = current.copy(state = TaskState.Pending, outcome = message.outcome,
                note = "Retrying: ${message.outcome.summary.take(200)}")) }
            return
        }
        val stage = if (message.state == TaskState.Succeeded && plan.kind == TaskKind.Agent && task.stage != BoardStage.Done) BoardStage.Review else task.stage
        val settled = store.advance(graphId, task.id) { value, current ->
            value.copy(graph = current.copy(state = message.state, outcome = message.outcome), stage = stage,
                stageChangedAt = if (stage != value.stage) System.currentTimeMillis() else value.stageChangedAt,
                history = (value.history + BoardEvent(System.currentTimeMillis(), scope.by, "${message.state.name} after attempt ${current.attempt}")).takeLast(50))
        }
        if (message.state == TaskState.Succeeded && plan.contract == WorkflowContract.Decision && message.outcome.verdict == "repair") repair(settled)
    }

    private fun repair(review: BoardTask) {
        val members = members()
        val plan = requireNotNull(review.graph)
        if (members.values.any { member -> member.graph?.dependsOn?.any { it.task == review.id && it.condition == TaskCondition.Repair } == true }) return
        val root = plan.repairOf ?: review.id
        val rounds = members.values.count { it.graph?.repairOf == root && it.graph.contract == WorkflowContract.Text }
        val graph = store.graph(graphId)
        if (rounds >= graph.envelope.repairRounds) {
            store.advance(graphId, review.id) { value, current -> value.copy(graph = current.copy(state = TaskState.Failed, note = "The review still asks for a repair and the envelope's ${graph.envelope.repairRounds} repair rounds are used")) }
            return
        }
        val builder = upstreamBuilder(review, members) ?: run {
            store.advance(graphId, review.id) { value, current -> value.copy(graph = current.copy(state = TaskState.Failed, note = "The review asks for a repair, but no agent task comes before it")) }
            return
        }
        val passing = members.values.filter { member -> member.graph?.state == TaskState.Pending && member.graph.dependsOn.any { it.task == review.id && it.condition == TaskCondition.Pass } }
        val ops = listOf<TaskGraphOp>(
            TaskGraphOp.Add("Repair: ${builder.title}".take(200), key = "repair", intent = "Fix only what the review of ${builder.id} found, and keep unrelated changes.\n\nThe review said:\n${review.graph?.outcome?.summary.orEmpty().take(4_000)}",
                acceptance = builder.acceptance, scope = builder.scope, seat = builder.graph?.seat, parent = builder.id, dependsOn = listOf(TaskDependency(review.id, TaskCondition.Repair))),
            TaskGraphOp.Add("Review the repair of ${builder.id}".take(200), key = "rereview", intent = "Review the repair against the original task and the earlier review. Pass only if it now meets the task.",
                acceptance = builder.acceptance, scope = builder.scope, seat = plan.seat, contract = WorkflowContract.Decision, dependsOn = listOf(TaskDependency("repair"))),
        ) + passing.map { TaskGraphOp.Connect("rereview", it.id, TaskCondition.Pass) }
        val applied = store.patch(graphId, TaskGraphPatch(ops, "${review.id} asked for a repair", graph.revision), TaskGraphCaller(scope.by, lead = true), approvedBy = "the envelope's repair rounds")
        applied.tasks.filter { it.graph?.state == TaskState.Pending && it.createdBy == scope.by && it.graph.repairOf == null && it.id !in members }.forEach { added ->
            store.advance(graphId, added.id) { value, current -> value.copy(graph = current.copy(repairOf = root)) }
        }
    }

    private fun upstreamBuilder(review: BoardTask, members: Map<String, BoardTask>): BoardTask? {
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque(review.graph?.dependsOn.orEmpty().map { it.task })
        while (queue.isNotEmpty()) {
            val next = members[queue.removeFirst()] ?: continue
            if (!seen.add(next.id)) continue
            val plan = next.graph ?: continue
            if (plan.kind == TaskKind.Agent && plan.contract == WorkflowContract.Text && plan.seat?.agent != null) return next
            queue.addAll(plan.dependsOn.map { it.task })
        }
        return null
    }

    companion object {
        const val MaxAttempts = 2
        val Dispatched = setOf(TaskKind.Agent, TaskKind.Check)
    }
}

internal class TaskActor(private val scope: TaskGraphScope, @Volatile override var task: BoardTask) :
    ActorNode<TaskMessage>(scope, ActorKey("taskgraph/${scope.graphId}/${task.id}", TaskMessage::class)), TaskOutcomes, ShapeAttributes {
    private val outcomeType = Type.create(TaskOutcomes::class)
    private val outcomes = registerProvider(Key("outcome"), outcomeType, this as TaskOutcomes)
    @Volatile private var job: Job? = null
    val busy: Boolean get() = job?.isActive == true
    private val store get() = scope.workspace.board

    override val shapeAttributes: Map<String, String>
        get() = buildMap {
            val plan = task.graph
            put("task", task.id); put("title", task.title)
            plan?.let {
                put("kind", it.kind.name); put("status", it.state.name); put("attempt", it.attempt.toString())
                it.seat?.let { seat -> put("agent", seat.describe()) }
                it.run?.let { run -> put("run", run) }
                it.outcome?.verdict?.let { verdict -> put("verdict", verdict) }
                it.note?.let { note -> put("note", note.take(300)) }
            }
            put("mailbox", status.value::class.simpleName.orEmpty())
        }

    fun wire(actors: Map<String, TaskActor>) {
        val wanted = task.graph?.dependsOn.orEmpty().map { it.task }.filter { it in actors }.toSet()
        val present = consumerPorts.snapshot()[outcomeType].orEmpty()
        present.keys.filter { it.key !in wanted }.forEach { key -> consumerPorts.remove(outcomeType, key)?.close() }
        wanted.forEach { upstream ->
            val port = registerConsumer<TaskOutcomes>(Key(upstream), outcomeType)
            if (!port.isConnected()) actors[upstream]?.let { connect(port, it.outcomes).getOrThrow() }
        }
    }

    private fun upstream(): List<TaskOutcomes> = consumerPorts.snapshot()[outcomeType].orEmpty().values.mapNotNull { port ->
        @Suppress("UNCHECKED_CAST")
        (port.impl as? TaskOutcomes)
    }

    override suspend fun receive(message: TaskMessage) {
        when (message) {
            TaskMessage.Start -> if (!busy) job = coroutineScope.launch(Dispatchers.IO) { perform() }
            TaskMessage.Stop -> {
                job?.cancel(); job = null
                task.graph?.run?.let(scope.workspace::threadOf)?.let(scope.workspace::latest)
                    ?.takeIf { it.status == AgentRunStatus.Running }
                    ?.let { runCatching { scope.workspace.cancel(it.id) } }
            }
        }
    }

    override fun close() {
        job?.cancel()
        super.close()
    }

    private suspend fun perform() {
        val current = runCatching { store.get(task.id) }.getOrNull() ?: return
        val plan = current.graph ?: return
        if (plan.state != TaskState.Running) return
        val settled = try {
            when (plan.kind) {
                TaskKind.Agent -> agent(current, plan)
                TaskKind.Check -> check(current, plan)
                else -> return
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            CoordinatorMessage.Settled(current.id, plan.attempt, TaskState.Failed,
                TaskOutcome(summary = failure.message ?: failure::class.simpleName.orEmpty(), at = System.currentTimeMillis()), transient = failure.transient())
        }
        scope.coordinator.send(settled)
    }

    private fun Exception.transient() = message.orEmpty().let { "capacity is busy" in it || "draining" in it || "closed" in it }

    private suspend fun agent(task: BoardTask, plan: TaskPlan): CoordinatorMessage.Settled {
        val seat = requireNotNull(plan.seat?.takeIf { it.agent != null }) { "${task.id} has no agent" }
        val adopted = plan.run?.takeIf { it.startsWith(BoardRefs.Hangar) }?.let(scope.workspace::threadOf)?.let(scope.workspace::latest)
        val run = adopted ?: run {
            val record = scope.workspace.submit(AgentSubmission(
                requestId = "taskgraph:${scope.graphId}:${task.id}:${plan.attempt}",
                provider = requireNotNull(seat.agent),
                prompt = brief(task, plan),
                model = seat.model,
                allowWrites = seat.access == TaskAccess.Write,
                effort = seat.effort?.let(::NativeEffort),
                timeoutMillis = 60 * 60 * 1000L,
            ))
            val ref = BoardRefs.Hangar + record.threadId
            store.link(task.id, ref, true, scope.by)
            store.advance(scope.graphId, task.id) { value, current -> value.copy(graph = current.copy(run = ref)) }
            record
        }
        val done = scope.workspace.settled(run.id)
        return settled(task, plan, done)
    }

    private fun settled(task: BoardTask, plan: TaskPlan, run: AgentRunRecord): CoordinatorMessage.Settled {
        val ref = BoardRefs.Hangar + run.threadId
        val base = TaskOutcome(
            summary = run.output.trim().take(TaskGraphRules.OutcomeChars),
            files = files(run.id),
            log = ref,
            tokens = run.usage?.let { (it.inputTokens ?: 0) + (it.outputTokens ?: 0) }?.takeIf { it > 0 },
            at = System.currentTimeMillis(),
        )
        fun settled(state: TaskState, outcome: TaskOutcome, transient: Boolean = false) = CoordinatorMessage.Settled(task.id, plan.attempt, state, outcome, transient)
        return when {
            run.status == AgentRunStatus.Completed && plan.contract == WorkflowContract.Decision ->
                decision(run.output)?.let { (verdict, summary) -> settled(if (verdict == "fail") TaskState.Failed else TaskState.Succeeded, base.copy(verdict = verdict, summary = summary.take(TaskGraphRules.OutcomeChars))) }
                    ?: settled(TaskState.Failed, base.copy(summary = "The review did not end with a Decision object {\"verdict\": \"pass\" | \"repair\" | \"fail\", \"summary\": \"...\"}.\n\n${base.summary}".take(TaskGraphRules.OutcomeChars)))
            run.status == AgentRunStatus.Completed -> settled(TaskState.Succeeded, base)
            run.recovery == AgentRecovery.NeedsReview -> settled(TaskState.Waiting, base.copy(
                summary = "The outcome is unknown: ${run.recoveryReason ?: "the owner stopped while this task ran"}.",
                questions = listOf("Inspect $ref, then report ${task.id} done or retry it.")))
            else -> settled(TaskState.Failed, base.copy(summary = (run.failure ?: "The run stopped without a result").take(TaskGraphRules.OutcomeChars)),
                transient = run.failure?.contains("Stopped by the user") != true && run.output.isBlank())
        }
    }

    private suspend fun check(task: BoardTask, plan: TaskPlan): CoordinatorMessage.Settled {
        val thread = upstream().mapNotNull { it.task.graph?.run }.firstNotNullOfOrNull(scope.workspace::threadOf)
            ?: return CoordinatorMessage.Settled(task.id, plan.attempt, TaskState.Failed,
                TaskOutcome(summary = "A check runs after the agent task whose work it checks; connect ${task.id} after one.", at = System.currentTimeMillis()))
        val check = requireNotNull(plan.check) { "${task.id} names no check" }
        val result = scope.workspace.runCheck(thread, "taskgraph:${scope.graphId}:${task.id}:${plan.attempt}",
            WorkflowStage(task.id, task.title, action = WorkflowAction.Check, checkId = check))
        return CoordinatorMessage.Settled(task.id, plan.attempt, if (result.ok) TaskState.Succeeded else TaskState.Failed, TaskOutcome(
            summary = result.text.take(TaskGraphRules.OutcomeChars),
            checks = listOf(TaskCheckResult(check, result.ok, result.receiptId, result.text.take(2_000))),
            at = System.currentTimeMillis(),
        ))
    }

    private fun files(runId: String): List<String> = runCatching {
        val found = linkedSetOf<String>()
        var after = 0L
        repeat(5) {
            val page = scope.workspace.activity.page(runId, after, 100)
            page.records.map { it.item }.filter { it.kind == ActivityKind.Tool && it.title.lowercase() in Editing }.forEach { item ->
                val input = item.input.orEmpty()
                Patched.findAll(input).forEach { found += it.groupValues[1].trim() }
                runCatching { kotlinx.serialization.json.Json.parseToJsonElement(input).jsonObject }.getOrNull()?.let { json ->
                    listOf("file_path", "path", "notebook_path").firstNotNullOfOrNull { json[it]?.jsonPrimitive?.contentOrNull }?.let(found::add)
                }
            }
            if (!page.hasMore) return@runCatching found.take(200)
            after = page.nextCursor
        }
        found.take(200)
    }.getOrDefault(emptyList())

    private fun brief(task: BoardTask, plan: TaskPlan): String = buildString {
        val graph = store.graph(scope.graphId)
        appendLine("You are doing task ${task.id} of the task graph ${graph.id}, \"${graph.title}\", which Hangar runs.")
        if (graph.goal.isNotBlank()) { appendLine(); appendLine("The graph's goal: ${graph.goal}") }
        appendLine(); appendLine("Your task: ${task.title}")
        if (task.intent.isNotBlank()) appendLine(task.intent)
        if (task.acceptance.isNotEmpty()) { appendLine(); appendLine("Done when:"); task.acceptance.forEach { appendLine("- $it") } }
        if (task.scope.isNotEmpty()) { appendLine(); appendLine("It touches: ${task.scope.joinToString()}") }
        val inputs = upstream().map { it.task }.filter { it.graph?.state == TaskState.Succeeded || it.graph?.state == TaskState.Failed }
        if (inputs.isNotEmpty()) {
            appendLine(); appendLine("What the tasks before yours produced:")
            inputs.forEach { input ->
                val outcome = input.graph?.outcome
                appendLine(); appendLine("## ${input.id} ${input.title} (${input.graph?.state?.name}${outcome?.verdict?.let { ", verdict $it" }.orEmpty()})")
                appendLine(outcome?.summary.orEmpty().take(TaskGraphRules.OutcomeChars).ifEmpty { "No summary." })
                outcome?.files?.takeIf { it.isNotEmpty() }?.let { appendLine("Files: ${it.take(40).joinToString()}") }
                outcome?.log?.let { appendLine("Read its session with the kernel operation session.log {\"session\": \"$it\"}.") }
            }
        }
        appendLine()
        appendLine("The person approved this envelope: agents ${graph.envelope.agents.joinToString().ifEmpty { "as listed" }}; " +
            "tasks may write ${graph.envelope.writes.joinToString().ifEmpty { "nothing" }}; at most ${graph.envelope.maxTasks} tasks.")
        appendLine("Always leave these to a person: ${TaskEnvelope.PersonOnly.joinToString()}.")
        appendLine()
        appendLine("Through the reaktor MCP server's kernel tool you can call:")
        appendLine("- taskgraph.read {\"graph\": \"${graph.id}\"} to see the graph and its revision;")
        appendLine("- taskgraph.patch {\"graph\": \"${graph.id}\", \"expectedRevision\": <revision>, \"reason\": \"...\", \"ops\": [...]} to add subtasks after your task, split your task or remove pending tasks you added, without asking;")
        appendLine("- taskgraph.report {\"graph\": \"${graph.id}\", \"task\": \"${task.id}\", \"status\": \"NeedsInput\" | \"Blocked\" | \"Failed\", \"summary\": \"...\"} when you are stuck.")
        appendLine()
        if (plan.contract == WorkflowContract.Decision) appendLine("End your final message with one JSON object: {\"verdict\": \"pass\" | \"repair\" | \"fail\", \"summary\": \"what you found\"}. " +
            "Use repair when the work needs specific fixes, and fail when it cannot be repaired.")
        else appendLine("When you finish, end with a short summary: what you did, the files you changed and the checks you ran.")
    }

    companion object {
        val Editing = setOf("edit", "write", "multiedit", "notebookedit", "apply_patch", "write_file", "replace", "create_file", "str_replace_based_edit_tool")
        val Patched = Regex("\\*\\*\\* (?:Update|Add|Delete) File: ([^\\n]+)")

        fun decision(text: String): Pair<String, String>? {
            val trimmed = text.trim()
            runCatching { return workflowDecision(trimmed) }
            val end = trimmed.lastIndexOf('}').takeIf { it >= 0 } ?: return null
            var start = trimmed.lastIndexOf('{', end)
            var tries = 0
            while (start >= 0 && tries++ < 64) {
                runCatching { return workflowDecision(trimmed.substring(start, end + 1)) }
                start = trimmed.lastIndexOf('{', start - 1)
            }
            return null
        }
    }
}
