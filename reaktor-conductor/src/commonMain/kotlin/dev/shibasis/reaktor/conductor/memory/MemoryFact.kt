package dev.shibasis.reaktor.conductor.memory

import dev.shibasis.reaktor.conductor.workspace.AgentMemory
import dev.shibasis.reaktor.conductor.workspace.BoardActor
import kotlinx.serialization.Serializable

@Serializable
enum class MemoryScopeKind { Workspace, Repository, Plan, Agent }

@Serializable
data class MemoryScope(val kind: MemoryScopeKind, val key: String) {
    init { require(key.isNotBlank() && key.length <= 2000) }
}

@Serializable
enum class MemoryKind { Fact, Decision, Preference, Gotcha, Reference }

@Serializable
enum class MemoryStatus { Proposed, Kept, Retired }

@Serializable
data class MemoryProvenance(
    val agent: String,
    val session: String? = null,
    val run: String? = null,
    val event: String? = null,
    val source: String,
    val sourceDate: Long,
    val sourceRevision: String? = null,
) {
    init {
        require(agent.isNotBlank() && agent.length <= 2000)
        require(source.isNotBlank() && source.length <= 2000 && sourceDate > 0)
    }
}

@Serializable
data class MemoryFact(
    val id: String,
    val scope: MemoryScope,
    val kind: MemoryKind,
    val title: String,
    val text: String,
    val links: List<String>,
    val provenance: List<MemoryProvenance>,
    val status: MemoryStatus,
    val revision: Long,
    val recordedAt: Long,
    val updatedAt: Long,
    val reviewedBy: BoardActor? = null,
    val supersedes: String? = null,
    val importance: Int = 1,
    val legacy: AgentMemory? = null,
) {
    init {
        require(id.matches(Regex("[a-f0-9]{64}")))
        require(title.isNotBlank() && title.length <= 200 && text.isNotBlank() && text.length <= 6000)
        require(links.size <= 100 && links.all { it.isNotBlank() && it.length <= 2000 })
        require(provenance.isNotEmpty() && revision > 0 && recordedAt > 0 && updatedAt >= recordedAt)
        require(importance in 1..3 && supersedes != id)
    }
}

@Serializable
data class RememberFact(
    val scope: MemoryScope,
    val kind: MemoryKind,
    val title: String,
    val text: String,
    val links: List<String> = emptyList(),
    val provenance: MemoryProvenance,
    val supersedes: String? = null,
    val importance: Int = 1,
)

@Serializable
data class MemoryQuery(
    val scopes: List<MemoryScope>,
    val text: String = "",
    val kind: MemoryKind? = null,
    val link: String? = null,
    val status: MemoryStatus? = MemoryStatus.Kept,
    val limit: Int = 20,
) {
    init { require(scopes.isNotEmpty() && scopes.size <= 100 && text.length <= 2000 && limit in 1..100) }
}

@Serializable
data class ReviewFact(val id: String, val revision: Long, val keep: Boolean, val by: BoardActor)

@Serializable
data class RetireFact(val id: String, val revision: Long, val by: BoardActor)

@Serializable
data class ReviseFact(val id: String, val revision: Long, val draft: RememberFact)

@Serializable
data class RecallFacts(val scopes: List<MemoryScope>, val links: List<String> = emptyList(), val limit: Int = 8)
