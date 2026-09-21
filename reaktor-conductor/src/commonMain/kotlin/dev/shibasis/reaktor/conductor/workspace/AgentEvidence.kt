package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import kotlinx.serialization.Serializable

@Serializable
data class AgentGraphSubject(val ref: String, val definitionRevision: String? = null, val sourcePath: String? = null)

@Serializable
data class AgentCandidate(
    val id: String,
    val workspaceRoot: String,
    val baseCommit: String?,
    val sourceDigest: String,
    val observedAt: Long,
    val changedFiles: List<String>,
    val diff: ArtifactRef?,
    val complete: Boolean,
    val notices: List<String> = emptyList(),
    val subjects: List<AgentGraphSubject> = emptyList(),
    val sourceRoots: List<String> = listOf(workspaceRoot),
    /**
     * Files whose length, not whose content, went into [sourceDigest].
     *
     * Binaries — APKs, vendored frameworks, checked-in archives — are not source, and streaming them
     * spends the capture budget that the code the snapshot exists to cover then cannot have. They
     * stay in the revision by size, which moves whenever one is replaced by a different build.
     */
    val fingerprintedBySize: Int = 0,
)

@Serializable
data class AgentFinding(
    val id: String,
    val candidateId: String,
    val producerRunId: String,
    val participant: String,
    val title: String,
    val detail: String,
    val severity: String,
    val subject: AgentGraphSubject? = null,
    val sourcePath: String? = null,
    val line: Int? = null,
    val resolvedByCandidate: String? = null,
    val resolution: String? = null,
)

@Serializable
data class AgentCandidateCheck(val id: String, val candidateId: String, val result: CheckResult, val source: String)

@Serializable
data class AgentTaskEvidence(
    val taskId: String,
    val candidates: List<AgentCandidate> = emptyList(),
    val findings: List<AgentFinding> = emptyList(),
    val checks: List<AgentCandidateCheck> = emptyList(),
    val requiredChecks: List<String> = emptyList(),
    val acceptedCandidate: String? = null,
)

@Serializable
data class AgentArtifactPage(val ref: ArtifactRef, val offset: Long, val text: String, val nextOffset: Long?, val truncated: Boolean)

@Serializable
data class AgentEvidenceEdge(val from: String, val relation: String, val to: String)

@Serializable
data class AgentEvidenceGraph(val task: AgentTaskEvidence, val edges: List<AgentEvidenceEdge>)
