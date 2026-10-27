package dev.shibasis.reaktor.conductor.workspace

import kotlinx.serialization.Serializable

/** A retained assertion with provenance, never an approval or current check receipt. */
@Serializable enum class AgentMemoryScope { Workspace, Graph, Task }

@Serializable data class AgentMemory(val id: String, val title: String, val text: String,
    val runId: String, val eventId: String, val participant: String, val sourceRevision: String?,
    val subjectRefs: List<String>, val recordedAt: Long,
    val scope: AgentMemoryScope = AgentMemoryScope.Workspace, val origin: AgentTaskTarget? = null,
    val status: AgentMemoryStatus = AgentMemoryStatus.Kept, val importance: Int = 1,
    val supersedes: String? = null, val source: String? = null, val sourceDate: Long? = null,
    val reviewedBy: BoardActor? = null)

@Serializable enum class AgentMemoryStatus { Proposed, Kept, Dropped }

@Serializable data class AgentMemoryDraft(val title: String, val text: String, val source: String,
    val sourceDate: Long, val importance: Int = 1, val supersedes: String? = null,
    val scope: AgentMemoryScope = AgentMemoryScope.Workspace, val runId: String? = null,
    val subjectRefs: List<String> = emptyList())
