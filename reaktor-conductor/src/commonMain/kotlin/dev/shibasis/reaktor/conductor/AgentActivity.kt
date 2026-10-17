package dev.shibasis.reaktor.conductor

import kotlinx.serialization.Serializable

@Serializable
enum class ActivityKind { Tool, Plan, Agent, Context, Checkpoint, Recovery, Control }

@Serializable
enum class ActivityStatus { Started, Completed, Failed, Waiting, Recorded, Interrupted }

@Serializable
data class AgentActivityItem(
    val id: String,
    val kind: ActivityKind,
    val title: String,
    val status: ActivityStatus = ActivityStatus.Recorded,
    val input: String? = null,
    val output: String? = null,
    val parentId: String? = null,
    val subjectRefs: List<String> = emptyList(),
    val detailTruncated: Boolean = false,
    val nativeAgents: List<NativeAgentState> = emptyList(),
)

@Serializable data class NativeAgentState(val id: String, val parentId: String? = null, val name: String? = null,
    val status: String = "unknown", val turnId: String? = null, val output: String? = null)

@Serializable
data class AgentActivityRecord(
    val sequence: Long,
    val runId: String,
    val agent: String,
    val attempt: Int,
    val recordedAt: Long,
    val item: AgentActivityItem,
)

@Serializable
data class AgentActivityPage(
    val records: List<AgentActivityRecord>,
    val nextCursor: Long,
    val hasMore: Boolean,
)
