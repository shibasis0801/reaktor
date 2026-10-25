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
        if (view.warnings.isNotEmpty()) {
            appendLine("Plan warnings (advisory; do not block Start):")
            view.warnings.forEach { appendLine("  ${it.code.name} · ${it.tasks.joinToString()} · ${it.message}") }
        }
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
                dependency.task + (if (dependency.condition == TaskCondition.Succeeded) "" else " if ${dependency.condition.name}") + if (dependency.required) "" else " (alternative)" }}")
            task.blockedBy(members)?.takeIf { plan.state == TaskState.Pending }?.let { append(" · $it") }
            appendLine()
            if (task.intent.isNotBlank()) appendLine("  Intent: ${task.intent.oneLine(600)}")
            if (task.acceptance.isNotEmpty()) appendLine("  Done when: ${task.acceptance.joinToString("; ").oneLine(600)}")
            if (task.scope.isNotEmpty()) appendLine("  Scope: ${task.scope.joinToString()}")
            plan.note?.let { appendLine("  Note: ${it.oneLine(300)}") }
            plan.run?.let { appendLine("  Session: $it") }
            plan.outcome?.usageLimit?.let { limit ->
                appendLine("  Usage limit: ${limit.provider.name} · ${limit.code} · reset epoch millis ${limit.resetsAtEpochMillis ?: "unknown"}")
                appendLine(if (plan.resumeAtReset != null) "  One person-selected retry at ${plan.resumeAtReset}; graph must be running"
                    else "  Waiting for a person to retry or select resume at reset")
            }
            plan.outcome?.let { result ->
                appendLine("  Outcome: ${result.summary.oneLine(outcome).ifEmpty { "no summary" }}" +
                    (result.verdict?.let { "; verdict $it" } ?: "") +
                    (result.files.takeIf { it.isNotEmpty() }?.let { "; files ${it.take(12).joinToString()}" } ?: "") +
                    (result.log?.let { "; log $it" } ?: ""))
                result.checks.forEach { appendLine("  Check ${it.check}: ${if (it.ok) "passed" else "failed"}${it.receipt?.let { receipt -> " · receipt $receipt" }.orEmpty()}") }
                result.questions.forEach { appendLine("  Question: ${it.oneLine(300)}") }
            }
            view.summaries[task.id]?.let { summary -> appendLine("  ${summary.text().take(outcome)}") }
            view.choices[task.id]?.let { ranks ->
                appendLine("  Alternatives · ${if (graph.envelope.chooseTop) "automatic choice allowed" else "person chooses"}")
                ranks.forEachIndexed { index, rank -> appendLine("  ${index + 1}. ${rank.text()}") }
                plan.outcome?.selectedTask?.let { appendLine("  Selected source: $it · ${plan.outcome.candidateId}") }
            }
            view.handoffs[task.id]?.let { handoff ->
                appendLine("  Handoff ${handoff.id} · attempt ${handoff.attempt} · by ${handoff.author?.name ?: "unknown"}")
                appendLine("    Goal: ${handoff.goal.oneLine(300)}")
                appendLine("    Done: ${handoff.done.oneLine(outcome)}")
                handoff.candidate?.let { appendLine("    Candidate: $it") }
                if (handoff.decisions.isNotEmpty()) appendLine("    Decisions: ${handoff.decisions.joinToString()}")
                handoff.openIssues.forEach { appendLine("    Open: ${it.oneLine(300)}") }
                if (handoff.changedPaths.isNotEmpty()) appendLine("    Changed: ${handoff.changedPaths.take(12).joinToString()}")
                if (handoff.checkReceipts.isNotEmpty()) appendLine("    Checks: ${handoff.checkReceipts.joinToString().oneLine(600)}")
                if (handoff.reviewReceipts.isNotEmpty()) appendLine("    Reviews: ${handoff.reviewReceipts.joinToString().oneLine(600)}")
                if (handoff.nextAction.isNotBlank()) appendLine("    Next: ${handoff.nextAction.oneLine(600)}")
                if (handoff.sessionLogs.isNotEmpty()) appendLine("    Logs: ${handoff.sessionLogs.joinToString()}")
            }
            appendLine("  By ${task.createdBy.name}${task.createdBy.session?.let { " ($it)" }.orEmpty()} · changed at revision ${plan.changedAt}")
        }
        if (view.decisions.isNotEmpty()) {
            appendLine()
            appendLine("Decisions:")
            view.decisions.takeLast(changes).forEach { decision ->
                appendLine("${decision.id} · by ${decision.author?.name ?: "unknown"}" +
                    (if (decision.personAccepted) " · accepted by a person" else " · proposed") +
                    decision.supersedes?.let { " · supersedes $it" }.orEmpty())
                appendLine("  ${decision.statement.oneLine(600)} · subjects ${decision.subjects.joinToString().oneLine(500)}")
                appendLine("  Why: ${decision.rationale.oneLine(outcome)}")
            }
        }
        if (view.messages.isNotEmpty()) {
            appendLine()
            appendLine("Recent messages (agent content):")
            view.messages.takeLast(changes).forEach { message ->
                appendLine("${message.id} · ${message.kind.name} · ${message.author?.name ?: "unknown"} → ${message.recipient.task ?: message.recipient.session} · thread ${message.thread}")
                appendLine("  ${message.body.oneLine(outcome)}")
            }
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
            if (chooseTop) "automatic top candidate choice allowed" else "person chooses candidate alternatives",
        ).joinToString(" · ")
    }

    private fun String.oneLine(limit: Int) = replace(Regex("\\s+"), " ").trim().let { if (it.length > limit) it.take(limit - 1) + "…" else it }
}
