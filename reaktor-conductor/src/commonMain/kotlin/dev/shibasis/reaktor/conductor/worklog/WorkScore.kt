package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.CheckOutcome
import dev.shibasis.reaktor.conductor.CheckResult
import dev.shibasis.reaktor.conductor.workspace.BoardStage
import dev.shibasis.reaktor.conductor.workspace.BoardTask
import dev.shibasis.reaktor.conductor.workspace.TaskGraphRules
import dev.shibasis.reaktor.conductor.workspace.TaskGraphView
import dev.shibasis.reaktor.conductor.workspace.TaskState
import kotlinx.serialization.Serializable

@Serializable
enum class WorkOutcome { Landed, Accepted, Abandoned, Open }

@Serializable
data class WorkScore(
    val startedAt: Long?,
    val endedAt: Long?,
    val wallMillis: Long?,
    val workingMillis: Long?,
    val personWaitMillis: Long?,
    val unknownWaits: Int,
    val turns: Int,
    val personPromptsAfterFirst: Int,
    val toolCalls: Int,
    val failedTools: Int,
    val builds: Int,
    val failedBuilds: Int,
    val tests: Int,
    val failedTests: Int,
    val files: List<String>,
    val added: Int,
    val removed: Int,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val reasoningTokens: Long?,
    val unknownUsageTurns: Int,
    val costUsd: Double?,
    val unknownCostTurns: Int,
    val outcome: WorkOutcome,
    val partial: Boolean,
    val notes: List<String> = emptyList(),
)

@Serializable
data class GraphWorkScore(
    val graph: String,
    val total: WorkScore,
    val tasks: Map<String, WorkScore>,
    val criticalPath: List<String>,
    val criticalPathMillis: Long?,
    val missingTasks: List<String>,
)

fun WorkLog.score(task: BoardTask? = null, checks: List<CheckResult> = emptyList(), now: Long? = null): WorkScore {
    val steps = turns.flatMap { it.steps.flatMap(WorkStep::scoredSteps) }
    val intervals = turns.mapNotNull { turn ->
        turn.startedAt?.let { start -> (if (turn.open) now ?: turn.endedAt else turn.endedAt)?.takeIf { it >= start }?.let { start to it } }
    }
    val questions = steps.filterIsInstance<WorkStep.Question>()
    val waits = questions.mapNotNull { question ->
        question.at?.let { start -> (question.answeredAt ?: now?.takeIf { question.status == StepStatus.Running })?.takeIf { it >= start }?.let { start to it } }
    }
    val unknownWaits = questions.size - waits.size
    val start = intervals.minOfOrNull { it.first }
    val end = intervals.maxOfOrNull { it.second }
    val wall = start?.let { end?.minus(it) }
    val active = intervals.unionMillis()
    val waitingInside = waits.flatMap { wait -> intervals.mapNotNull { interval ->
        val from = maxOf(wait.first, interval.first)
        val to = minOf(wait.second, interval.second)
        (from to to).takeIf { to >= from }
    } }.unionMillis()
    val timed = intervals.size == turns.size && unknownWaits == 0 && turns.isNotEmpty()
    val tools = steps.filter { it is WorkStep.Command || it is WorkStep.Edit || it is WorkStep.Tool || it is WorkStep.Subagent || it is WorkStep.Question }
    val commands = steps.filterIsInstance<WorkStep.Command>().toMutableList()
    val invocations = checks.distinctBy { it.log?.id ?: "${it.revision}:${it.command}:${it.outcome}:${it.durationMillis}" }.map { check ->
        val command = check.command.joinToString(" ")
        val index = commands.indexOfFirst { it.command.trim() == command.trim() }
        if (index >= 0) commands.removeAt(index)
        command to (check.outcome == CheckOutcome.Failed || check.outcome == CheckOutcome.Errored)
    } + commands.map { it.command to (it.status == StepStatus.Failed || it.exitCode?.let { code -> code != 0 } == true) }
    val edits = steps.filterIsInstance<WorkStep.Edit>().filter { it.status == StepStatus.Done }
    val build = invocations.filter { BuildCommand.containsMatchIn(it.first) }
    val test = invocations.filter { TestCommand.containsMatchIn(it.first) }
    val cut = steps.any { when (it) { is WorkStep.Edit -> it.cut; is WorkStep.Tool -> it.cut; is WorkStep.Command -> it.cut; else -> false } }
    return WorkScore(start, end, wall, (active - waitingInside).takeIf { timed }, wall?.minus(active)?.plus(waitingInside)?.takeIf { timed }, unknownWaits,
        turns.size, (turns.count { !it.prompt.isNullOrBlank() } - 1).coerceAtLeast(0), tools.size,
        tools.count { it.status == StepStatus.Failed || it is WorkStep.Command && it.exitCode?.let { code -> code != 0 } == true }, build.size, build.count { it.second }, test.size, test.count { it.second },
        edits.map { it.file }.distinct().sorted(), edits.sumOf { it.added }, edits.sumOf { it.removed },
        turns.mapNotNull { it.usage?.input }.sumOrNull(), turns.mapNotNull { it.usage?.output }.sumOrNull(), turns.mapNotNull { it.usage?.reasoning }.sumOrNull(),
        turns.count { it.usage?.input == null || it.usage.output == null },
        turns.mapNotNull { it.usage?.costUsd }.takeIf { it.isNotEmpty() }?.sum(), turns.count { it.usage?.costUsd == null },
        when { task?.stage == BoardStage.Done -> WorkOutcome.Accepted; task?.graph?.state in setOf(TaskState.Cancelled, TaskState.Skipped) -> WorkOutcome.Abandoned; else -> WorkOutcome.Open },
        earlier || cut || intervals.size != turns.size || turns.isEmpty(),
        buildList {
            if (earlier) add("Only the retained portion of the log was scored.")
            if (steps.any { it is WorkStep.Subagent }) add("Token and cost totals cover the parent turns; native child usage is not attributed separately.")
            if (cut) add("Some recorded steps or diffs were shortened; counts are lower bounds.")
            if (!timed) add("Working and person-wait time need complete turn and question timestamps.")
            if (invocations.isNotEmpty()) add("Build/test counts classify recorded commands; a command may count in both. Matching check receipts replace one shell occurrence.")
            add("Tool counts describe normalized recorded actions, including nested agents and questions; plan updates are excluded. Edit counts describe successful recorded edits, not the final Git diff. A finished turn alone does not prove acceptance or landing.")
        })
}

fun TaskGraphView.score(cards: Map<String, WorkScore>): GraphWorkScore {
    val members = tasks.associateBy { it.id }
    val kept = cards.entries.filter { it.key in members }.sortedBy { it.key }.associate { it.toPair() }
    val missing = members.keys.filterNot(kept::containsKey).sorted()
    val scores = kept.values.toList()
    val total = scores.sumScores(missing.isEmpty())
    if (TaskGraphRules.cycle(members) != null) return GraphWorkScore(graph.id, total.copy(partial = true), kept, emptyList(), null, missing)
    val paths = mutableMapOf<String, Pair<List<String>, Long>>()
    fun path(id: String): Pair<List<String>, Long> = paths.getOrPut(id) {
        val upstream = members[id]?.graph?.dependsOn.orEmpty().filter { it.required || TaskGraphRules.active(it, members[it.task]) }
            .filter { it.task in members }.map { path(it.task) }
            .sortedWith(compareByDescending<Pair<List<String>, Long>> { it.second }.thenBy { it.first.joinToString("/") }).firstOrNull()
        (upstream?.first.orEmpty() + id) to ((upstream?.second ?: 0) + (kept[id]?.wallMillis ?: 0))
    }
    val critical = members.keys.sorted().map(::path).sortedWith(compareByDescending<Pair<List<String>, Long>> { it.second }.thenBy { it.first.joinToString("/") }).firstOrNull()
    return GraphWorkScore(graph.id, total, kept, critical?.first.orEmpty(), critical?.second?.takeIf { missing.isEmpty() && scores.all { it.wallMillis != null && !it.partial } }, missing)
}

fun List<WorkScore>.sumScores(complete: Boolean = true): WorkScore {
    val scores = this
    val start = scores.mapNotNull { it.startedAt }.minOrNull()
    val end = scores.mapNotNull { it.endedAt }.maxOrNull()
    return WorkScore(start, end, start?.let { end?.minus(it) }, scores.mapNotNull { it.workingMillis }.sumOrNull()?.takeIf { complete && scores.all { it.workingMillis != null } }, scores.mapNotNull { it.personWaitMillis }.sumOrNull()?.takeIf { complete && scores.all { it.personWaitMillis != null } },
        scores.sumOf { it.unknownWaits }, scores.sumOf { it.turns }, scores.sumOf { it.personPromptsAfterFirst }, scores.sumOf { it.toolCalls }, scores.sumOf { it.failedTools },
        scores.sumOf { it.builds }, scores.sumOf { it.failedBuilds }, scores.sumOf { it.tests }, scores.sumOf { it.failedTests }, scores.flatMap { it.files }.distinct().sorted(),
        scores.sumOf { it.added }, scores.sumOf { it.removed }, scores.mapNotNull { it.inputTokens }.sumOrNull(), scores.mapNotNull { it.outputTokens }.sumOrNull(),
        scores.mapNotNull { it.reasoningTokens }.sumOrNull(), scores.sumOf { it.unknownUsageTurns }, scores.mapNotNull { it.costUsd }.takeIf { it.isNotEmpty() }?.sum(),
        scores.sumOf { it.unknownCostTurns }, when {
            complete && scores.isNotEmpty() && scores.all { it.outcome == WorkOutcome.Landed } -> WorkOutcome.Landed
            complete && scores.isNotEmpty() && scores.all { it.outcome == WorkOutcome.Accepted || it.outcome == WorkOutcome.Landed } -> WorkOutcome.Accepted
            complete && scores.isNotEmpty() && scores.all { it.outcome == WorkOutcome.Abandoned } -> WorkOutcome.Abandoned
            else -> WorkOutcome.Open
        }, !complete || scores.any { it.partial }, (scores.flatMap { it.notes } + "Working time, tokens, cost and edit events are summed; wall time spans the observed sessions. Unknown metrics remain incomplete.").distinct())
}

private fun WorkStep.scoredSteps(): List<WorkStep> = listOf(this) + if (this is WorkStep.Subagent) steps.flatMap(WorkStep::scoredSteps) else emptyList()

private fun List<Long>.sumOrNull(): Long? = takeIf { it.isNotEmpty() }?.sum()

private fun List<Pair<Long, Long>>.unionMillis(): Long {
    var end: Long? = null
    return sortedBy { it.first }.sumOf { (start, finish) ->
        val added = (finish - maxOf(start, end ?: start)).coerceAtLeast(0)
        end = maxOf(end ?: finish, finish)
        added
    }
}

private val BuildCommand = Regex("(?:^|[\\s/])(?:gradlew?|mvn|ninja|make)(?:\\s|$)|(?:cargo|npm|pnpm|yarn)\\s+(?:run\\s+)?build(?:\\s|$)", RegexOption.IGNORE_CASE)
private val TestCommand = Regex("(?:^|[\\s:/])(?:[\\w-]*test[\\w-]*|check|hangarFlows|maestro|vitest|jest)(?:[\\s:]|$)", RegexOption.IGNORE_CASE)
