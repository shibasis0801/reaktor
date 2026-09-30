package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import kotlinx.serialization.Serializable

@Serializable data class AgentCheckpoint(val id: String, val runId: String, val recordedAt: Long,
    val sourceRevision: String?, val completed: List<String>, val inFlight: List<String>, val workflow: WorkflowProgress)
@Serializable data class AgentRunbook(val revision: String, val definition: WorkflowDefinition)
