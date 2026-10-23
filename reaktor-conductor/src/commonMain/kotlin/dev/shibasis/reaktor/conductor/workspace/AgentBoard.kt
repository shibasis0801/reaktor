package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class BoardStage { Backlog, Planning, Review, Done }

@Serializable
enum class BoardColumn(val label: String) {
    Backlog("Backlog"), Planning("Planning"), Running("Running"), NeedsYou("Needs you"), Review("Review"), Done("Done")
}

@Serializable
enum class BoardActorKind { Person, Agent, Schedule }

@Serializable
data class BoardActor(val kind: BoardActorKind, val name: String, val session: String? = null)

@Serializable
sealed interface BoardBlocker {
    @Serializable
    @SerialName("task")
    data class Task(val task: String) : BoardBlocker

    @Serializable
    @SerialName("owner")
    data class Owner(val owner: String, val action: String) : BoardBlocker
}

@Serializable
data class BoardLinks(val mission: String? = null, val issue: String? = null, val pullRequest: String? = null)

@Serializable
data class BoardEvent(val at: Long, val by: BoardActor, val change: String)

@Serializable
data class BoardTask(
    val id: String,
    val title: String,
    val intent: String = "",
    val acceptance: List<String> = emptyList(),
    val scope: List<String> = emptyList(),
    val stage: BoardStage = BoardStage.Backlog,
    val work: List<String> = emptyList(),
    val blocker: BoardBlocker? = null,
    val blockedAt: Long? = null,
    val links: BoardLinks = BoardLinks(),
    val parent: String? = null,
    val createdBy: BoardActor,
    val createdAt: Long,
    val updatedAt: Long,
    val stageChangedAt: Long,
    val revision: Long = 1,
    val history: List<BoardEvent> = emptyList(),
    val graph: TaskPlan? = null,
) {
    val number: Int get() = id.removePrefix("T").toIntOrNull() ?: 0
}

@Serializable
data class BoardDraft(
    val title: String,
    val intent: String = "",
    val acceptance: List<String> = emptyList(),
    val scope: List<String> = emptyList(),
    val stage: BoardStage = BoardStage.Backlog,
    val work: List<String> = emptyList(),
    val links: BoardLinks = BoardLinks(),
    val parent: String? = null,
)

@Serializable
data class BoardPatch(
    val title: String? = null,
    val intent: String? = null,
    val acceptance: List<String>? = null,
    val scope: List<String>? = null,
    val links: BoardLinks? = null,
    val blocker: BoardBlocker? = null,
    val unblock: Boolean = false,
)

@Serializable
enum class BoardWorkState { Executing, Asking, Stopped }

@Serializable
data class BoardWork(val ref: String, val state: BoardWorkState, val since: Long)

@Serializable
data class BoardEntry(
    val task: BoardTask,
    val column: BoardColumn,
    val since: Long,
    val blocked: String? = null,
    val work: List<BoardWork> = emptyList(),
)

@Serializable
data class BoardPage(val revision: Long, val entries: List<BoardEntry>)

val BoardStage.column: BoardColumn get() = when (this) {
    BoardStage.Backlog -> BoardColumn.Backlog
    BoardStage.Planning -> BoardColumn.Planning
    BoardStage.Review -> BoardColumn.Review
    BoardStage.Done -> BoardColumn.Done
}

fun BoardTask.blockedBy(tasks: Map<String, BoardTask>): String? = when (val blocker = blocker) {
    is BoardBlocker.Task -> tasks[blocker.task]?.takeIf { it.stage != BoardStage.Done }?.let { "Waiting on ${it.id}: ${it.title}" }
    is BoardBlocker.Owner -> "Waiting on ${blocker.owner} to ${blocker.action}"
    null -> graph?.takeIf { it.state == TaskState.Pending }?.dependsOn
        ?.filter { dependency -> tasks[dependency.task]?.graph?.state.let { it == null || it == TaskState.Pending || it == TaskState.Running || it == TaskState.Waiting } }
        ?.takeIf { it.isNotEmpty() }?.let { open -> "Waiting on ${open.joinToString(", ") { it.task }}" }
}

fun BoardTask.entry(work: List<BoardWork>, tasks: Map<String, BoardTask>): BoardEntry {
    val blocked = blockedBy(tasks)
    val asking = work.filter { it.state == BoardWorkState.Asking }.minOfOrNull { it.since }
    val executing = work.filter { it.state == BoardWorkState.Executing }.minOfOrNull { it.since }
    val stopped = work.filter { it.state == BoardWorkState.Stopped && it.since > stageChangedAt }.maxOfOrNull { it.since }
    val planned = graph?.state
    val (column, since) = when {
        stage == BoardStage.Done -> BoardColumn.Done to stageChangedAt
        planned == TaskState.Skipped || planned == TaskState.Cancelled -> BoardColumn.Done to updatedAt
        planned == TaskState.Succeeded && graph?.kind != TaskKind.Agent -> BoardColumn.Done to updatedAt
        planned == TaskState.Failed || planned == TaskState.Waiting -> BoardColumn.NeedsYou to updatedAt
        asking != null -> BoardColumn.NeedsYou to asking
        executing != null -> BoardColumn.Running to executing
        planned == TaskState.Running -> BoardColumn.Running to updatedAt
        stage == BoardStage.Review -> BoardColumn.Review to stageChangedAt
        blocker is BoardBlocker.Owner -> BoardColumn.NeedsYou to (blockedAt ?: updatedAt)
        blocked != null -> stage.column to stageChangedAt
        stopped != null -> BoardColumn.NeedsYou to stopped
        else -> stage.column to stageChangedAt
    }
    return BoardEntry(this, column, since, blocked, work)
}

fun AgentRunRecord.boardWork(ref: String): BoardWork = when {
    pendingHandoff != null || recovery == AgentRecovery.NeedsReview || pendingCount > 0 || pending.isNotEmpty() ||
        participants.values.any { it.pending.isNotEmpty() } -> BoardWork(ref, BoardWorkState.Asking, updatedAt)
    status == AgentRunStatus.Running || recovery == AgentRecovery.Pending || recovery == AgentRecovery.Resuming ->
        BoardWork(ref, BoardWorkState.Executing, startedAt)
    else -> BoardWork(ref, BoardWorkState.Stopped, updatedAt)
}

object BoardRefs {
    const val Hangar = "hangar:"
    const val Claude = "claude:"
    const val Codex = "codex:"
    const val Antigravity = "agy:"
    val pattern = Regex("(hangar|claude|codex|agy):[A-Za-z0-9._-]{1,200}")

    fun session(runtime: RuntimeKind, id: String): String = when (runtime) {
        RuntimeKind.Codex -> Codex
        RuntimeKind.Gemini -> Antigravity
        else -> Claude
    } + id
}
