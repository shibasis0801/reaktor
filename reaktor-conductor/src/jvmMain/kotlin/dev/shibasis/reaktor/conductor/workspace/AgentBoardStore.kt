package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

class AgentBoardStore(
    private val directory: Path,
    private val clock: () -> Long = System::currentTimeMillis,
    private val resolveSeat: (TaskSeat) -> ResolvedTaskSeat = TaskSeatResolver()::resolve,
    private val scopeModules: (List<String>) -> List<String> = { emptyList() },
    private val summarize: (BoardTask) -> TaskWorkSummary? = { null },
) {
    @Serializable
    private data class Ledger(val revision: Long = 0, val next: Int = 1, val graphs: Int = 1)

    private val ledgerFile = directory.resolve("board.json")
    private val tasks = mutableMapOf<String, BoardTask>()
    private val graphs = mutableMapOf<String, TaskGraph>()
    private val coordination = mutableMapOf<String, TaskCoordination>()
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

    @Synchronized fun link(id: String, ref: String, linked: Boolean, by: BoardActor, attempt: Int? = null): BoardTask {
        val task = get(id)
        val work = if (linked) (task.work + listOf(ref).refs()).distinct() else task.work - ref
        require(work.size <= 50) { "A task links at most 50 runs and sessions" }
        if (attempt != null) {
            val plan = requireNotNull(task.graph) { "$id is not in a task graph" }
            require(linked && plan.state == TaskState.Running && attempt == plan.attempt) { "$id current attempt ${plan.attempt}, linking attempt $attempt" }
            return advance(plan.id, id) { value, current ->
                value.copy(work = work, graph = current.copy(run = ref, workAttempts = current.workAttempts + (ref to attempt)))
                    .changed(clock(), by.checked(), "Linked $ref to attempt $attempt")
            }
        }
        if (work == task.work) return task
        return save(task.copy(work = work).changed(clock(), by.checked(), if (linked) "Linked $ref" else "Unlinked $ref"))
    }

    @Synchronized fun graphs(): List<TaskGraph> = graphs.values.sortedByDescending { it.number }

    @Synchronized fun graph(id: String): TaskGraph = graphs[id] ?: error("$id is not a task graph on this board")

    @Synchronized fun members(id: String): List<BoardTask> = tasks.values.filter { it.graph?.id == id }.sortedBy { it.number }

    @Synchronized fun view(id: String): TaskGraphView {
        val members = members(id).associateBy { it.id }
        val records = coordination(id)
        val summaries = members.values.mapNotNull { task -> summarize(task)?.let { task.id to it } }.toMap()
        val handoffs = records.handoffs.associateBy { it.task }.mapValues { (task, handoff) ->
            summaries[task]?.takeIf { handoff.recordedSummary && it.attempt == handoff.attempt && it.candidate == handoff.candidate }?.let { summary ->
                handoff.copy(done = summary.text(), checkReceipts = summary.checks.map { it.id }.take(50),
                    openIssues = (members[task]?.graph?.outcome?.questions.orEmpty() + summary.findings.map { it.title }).take(30))
            } ?: handoff
        }
        return TaskGraphView(graph(id), members.values.toList(),
            if (graph(id).status == TaskGraphStatus.Running) TaskGraphRules.ready(members).map { it.id } else emptyList(),
            members.values.mapNotNull { task ->
                (TaskGraphRules.readiness(task, members) as? TaskGraphRules.Readiness.Blocked)?.let { task.id to it.reasons }
            }.toMap(), records.messages, handoffs, records.decisions, warnings(members), summaries)
    }

    @Synchronized fun coordination(id: String): TaskCoordination {
        graph(id)
        return coordination.getOrPut(id) {
            val file = directory.resolve("graphs/$id-coordination.json")
            if (Files.exists(file)) {
                require(Files.size(file) <= TaskCoordination.Retained.toLong() * 3 * (TaskCoordination.RecordBytes + 4096) + 4096)
                ConductorJson.decodeFromString(TaskCoordination.serializer(), Files.readString(file))
            } else TaskCoordination()
        }
    }

    @Synchronized fun appendMessage(draft: AgentMessage, by: BoardActor, ownedSessions: Set<String> = emptySet()): AgentMessage {
        val records = coordination(draft.graph)
        coordinationTask(draft.graph, draft.task, draft.attempt)
        require((draft.recipient.task == null) != (draft.recipient.session == null)) { "Name one recipient: a task or an owned session" }
        draft.recipient.task?.let { coordinationTask(draft.graph, it, null) }
        draft.recipient.session?.let { require(BoardRefs.pattern.matches(it) && it in ownedSessions) { "$it is not an owned session" } }
        require(draft.body.isNotBlank() && draft.body.encodeToByteArray().size <= TaskCoordination.MessageBytes) { "A message body is 1 to ${TaskCoordination.MessageBytes} UTF-8 bytes" }
        val id = "${draft.graph}-M${records.next}"
        val reply = draft.replyTo?.let { reply -> records.messages.firstOrNull { it.id == reply } ?: error("Unknown retained message $reply") }
        val thread = draft.thread ?: reply?.thread ?: id
        require(thread == id || records.messages.any { it.thread == thread }) { "Unknown retained thread $thread" }
        require(reply == null || reply.thread == thread) { "A reply stays in its message's thread" }
        val value = draft.copy(id = id, thread = thread, author = by.checked(), at = clock(),
            artifacts = coordinationRefs(draft.artifacts, "artifacts", 100))
        recordSize(ConductorJson.encodeToString(AgentMessage.serializer(), value))
        saveCoordination(draft.graph, records.copy(next = records.next + 1,
            messages = (records.messages + value).takeLast(TaskCoordination.Retained)), by, "Message $id · ${draft.kind.name}")
        return value
    }

    @Synchronized fun appendHandoff(draft: TaskHandoff, by: BoardActor): TaskHandoff {
        val records = coordination(draft.graph)
        coordinationTask(draft.graph, draft.task, draft.attempt)
        require(draft.attempt > 0) { "A handoff names a started attempt" }
        val value = draft.copy(id = "${draft.graph}-H${records.next}", author = by.checked(), at = clock(),
            goal = BoardText.bounded(draft.goal, "goal", 4_000),
            done = BoardText.bounded(draft.done, "done", 8_000),
            candidate = draft.candidate?.let { BoardText.bounded(it, "candidate", 500).also { value -> require(value.isNotEmpty()) } },
            decisions = coordinationRefs(draft.decisions, "decisions", 50),
            openIssues = coordinationRefs(draft.openIssues, "open issues", 30, 1_000),
            changedPaths = coordinationRefs(draft.changedPaths, "changed paths", 100),
            checkReceipts = coordinationRefs(draft.checkReceipts, "check receipts", 50),
            reviewReceipts = coordinationRefs(draft.reviewReceipts, "review receipts", 50),
            nextAction = BoardText.bounded(draft.nextAction, "next action", 2_000),
            sessionLogs = coordinationRefs(draft.sessionLogs, "session logs", 50).also { logs ->
                require(logs.all(BoardRefs.pattern::matches)) { "Session logs name session refs" }
            })
        require(value.goal.isNotEmpty() && value.done.isNotEmpty()) { "A handoff names its goal and what was done" }
        require(value.decisions.all { id -> records.decisions.any { it.id == id } }) { "A handoff names retained decisions from its graph" }
        recordSize(ConductorJson.encodeToString(TaskHandoff.serializer(), value))
        saveCoordination(draft.graph, records.copy(next = records.next + 1,
            handoffs = (records.handoffs + value).takeLast(TaskCoordination.Retained)), by, "Handoff ${value.id} · ${value.task} attempt ${value.attempt}")
        return value
    }

    @Synchronized fun appendDecision(draft: TaskDecision, by: BoardActor): TaskDecision {
        val records = coordination(draft.graph)
        by.checked()
        require(!draft.personAccepted || by.kind == BoardActorKind.Person) { "Only a person accepts a decision" }
        draft.supersedes?.let { id -> require(records.decisions.any { it.id == id }) { "Unknown retained decision $id" } }
        val value = draft.copy(id = "${draft.graph}-D${records.next}", author = by, at = clock(),
            statement = BoardText.bounded(draft.statement, "statement", 2_000),
            rationale = BoardText.bounded(draft.rationale, "rationale", 8_000),
            subjects = coordinationRefs(draft.subjects, "subjects", 50))
        require(value.statement.isNotEmpty() && value.rationale.isNotEmpty() && value.subjects.isNotEmpty()) { "A decision names its statement, rationale and subjects" }
        recordSize(ConductorJson.encodeToString(TaskDecision.serializer(), value))
        saveCoordination(draft.graph, records.copy(next = records.next + 1,
            decisions = (records.decisions + value).takeLast(TaskCoordination.Retained)), by, "Decision ${value.id}")
        return value
    }

    private fun coordinationTask(graph: String, task: String?, attempt: Int?) {
        require(task != null || attempt == null) { "An attempt names its task" }
        task?.let {
            val plan = requireNotNull(get(it).graph?.takeIf { it.id == graph }) { "$it is not in $graph" }
            require(attempt == null || attempt in 0..plan.attempt) { "$it has attempts 0 to ${plan.attempt}, not $attempt" }
        }
    }

    private fun coordinationRefs(values: List<String>, name: String, count: Int, length: Int = 500): List<String> {
        require(values.size <= count) { "$name has at most $count items" }
        return BoardText.lines(values, name, count, length)
    }

    private fun recordSize(json: String) {
        require(json.encodeToByteArray().size <= TaskCoordination.RecordBytes) { "A coordination record is at most ${TaskCoordination.RecordBytes} UTF-8 bytes" }
    }

    private fun saveCoordination(id: String, value: TaskCoordination, by: BoardActor, change: String) {
        atomicWrite(directory.resolve("graphs/$id-coordination.json"), ConductorJson.encodeToString(TaskCoordination.serializer(), value))
        coordination[id] = value
        settleGraph(graph(id), by, clock(), change)
    }

    @Synchronized fun summaries(): List<TaskGraphSummary> = graphs().map { graph ->
        val members = members(graph.id).filter { it.graph?.kind != TaskKind.Note }
        fun count(vararg states: TaskState) = members.count { it.graph?.state in states }
        TaskGraphSummary(graph.id, graph.title, graph.status, graph.revision, members.size,
            count(TaskState.Succeeded, TaskState.Skipped, TaskState.Cancelled), count(TaskState.Running), count(TaskState.Waiting, TaskState.Limited), count(TaskState.Failed),
            members.mapNotNull { task -> task.graph?.let { it.resolvedSeat?.seat?.agent ?: it.seat?.let { seat -> runCatching { resolveSeat(seat).seat.agent }.getOrNull() } } }.distinct(), graph.tokensUsed, graph.envelope.tokens, graph.lead, graph.changedSinceApproval, graph.updatedAt,
            graph.reason, graph.createdBy.name)
    }

    @Synchronized fun propose(draft: TaskGraphDraft, by: BoardActor): TaskGraphView {
        require(draft.tasks.size in 1..TaskGraphRules.MaxTasks) { "A graph has 1 to ${TaskGraphRules.MaxTasks} tasks" }
        by.checked()
        val now = clock()
        val resolved = draft.tasks.associateWith { it.seat?.let(resolveSeat) }
        val effective = resolved.mapValues { it.value?.seat }
        val seats = resolved.values.filterNotNull().flatMap { listOf(it.seat) + it.lenses.map { lens -> lens.seat } }.filterNot { it.lead }
        val envelope = draft.envelope.copy(
            agents = draft.envelope.agents.ifEmpty { seats.mapNotNull { it.agent }.distinct() },
            writes = draft.envelope.writes.ifEmpty { draft.tasks.filter { effective[it]?.access == TaskAccess.Write }.flatMap { it.scope }.distinct() },
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
            TaskGraphEditing(shell, emptyList(), TaskGraphCaller(by, lead = true), now, { resolveSeat(it).seat }) { "T${next++}" }
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
        val applied = TaskGraphEditing(graph, members(id), caller, clock(), { resolveSeat(it).seat }) { "T${next++}" }.apply(patch, approvedBy)
        commit(applied.graph, applied.tasks, applied.removed, next)
        return applied
    }

    private fun warnings(members: Map<String, BoardTask>): List<TaskGraphWarning> = TaskGraphRules.warnings(members,
        members.mapValues { (_, task) -> scopeModules(task.scope) })

    @Synchronized fun check(id: String, patch: TaskGraphPatch, caller: TaskGraphCaller): TaskGraphCheck {
        val before = members(id).associateBy { it.id }
        fun preview(approvedBy: String? = null): TaskGraphApplied {
            var next = ledger.next
            return TaskGraphEditing(graph(id), before.values.toList(), caller, clock(), { resolveSeat(it).seat }) { "T${next++}" }.apply(patch, approvedBy)
        }
        var outside = emptyList<String>()
        val applied = try { preview() } catch (error: TaskGraphOutside) {
            outside = error.reasons
            preview("Preview only")
        }
        val projected = before.toMutableMap()
        applied.removed.forEach(projected::remove)
        applied.tasks.forEach { projected[it.id] = it }
        return TaskGraphCheck(outside, warnings(projected))
    }

    @Synchronized fun outside(id: String, patch: TaskGraphPatch, caller: TaskGraphCaller): List<String> = check(id, patch, caller).outside

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

    @Synchronized internal fun resolveIntegration(task: String, attempt: Int, tree: AgentWorktree,
        paths: List<String>, reason: String, by: BoardActor): BoardTask {
        val current = get(task)
        val plan = requireNotNull(current.graph)
        val graph = graph(plan.id)
        require(by.kind == BoardActorKind.Schedule && plan.kind == TaskKind.Merge &&
            plan.attempt == attempt && plan.worktreeId == tree.id &&
            plan.state in setOf(TaskState.Running, TaskState.Succeeded)) { "The integration attempt changed" }
        require(graph.status == TaskGraphStatus.Running && tree.inputs.isNotEmpty()) { "Resolution requires a running graph and retained inputs" }
        require(paths.size <= 200 && paths.all { it.length <= 4000 })
        val original = members(plan.id)
        val inputs = tree.inputs.keys.map(::get).sortedBy { it.number }
        val scope = current.scope.ifEmpty { inputs.flatMap { it.scope }.distinct() }
        val seat = (listOfNotNull(plan.seat) + inputs.mapNotNull { it.graph?.resolvedSeat?.seat ?: it.graph?.seat })
            .firstOrNull { request ->
                runCatching { resolveSeat(request).seat }.getOrNull()?.let { effective ->
                    effective.access == TaskAccess.Write && !effective.lead &&
                        TaskGraphRules.outside(effective, scope, graph.envelope).isEmpty()
                } == true
            }
        val pending = current.copy(graph = plan.copy(state = TaskState.Pending, worktreeId = null, readySince = null,
            outcome = null, note = "Waiting for integration resolution: ${reason.take(1000)}"))
        var next = ledger.next
        val applied = TaskGraphEditing(graph, original.map { if (it.id == task) pending else it },
            TaskGraphCaller(by, lead = true), clock(), { resolveSeat(it).seat }) { "T${next++}" }.apply(TaskGraphPatch(
            ops = listOf(TaskGraphOp.Add("Resolve $task: ${current.title}".take(200), key = "resolution",
                intent = ("Resolve the integration in the assigned owned checkout. Preserve every input's intended change. " +
                    "Remove conflict markers and leave the combined result for independent review and checks.\n" +
                    "Reason: $reason\nConflicting paths:\n${paths.joinToString("\n").take(8000)}\n" +
                    "Exact inputs:\n${tree.inputs.entries.joinToString("\n") { "${it.key}: ${it.value}" }}").take(20000),
                acceptance = listOf("All named integration conflicts are resolved", "Every input's intended change is retained",
                    "The combined candidate is independently reviewed and checked"),
                scope = scope, seat = seat, dependsOn = inputs.map { TaskDependency(it.id) }, parent = task)) +
                plan.dependsOn.map { TaskGraphOp.Disconnect(it.task, task) } +
                TaskGraphOp.Connect("resolution", task),
            reason = "Resolve integration $task within its approved envelope", expectedRevision = graph.revision))
        val resolution = applied.tasks.single { value -> original.none { it.id == value.id } }
            .let { value -> value.copy(graph = requireNotNull(value.graph).copy(repairOf = task, worktreeId = tree.id)) }
        commit(applied.graph, applied.tasks.map { if (it.id == resolution.id) resolution else it }, applied.removed, next)
        return resolution
    }

    @Synchronized internal fun freezeBase(id: String, commits: Map<String, String>): Map<String, String> {
        val current = graph(id)
        if (current.baseCommits.isNotEmpty()) {
            require(current.baseCommits == commits) { "The graph baseline is already frozen" }
            return current.baseCommits
        }
        require(commits.size in 1..16 && commits.all { (source, commit) -> source.length in 1..4096 && commit.matches(Regex("[a-f0-9]{40,64}")) })
        commit(current.copy(baseCommits = commits, revision = current.revision + 1, updatedAt = clock()), emptyList(), emptyList(), ledger.next)
        return commits
    }

    @Synchronized internal fun sourceInputs(task: String): List<BoardTask> {
        val plan = requireNotNull(get(task).graph)
        val members = members(plan.id).associateBy { it.id }
        val pending = ArrayDeque(plan.dependsOn.filter { it.required }.map { it.task })
        val seen = mutableSetOf<String>()
        val inputs = mutableListOf<BoardTask>()
        while (pending.isNotEmpty()) {
            val next = members.getValue(pending.removeFirst())
            if (!seen.add(next.id)) continue
            val source = requireNotNull(next.graph)
            if (source.state == TaskState.Succeeded && source.worktreeId != null && source.outcome?.candidateId != null) inputs += next
            else pending.addAll(source.dependsOn.filter { it.required }.map { it.task })
        }
        return inputs.sortedBy { it.id.removePrefix("T").toIntOrNull() ?: Int.MAX_VALUE }
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
        require(plan.recovery != AgentRecovery.NeedsReview) { "Reconcile the interrupted attempt in Hangar before reporting a result" }
        require(plan.state != TaskState.Limited) { "A Limited attempt waits for a person to retry it" }
        val callerAttempt = plan.attemptOf(caller.refs)
        val refusal = "An agent reports only on its own task's current attempt; $task current attempt ${plan.attempt}, caller attempt ${callerAttempt ?: "unlinked"}${outcome.attempt?.let { ", reported attempt $it" }.orEmpty()}"
        if (plan.kind == TaskKind.Gate) require(caller.person) { "Only a person passes a gate" }
        check(caller.person || plan.state != TaskState.Pending &&
            (callerAttempt == plan.attempt || caller.lead && plan.seat?.lead == true)) { refusal }
        check(outcome.attempt == null || outcome.attempt == plan.attempt) { refusal }
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
                checks = bounded.checks.ifEmpty { old.checks }, log = bounded.log ?: old.log, tokens = bounded.tokens ?: old.tokens, at = now, attempt = plan.attempt)
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
            value.copy(work = (value.work + session).distinct(), graph = plan.copy(state = TaskState.Running, attempt = plan.attempt + 1, run = session,
                outcome = null, workAttempts = plan.workAttempts + (session to plan.attempt + 1), resolvedSeat = resolveSeat(requireNotNull(plan.seat))),
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
