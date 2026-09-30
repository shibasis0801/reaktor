package dev.shibasis.reaktor.conductor

import kotlinx.serialization.Serializable

@Serializable
data class ProtocolCheckpoint(
    val thread: ThreadDocument,
    val promptId: EventId,
    val completed: Map<String, ThreadEvent> = emptyMap(),
    val inFlight: Set<String> = emptySet(),
    val workflow: WorkflowProgress = WorkflowProgress(),
    val sourceRevision: String? = null,
)
