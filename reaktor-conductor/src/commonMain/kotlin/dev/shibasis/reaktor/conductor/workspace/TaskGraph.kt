package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.WorkflowContract
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class TaskGraphStatus { Draft, Proposed, Running, Paused, Finished, Failed, Cancelled }

@Serializable
enum class TaskKind { Agent, Check, Gate, Merge, Note }

@Serializable
enum class TaskCondition { Always, Succeeded, Failed, Pass, Repair, Accepted }

@Serializable
enum class TaskState { Pending, Running, Waiting, Succeeded, Failed, Skipped, Cancelled }

@Serializable
enum class TaskAccess { Read, Write }

@Serializable
data class TaskSeat(
    val agent: RuntimeKind? = null,
    val model: String? = null,
    val effort: String? = null,
    val access: TaskAccess = TaskAccess.Read,
    val lead: Boolean = false,
)

@Serializable
data class TaskDependency(val task: String, val condition: TaskCondition = TaskCondition.Succeeded)

@Serializable
data class TaskLayout(val x: Double, val y: Double)

@Serializable
data class TaskCheckResult(val check: String, val ok: Boolean, val receipt: String? = null, val text: String = "")

@Serializable
data class TaskOutcome(
    val summary: String = "",
    val verdict: String? = null,
    val files: List<String> = emptyList(),
    val checks: List<TaskCheckResult> = emptyList(),
    val questions: List<String> = emptyList(),
    val log: String? = null,
    val tokens: Long? = null,
    val at: Long = 0,
)

@Serializable
data class TaskPlan(
    val id: String,
    val dependsOn: List<TaskDependency> = emptyList(),
    val seat: TaskSeat? = null,
    val kind: TaskKind = TaskKind.Agent,
    val contract: WorkflowContract = WorkflowContract.Text,
    val check: String? = null,
    val state: TaskState = TaskState.Pending,
    val attempt: Int = 0,
    val outcome: TaskOutcome? = null,
    val layout: TaskLayout? = null,
    val run: String? = null,
    val repairOf: String? = null,
    val readySince: Long? = null,
    val changedAt: Long = 0,
    val note: String? = null,
    val held: Boolean = false,
)

@Serializable
data class TaskEnvelope(
    val agents: List<RuntimeKind> = emptyList(),
    val models: List<String> = emptyList(),
    val efforts: List<String> = emptyList(),
    val maxTasks: Int = 24,
    val perAgent: Int = 1,
    val writes: List<String> = emptyList(),
    val tokens: Long? = null,
    val repairRounds: Int = 1,
) {
    companion object {
        val PersonOnly = listOf("landing in the main checkout", "pushing", "opening or merging a pull request", "deploying", "writing to production")
    }
}

@Serializable
data class TaskGraphChange(
    val revision: Long,
    val at: Long,
    val by: BoardActor,
    val reason: String,
    val summary: List<String>,
    val undo: List<TaskGraphOp> = emptyList(),
)

@Serializable
data class TaskGraph(
    val id: String,
    val title: String,
    val goal: String = "",
    val status: TaskGraphStatus = TaskGraphStatus.Proposed,
    val lead: String? = null,
    val envelope: TaskEnvelope = TaskEnvelope(),
    val approvedRevision: Long? = null,
    val revision: Long = 1,
    val createdBy: BoardActor,
    val createdAt: Long,
    val updatedAt: Long,
    val tokensUsed: Long = 0,
    val reason: String? = null,
    val changes: List<TaskGraphChange> = emptyList(),
) {
    val number: Int get() = id.removePrefix("G").toIntOrNull() ?: 0
    val changedSinceApproval: Int get() = approvedRevision?.let { approved -> changes.count { it.revision > approved } } ?: 0
}

@Serializable
sealed interface TaskGraphOp {
    @Serializable
    @SerialName("add")
    data class Add(
        val title: String,
        val key: String? = null,
        val intent: String = "",
        val acceptance: List<String> = emptyList(),
        val scope: List<String> = emptyList(),
        val kind: TaskKind = TaskKind.Agent,
        val seat: TaskSeat? = null,
        val contract: WorkflowContract = WorkflowContract.Text,
        val check: String? = null,
        val dependsOn: List<TaskDependency> = emptyList(),
        val layout: TaskLayout? = null,
        val parent: String? = null,
    ) : TaskGraphOp

    @Serializable
    @SerialName("update")
    data class Update(
        val task: String,
        val title: String? = null,
        val intent: String? = null,
        val acceptance: List<String>? = null,
        val scope: List<String>? = null,
        val kind: TaskKind? = null,
        val contract: WorkflowContract? = null,
        val check: String? = null,
        val layout: TaskLayout? = null,
    ) : TaskGraphOp

    @Serializable
    @SerialName("split")
    data class Split(val task: String, val into: List<Add>) : TaskGraphOp

    @Serializable
    @SerialName("remove")
    data class Remove(val task: String) : TaskGraphOp

    @Serializable
    @SerialName("connect")
    data class Connect(val from: String, val to: String, val condition: TaskCondition = TaskCondition.Succeeded) : TaskGraphOp

    @Serializable
    @SerialName("disconnect")
    data class Disconnect(val from: String, val to: String) : TaskGraphOp

    @Serializable
    @SerialName("seat")
    data class Seat(val task: String, val seat: TaskSeat) : TaskGraphOp

    @Serializable
    @SerialName("note")
    data class Note(val text: String, val layout: TaskLayout? = null) : TaskGraphOp

    @Serializable
    @SerialName("retry")
    data class Retry(val task: String) : TaskGraphOp

    @Serializable
    @SerialName("envelope")
    data class Envelope(val envelope: TaskEnvelope) : TaskGraphOp

    @Serializable
    @SerialName("restore")
    data class Restore(val task: String, val dependsOn: List<TaskDependency>, val seat: TaskSeat?, val state: TaskState) : TaskGraphOp
}

@Serializable
data class TaskGraphPatch(val ops: List<TaskGraphOp>, val reason: String, val expectedRevision: Long? = null)

@Serializable
data class TaskGraphDraft(
    val title: String,
    val goal: String = "",
    val tasks: List<TaskGraphOp.Add>,
    val envelope: TaskEnvelope = TaskEnvelope(),
    val lead: String? = null,
)

@Serializable
enum class TaskReport { Running, NeedsInput, Review, Blocked, Failed, Succeeded }

@Serializable
data class TaskGraphView(val graph: TaskGraph, val tasks: List<BoardTask>, val ready: List<String> = emptyList())

@Serializable
data class TaskGraphSummary(
    val id: String,
    val title: String,
    val status: TaskGraphStatus,
    val revision: Long,
    val tasks: Int,
    val finished: Int,
    val running: Int,
    val waiting: Int,
    val failed: Int,
    val agents: List<RuntimeKind>,
    val tokensUsed: Long,
    val tokens: Long?,
    val lead: String?,
    val changedSinceApproval: Int,
    val updatedAt: Long,
    val reason: String? = null,
    val proposedBy: String? = null,
)
