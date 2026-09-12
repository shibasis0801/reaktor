package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Tool clients must see default statuses/flags without knowing Kotlin constructor defaults.
val AgentWorkspaceJson: Json = Json(ConductorJson) { encodeDefaults = true }

@Serializable
enum class AgentRunStatus { Running, Completed, Failed, Interrupted }

@Serializable
data class AgentSubmission(
    val requestId: String,
    val provider: RuntimeKind,
    val prompt: String,
    val threadId: String? = null,
    val model: String? = null,
    val allowWrites: Boolean = false,
    val context: ContextPacket? = null,
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
)

@Serializable
data class AgentTranscript(val threadId: String, val events: List<ThreadEvent>, val partial: Boolean)

@Serializable
data class AgentWorkspaceInfo(
    val workspaceRoot: String,
    val providers: List<RuntimeKind>,
    val maxActiveRuns: Int,
    val sessionMode: String = "CLI batch turns with persisted provider continuation",
)
