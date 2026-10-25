package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.worklog.PlanItem
import dev.shibasis.reaktor.conductor.worklog.PlanState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class TaskWorkSummary(
    val attempt: Int,
    val candidate: String?,
    val plan: List<PlanItem> = emptyList(),
    val files: List<String> = emptyList(),
    val added: Int? = null,
    val removed: Int? = null,
    val checks: List<AgentCandidateCheck> = emptyList(),
    val findings: List<AgentFinding> = emptyList(),
    val missingChecks: List<String> = emptyList(),
    val limitations: List<String> = emptyList(),
) {
    fun text(): String = buildString {
        appendLine("Recorded task summary · attempt $attempt · candidate ${candidate ?: "unknown"}")
        limitations.forEach { appendLine("Evidence limit: $it") }
        appendLine("Plan (provider reported):")
        if (plan.isEmpty()) appendLine("  No recorded plan")
        plan.take(20).forEach { appendLine("  ${it.state}: ${it.text}") }
        if (plan.size > 20) appendLine("  Showing 20 of ${plan.size} recorded steps")
        appendLine("Diff: ${files.size} files · ${added?.let { "+$it" } ?: "unknown additions"} / ${removed?.let { "−$it" } ?: "unknown removals"}")
        files.take(12).forEach { appendLine("  $it") }
        appendLine("Checks: ${checks.count { it.result.outcome == CheckOutcome.Passed }} passed · ${checks.count { it.result.outcome in listOf(CheckOutcome.Failed, CheckOutcome.Errored) }} failed · ${checks.count { it.result.outcome == CheckOutcome.Unknown }} unknown · ${missingChecks.size} missing")
        checks.take(20).forEach { appendLine("  ${it.result.command.joinToString(" ")}: ${it.result.outcome} · receipt ${it.id}") }
        missingChecks.take(20).forEach { appendLine("  Missing: $it") }
        appendLine("Open findings: ${findings.size}")
        findings.take(10).forEach { appendLine("  ${it.severity}: ${it.title} · ${it.id}") }
    }.trim().let { if (it.length <= 8000) it else it.take(7900) + "\nSummary cut; read the linked plan, diff, check receipts and findings for full evidence." }
}

object TaskSummaries {
    fun plan(item: AgentActivityItem): List<PlanItem> {
        if (item.parentId != null || item.status in listOf(ActivityStatus.Failed, ActivityStatus.Interrupted) ||
            (item.kind != ActivityKind.Plan && item.title !in listOf("TodoWrite", "update_plan", "mcp__ccd_turn__update_plan"))) return emptyList()
        return listOfNotNull(item.input, item.output).firstNotNullOfOrNull { text ->
            val json = runCatching { Json.parseToJsonElement(text) }.getOrNull()
            val items = json as? JsonArray ?: (json as? JsonObject)?.let { value ->
                (value["plan"] ?: value["todos"] ?: value["items"]) as? JsonArray
            }
            items?.mapNotNull { value ->
                val entry = value as? JsonObject ?: return@mapNotNull null
                fun string(key: String) = (entry[key] as? JsonPrimitive)?.contentOrNull
                val label = string("step") ?: string("content") ?: string("text") ?: return@mapNotNull null
                val state = when (string("status")) {
                    "completed", "done" -> PlanState.Done
                    "in_progress", "active" -> PlanState.Active
                    "pending" -> PlanState.Pending
                    else -> if ((entry["completed"] as? JsonPrimitive)?.booleanOrNull == true) PlanState.Done else PlanState.Pending
                }
                PlanItem(label.take(500), state)
            }?.takeIf { it.isNotEmpty() }?.take(100)
        }.orEmpty()
    }

    fun of(task: BoardTask, evidence: AgentTaskEvidence, activity: AgentActivityRecord? = null, diff: AgentArtifactPage? = null): TaskWorkSummary? {
        val taskPlan = task.graph ?: return null
        if (taskPlan.kind != TaskKind.Agent || taskPlan.attempt == 0) return null
        require(evidence.taskId == task.id)
        val outcome = taskPlan.outcome?.takeIf { it.attempt == taskPlan.attempt }
        val candidate = outcome?.candidateId?.let { id -> evidence.candidates.firstOrNull { it.id == id } }
        val recordedPlan = activity?.takeIf { it.runId == taskPlan.runId }?.item
        val checks = evidence.checks.filter { candidate != null && it.candidateId == candidate.id && it.result.revision == candidate.id }
            .groupBy { it.id }.values.map { it.last() }.sortedBy { it.result.command.joinToString(" ") }
        val page = diff?.takeIf { candidate?.diff?.id == it.ref.id && it.offset == 0L }
        var inHunk = false
        var added = 0
        var removed = 0
        page?.text?.lineSequence()?.forEach { line ->
            when {
                line.startsWith("diff ") -> inHunk = false
                line.startsWith("@@ ") -> inHunk = true
                inHunk && line.startsWith("+") -> added++
                inHunk && line.startsWith("-") -> removed++
            }
        }
        val partial = page?.truncated == true || page?.nextOffset != null || candidate?.complete == false ||
            page?.text?.contains("Binary files ") == true || page?.text?.contains("GIT binary patch") == true
        return TaskWorkSummary(taskPlan.attempt, candidate?.id, recordedPlan?.let(::plan).orEmpty(),
            candidate?.changedFiles.orEmpty(), page?.let { added }, page?.let { removed }, checks,
            evidence.findings.filter { candidate != null && it.candidateId == candidate.id && it.resolvedByCandidate != candidate.id },
            evidence.requiredChecks.filter { required -> checks.none { it.id == required } },
            buildList {
                if (candidate == null) add("No candidate recorded for this attempt")
                if (page == null) add("Candidate diff unavailable; line counts unknown")
                if (partial) add("Partial diff; line counts cover only the recorded text")
                if (recordedPlan?.detailTruncated == true || recordedPlan?.let { plan(it).size >= 100 } == true) add("Native plan may be partial")
                if (checks.isEmpty()) add("No exact-candidate check receipts")
            })
    }
}
