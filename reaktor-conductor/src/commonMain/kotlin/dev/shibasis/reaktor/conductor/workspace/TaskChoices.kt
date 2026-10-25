package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.CheckCoverage
import dev.shibasis.reaktor.conductor.CheckOutcome
import dev.shibasis.reaktor.conductor.WorkflowContract
import kotlinx.serialization.Serializable

@Serializable
data class TaskAlternatives(val seats: List<TaskSeat>, val check: String, val review: TaskSeat)

@Serializable
data class TaskChoiceRank(
    val task: String,
    val candidate: String?,
    val eligible: Boolean,
    val passed: Int,
    val failed: Int,
    val confirmed: Int,
    val severity: Int,
    val lines: Int?,
    val reasons: List<String> = emptyList(),
    val limitations: List<String> = emptyList(),
) {
    fun text(): String = "$task · candidate ${candidate ?: "unknown"} · ${if (eligible) "eligible" else "ineligible"} · $passed passing checks · $failed failed checks · $confirmed confirmed findings · ${lines?.let { "$it changed lines" } ?: "change size unknown"}" +
        (reasons + limitations).joinToString("; ", prefix = if (reasons.isEmpty() && limitations.isEmpty()) "" else " · ")
}

object TaskChoices {
    private fun branch(writer: String, review: String, tasks: Map<String, BoardTask>): List<String> =
        listOf(writer, review) + tasks[review]?.graph?.dependsOn.orEmpty().map { it.task }

    fun owner(task: String, tasks: Map<String, BoardTask>): BoardTask? = tasks.values.firstOrNull { selection ->
        selection.graph?.choices.orEmpty().any { (writer, review) -> task in branch(writer, review, tasks) }
    }

    fun selectionAllows(task: String, tasks: Map<String, BoardTask>): Boolean {
        val selection = owner(task, tasks)?.graph ?: return true
        val outcome = selection.outcome
        return selection.state == TaskState.Succeeded && outcome?.selectedTask == task &&
            outcome.candidateId == tasks[task]?.graph?.outcome?.candidateId
    }

    fun validate(tasks: Map<String, BoardTask>, resolve: (TaskSeat) -> TaskSeat) {
        val seen = mutableSetOf<String>()
        tasks.values.filter { !it.graph?.choices.isNullOrEmpty() }.forEach { selection ->
            val plan = requireNotNull(selection.graph)
            require(plan.kind == TaskKind.Gate && plan.seat == null && plan.choices.size in 2..3) { "A selection keeps two or three isolated writing branches" }
            require(plan.dependsOn.toSet() == plan.choices.values.map { TaskDependency(it, TaskCondition.Always, required = false) }.toSet()) { "A selection waits for every candidate panel" }
            plan.choices.forEach { (writerId, reviewId) ->
                val writer = requireNotNull(tasks[writerId]) { "A selection retains its writing tasks" }
                val review = requireNotNull(tasks[reviewId]) { "A selection retains its review panels" }
                val writing = requireNotNull(writer.graph)
                val panel = requireNotNull(review.graph)
                val check = panel.dependsOn.singleOrNull()?.task?.let(tasks::get)
                require(writer.parent == selection.id && writing.kind == TaskKind.Agent && writing.seat?.let(resolve)?.access == TaskAccess.Write) { "A selection branch keeps its owned writer" }
                require(review.parent == writerId && panel.kind == TaskKind.Agent && panel.contract == WorkflowContract.Decision &&
                    panel.seat?.let(resolve)?.access == TaskAccess.Read && panel.seat.lenses.size in 2..6) { "Each candidate keeps a read-only panel" }
                require(check?.parent == writerId && check.graph?.kind == TaskKind.Check && !check.graph.check.isNullOrBlank() &&
                    check.graph.dependsOn == listOf(TaskDependency(writerId)) && panel.dependsOn == listOf(TaskDependency(check.id))) { "Each candidate keeps its own required check before its panel" }
                require(branch(writerId, reviewId, tasks).all(seen::add)) { "Selection branches cannot share writers, checks or panels" }
            }
        }
    }

    fun rank(selection: BoardTask, tasks: Map<String, BoardTask>, summaries: Map<String, TaskWorkSummary>): List<TaskChoiceRank> =
        selection.graph?.choices.orEmpty().map { (writerId, reviewId) ->
            val writer = tasks[writerId]?.graph
            val review = tasks[reviewId]?.graph
            val check = review?.dependsOn?.singleOrNull()?.task?.let(tasks::get)?.graph
            val candidate = writer?.outcome?.candidateId
            val summary = summaries[writerId]?.takeIf { it.attempt == writer?.attempt && it.candidate == candidate && candidate != null }
            val checks = summary?.checks.orEmpty().filter { it.candidateId == candidate && it.result.revision == candidate }
            val passed = checks.count { it.source == "kernel" && it.result.outcome == CheckOutcome.Passed && it.result.coverage == CheckCoverage.Complete }
            val failed = checks.count { it.result.outcome in setOf(CheckOutcome.Failed, CheckOutcome.Errored) }
            val findings = summary?.findings.orEmpty().filter { it.candidateId == candidate && it.resolvedByCandidate != candidate }
            val confirmed = findings.filter { it.confirmation != null }
            val reasons = buildList {
                if (writer?.state != TaskState.Succeeded || writer.worktreeId == null || writer.outcome?.attempt != writer.attempt || candidate == null || summary == null)
                    add("No current successful writing candidate")
                if (check?.state != TaskState.Succeeded || check.outcome == null || check.outcome.candidateId != candidate || check.outcome.attempt != check.attempt || check.outcome.checks.isEmpty() || check.outcome.checks.any { !it.ok })
                    add("The candidate's Check has not passed")
                if (review?.state != TaskState.Succeeded || review.outcome == null || review.outcome.candidateId != candidate || review.outcome.attempt != review.attempt ||
                    review.outcome.verdict != "pass" || review.resolvedSeat?.lenses?.size !in 2..6)
                    add("No passing panel for this exact candidate")
                if (passed == 0 || passed != checks.size || !summary?.missingChecks.isNullOrEmpty()) add("Complete passing kernel check receipts are missing")
                if (findings.any { it.severity in setOf("blocker", "high") }) add("Blocking findings remain open")
            }
            val lines = summary?.takeIf { it.added != null && it.removed != null && it.limitations.none { limit -> limit.contains("diff", ignoreCase = true) } }
                ?.let { requireNotNull(it.added) + requireNotNull(it.removed) }
            TaskChoiceRank(writerId, candidate, reasons.isEmpty(), passed, failed, confirmed.size,
                confirmed.sumOf { when (it.severity) { "blocker" -> 1000; "high" -> 100; "medium" -> 10; else -> 1 } }, lines,
                reasons, summary?.limitations.orEmpty())
        }.sortedWith(compareByDescending<TaskChoiceRank> { it.eligible }.thenByDescending { it.passed }.thenBy { it.failed }
            .thenBy { it.severity }.thenBy { it.confirmed }.thenBy { it.lines ?: Int.MAX_VALUE }
            .thenBy { it.task.removePrefix("T").toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.task })
}
