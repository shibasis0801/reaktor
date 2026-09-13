package dev.shibasis.reaktor.conductor.workspace

import kotlinx.serialization.Serializable

@Serializable data class AgentWorktreeRoot(val source: String, val path: String, val baseline: String)
@Serializable data class AgentWorktree(val id: String, val runId: String, val participant: String,
    val sourceRevision: String, val roots: List<AgentWorktreeRoot>, val appliedPatch: String? = null,
    val applyState: String? = null)
@Serializable data class AgentWorktreeReview(val worktree: AgentWorktree, val patchDigest: String,
    val sourceRevision: String, val changedFiles: List<String>, val diff: String, val conflicts: List<String>)
