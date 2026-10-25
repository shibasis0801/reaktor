package dev.shibasis.reaktor.conductor.workspace

import kotlinx.serialization.Serializable

@Serializable
enum class AgentMessageKind { Question, Answer, Handoff, Finding, DecisionRequest, ResourceConflict }

@Serializable
data class AgentRecipient(val task: String? = null, val session: String? = null)

@Serializable
data class AgentMessage(
    val graph: String,
    val recipient: AgentRecipient,
    val kind: AgentMessageKind,
    val body: String,
    val id: String = "",
    val thread: String? = null,
    val replyTo: String? = null,
    val author: BoardActor? = null,
    val task: String? = null,
    val attempt: Int? = null,
    val artifacts: List<String> = emptyList(),
    val at: Long = 0,
    val requestId: String? = null,
    val requestRevision: Long? = null,
)

@Serializable
enum class AgentMessageState { Accepted, Queued, Delivered, Acknowledged }

@Serializable
data class AgentMessageEvent(val id: String, val message: String, val state: AgentMessageState, val by: BoardActor, val at: Long,
    val runId: String? = null, val turnId: String? = null)

@Serializable
data class AgentMessageTarget(val graph: String, val message: String, val run: String, val task: String? = null, val attempt: Int? = null)

@Serializable
data class TaskHandoff(
    val graph: String,
    val task: String,
    val attempt: Int,
    val goal: String,
    val done: String,
    val id: String = "",
    val author: BoardActor? = null,
    val candidate: String? = null,
    val decisions: List<String> = emptyList(),
    val openIssues: List<String> = emptyList(),
    val changedPaths: List<String> = emptyList(),
    val checkReceipts: List<String> = emptyList(),
    val reviewReceipts: List<String> = emptyList(),
    val nextAction: String = "",
    val sessionLogs: List<String> = emptyList(),
    val at: Long = 0,
    val recordedSummary: Boolean = false,
)

@Serializable
data class TaskDecision(
    val graph: String,
    val statement: String,
    val rationale: String,
    val subjects: List<String>,
    val id: String = "",
    val author: BoardActor? = null,
    val supersedes: String? = null,
    val personAccepted: Boolean = false,
    val at: Long = 0,
)

@Serializable
data class TaskCoordination(
    val next: Long = 1,
    val messages: List<AgentMessage> = emptyList(),
    val handoffs: List<TaskHandoff> = emptyList(),
    val decisions: List<TaskDecision> = emptyList(),
    val messageEvents: List<AgentMessageEvent> = emptyList(),
) {
    companion object {
        const val Retained = 200
        const val MessageBytes = 8 * 1024
        const val RecordBytes = 64 * 1024
    }
}
