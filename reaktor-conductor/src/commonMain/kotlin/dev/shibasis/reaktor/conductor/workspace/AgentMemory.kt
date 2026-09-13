package dev.shibasis.reaktor.conductor.workspace

import kotlinx.serialization.Serializable

/** A retained assertion with provenance, never an approval or current check receipt. */
@Serializable data class AgentMemory(val id: String, val title: String, val text: String,
    val runId: String, val eventId: String, val participant: String, val sourceRevision: String?,
    val subjectRefs: List<String>, val recordedAt: Long)
