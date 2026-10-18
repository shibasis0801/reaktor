package dev.shibasis.reaktor.conductor.worklog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WorkLog(val turns: List<WorkTurn>, val earlier: Boolean = false) {
    val edits: List<WorkStep.Edit> get() = turns.flatMap { turn -> turn.steps.flatMap(WorkStep::edits) }
    val files: List<String> get() = edits.map { it.file }.distinct()
    val running: Boolean get() = turns.lastOrNull()?.open == true

    companion object {
        val Empty = WorkLog(emptyList())
    }
}

@Serializable
data class WorkTurn(
    val id: String,
    val prompt: String?,
    val startedAt: Long?,
    val endedAt: Long?,
    val steps: List<WorkStep>,
    val usage: WorkUsage? = null,
    val open: Boolean = false,
) {
    val files: List<String> get() = steps.flatMap(WorkStep::edits).map { it.file }.distinct()
    val millis: Long? get() = startedAt?.let { start -> endedAt?.minus(start)?.takeIf { it >= 0 } }
}

@Serializable
data class WorkUsage(val context: Long? = null, val output: Long? = null, val reasoning: Long? = null) {
    operator fun plus(next: WorkUsage) = WorkUsage(next.context ?: context, sum(output, next.output), sum(reasoning, next.reasoning))

    private fun sum(a: Long?, b: Long?) = if (a == null && b == null) null else (a ?: 0) + (b ?: 0)
}

@Serializable
data class RateWindow(val usedPercent: Double, val minutes: Long? = null, val resetsAt: Long? = null)

@Serializable
data class RateLimits(val windows: List<RateWindow>, val plan: String? = null, val at: Long? = null)

@Serializable
enum class StepStatus { Running, Done, Failed, Interrupted }

@Serializable
enum class ToolKind { Read, Search, Web, Mcp, Other }

@Serializable
enum class EditKind { Add, Update, Delete }

@Serializable
enum class PlanState { Pending, Active, Done }

@Serializable
enum class NoteKind { Compaction, Interrupted, Error, Checkpoint, Info }

@Serializable
data class DiffHunk(val oldStart: Int?, val newStart: Int?, val lines: List<String>)

@Serializable
data class PlanItem(val text: String, val state: PlanState)

@Serializable
sealed interface WorkStep {
    val id: String
    val at: Long?
    val status: StepStatus get() = StepStatus.Done

    @Serializable @SerialName("message")
    data class Message(override val id: String, override val at: Long?, val text: String, val author: String? = null) : WorkStep

    @Serializable @SerialName("reasoning")
    data class Reasoning(override val id: String, override val at: Long?, val text: String, val summary: Boolean, val millis: Long? = null) : WorkStep

    @Serializable @SerialName("command")
    data class Command(
        override val id: String,
        override val at: Long?,
        val command: String,
        val purpose: String? = null,
        val output: String? = null,
        val exitCode: Int? = null,
        override val status: StepStatus = StepStatus.Running,
        val millis: Long? = null,
        val cut: Boolean = false,
    ) : WorkStep

    @Serializable @SerialName("edit")
    data class Edit(
        override val id: String,
        override val at: Long?,
        val file: String,
        val kind: EditKind,
        val hunks: List<DiffHunk>,
        override val status: StepStatus = StepStatus.Running,
        val cut: Boolean = false,
        val movedTo: String? = null,
    ) : WorkStep {
        val added: Int get() = hunks.sumOf { hunk -> hunk.lines.count { it.startsWith('+') } }
        val removed: Int get() = hunks.sumOf { hunk -> hunk.lines.count { it.startsWith('-') } }
        val line: Int? get() = hunks.firstOrNull()?.newStart
    }

    @Serializable @SerialName("tool")
    data class Tool(
        override val id: String,
        override val at: Long?,
        val kind: ToolKind,
        val name: String,
        val summary: String? = null,
        val input: String? = null,
        val output: String? = null,
        override val status: StepStatus = StepStatus.Running,
        val millis: Long? = null,
        val server: String? = null,
        val cut: Boolean = false,
    ) : WorkStep

    @Serializable @SerialName("plan")
    data class Plan(override val id: String, override val at: Long?, val items: List<PlanItem>, val note: String? = null) : WorkStep

    @Serializable @SerialName("subagent")
    data class Subagent(
        override val id: String,
        override val at: Long?,
        val name: String,
        val prompt: String? = null,
        val model: String? = null,
        val steps: List<WorkStep> = emptyList(),
        val result: String? = null,
        override val status: StepStatus = StepStatus.Running,
    ) : WorkStep

    @Serializable @SerialName("question")
    data class Question(override val id: String, override val at: Long?, val text: String, val answer: String? = null, override val status: StepStatus = StepStatus.Running) : WorkStep

    @Serializable @SerialName("note")
    data class Note(override val id: String, override val at: Long?, val kind: NoteKind, val text: String) : WorkStep
}

fun WorkLog.settled(live: Boolean = false): WorkLog = copy(turns = turns.mapIndexed { index, turn ->
    if (live && index == turns.lastIndex) turn else turn.copy(open = false, steps = turn.steps.map(WorkStep::settled))
})

fun WorkStep.settled(): WorkStep = when (this) {
    is WorkStep.Subagent -> copy(status = status.settled(), steps = steps.map(WorkStep::settled))
    is WorkStep.Command -> copy(status = status.settled())
    is WorkStep.Edit -> copy(status = status.settled())
    is WorkStep.Tool -> copy(status = status.settled())
    is WorkStep.Question -> copy(status = status.settled())
    else -> this
}

private fun StepStatus.settled() = if (this == StepStatus.Running) StepStatus.Interrupted else this

fun WorkStep.edits(): List<WorkStep.Edit> = when (this) {
    is WorkStep.Edit -> listOf(this)
    is WorkStep.Subagent -> steps.flatMap(WorkStep::edits)
    else -> emptyList()
}
