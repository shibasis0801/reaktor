package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.WorkflowContract

object TaskGraphText {
    const val Limit = 60_000

    fun render(view: TaskGraphView, limit: Int = Limit): String {
        val full = render(view, outcome = 1_200, changes = 20)
        if (full.length <= limit) return full
        val brief = render(view, outcome = 300, changes = 8)
        if (brief.length <= limit) return brief
        return brief.take(limit - 120) + "\n… cut at $limit characters; read one task with board.read {\"taskId\": \"T…\"}."
    }

    private fun render(view: TaskGraphView, outcome: Int, changes: Int): String = buildString {
        val graph = view.graph
        val members = view.tasks.associateBy { it.id }
        append("${graph.id} · ${graph.title} · ${graph.status.name} · revision ${graph.revision}")
        graph.approvedRevision?.let { append(" (approved at $it; ${graph.changedSinceApproval} changes since)") }
        appendLine()
        graph.reason?.let { appendLine("Why: $it") }
        if (graph.goal.isNotBlank()) appendLine("Goal: ${graph.goal}")
        graph.lead?.let { appendLine("Lead: $it") }
        appendLine("Envelope: ${envelope(graph)}")
        if (view.ready.isNotEmpty()) appendLine("Ready: ${view.ready.joinToString()}")
        appendLine()
        view.tasks.sortedBy { it.number }.forEach { task ->
            val plan = task.graph ?: return@forEach
            append("${task.id} ${task.title} · ${plan.kind.name}")
            plan.seat?.let { append(" · ${it.describe()}") }
            if (plan.contract == WorkflowContract.Decision) append(" · Decision")
            plan.check?.let { append(" · check $it") }
            append(" · ${plan.state.name}")
            if (plan.attempt > 0) append(" (attempt ${plan.attempt})")
            if (plan.dependsOn.isNotEmpty()) append(" · after ${plan.dependsOn.joinToString { dependency ->
                dependency.task + if (dependency.condition == TaskCondition.Succeeded) "" else " if ${dependency.condition.name}" }}")
            task.blockedBy(members)?.takeIf { plan.state == TaskState.Pending }?.let { append(" · $it") }
            appendLine()
            if (task.intent.isNotBlank()) appendLine("  Intent: ${task.intent.oneLine(600)}")
            if (task.acceptance.isNotEmpty()) appendLine("  Done when: ${task.acceptance.joinToString("; ").oneLine(600)}")
            if (task.scope.isNotEmpty()) appendLine("  Scope: ${task.scope.joinToString()}")
            plan.note?.let { appendLine("  Note: ${it.oneLine(300)}") }
            plan.run?.let { appendLine("  Session: $it") }
            plan.outcome?.let { result ->
                appendLine("  Outcome: ${result.summary.oneLine(outcome).ifEmpty { "no summary" }}" +
                    (result.verdict?.let { "; verdict $it" } ?: "") +
                    (result.files.takeIf { it.isNotEmpty() }?.let { "; files ${it.take(12).joinToString()}" } ?: "") +
                    (result.log?.let { "; log $it" } ?: ""))
                result.checks.forEach { appendLine("  Check ${it.check}: ${if (it.ok) "passed" else "failed"}${it.receipt?.let { receipt -> " · receipt $receipt" }.orEmpty()}") }
                result.questions.forEach { appendLine("  Question: ${it.oneLine(300)}") }
            }
            appendLine("  By ${task.createdBy.name}${task.createdBy.session?.let { " ($it)" }.orEmpty()} · changed at revision ${plan.changedAt}")
        }
        val recent = graph.changes.takeLast(changes)
        if (recent.isNotEmpty()) {
            appendLine()
            appendLine("Recent changes:")
            recent.asReversed().forEach { change -> appendLine("r${change.revision} · ${change.by.name} · ${change.reason.oneLine(200)} · ${change.summary.joinToString("; ").oneLine(400)}") }
        }
    }

    fun envelope(graph: TaskGraph): String = with(graph.envelope) {
        listOfNotNull(
            "agents ${agents.joinToString { it.name }.ifEmpty { "none yet" }}",
            models.takeIf { it.isNotEmpty() }?.let { "models ${it.joinToString()}" },
            efforts.takeIf { it.isNotEmpty() }?.let { "efforts ${it.joinToString()}" },
            "at most $maxTasks tasks",
            "$perAgent at once per agent",
            "writes ${writes.joinToString().ifEmpty { "nothing" }}",
            tokens?.let { "budget ${graph.tokensUsed} of $it tokens" } ?: "no token budget (${graph.tokensUsed} used)",
            "$repairRounds repair round${if (repairRounds == 1) "" else "s"}",
        ).joinToString(" · ")
    }

    private fun String.oneLine(limit: Int) = replace(Regex("\\s+"), " ").trim().let { if (it.length > limit) it.take(limit - 1) + "…" else it }
}
