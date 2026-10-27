package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import kotlinx.serialization.Serializable

@Serializable
data class TaskGraphTemplate(val id: String, val revision: String, val draft: TaskGraphDraft)

object TaskGraphTemplates {
    fun ordered(draft: TaskGraphDraft): TaskGraphDraft {
        require(draft.lead == null) { "A template cannot retain a lead session" }
        require(draft.tasks.size in 1..TaskGraphRules.MaxTasks)
        val keys = draft.tasks.map { requireNotNull(it.key) { "Every template task needs a profile-slot key" } }
        require(keys.distinct().size == keys.size) { "Template task keys must be distinct" }
        val pending = draft.tasks.toMutableList()
        val done = mutableSetOf<String>()
        val ordered = mutableListOf<TaskGraphOp.Add>()
        while (pending.isNotEmpty()) {
            val task = pending.firstOrNull { task -> task.dependsOn.all { it.task in done } && (task.parent == null || task.parent in done) }
                ?: error("Template dependencies or parents contain a cycle or an unknown task")
            ordered += task
            done += requireNotNull(task.key)
            pending.remove(task)
        }
        return draft.copy(tasks = ordered)
    }

    fun validate(draft: TaskGraphDraft, resolve: (TaskSeat) -> TaskSeat): TaskGraphDraft {
        val ordered = ordered(draft)
        val person = BoardActor(BoardActorKind.Person, "Template validation")
        val shell = TaskGraph("G1", BoardText.title(draft.title), BoardText.bounded(draft.goal, "goal", 20_000),
            envelope = draft.envelope, createdBy = person, createdAt = 0, updatedAt = 0)
        var next = 1
        TaskGraphEditing(shell, emptyList(), TaskGraphCaller(person), 0, resolve) { "T${next++}" }
            .apply(TaskGraphPatch(ordered.tasks, "Validate template"))
        return ordered
    }

    fun convert(definition: WorkflowDefinition): TaskGraphDraft {
        definition.validate()
        val participants = definition.participants.associateBy { it.id }
        definition.participants.forEach { agent ->
            require(agent.tools.copy(allowWrites = false) == ToolPolicy() && agent.budget == AgentBudget() &&
                agent.harnessArgs.isEmpty() && agent.attributes.isEmpty()) {
                "Participant ${agent.id.value} has policy, budget or harness settings that need an explicit graph profile before conversion"
            }
        }
        require(definition.stages.none { it.candidateId != null }) { "Pinned runbook candidates cannot become reusable templates" }
        val keys = definition.stages.mapIndexed { index, stage -> stage.id to "stage-${index + 1}" }.toMap()
        val tasks = definition.stages.map { stage ->
            val agent = stage.agent?.let(participants::get).takeIf { stage.action == WorkflowAction.Agent }
            TaskGraphOp.Add(stage.title, key = keys.getValue(stage.id),
                intent = listOfNotNull(agent?.instructions?.takeIf(String::isNotBlank), stage.instruction.takeIf(String::isNotBlank)).joinToString("\n\n"),
                kind = when (stage.action) { WorkflowAction.Agent -> TaskKind.Agent; WorkflowAction.Check -> TaskKind.Check; WorkflowAction.Gate -> TaskKind.Gate },
                seat = agent?.let { TaskSeat(it.runtime, it.model, it.effort?.value, if (it.tools.allowWrites) TaskAccess.Write else TaskAccess.Read) },
                contract = stage.contract, check = stage.checkId,
                dependsOn = definition.edges.filter { it.to == stage.id }.map { TaskDependency(keys.getValue(it.from), TaskCondition.valueOf(it.whenResult.name), required = false) })
        }
        return ordered(TaskGraphDraft(definition.title,
            goal = "Converted from runbook ${definition.id}. Review task scopes and checks before approval." +
                definition.subjectRefs.takeIf { it.isNotEmpty() }?.joinToString(prefix = "\nSubjects: ").orEmpty(), tasks = tasks))
    }

    fun definitions(view: TaskGraphView): TaskGraphDraft {
        val members = view.tasks.filter { it.graph != null && it.graph.state != TaskState.Cancelled }.associateBy { it.id }
        val selections = members.values.filter { !it.graph?.choices.isNullOrEmpty() }
        val collapsed = mutableMapOf<String, TaskGraphOp.Add>()
        val hidden = mutableSetOf<String>()
        selections.forEach { selection ->
            val branches = requireNotNull(selection.graph).choices.map { (writerId, reviewId) ->
                val writer = members.getValue(writerId)
                val review = members.getValue(reviewId)
                val check = members.getValue(requireNotNull(review.graph).dependsOn.single().task)
                Triple(writer, check, review)
            }
            val first = branches.first()
            val selectionPlan = requireNotNull(selection.graph)
            require(selectionPlan.kind == TaskKind.Gate && selectionPlan.contract == WorkflowContract.Text &&
                selectionPlan.seat == null && selectionPlan.check == null && selection.intent == first.first.intent &&
                selection.acceptance == first.first.acceptance && selection.scope == first.first.scope &&
                selectionPlan.dependsOn == branches.map { TaskDependency(it.third.id, TaskCondition.Always, required = false) } &&
                branches.withIndex().all { (index, branch) ->
                    val (writer, check, review) = branch
                    val writing = requireNotNull(writer.graph)
                    val checking = requireNotNull(check.graph)
                    val reviewing = requireNotNull(review.graph)
                    writer.title == selection.title.take(170) + " · attempt ${index + 1}" && writer.parent == selection.id &&
                        writing.kind == TaskKind.Agent && writing.contract == WorkflowContract.Text && writing.check == null &&
                        writing.choices.isEmpty() && writing.layout == selectionPlan.layout &&
                        check.title == "Check ${writer.id}" && check.parent == writer.id && check.intent.isEmpty() && check.acceptance.isEmpty() &&
                        check.scope == writer.scope && checking.kind == TaskKind.Check && checking.contract == WorkflowContract.Text &&
                        checking.seat == null && checking.layout == null && checking.choices.isEmpty() && checking.dependsOn == listOf(TaskDependency(writer.id)) &&
                        review.title == "Review ${writer.id}" && review.parent == writer.id && review.intent == writer.intent &&
                        review.acceptance == writer.acceptance && review.scope == writer.scope && reviewing.kind == TaskKind.Agent &&
                        reviewing.contract == WorkflowContract.Decision && reviewing.check == null && reviewing.layout == null &&
                        reviewing.choices.isEmpty() && reviewing.dependsOn == listOf(TaskDependency(check.id))
                }) { "Customized alternative branches need an explicit reusable draft; their definitions cannot be collapsed without losing edits" }
            require(branches.all { (writer, check, review) ->
                writer.intent == first.first.intent && writer.acceptance == first.first.acceptance && writer.scope == first.first.scope &&
                    writer.graph?.dependsOn == first.first.graph?.dependsOn && check.graph?.check == first.second.graph?.check &&
                    review.graph?.seat == first.third.graph?.seat && review.intent == first.third.intent &&
                    review.acceptance == first.third.acceptance
            }) { "Customized alternative branches need separate templates; their definitions cannot be collapsed without losing edits" }
            hidden += branches.flatMap { listOf(it.first.id, it.second.id, it.third.id) }
            collapsed[selection.id] = TaskGraphOp.Add(selection.title, key = selection.id, intent = first.first.intent,
                acceptance = first.first.acceptance, scope = first.first.scope, parent = selection.parent,
                dependsOn = requireNotNull(first.first.graph).dependsOn, layout = selection.graph.layout,
                alternatives = TaskAlternatives(branches.map { requireNotNull(it.first.graph?.seat) },
                    requireNotNull(first.second.graph?.check), requireNotNull(first.third.graph?.seat)))
        }
        require(members.values.all { task ->
            val plan = requireNotNull(task.graph)
            plan.excludedContext.isEmpty() && plan.repairOf == null &&
                (task.id in hidden || task.id in collapsed || plan.dependsOn.none { it.task in hidden })
        }) { "This graph has repair, excluded-context or branch-specific definitions that need an explicit reusable draft" }
        val tasks = members.values.filter { it.id !in hidden }.map { task ->
            collapsed[task.id] ?: requireNotNull(task.graph).let { plan ->
                TaskGraphOp.Add(task.title, task.id, task.intent, task.acceptance, task.scope, plan.kind, plan.seat,
                    plan.contract, plan.check, plan.dependsOn, plan.layout, task.parent)
            }
        }
        return ordered(TaskGraphDraft(view.graph.title, view.graph.goal, tasks, view.graph.envelope))
    }

    fun slots(draft: TaskGraphDraft): Map<String, TaskSeat> = buildMap {
        draft.tasks.forEach { task ->
            task.seat?.let { put(requireNotNull(task.key), it) }
            task.alternatives?.let { alternatives ->
                alternatives.seats.forEachIndexed { index, seat -> put("${task.key}/${index + 1}", seat) }
                put("${task.key}/review", alternatives.review)
            }
        }
    }

    fun bind(draft: TaskGraphDraft, slots: Map<String, TaskSeat>): TaskGraphDraft {
        require(slots.keys.all { it in this.slots(draft) }) { "Unknown template profile slot" }
        return draft.copy(tasks = draft.tasks.map { task -> task.copy(seat = slots[task.key] ?: task.seat,
            alternatives = task.alternatives?.let { alternatives -> alternatives.copy(
                seats = alternatives.seats.mapIndexed { index, seat -> slots["${task.key}/${index + 1}"] ?: seat },
                review = slots["${task.key}/review"] ?: alternatives.review) }) })
    }

    val builtIns: List<TaskGraphTemplate> by lazy {
        val writer = TaskSeat(profile = "implementer", access = TaskAccess.Write)
        val reviewer = TaskSeat(profile = "reviewer", access = TaskAccess.Read)
        val panel = reviewer.copy(lenses = listOf("codex-correctness", "claude-correctness"))
        fun agent(key: String, title: String, seat: TaskSeat, after: List<TaskDependency> = emptyList(), contract: WorkflowContract = WorkflowContract.Text) =
            TaskGraphOp.Add(title, key, intent = "Apply the graph goal to this task and record evidence.", seat = seat, dependsOn = after, contract = contract)
        fun after(key: String, condition: TaskCondition = TaskCondition.Succeeded) = listOf(TaskDependency(key, condition))
        fun template(id: String, title: String, tasks: List<TaskGraphOp.Add>) =
            TaskGraphTemplate("builtin-$id", "1", TaskGraphDraft(title, tasks = tasks))
        listOf(
            template("implement-review-check", "Implement, review and check", listOf(
                agent("writer", "Implement the goal", writer),
                agent("reviewer", "Review the candidate", reviewer, after("writer"), WorkflowContract.Decision),
                TaskGraphOp.Add("Check the candidate", "check", kind = TaskKind.Check, check = "Choose a check", dependsOn = after("reviewer", TaskCondition.Pass)))),
            template("two-writers-merge", "Two writers and a merge", listOf(
                agent("writer-a", "Implement the first part", writer),
                agent("writer-b", "Implement the second part", writer.copy(agent = RuntimeKind.ClaudeCode)),
                TaskGraphOp.Add("Integrate both candidates", "merge", kind = TaskKind.Merge, dependsOn = after("writer-a") + after("writer-b")),
                TaskGraphOp.Add("Check the integrated candidate", "check", kind = TaskKind.Check, check = "Choose a check", dependsOn = after("merge")))),
            template("compare-providers", "Compare two providers on one task", listOf(
                TaskGraphOp.Add("Implement the goal independently", "compare", intent = "Implement the same goal independently. Keep candidates isolated.",
                    alternatives = TaskAlternatives(listOf(writer, writer.copy(agent = RuntimeKind.ClaudeCode)), "Choose a check", panel)))),
            template("council", "Council with independent perspectives", listOf(
                agent("writer", "Implement the goal", writer),
                agent("council", "Review with independent perspectives", panel, after("writer"), WorkflowContract.Decision))),
            template("investigate-plan-implement", "Investigate, plan and implement", listOf(
                agent("investigator", "Investigate the goal", TaskSeat(profile = "skeptic")),
                agent("planner", "Plan the implementation", TaskSeat(profile = "architect"), after("investigator")),
                agent("writer", "Implement the plan", writer, after("planner")),
                TaskGraphOp.Add("Check the candidate", "check", kind = TaskKind.Check, check = "Choose a check", dependsOn = after("writer")))),
            template("competing-explanations", "Disprove competing explanations", listOf(
                agent("codex-investigation", "Find a falsifiable explanation", TaskSeat(RuntimeKind.Codex, profile = "skeptic"))
                    .copy(intent = "Investigate the graph goal independently. State a falsifiable explanation, source evidence, a competing explanation and a concrete check that would disprove yours. Separate observations from guesses."),
                agent("claude-investigation", "Find an independent explanation", TaskSeat(RuntimeKind.ClaudeCode, profile = "skeptic"))
                    .copy(intent = "Investigate the graph goal independently. State a falsifiable explanation, source evidence, a competing explanation and a concrete check that would disprove yours. Separate observations from guesses."),
                agent("codex-challenge", "Try to disprove Claude's explanation", TaskSeat(RuntimeKind.Codex, profile = "skeptic"),
                    after("codex-investigation") + after("claude-investigation"))
                    .copy(intent = "Try to disprove the other investigator's explanation using source evidence and a discriminating check. Challenge your own explanation too. Record which claims failed and which remain unknown; agreement is not evidence."),
                agent("claude-challenge", "Try to disprove Codex's explanation", TaskSeat(RuntimeKind.ClaudeCode, profile = "skeptic"),
                    after("codex-investigation") + after("claude-investigation"))
                    .copy(intent = "Try to disprove the other investigator's explanation using source evidence and a discriminating check. Challenge your own explanation too. Record which claims failed and which remain unknown; agreement is not evidence."),
                agent("explanation", "Identify the explanation that survives", reviewer,
                    after("codex-challenge") + after("claude-challenge"))
                    .copy(intent = "Compare the independent investigations and challenges. Name the explanation supported by the evidence and the check that distinguishes it from rejected explanations. If none survives, state that uncertainty explicitly and ask for the missing evidence."),
                agent("writer", "Reproduce and fix the demonstrated cause", writer, after("explanation"))
                    .copy(intent = "Use the surviving explanation to add a narrow reproduction and fix inside the approved scope. If the explanation is still unknown, stop and ask for evidence. Record how the reproduction fails before the fix and passes after it."),
                TaskGraphOp.Add("Check the surviving explanation and fix", "check", kind = TaskKind.Check,
                    intent = "Run the named check against the exact candidate. Retain the receipt showing the reproduction and fix.",
                    check = "Choose a check", dependsOn = after("writer"))))
        )
    }
}
