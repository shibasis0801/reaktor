package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.Entitlement
import dev.shibasis.reaktor.conductor.RuntimeKind
import kotlinx.serialization.Serializable

@Serializable
data class AgentHeldItem(val thread: String, val item: String, val title: String)

@Serializable
data class AgentPresence(
    val actor: BoardActor,
    val runtime: RuntimeKind,
    val configuredModel: String? = null,
    val configuredEffort: String? = null,
    val quotaPools: List<Entitlement> = emptyList(),
    val quota: String = "Remaining quota is unknown",
    val live: Boolean,
    val connected: Boolean,
    val lastSeen: Long,
    val lastAction: String = "",
    val lastActionAt: Long = 0,
    val held: List<AgentHeldItem> = emptyList(),
)

@Serializable
data class AgentPresencePage(val agents: List<AgentPresence>, val observedAt: Long)
