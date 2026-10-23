package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

class AgentBoardStore(private val directory: Path, private val clock: () -> Long = System::currentTimeMillis) {
    @Serializable
    private data class Ledger(val revision: Long = 0, val next: Int = 1, val graphs: Int = 1)

    private val ledgerFile = directory.resolve("board.json")
    private val tasks = mutableMapOf<String, BoardTask>()
    private val graphs = mutableMapOf<String, TaskGraph>()
    private var ledger: Ledger
    private val _changes: MutableStateFlow<Long>
    val changes: StateFlow<Long> get() = _changes.asStateFlow()
    val revision: Long get() = _changes.value

    init {
        privateDirectory(directory.resolve("tasks"))
        privateDirectory(directory.resolve("graphs"))
        Files.list(directory.resolve("tasks")).use { files ->
            files.filter { it.fileName.toString().matches(TaskFile) }.forEach { path ->
                ConductorJson.decodeFromString(BoardTask.serializer(), Files.readString(path)).let { tasks[it.id] = it }
            }
        }
        Files.list(directory.resolve("graphs")).use { files ->
            files.filter { it.fileName.toString().matches(GraphFile) }.forEach { path ->
                ConductorJson.decodeFromString(TaskGraph.serializer(), Files.readString(path)).let { graphs[it.id] = it }
            }
        }
        val saved = if (Files.exists(ledgerFile)) ConductorJson.decodeFromString(Ledger.serializer(), Files.readString(ledgerFile)) else Ledger()
        ledger = saved.copy(
            next = maxOf(saved.next, (tasks.values.maxOfOrNull { it.number } ?: 0) + 1),
            graphs = maxOf(saved.graphs, (graphs.values.maxOfOrNull { it.number } ?: 0) + 1),
        )
        _changes = MutableStateFlow(ledger.revision)
    }

    @Synchronized fun tasks(): List<BoardTask> = tasks.values.sortedByDescending { it.number }

    @Synchronized fun get(id: String): BoardTask = tasks[id] ?: error("$id is not on the board")

    @Synchronized fun create(draft: BoardDraft, by: BoardActor): BoardTask {
        require(draft.stage != BoardStage.Done) { "A new task cannot start in Done" }
        val now = clock()
        val task = BoardTask(
            id = "T${ledger.next}",
            title = BoardText.title(draft.title),
            intent = BoardText.bounded(draft.intent, "intent", 20_000),
            acceptance = BoardText.lines(draft.acceptance, "acceptance", 30, 500),
            scope = BoardText.lines(draft.scope, "scope", 100, 500),
            stage = draft.stage,
            work = draft.work.refs(),
            links = draft.links.checked(),
            parent = draft.parent?.let { get(it).id },
            createdBy = by.checked(),
            createdAt = now,
            updatedAt = now,
            stageChangedAt = now,
            history = listOf(BoardEvent(now, by, "Created in ${draft.stage.column.label}")),
        )
        return save(task, ledger.next + 1)
    }

    @Synchronized fun update(id: String, patch: BoardPatch, by: BoardActor, expected: Long? = null): BoardTask {
        val task = current(id, expected)
        require(!(patch.unblock && patch.blocker != null)) { "Set a blocker or clear it, not both" }
        val now = clock()
        val blocker = when {
            patch.unblock -> null
            patch.blocker != null -> patch.blocker.checked(id)
            else -> task.blocker
        }
        val next = task.copy(
            title = patch.title?.let(BoardText::title) ?: task.title,
            intent = patch.intent?.let { BoardText.bounded(it, "intent", 20_000) } ?: task.intent,
            acceptance = patch.acceptance?.let { BoardText.lines(it, "acceptance", 30, 500) } ?: task.acceptance,
            scope = patch.scope?.let { BoardText.lines(it, "scope", 100, 500) } ?: task.scope,
            links = patch.links?.checked() ?: task.links,
            blocker = blocker,
            blockedAt = if (blocker == task.blocker) task.blockedAt else blocker?.let { now },
        )
        val edited = listOfNotNull(
            "title".takeIf { next.title != task.title },
            "intent".takeIf { next.intent != task.intent },
            "acceptance".takeIf { next.acceptance != task.acceptance },
            "scope".takeIf { next.scope != task.scope },
            "links".takeIf { next.links != task.links },
        )
        val changes = listOfNotNull(
            edited.takeIf { it.isNotEmpty() }?.let { "Edited ${it.joinToString(", ")}" },
            when {
                blocker == task.blocker -> null
                blocker == null -> "Unblocked"
                else -> "Blocked: ${next.blockedBy(tasks).orEmpty()}"
            },
        )
        return if (changes.isEmpty()) task else save(next.changed(now, by.checked(), changes.joinToString("; ")))
    }

    @Synchronized fun move(id: String, stage: BoardStage, by: BoardActor, expected: Long? = null): BoardTask {
        val task = current(id, expected)
        if (by.checked().kind != BoardActorKind.Person) {
            require(stage != BoardStage.Done) { "Only a person moves a task to Done. Move ${task.id} to Review and a person lands it." }
            require(task.stage != BoardStage.Done) { "${task.id} is done; only a person reopens it." }
        }
        val now = clock()
        val change = if (stage == task.stage) "Kept in ${stage.column.label}" else "Moved to ${stage.column.label}"
        return save(task.copy(stage = stage, stageChangedAt = now).changed(now, by, change))
    }

    @Synchronized fun link(id: String, ref: String, linked: Boolean, by: BoardActor): BoardTask {
        val task = get(id)
        val work = if (linked) (task.work + listOf(ref).refs()).distinct() else task.work - ref
        require(work.size <= 50) { "A task links at most 50 runs and sessions" }
        if (work == task.work) return task
        return save(task.copy(work = work).changed(clock(), by.checked(), if (linked) "Linked $ref" else "Unlinked $ref"))
    }

    @Synchronized fun graphs(): List<TaskGraph> = graphs.values.sortedByDescending { it.number }

    @Synchronized fun graph(id: String): TaskGraph = graphs[id] ?: error("$id is not a task graph on this board")

    @Synchronized fun members(id: String): List<BoardTask> = tasks.values.filter { it.graph?.id == id }.sortedBy { it.number }

    @Synchronized fun view(id: String): TaskGraphView {
        val members = members(id).associateBy { it.id }
        return TaskGraphView(graph(id), members.values.toList(), if (graph(id).status == TaskGraphStatus.Running) TaskGraphRules.ready(members).map { it.id } else emptyList())
    }

    @Synchronized fun summaries(): List<TaskGraphSummary> = graphs().map { graph ->
        val members = members(graph.id).filter { it.graph?.kind != TaskKind.Note }
        fun count(vararg states: TaskState) = members.count { it.graph?.state in states }
        TaskGraphSummary(graph.id, graph.title, graph.status, graph.revision, members.size,
            count(TaskState.Succeeded, TaskState.Skipped, TaskState.Cancelled), count(TaskState.Running), count(TaskState.Waiting), count(TaskState.Failed),
            members.mapNotNull { it.graph?.seat?.agent }.distinct(), graph.tokensUsed, graph.envelope.tokens, graph.lead, graph.changedSinceApproval, graph.updatedAt,
            graph.reason, graph.createdBy.name)
    }

    @Synchronized fun propose(draft: TaskGraphDraft, by: BoardActor): TaskGraphView {
        require(draft.tasks.size in 1..TaskGraphRules.MaxTasks) { "A graph has 1 to ${TaskGraphRules.MaxTasks} tasks" }
        by.checked()
        val now = clock()
        val seats = draft.tasks.mapNotNull { it.seat }.filterNot { it.lead }
        val envelope = draft.envelope.copy(
            agents = draft.envelope.agents.ifEmpty { seats.mapNotNull { it.agent }.distinct() },
            writes = draft.envelope.writes.ifEmpty { draft.tasks.filter { it.seat?.access == TaskAccess.Write }.flatMap { it.scope }.distinct() },
            maxTasks = maxOf(draft.envelope.maxTasks, draft.tasks.size),
        )
        val shell = TaskGraph(
            id = "G${ledger.graphs}",
            title = BoardText.title(draft.title),
            goal = BoardText.bounded(draft.goal, "goal", 20_000),
            status = if (by.kind == BoardActorKind.Person) TaskGraphStatus.Draft else TaskGraphStatus.Proposed,
            lead = (draft.lead ?: by.session)?.also { require(BoardRefs.pattern.matches(it)) { "$it is not a session: use claude:<session>, codex:<session>, agy:<conversation> or hangar:<conversation>" } },
            envelope = envelope,
            revision = 0,
            createdBy = by,
            createdAt = now,
            updatedAt = now,
        )
        var next = ledger.next
        val applied = try {
            TaskGraphEditing(shell, emptyList(), TaskGraphCaller(by, lead = true), now) { "T${next++}" }
                .apply(TaskGraphPatch(draft.tasks, "Proposed ${shell.title}", expectedRevision = 0))
        } catch (outside: TaskGraphOutside) {
            throw IllegalArgumentException("The proposal's tasks fall outside its own envelope: ${outside.reasons.joinToString("; ")}")
        }
        commit(applied.graph, applied.tasks, emptyList(), next, ledger.graphs + 1)
        return view(shell.id)
    }

    @Synchronized fun patch(id: String, patch: TaskGraphPatch, caller: TaskGraphCaller, approvedBy: String? = null): TaskGraphApplied {
        val graph = graph(id)
        var next = ledger.next
        val applied = TaskGraphEditing(graph, members(id), caller, clock()) { "T${next++}" }.apply(patch, approvedBy)
        commit(applied.graph, applied.tasks, applied.removed, next)
        return applied
    }

    @Synchronized fun outside(id: String, patch: TaskGraphPatch, caller: TaskGraphCaller): List<String> {
        var next = ledger.next
        return try { TaskGraphEditing(graph(id), members(id), caller, clock()) { "T${next++}" }.apply(patch); emptyList() }
        catch (outside: TaskGraphOutside) { outside.reasons }
    }

    @Synchronized fun revert(id: String, revision: Long, by: BoardActor): TaskGraphApplied {
        require(by.kind == BoardActorKind.Person) { "Only a person reverts a change" }
        val change = graph(id).changes.firstOrNull { it.revision == revision } ?: error("$id has no change at revision $revision")
        require(change.undo.isNotEmpty()) { "Revision $revision cannot be reverted" }
        return patch(id, TaskGraphPatch(change.undo, "Revert revision $revision: ${change.reason}"), TaskGraphCaller(by))
    }

    @Synchronized fun start(id: String, by: BoardActor, expected: Long? = null): TaskGraph {
        require(by.kind == BoardActorKind.Person) { "Only a person approves and starts a graph" }
        val graph = graph(id)
        require(expected == null || graph.revision == expected) { "$id changed since revision $expected; look at it again before approving" }
        require(graph.status in setOf(TaskGraphStatus.Draft, TaskGraphStatus.Proposed, TaskGraphStatus.Paused)) { "$id is ${graph.status.name.lowercase()}" }
        require(members(id).any { it.graph?.kind != TaskKind.Note }) { "$id has no tasks to run" }
        val now = clock()
        return settleGraph(graph.copy(status = TaskGraphStatus.Running, approvedRevision = graph.revision + 1, reason = null), by, now,
            if (graph.status == TaskGraphStatus.Paused) "Resumed" else "Approved and started")
    }

    @Synchronized fun pause(id: String, caller: TaskGraphCaller, reason: String): TaskGraph {
        require(caller.person || caller.lead) { "Only a person or the lead pauses a graph" }
        val graph = graph(id)
        require(graph.status == TaskGraphStatus.Running) { "$id is not running" }
        return settleGraph(graph.copy(status = TaskGraphStatus.Paused, reason = BoardText.bounded(reason, "reason", 500).ifEmpty { "Paused by ${caller.by.name}" }), caller.by, clock(), "Paused")
    }

    @Synchronized fun cancel(id: String, by: BoardActor): TaskGraph {
        require(by.kind == BoardActorKind.Person) { "Only a person cancels a graph" }
        val graph = graph(id)
        require(graph.status !in setOf(TaskGraphStatus.Finished, TaskGraphStatus.Failed, TaskGraphStatus.Cancelled)) { "$id is already ${graph.status.name.lowercase()}" }
        val now = clock()
        val open = members(id).filter { it.graph?.state in TaskGraphRules.Open }.map { task ->
            task.copy(graph = task.graph?.copy(state = TaskState.Cancelled, note = "The graph was cancelled"), updatedAt = now)
        }
        val next = graph.copy(status = TaskGraphStatus.Cancelled, revision = graph.revision + 1, updatedAt = now, reason = "Cancelled by ${by.name}",
            changes = (graph.changes + TaskGraphChange(graph.revision + 1, now, by, "Cancelled", listOf("Cancelled $id"))).takeLast(200))
        commit(next, open, emptyList(), ledger.next)
        return next
    }

    @Synchronized fun advance(id: String, task: String, change: (BoardTask, TaskPlan) -> BoardTask): BoardTask {
        val current = get(task)
        val plan = requireNotNull(current.graph?.takeIf { it.id == id }) { "$task is not in $id" }
        val next = change(current, plan).copy(updatedAt = clock())
        if (next == current) return current
        val graph = graph(id)
        val tokens = (next.graph?.outcome?.tokens ?: 0) - (plan.outcome?.tokens ?: 0)
        commit(graph.copy(revision = graph.revision + 1, updatedAt = clock(), tokensUsed = graph.tokensUsed + tokens.coerceAtLeast(0)), listOf(next), emptyList(), ledger.next)
        return next
    }

    @Synchronized fun hold(id: String, status: TaskGraphStatus, reason: String, by: BoardActor): TaskGraph {
        val graph = graph(id)
        if (graph.status == status && graph.reason == reason) return graph
        val now = clock()
        val revision = graph.revision + 1
        val next = graph.copy(status = status, reason = reason, revision = revision, updatedAt = now,
            changes = (graph.changes + TaskGraphChange(revision, now, by, reason, listOf("${status.name} $id"))).takeLast(200))
        commit(next, emptyList(), emptyList(), ledger.next)
        return next
    }

    @Synchronized fun report(id: String, task: String, report: TaskReport, outcome: TaskOutcome, caller: TaskGraphCaller): BoardTask {
        val current = get(task)
        val plan = requireNotNull(current.graph?.takeIf { it.id == id }) { "$task is not in $id" }
        when {
            plan.kind == TaskKind.Gate -> require(caller.person) { "Only a person passes a gate" }
            caller.person -> Unit
            caller.lead && plan.seat?.lead == true -> Unit
            task in caller.own -> Unit
            else -> error("An agent reports only on its own task; $task belongs to another session")
        }
        require(plan.state !in setOf(TaskState.Cancelled, TaskState.Skipped)) { "$task is ${plan.state.name.lowercase()}" }
        val bounded = outcome.bounded()
        val now = clock()
        val state = when (report) {
            TaskReport.Running -> TaskState.Running
            TaskReport.NeedsInput, TaskReport.Blocked -> TaskState.Waiting
            TaskReport.Review, TaskReport.Succeeded -> TaskState.Succeeded
            TaskReport.Failed -> TaskState.Failed
        }
        if (plan.kind == TaskKind.Gate) require(state != TaskState.Succeeded || bounded.verdict in setOf(null, "accepted")) { "A gate is accepted or failed" }
        val verdict = if (plan.kind == TaskKind.Gate && state == TaskState.Succeeded) "accepted" else bounded.verdict
        if (plan.contract == dev.shibasis.reaktor.conductor.WorkflowContract.Decision && state == TaskState.Succeeded)
            require(verdict in setOf("pass", "repair", "fail")) { "$task has the Decision contract; report verdict pass, repair or fail" }
        val finalState = if (verdict == "fail") TaskState.Failed else state
        val merged = (plan.outcome ?: TaskOutcome()).let { old ->
            bounded.copy(summary = bounded.summary.ifEmpty { old.summary }, verdict = verdict ?: old.verdict, files = bounded.files.ifEmpty { old.files },
                checks = bounded.checks.ifEmpty { old.checks }, log = bounded.log ?: old.log, tokens = bounded.tokens ?: old.tokens, at = now)
        }
        val stage = if (finalState == TaskState.Succeeded && plan.kind == TaskKind.Agent && current.stage != BoardStage.Done) BoardStage.Review else current.stage
        return advance(id, task) { value, _ ->
            value.copy(graph = plan.copy(state = finalState, outcome = merged, note = if (report == TaskReport.Blocked) bounded.summary.take(300) else plan.note),
                stage = stage, stageChangedAt = if (stage != current.stage) now else current.stageChangedAt,
                revision = value.revision + 1, history = (value.history + BoardEvent(now, caller.by, "Reported ${report.name}")).takeLast(50))
        }
    }

    @Synchronized fun claim(id: String, task: String, caller: TaskGraphCaller): BoardTask {
        require(caller.lead) { "Only the graph's lead session claims its tasks" }
        val session = requireNotNull(caller.by.session?.takeIf(BoardRefs.pattern::matches)) { "The lead claims with its session" }
        val members = members(id).associateBy { it.id }
        val current = members[task] ?: error("$task is not in $id")
        val plan = requireNotNull(current.graph)
        require(plan.seat?.lead == true) { "$task is not the lead's task" }
        require(graph(id).status == TaskGraphStatus.Running) { "$id is not running" }
        require(TaskGraphRules.readiness(current, members) == TaskGraphRules.Readiness.Ready) { "$task is not ready: ${current.blockedBy(members) ?: "it is ${plan.state.name.lowercase()}"}" }
        val now = clock()
        return advance(id, task) { value, _ ->
            value.copy(work = (value.work + session).distinct(), graph = plan.copy(state = TaskState.Running, attempt = plan.attempt + 1, run = session),
                revision = value.revision + 1, history = (value.history + BoardEvent(now, caller.by, "Claimed by the lead")).takeLast(50))
        }
    }

    private fun settleGraph(next: TaskGraph, by: BoardActor, now: Long, change: String): TaskGraph {
        val revision = next.revision + 1
        val saved = next.copy(revision = revision, updatedAt = now,
            changes = (next.changes + TaskGraphChange(revision, now, by, change, listOf("$change ${next.id}"))).takeLast(200))
        commit(saved, emptyList(), emptyList(), ledger.next)
        return saved
    }

    private fun commit(graph: TaskGraph, changed: List<BoardTask>, removed: List<String>, next: Int, graphsNext: Int = ledger.graphs) {
        changed.forEach { task ->
            atomicWrite(directory.resolve("tasks/${task.id}.json"), ConductorJson.encodeToString(BoardTask.serializer(), task))
            tasks[task.id] = task
        }
        removed.forEach { id -> Files.deleteIfExists(directory.resolve("tasks/$id.json")); tasks.remove(id) }
        atomicWrite(directory.resolve("graphs/${graph.id}.json"), ConductorJson.encodeToString(TaskGraph.serializer(), graph))
        graphs[graph.id] = graph
        ledger = Ledger(ledger.revision + 1, maxOf(next, ledger.next), graphsNext)
        atomicWrite(ledgerFile, ConductorJson.encodeToString(Ledger.serializer(), ledger))
        _changes.value = ledger.revision
    }

    private fun TaskOutcome.bounded() = copy(
        summary = summary.trim().take(TaskGraphRules.OutcomeChars),
        verdict = verdict?.trim()?.lowercase()?.takeIf(String::isNotEmpty)?.also { require(it.length <= 40) { "A verdict is one word" } },
        files = files.take(200).map { it.take(500) },
        checks = checks.take(50).map { it.copy(text = it.text.take(2_000)) },
        questions = questions.take(10).map { it.take(1_000) },
        log = log?.takeIf { BoardRefs.pattern.matches(it) },
    )

    private fun current(id: String, expected: Long?): BoardTask = get(id).also { task ->
        require(expected == null || task.revision == expected) { "${task.id} changed since revision $expected; read it again" }
    }

    private fun save(task: BoardTask, next: Int = ledger.next): BoardTask {
        atomicWrite(directory.resolve("tasks/${task.id}.json"), ConductorJson.encodeToString(BoardTask.serializer(), task))
        tasks[task.id] = task
        ledger = ledger.copy(revision = ledger.revision + 1, next = next)
        atomicWrite(ledgerFile, ConductorJson.encodeToString(Ledger.serializer(), ledger))
        _changes.value = ledger.revision
        return task
    }

    private fun BoardTask.changed(now: Long, by: BoardActor, change: String) =
        copy(updatedAt = now, revision = revision + 1, history = (history + BoardEvent(now, by, change)).takeLast(50))

    private fun BoardBlocker.checked(id: String): BoardBlocker = when (this) {
        is BoardBlocker.Task -> {
            require(task != id) { "A task cannot wait on itself" }
            var next: String? = task
            val seen = mutableSetOf(id)
            while (next != null) {
                require(seen.add(next)) { "$id and $task would wait on each other" }
                next = (get(next).blocker as? BoardBlocker.Task)?.task
            }
            this
        }
        is BoardBlocker.Owner -> BoardBlocker.Owner(BoardText.bounded(owner, "owner", 100).also { require(it.isNotEmpty()) { "Name who unblocks it" } },
            BoardText.bounded(action, "action", 300).also { require(it.isNotEmpty()) { "Name the action that unblocks it" } })
    }

    private fun BoardActor.checked() = also {
        require(name.isNotBlank() && name.length <= 100) { "Name who makes the change" }
        session?.let { require(BoardRefs.pattern.matches(it)) { "$it is not a session ref" } }
    }

    private fun BoardLinks.checked() = also {
        listOfNotNull(mission, issue, pullRequest).forEach { require(it.isNotBlank() && it.length <= 500) { "Links are 1 to 500 characters" } }
    }

    private fun List<String>.refs(): List<String> = distinct().also { refs ->
        require(refs.size <= 50) { "A task links at most 50 runs and sessions" }
        refs.forEach { require(BoardRefs.pattern.matches(it)) { "$it is not a run or session: use hangar:<thread>, claude:<session>, codex:<session> or agy:<conversation>" } }
    }

    private companion object {
        val TaskFile = Regex("T[0-9]{1,9}\\.json")
        val GraphFile = Regex("G[0-9]{1,9}\\.json")
    }
}
