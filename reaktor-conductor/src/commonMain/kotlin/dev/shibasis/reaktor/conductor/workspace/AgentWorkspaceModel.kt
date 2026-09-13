package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Tool clients must see default statuses/flags without knowing Kotlin constructor defaults.
val AgentWorkspaceJson: Json = Json(ConductorJson) { encodeDefaults = true }

@Serializable
enum class AgentRunStatus { Running, Completed, Failed, Interrupted }

@Serializable
enum class AgentCollaboration { Single, Compare, Council }

@Serializable
data class AgentPartner(val provider: RuntimeKind, val model: String? = null, val effort: NativeEffort? = null)

@Serializable
data class AgentParticipantRun(
    val provider: RuntimeKind,
    val status: AgentRunStatus = AgentRunStatus.Running,
    val output: String = "",
    val outputTruncated: Boolean = false,
    val lastTool: String? = null,
    val session: ProviderSession? = null,
    /**
     * Reasoning is kept out of [output] so a view can collapse it by default and so a transport
     * that exposes none is visibly different from one that produced none this turn.
     */
    val reasoning: String = "",
    val reasoningTruncated: Boolean = false,
    val reasoningFidelity: ReasoningFidelity? = null,
    val effort: EffortRecord = EffortRecord.none,
    /** Requests this participant is blocked on. Persisted, so a reconnect does not re-ask. */
    val pending: List<PendingRequest> = emptyList(),
)

@Serializable
data class AgentSubmission(
    val requestId: String,
    val provider: RuntimeKind,
    val prompt: String,
    val threadId: String? = null,
    val model: String? = null,
    val allowWrites: Boolean = false,
    val context: ContextPacket? = null,
    val collaboration: AgentCollaboration = AgentCollaboration.Single,
    val partner: AgentPartner? = null,
    /** Provider vocabulary, validated against the capability record before anything is dispatched. */
    val effort: NativeEffort? = null,
)

@Serializable
data class AgentRunRecord(
    val id: String,
    val threadId: String,
    val provider: RuntimeKind,
    val requestFingerprint: String,
    val title: String,
    val model: String? = null,
    val allowWrites: Boolean = false,
    val status: AgentRunStatus = AgentRunStatus.Running,
    val revision: Long = 1,
    val startedAt: Long,
    val updatedAt: Long,
    val output: String = "",
    val outputTruncated: Boolean = false,
    val lastTool: String? = null,
    val session: ProviderSession? = null,
    val usage: AgentUsage? = null,
    val reportedUsage: AgentUsage? = null,
    val failure: String? = null,
    val collaboration: AgentCollaboration = AgentCollaboration.Single,
    val partner: AgentPartner? = null,
    val participants: Map<String, AgentParticipantRun> = emptyMap(),
    val turnUsage: UsageSummary? = null,
    val effort: EffortRecord = EffortRecord.none,
    val serviceTier: String? = null,
    val reasoning: String = "",
    val reasoningTruncated: Boolean = false,
    val reasoningFidelity: ReasoningFidelity? = null,
    val pending: List<PendingRequest> = emptyList(),
)

@Serializable
data class AgentTranscript(val threadId: String, val events: List<ThreadEvent>, val partial: Boolean)

@Serializable
data class AgentWorkspaceInfo(
    val workspaceRoot: String,
    val providers: List<RuntimeKind>,
    val maxActiveRuns: Int,
    val sessionMode: String = "CLI batch turns with persisted provider continuation",
    val collaborations: List<AgentCollaboration> = listOf(AgentCollaboration.Single),
    /**
     * What each installed harness advertises here. A client must offer only what this reports:
     * a control with no capability behind it is how a schema entry becomes a broken button.
     */
    val capabilities: List<ProviderCapability> = emptyList(),
) {
    fun capability(provider: RuntimeKind): ProviderCapability? = capabilities.firstOrNull { it.runtime == provider }
}

/** Drops one answered request from the run and whichever participant was waiting on it. */
fun AgentRunRecord.withRequestResolved(agent: String, requestId: String): AgentRunRecord = copy(
    pending = pending.filterNot { it.id == requestId },
    participants = participants.mapValues { (id, participant) ->
        if (id == agent) participant.copy(pending = participant.pending.filterNot { it.id == requestId }) else participant
    },
)
