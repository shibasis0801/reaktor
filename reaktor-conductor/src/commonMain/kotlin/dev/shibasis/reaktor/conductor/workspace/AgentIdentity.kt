package dev.shibasis.reaktor.conductor.workspace

import kotlinx.serialization.Serializable

@Serializable
data class AgentIdentity(val actor: BoardActor, val profile: AgentProfile, val bindingRevision: Long = 0)

@Serializable
data class AgentProfileBinding(val agent: String, val profile: String? = null, val revision: Long = 0, val by: BoardActor, val updatedAt: Long)
