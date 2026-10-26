package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.WorkflowContract

data class TaskGraphCaller(val by: BoardActor, val lead: Boolean = false, val own: Set<String> = emptySet(), val refs: Set<String> = setOfNotNull(by.session)) {
    val person: Boolean get() = by.kind == BoardActorKind.Person
    val agent: Boolean get() = by.kind == BoardActorKind.Agent

    fun owns(task: BoardTask): Boolean = task.id in own || by.session != null && task.createdBy.session == by.session
}

class TaskGraphConflict(message: String) : IllegalStateException(message)

class TaskGraphOutside(val reasons: List<String>) : IllegalStateException(
    "This change is outside the graph's envelope: ${reasons.joinToString("; ")}. A person approves it in Hangar, then repeat the identical call.")

data class TaskGraphApplied(val graph: TaskGraph, val tasks: List<BoardTask>, val removed: List<String>, val summary: List<String>, val undo: List<TaskGraphOp>)

object BoardText {
    fun title(value: String): String = value.trim().also { require(it.isNotEmpty() && it.length <= 200 && '\n' !in it) { "A title is one line of 1 to 200 characters" } }

    fun bounded(value: String, name: String, limit: Int): String = value.trim().also { require(it.length <= limit) { "$name is at most $limit characters" } }

    fun lines(values: List<String>, name: String, count: Int, limit: Int): List<String> = values.map { bounded(it, name, limit) }.filter(String::isNotEmpty).also {
        require(it.size <= count) { "$name has at most $count items" }
    }
}

object TaskGraphRules {
    const val MaxTasks = 200
    const val OutcomeChars = 8_000
    val Settled = setOf(TaskState.Succeeded, TaskState.Failed, TaskState.Skipped, TaskState.Cancelled)
    val Open = setOf(TaskState.Pending, TaskState.Running, TaskState.Waiting, TaskState.Limited)

    fun active(dependency: TaskDependency, upstream: BoardTask?): Boolean {
        val plan = upstream?.graph ?: return false
        val verdict = plan.outcome?.verdict
        return when (dependency.condition) {
            TaskCondition.Always -> plan.state == TaskState.Succeeded || plan.state == TaskState.Failed
            TaskCondition.Succeeded -> plan.state == TaskState.Succeeded
            TaskCondition.Failed -> plan.state == TaskState.Failed
            TaskCondition.Pass -> plan.state == TaskState.Succeeded && verdict == "pass"
            TaskCondition.Repair -> plan.state == TaskState.Succeeded && verdict == "repair"
            TaskCondition.Accepted -> plan.state == TaskState.Succeeded && (verdict == "accepted" || upstream.stage == BoardStage.Done)
        }
    }

    fun settled(dependency: TaskDependency, upstream: BoardTask?): Boolean {
        val plan = upstream?.graph ?: return true
        if (plan.state !in Settled) return false
        return dependency.condition != TaskCondition.Accepted || plan.state != TaskState.Succeeded || active(dependency, upstream)
    }

    sealed interface Readiness {
        data object Waiting : Readiness
        data object Ready : Readiness
        data object Skip : Readiness
        data class Blocked(val reasons: List<String>) : Readiness
    }

    fun readiness(task: BoardTask, tasks: Map<String, BoardTask>): Readiness {
        val plan = task.graph ?: return Readiness.Waiting
        if (plan.state != TaskState.Pending || plan.kind == TaskKind.Note) return Readiness.Waiting
        val dependencies = plan.dependsOn.filter { tasks[it.task]?.graph?.kind != TaskKind.Note }
        if (plan.choices.isNotEmpty()) return if (plan.choices.values.all { tasks[it]?.graph?.state in Settled }) Readiness.Ready else Readiness.Waiting
        if (dependencies.isEmpty()) return Readiness.Ready
        if (!dependencies.all { settled(it, tasks[it.task]) }) return Readiness.Waiting
        val unmet = dependencies.filter { it.required && !active(it, tasks[it.task]) }
        if (unmet.isNotEmpty() && TaskChoices.owner(task.id, tasks) != null) return Readiness.Skip
        if (unmet.isNotEmpty()) return Readiness.Blocked(unmet.map {
            "${task.id} requires ${it.task} ${it.condition.name}, but ${it.task} is ${tasks[it.task]?.graph?.state?.name ?: "missing"}"
        })
        val alternatives = dependencies.filterNot { it.required }
        return if (alternatives.isEmpty() || alternatives.any { active(it, tasks[it.task]) }) Readiness.Ready else Readiness.Skip
    }

    fun ready(tasks: Map<String, BoardTask>): List<BoardTask> {
        val dependents = dependents(tasks)
        return tasks.values.filter { readiness(it, tasks) == Readiness.Ready }.sortedWith(
            compareByDescending<BoardTask> { reach(it.id, dependents) }.thenBy { it.graph?.readySince ?: it.createdAt }.thenBy { it.number })
    }

    fun dependents(tasks: Map<String, BoardTask>): Map<String, List<String>> =
        tasks.values.flatMap { task -> task.graph?.dependsOn.orEmpty().map { it.task to task.id } }.groupBy({ it.first }, { it.second })

    fun reach(id: String, dependents: Map<String, List<String>>): Int {
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque(dependents[id].orEmpty())
        while (queue.isNotEmpty()) { val next = queue.removeFirst(); if (seen.add(next)) queue.addAll(dependents[next].orEmpty()) }
        return seen.size
    }

    fun cycle(tasks: Map<String, BoardTask>): List<String>? {
        val state = mutableMapOf<String, Int>()
        val path = ArrayDeque<String>()
        fun visit(id: String): List<String>? {
            when (state[id]) { 1 -> return path.dropWhile { it != id } + id; 2 -> return null }
            state[id] = 1; path.addLast(id)
            tasks[id]?.graph?.dependsOn.orEmpty().forEach { dependency -> if (dependency.task in tasks) visit(dependency.task)?.let { return it } }
            path.removeLast(); state[id] = 2
            return null
        }
        tasks.keys.sorted().forEach { visit(it)?.let { found -> return found } }
        return null
    }

    fun finished(tasks: Map<String, BoardTask>): TaskGraphStatus? {
        val working = tasks.values.filter { it.graph?.kind != TaskKind.Note }
        if (working.isEmpty() || working.any { it.graph?.state in Open }) return null
        val dependents = dependents(tasks)
        val unhandled = working.filter { task -> task.graph?.state == TaskState.Failed &&
            TaskChoices.owner(task.id, tasks)?.graph?.state != TaskState.Succeeded && dependents[task.id].orEmpty().none { id ->
            tasks[id]?.graph?.dependsOn?.any { it.task == task.id && (it.condition == TaskCondition.Failed || it.condition == TaskCondition.Always) } == true &&
                tasks[id]?.graph?.state == TaskState.Succeeded
        } }
        return if (unhandled.isEmpty() && working.any { it.graph?.state == TaskState.Succeeded }) TaskGraphStatus.Finished else TaskGraphStatus.Failed
    }

    fun unhandledFailure(task: BoardTask, tasks: Map<String, BoardTask>): Boolean =
        task.graph?.state == TaskState.Failed && task.graph.held.not() && TaskChoices.owner(task.id, tasks) == null && dependents(tasks)[task.id].orEmpty().any { id ->
            tasks[id]?.graph?.dependsOn?.any { it.task == task.id && it.condition != TaskCondition.Failed && it.condition != TaskCondition.Always } == true
        }

    fun outside(seat: TaskSeat?, scope: List<String>, envelope: TaskEnvelope): List<String> = buildList {
        if (seat == null || seat.lead) return@buildList
        val agent = seat.agent ?: return@buildList
        if (agent !in envelope.agents) add("${agent.name} is not one of the envelope's agents (${envelope.agents.joinToString { it.name }.ifEmpty { "none" }})")
        seat.model?.let { model -> if (envelope.models.isNotEmpty() && model !in envelope.models) add("model $model is not one the envelope allows") }
        seat.effort?.let { effort -> if (envelope.efforts.isNotEmpty() && effort !in envelope.efforts) add("effort $effort is not one the envelope allows") }
        if (seat.access == TaskAccess.Write) {
            if (scope.isEmpty()) add("a writing task names the modules or paths it writes in its scope")
            scope.filterNot { entry -> envelope.writes.any { covers(it, entry) } }.forEach { add("$it is outside the envelope's writable modules (${envelope.writes.joinToString().ifEmpty { "none" }})") }
        }
    }

    fun covers(allowed: String, entry: String): Boolean {
        val a = allowed.trim().trimEnd('/', ':'); val b = entry.trim().trimEnd('/', ':')
        return a.isEmpty() || a == "*" || b == a || b.startsWith("$a/") || b.startsWith("$a:")
    }

    fun overlaps(first: List<String>, second: List<String>): Boolean = first.any { a -> second.any { b -> covers(a, b) || covers(b, a) } }

    fun warnings(tasks: Map<String, BoardTask>, modules: Map<String, List<String>> = emptyMap()): List<TaskGraphWarning> {
        val working = tasks.values.filter { it.graph != null && it.graph.kind != TaskKind.Note }.sortedBy { it.number }
        val downstream = dependents(working.associateBy { it.id })
        fun descendants(id: String): Set<String> {
            val found = mutableSetOf<String>()
            val queue = ArrayDeque(downstream[id].orEmpty())
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                if (next != id && found.add(next)) queue.addAll(downstream[next].orEmpty())
            }
            return found
        }
        val after = working.associate { it.id to descendants(it.id) }
        val writers = working.filter { it.graph?.kind == TaskKind.Merge ||
            it.graph?.let { plan -> plan.kind == TaskKind.Agent && plan.seat?.access == TaskAccess.Write } == true }
        fun verifies(task: BoardTask): Boolean = task.graph?.let { plan ->
            plan.kind == TaskKind.Check || plan.kind == TaskKind.Agent && plan.seat?.access != TaskAccess.Write &&
                (plan.contract == WorkflowContract.Decision || plan.seat?.profile?.contains("review", ignoreCase = true) == true ||
                    task.title.contains("review", ignoreCase = true))
        } == true
        return buildList {
            writers.forEach { writer ->
                if (after[writer.id].orEmpty().none { id -> tasks[id]?.let(::verifies) == true })
                    add(TaskGraphWarning(TaskGraphWarningCode.WriterWithoutVerification, listOf(writer.id),
                        "${writer.id} writes source without a later review or Check. Add independent verification after it."))
            }
            val unchecked = working.filter { task ->
                downstream[task.id].isNullOrEmpty() && task.graph?.kind != TaskKind.Check &&
                    working.none { it.graph?.kind == TaskKind.Check && task.id in after[it.id].orEmpty() }
            }
            if (unchecked.isNotEmpty()) add(TaskGraphWarning(TaskGraphWarningCode.TerminalWithoutCheck, unchecked.map { it.id },
                "No Check precedes ${unchecked.joinToString { it.id }}. Add a Check on each completion path."))
            writers.forEachIndexed { index, first ->
                val peers = writers.drop(index + 1).filter { second ->
                    val moduleAlias = (first.scope + second.scope).any { it.startsWith(":") || ':' in it && !it.contains('/') }
                    val sharedModule = moduleAlias && modules[first.id].orEmpty().any { it in modules[second.id].orEmpty() }
                    (overlaps(first.scope, second.scope) || sharedModule) &&
                        second.id !in after[first.id].orEmpty() && first.id !in after[second.id].orEmpty()
                }
                if (peers.isNotEmpty()) add(TaskGraphWarning(TaskGraphWarningCode.OverlappingWriters, listOf(first.id) + peers.map { it.id },
                    "${first.id} and ${peers.joinToString { it.id }} write overlapping scopes without a dependency. Order them or split their scopes."))
            }
            working.forEach { task ->
                val owned = modules[task.id].orEmpty().distinct().sorted()
                if (owned.size >= 4) add(TaskGraphWarning(TaskGraphWarningCode.BroadScope, listOf(task.id),
                    "${task.id} spans ${owned.size} radar-resolved modules. Split it by module or a shared interface.", owned))
                if ((task.acceptance.isEmpty() && task.graph?.check.isNullOrBlank()) ||
                    task.acceptance.any { !observableAcceptance(it) })
                    add(TaskGraphWarning(TaskGraphWarningCode.UnclearAcceptance, listOf(task.id),
                        "${task.id} has acceptance that names no check or observable result. State what must pass or what the user must observe."))
            }
        }
    }

    private val acceptanceOutcome = Regex("\\b(test(?:s|ed)?|check(?:s|ed)?|assert(?:s|ion)?|receipt|pass(?:es)?|fail(?:s)?|return(?:s)?|show(?:s)?|render(?:s)?|display(?:s)?|parse(?:s)?|reject(?:s)?|accept(?:s)?|save(?:s|d)?|persist(?:s)?|contain(?:s)?|equal(?:s)?|produce(?:s)?|emit(?:s)?|open(?:s)?|close(?:s)?|remain(?:s)?|preserve(?:s)?|retain(?:s)?|visible|enabled|disabled|blocked|refused|succeed(?:s)?|compile(?:s)?|build(?:s)?|runs?)\\b|:[A-Za-z][A-Za-z0-9_-]*:[A-Za-z]", RegexOption.IGNORE_CASE)

    private fun observableAcceptance(line: String): Boolean = acceptanceOutcome.containsMatchIn(line)

    fun validSeat(seat: TaskSeat?) {
        if (seat == null) return
        require(seat.lead != (seat.agent != null || seat.profile != null)) { "A seat is either lead or an agent/profile with model, effort and access overrides" }
        seat.profile?.let { require(it.matches(Regex("[a-z][a-z0-9-]{0,63}"))) { "A profile id is 1 to 64 lowercase letters, digits or dashes, starting with a letter" } }
        seat.model?.let { require(it.length in 1..256) { "A model name has 1 to 256 characters" } }
        seat.effort?.let { require(it.length in 1..32) { "An effort is a short provider token" } }
        require(seat.lenses.isEmpty() || !seat.lead && seat.access != TaskAccess.Write && seat.lenses.size in 2..6) { "A read-only panel has two to six lens profiles" }
        require(seat.lenses.distinct().size == seat.lenses.size && seat.lenses.all { it.matches(Regex("[a-z][a-z0-9-]{0,63}")) }) { "Use distinct lens profile ids" }
    }
}

class TaskGraphEditing(
    private val graph: TaskGraph,
    tasks: List<BoardTask>,
    private val caller: TaskGraphCaller,
    private val now: Long,
    private val resolveSeat: (TaskSeat) -> TaskSeat = { TaskSeatResolver().resolve(it).seat },
    private val allocate: () -> String,
) {
    private val members = tasks.filter { it.graph?.id == graph.id }.associateBy { it.id }.toMutableMap()
    private val original = members.toMap()
    private val touched = linkedSetOf<String>()
    private val removed = linkedSetOf<String>()
    private val keys = mutableMapOf<String, String>()
    private val outside = mutableListOf<String>()
    private val summary = mutableListOf<String>()
    private val undo = mutableListOf<TaskGraphOp>()
    private var envelope = graph.envelope
    private val revision = graph.revision + 1

    fun apply(patch: TaskGraphPatch, approvedBy: String? = null): TaskGraphApplied {
        require(patch.ops.isNotEmpty() && patch.ops.size <= 100) { "A change has 1 to 100 operations" }
        val reason = BoardText.bounded(patch.reason, "reason", 500)
        require(reason.isNotEmpty() || caller.person) { "Give a reason for the change" }
        require(graph.status !in setOf(TaskGraphStatus.Finished, TaskGraphStatus.Failed, TaskGraphStatus.Cancelled) || caller.person) {
            "${graph.id} is ${graph.status.name.lowercase()}; only a person changes it now"
        }
        if (!caller.person) requireNotNull(patch.expectedRevision) { "Name the graph revision you read as expectedRevision; taskgraph.read shows it" }
        patch.ops.forEach { op -> conflicts(op, patch.expectedRevision); apply(op) }
        members.values.forEach { task ->
            val plan = task.graph ?: return@forEach
            if (!plan.seat?.lenses.isNullOrEmpty()) require(plan.kind == TaskKind.Agent && plan.contract == WorkflowContract.Decision && resolveSeat(requireNotNull(plan.seat)).access == TaskAccess.Read) { "Lens profiles belong to a read-only Decision task" }
        }
        TaskChoices.validate(members, resolveSeat)
        TaskGraphRules.cycle(members)?.let { cycle -> error("This change would make a cycle: ${cycle.joinToString(" → ")}") }
        val size = members.values.count { it.graph?.kind != TaskKind.Note }
        require(size <= TaskGraphRules.MaxTasks) { "A graph holds at most ${TaskGraphRules.MaxTasks} tasks" }
        if (!caller.person && size > envelope.maxTasks && size > original.values.count { it.graph?.kind != TaskKind.Note })
            outside += "the graph would hold $size tasks; the envelope allows ${envelope.maxTasks}"
        if (outside.isNotEmpty() && approvedBy == null) throw TaskGraphOutside(outside.distinct())
        if (outside.isNotEmpty()) summary += "Outside the envelope, allowed by $approvedBy: ${outside.distinct().joinToString("; ")}"
        val changed = touched.filter { it !in removed }.mapNotNull { id -> members[id]?.let { task ->
            task.copy(graph = task.graph?.copy(changedAt = revision), updatedAt = now)
        } }
        val recorded = if (summary.isEmpty()) graph.changes
            else (graph.changes + TaskGraphChange(revision, now, caller.by, reason.ifEmpty { "Edited in Hangar" }, summary.toList(), undo.asReversed().toList())).takeLast(200)
        val next = graph.copy(envelope = envelope, revision = revision, updatedAt = now, changes = recorded)
        return TaskGraphApplied(next, changed, removed.toList(), summary.toList(), undo.asReversed().toList())
    }

    private fun conflicts(op: TaskGraphOp, expected: Long?) {
        if (expected == null) return
        subjects(op).forEach { id ->
            val task = original[id] ?: return@forEach
            if ((task.graph?.changedAt ?: 0) > expected) throw TaskGraphConflict("$id changed at revision ${task.graph?.changedAt}, after the revision $expected you read; read the graph again")
        }
    }

    private fun subjects(op: TaskGraphOp): List<String> = when (op) {
        is TaskGraphOp.Add, is TaskGraphOp.Note, is TaskGraphOp.Envelope -> emptyList()
        is TaskGraphOp.Update -> listOf(op.task)
        is TaskGraphOp.Split -> listOf(op.task)
        is TaskGraphOp.Remove -> listOf(op.task)
        is TaskGraphOp.Connect -> listOf(op.to)
        is TaskGraphOp.Disconnect -> listOf(op.to)
        is TaskGraphOp.Seat -> listOf(op.task)
        is TaskGraphOp.Retry -> listOf(op.task)
        is TaskGraphOp.Restore -> listOf(op.task)
    }

    private fun id(ref: String): String = keys[ref] ?: ref

    private fun task(ref: String): BoardTask = members[id(ref)] ?: error("${id(ref)} is not a task in ${graph.id}")

    private fun plan(task: BoardTask): TaskPlan = requireNotNull(task.graph)

    private fun put(task: BoardTask) { members[task.id] = task; touched += task.id }

    private fun refuse(message: String): Nothing = throw IllegalArgumentException(message)

    private fun apply(op: TaskGraphOp) {
        when (op) {
            is TaskGraphOp.Add -> add(op)
            is TaskGraphOp.Update -> update(op)
            is TaskGraphOp.Split -> split(op)
            is TaskGraphOp.Remove -> remove(op.task)
            is TaskGraphOp.Connect -> connect(op)
            is TaskGraphOp.Disconnect -> disconnect(op)
            is TaskGraphOp.Seat -> seat(op)
            is TaskGraphOp.Note -> note(op)
            is TaskGraphOp.Retry -> retry(op.task)
            is TaskGraphOp.Envelope -> envelope(op.envelope)
            is TaskGraphOp.Restore -> restore(op)
        }
    }

    private fun add(op: TaskGraphOp.Add, parent: String? = op.parent?.let(::id)): BoardTask {
        op.alternatives?.let { return alternatives(op, it, parent) }
        val dependsOn = op.dependsOn.map { it.copy(task = id(it.task)) }.distinctBy { it.task }
        dependsOn.forEach { require(it.task in members) { "${it.task} is not a task in ${graph.id}" } }
        parent?.let { require(it in members) { "$it is not a task in ${graph.id}" } }
        if (!caller.person && !caller.lead) {
            val anchored = listOfNotNull(parent) + dependsOn.map { it.task }
            if (anchored.none { ref -> members[ref]?.let(caller::owns) == true || ref in keys.values }) refuse("An agent adds tasks as subtasks of its own task or after it; name parent or a dependency on your task")
        }
        TaskGraphRules.validSeat(op.seat)
        val effective = op.seat?.let(resolveSeat)
        if (op.kind == TaskKind.Check) require(!op.check.isNullOrBlank()) { "A Check task names the check it runs" }
        if (!caller.person) outside += (listOfNotNull(effective) + op.seat?.lenses.orEmpty().map { resolveSeat(TaskSeat(profile = it, access = TaskAccess.Read)) }).flatMap { TaskGraphRules.outside(it, op.scope, envelope) }
        op.key?.let { require(it.matches(KeyPattern)) { "A key is 1 to 40 letters, digits, dashes or underscores" }; require(it !in keys) { "The key $it is used twice" } }
        val id = allocate()
        val task = BoardTask(
            id = id,
            title = BoardText.title(op.title),
            intent = BoardText.bounded(op.intent, "intent", 20_000),
            acceptance = BoardText.lines(op.acceptance, "acceptance", 30, 500),
            scope = BoardText.lines(op.scope, "scope", 100, 500),
            stage = BoardStage.Planning,
            parent = parent,
            createdBy = caller.by,
            createdAt = now,
            updatedAt = now,
            stageChangedAt = now,
            history = listOf(BoardEvent(now, caller.by, "Added to ${graph.id}")),
            graph = TaskPlan(graph.id, dependsOn, op.seat, op.kind, op.contract, op.check?.trim()?.takeIf(String::isNotEmpty), layout = op.layout),
        )
        op.key?.let { keys[it] = id }
        put(task)
        summary += "Added $id ${task.title}" + dependsOn.takeIf { it.isNotEmpty() }?.let { list -> " after ${list.joinToString { it.task }}" }.orEmpty()
        undo += TaskGraphOp.Remove(id)
        return task
    }

    private fun alternatives(op: TaskGraphOp.Add, alternatives: TaskAlternatives, parent: String?): BoardTask {
        require(op.kind == TaskKind.Agent && op.contract == WorkflowContract.Text && op.seat == null) { "Alternatives belong to a writing task; put its seats in alternatives" }
        require(alternatives.seats.size in 2..3 && alternatives.check.isNotBlank()) { "Alternatives name two or three writing seats and a check" }
        require(alternatives.seats.all { !it.lead && resolveSeat(it).access == TaskAccess.Write && it.lenses.isEmpty() }) { "Every alternative is an owned writing seat" }
        require(!alternatives.review.lead && resolveSeat(alternatives.review).access == TaskAccess.Read && alternatives.review.lenses.size in 2..6) { "Each alternative needs a read-only review panel with two to six lenses" }
        val choices = alternatives.seats.mapIndexed { index, seat ->
            val writer = add(op.copy(title = op.title.take(170) + " · attempt ${index + 1}", key = null, seat = seat, alternatives = null), parent)
            val check = add(TaskGraphOp.Add("Check ${writer.id}", scope = op.scope, kind = TaskKind.Check, check = alternatives.check,
                dependsOn = listOf(TaskDependency(writer.id)), parent = writer.id))
            val review = add(TaskGraphOp.Add("Review ${writer.id}", intent = op.intent, acceptance = op.acceptance, scope = op.scope,
                seat = alternatives.review, contract = WorkflowContract.Decision, dependsOn = listOf(TaskDependency(check.id)), parent = writer.id))
            writer.id to review.id
        }.toMap()
        val selection = add(op.copy(kind = TaskKind.Gate, seat = null, alternatives = null,
            dependsOn = choices.values.map { TaskDependency(it, TaskCondition.Always, required = false) }), parent)
        put(selection.copy(graph = requireNotNull(selection.graph).copy(choices = choices)))
        choices.keys.forEach { writer -> put(members.getValue(writer).copy(parent = selection.id)) }
        return members.getValue(selection.id)
    }

    private fun update(op: TaskGraphOp.Update) {
        val task = task(op.task)
        val plan = plan(task)
        val text = op.title != null || op.intent != null || op.acceptance != null || op.scope != null || op.kind != null || op.contract != null || op.check != null
        if (!caller.person && text) when {
            caller.lead -> if (task.createdBy.kind != BoardActorKind.Agent) refuse("The lead edits only tasks that agents added; ${task.id} was added by a person")
            caller.owns(task) -> if (op.acceptance != null && task.createdBy.kind == BoardActorKind.Person) refuse("A person wrote ${task.id}'s acceptance; ask them to change it")
            else -> refuse("An agent edits only its own task")
        }
        if (op.excludedContext != null) {
            require(caller.person) { "Only a person changes optional task context" }
            require(plan.state == TaskState.Pending) { "Task context is pinned after dispatch" }
            require(op.excludedContext.size <= 100 && op.excludedContext.all { it.length <= 200 && (it.startsWith("memory:") || it.startsWith("decision:")) }) { "Only optional decision and memory references can be excluded" }
        }
        if (op.kind != null || op.contract != null) require(plan.state == TaskState.Pending) { "${task.id} has started; its kind and contract stay as they are" }
        val next = task.copy(
            title = op.title?.let(BoardText::title) ?: task.title,
            intent = op.intent?.let { BoardText.bounded(it, "intent", 20_000) } ?: task.intent,
            acceptance = op.acceptance?.let { BoardText.lines(it, "acceptance", 30, 500) } ?: task.acceptance,
            scope = op.scope?.let { BoardText.lines(it, "scope", 100, 500) } ?: task.scope,
            graph = plan.copy(
                kind = op.kind ?: plan.kind,
                contract = op.contract ?: plan.contract,
                check = op.check?.trim()?.takeIf(String::isNotEmpty) ?: plan.check,
                layout = op.layout ?: plan.layout,
                excludedContext = op.excludedContext?.distinct() ?: plan.excludedContext,
            ),
        )
        if (!caller.person && op.scope != null) outside += TaskGraphRules.outside(plan.seat?.let(resolveSeat), next.scope, envelope)
        val fields = listOfNotNull(
            "title".takeIf { next.title != task.title }, "intent".takeIf { next.intent != task.intent },
            "acceptance".takeIf { next.acceptance != task.acceptance }, "scope".takeIf { next.scope != task.scope },
            "kind".takeIf { next.graph?.kind != plan.kind }, "contract".takeIf { next.graph?.contract != plan.contract },
            "check".takeIf { next.graph?.check != plan.check },
            "excludedContext".takeIf { next.graph?.excludedContext != plan.excludedContext },
        )
        val moved = next.graph?.layout != plan.layout
        if (fields.isEmpty() && !moved) return
        put(if (fields.isEmpty()) next else next.copy(revision = task.revision + 1, history = (task.history + BoardEvent(now, caller.by, "Edited ${fields.joinToString(", ")}")).takeLast(50)))
        if (fields.isNotEmpty()) summary += "Edited ${task.id} ${fields.joinToString(", ")}"
        undo += TaskGraphOp.Update(task.id,
            title = task.title.takeIf { "title" in fields }, intent = task.intent.takeIf { "intent" in fields },
            acceptance = task.acceptance.takeIf { "acceptance" in fields }, scope = task.scope.takeIf { "scope" in fields },
            kind = plan.kind.takeIf { "kind" in fields }, contract = plan.contract.takeIf { "contract" in fields },
            check = plan.check.takeIf { "check" in fields }, layout = plan.layout.takeIf { moved },
            excludedContext = plan.excludedContext.takeIf { "excludedContext" in fields })
    }

    private fun split(op: TaskGraphOp.Split) {
        val task = task(op.task)
        val plan = plan(task)
        require(op.into.size in 2..12) { "Split a task into 2 to 12 subtasks" }
        if (!caller.person && !caller.lead && !caller.owns(task)) refuse("An agent splits only its own task")
        require(plan.state == TaskState.Pending || plan.state == TaskState.Running) { "${task.id} is ${plan.state.name.lowercase()}; split a pending or running task" }
        val dependents = members.values.filter { member -> member.graph?.dependsOn?.any { it.task == task.id } == true }
        val before = dependents.associate { it.id to plan(it).dependsOn }
        val created = op.into.map { sub -> add(sub.copy(dependsOn = (plan.dependsOn + sub.dependsOn).distinctBy { it.task }), parent = task.id) }
        dependents.forEach { dependent ->
            val existing = plan(dependent).dependsOn
            val dependency = existing.first { it.task == task.id }
            val extra = created.map { dependency.copy(task = it.id) }
            val next = if (plan.state == TaskState.Pending) existing.filterNot { it.task == task.id } + extra else existing + extra
            put(dependent.copy(graph = plan(dependent).copy(dependsOn = next.distinctBy { it.task })))
        }
        if (plan.state == TaskState.Pending) {
            put(task.copy(graph = plan.copy(state = TaskState.Cancelled, note = "Split into ${created.joinToString { it.id }}")))
            undo += TaskGraphOp.Restore(task.id, plan.dependsOn, plan.seat, plan.state)
        }
        before.forEach { (id, dependsOn) -> undo += TaskGraphOp.Restore(id, dependsOn, plan(members.getValue(id)).seat, plan(members.getValue(id)).state) }
        summary += "Split ${task.id} into ${created.joinToString { it.id }}"
    }

    private fun remove(ref: String) {
        val task = task(ref)
        val plan = plan(task)
        val choice = plan.choices.isNotEmpty() || TaskChoices.owner(task.id, members) != null
        if (choice && plan.state == TaskState.Cancelled) return
        if (plan.choices.isNotEmpty() && plan.state in TaskGraphRules.Open) {
            members.values.filter { TaskChoices.owner(it.id, members)?.id == task.id && it.graph?.state in TaskGraphRules.Open }
                .map { it.id }.forEach(::remove)
        }
        if (!caller.person) when {
            caller.lead -> if (task.createdBy.kind != BoardActorKind.Agent || plan.state != TaskState.Pending) refuse("The lead removes only pending tasks that agents added")
            task.createdBy.session == null || task.createdBy.session != caller.by.session -> refuse("An agent removes only pending tasks it added")
            plan.state != TaskState.Pending -> refuse("${task.id} has started; it can only be cancelled")
        }
        if (plan.state == TaskState.Pending && (plan.choices.isNotEmpty() || TaskChoices.owner(task.id, members) != null)) {
            undo += TaskGraphOp.Restore(task.id, plan.dependsOn, plan.seat, plan.state)
            put(task.copy(graph = plan.copy(state = TaskState.Cancelled, note = "Cancelled by ${caller.by.name}; alternatives retain their evidence")))
            summary += "Cancelled ${task.id} ${task.title}"
            return
        }
        when (plan.state) {
            TaskState.Pending -> {
                val dependents = members.values.filter { member -> member.graph?.dependsOn?.any { it.task == task.id } == true }
                dependents.forEach { dependent ->
                    val dependency = plan(dependent).dependsOn.first { it.task == task.id }
                    put(dependent.copy(graph = plan(dependent).copy(dependsOn = plan(dependent).dependsOn - dependency)))
                    undo += TaskGraphOp.Connect(task.id, dependent.id, dependency.condition, dependency.required)
                }
                members.remove(task.id); removed += task.id; touched -= task.id
                summary += "Removed ${task.id} ${task.title}"
                undo += TaskGraphOp.Add(task.title, key = task.id, intent = task.intent, acceptance = task.acceptance, scope = task.scope, kind = plan.kind,
                    seat = plan.seat, contract = plan.contract, check = plan.check, dependsOn = plan.dependsOn, layout = plan.layout, parent = task.parent)
            }
            TaskState.Running, TaskState.Waiting, TaskState.Limited -> {
                put(task.copy(graph = plan.copy(state = TaskState.Cancelled, note = "Cancelled by ${caller.by.name}")))
                summary += "Cancelled ${task.id} ${task.title}"
            }
            else -> refuse("${task.id} is ${plan.state.name.lowercase()}; finished tasks stay on the record")
        }
    }

    private fun connect(op: TaskGraphOp.Connect) {
        val from = task(op.from); val to = task(op.to)
        require(from.id != to.id) { "A task cannot depend on itself" }
        if (!caller.person) when {
            caller.lead -> if (plan(to).state != TaskState.Pending || plan(from).state != TaskState.Pending &&
                plan(to).dependsOn.none { it.task == from.id && it.condition == op.condition }) refuse("The lead connects pending tasks or updates an existing route to a pending task")
            !(caller.owns(from) && to.createdBy.session != null && to.createdBy.session == caller.by.session) -> refuse("An agent connects its own task to tasks it added")
        }
        require(plan(to).state == TaskState.Pending) { "${to.id} has started; its dependencies stay as they are" }
        val existing = plan(to).dependsOn.firstOrNull { it.task == from.id }
        if (existing?.condition == op.condition && existing.required == op.required) return
        if (op.condition in setOf(TaskCondition.Pass, TaskCondition.Repair)) require(plan(from).contract == WorkflowContract.Decision) { "${from.id} needs the Decision contract for a ${op.condition.name} dependency" }
        put(to.copy(graph = plan(to).copy(dependsOn = plan(to).dependsOn.filterNot { it.task == from.id } + TaskDependency(from.id, op.condition, op.required))))
        summary += "${to.id} depends on ${from.id}" + (if (op.condition == TaskCondition.Succeeded) "" else " (${op.condition.name})") + if (op.required) "" else " (alternative)"
        undo += existing?.let { TaskGraphOp.Connect(from.id, to.id, it.condition, it.required) } ?: TaskGraphOp.Disconnect(from.id, to.id)
    }

    private fun disconnect(op: TaskGraphOp.Disconnect) {
        val from = task(op.from); val to = task(op.to)
        val existing = plan(to).dependsOn.firstOrNull { it.task == from.id } ?: return
        if (!caller.person) when {
            caller.lead -> if (plan(to).state != TaskState.Pending) refuse("The lead disconnects pending tasks")
            !(caller.owns(from) && to.createdBy.session != null && to.createdBy.session == caller.by.session) -> refuse("An agent disconnects its own task from tasks it added")
        }
        require(plan(to).state == TaskState.Pending) { "${to.id} has started; its dependencies stay as they are" }
        put(to.copy(graph = plan(to).copy(dependsOn = plan(to).dependsOn - existing)))
        summary += "${to.id} no longer depends on ${from.id}"
        undo += TaskGraphOp.Connect(from.id, to.id, existing.condition, existing.required)
    }

    private fun seat(op: TaskGraphOp.Seat) {
        val task = task(op.task)
        val plan = plan(task)
        TaskGraphRules.validSeat(op.seat)
        val effective = resolveSeat(op.seat)
        if (!caller.person && !caller.lead) refuse("Only a person or the lead changes who does a task")
        require(plan.state == TaskState.Pending) { "${task.id} has started; stop it before giving it to someone else" }
        if (!caller.person) outside += (listOf(effective) + op.seat.lenses.map { resolveSeat(TaskSeat(profile = it, access = TaskAccess.Read)) }).flatMap { TaskGraphRules.outside(it, task.scope, envelope) }
        if (plan.seat == op.seat) return
        put(task.copy(graph = plan.copy(seat = op.seat)))
        summary += "${task.id} goes to ${op.seat.describe()}"
        undo += TaskGraphOp.Restore(task.id, plan.dependsOn, plan.seat, plan.state)
    }

    private fun note(op: TaskGraphOp.Note) {
        if (!caller.person && !caller.lead && caller.own.isEmpty()) refuse("Only a person, the lead or a task's agent adds notes")
        val text = BoardText.bounded(op.text, "note", 2_000)
        require(text.isNotEmpty()) { "A note has text" }
        val id = allocate()
        put(BoardTask(id, BoardText.title(text.lineSequence().first().take(200)), intent = text, stage = BoardStage.Planning, createdBy = caller.by,
            createdAt = now, updatedAt = now, stageChangedAt = now, history = listOf(BoardEvent(now, caller.by, "Noted on ${graph.id}")),
            graph = TaskPlan(graph.id, kind = TaskKind.Note, layout = op.layout)))
        summary += "Noted $id"
        undo += TaskGraphOp.Remove(id)
    }

    private fun retry(ref: String) {
        val task = task(ref)
        val plan = plan(task)
        if (!caller.person && !caller.lead) refuse("Only a person or the lead retries a task")
        require(plan.recovery != AgentRecovery.NeedsReview) { "Reconcile the interrupted attempt in Hangar before retrying" }
        if (plan.state == TaskState.Limited && !caller.person) refuse("Only a person retries a usage-limited task")
        require(plan.state in setOf(TaskState.Failed, TaskState.Cancelled, TaskState.Skipped, TaskState.Waiting, TaskState.Limited)) { "${task.id} is ${plan.state.name.lowercase()}; retry a failed, cancelled, skipped or waiting task" }
        put(task.copy(graph = plan.copy(state = TaskState.Pending, note = "Retry asked by ${caller.by.name}", readySince = null, resumeAtReset = null)))
        summary += "Retry ${task.id}"
        undo += TaskGraphOp.Restore(task.id, plan.dependsOn, plan.seat, plan.state)
    }

    private fun envelope(next: TaskEnvelope) {
        require(caller.person || next.chooseTop == envelope.chooseTop) { "Only a person changes automatic candidate selection" }
        require(next.maxTasks in 1..TaskGraphRules.MaxTasks && next.perAgent in 1..8 && next.repairRounds in 0..5) { "An envelope allows 1 to ${TaskGraphRules.MaxTasks} tasks, 1 to 8 at once per agent and 0 to 5 repair rounds" }
        require(next.tokens == null || next.tokens > 0) { "A token budget is positive" }
        require(next.writes.size <= 100 && next.models.size <= 50 && next.efforts.size <= 20) { "An envelope names at most 100 writable modules, 50 models and 20 efforts" }
        if (!caller.person) outside += "changing the envelope"
        summary += "Changed the envelope"
        undo += TaskGraphOp.Envelope(envelope)
        envelope = next
    }

    private fun restore(op: TaskGraphOp.Restore) {
        if (!caller.person) refuse("Only a person reverts a change")
        val task = task(op.task)
        op.seat?.let(resolveSeat)
        put(task.copy(graph = plan(task).copy(dependsOn = op.dependsOn.filter { it.task in members }, seat = op.seat, state = op.state)))
        summary += "Restored ${task.id}"
    }

    private companion object {
        val KeyPattern = Regex("[A-Za-z0-9_-]{1,40}")
    }
}

fun TaskSeat.describe(): String = if (lead) "the lead session" else listOfNotNull(profile?.let { "profile:$it" }, agent?.name, model, effort, access?.name?.lowercase(), lenses.takeIf { it.isNotEmpty() }?.joinToString(prefix = "panel: ")).joinToString(" · ")
