package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.Entitlement
import dev.shibasis.reaktor.conductor.RuntimeKind
import kotlinx.serialization.Serializable

@Serializable
enum class PresenceState { Working, Waiting, Gone }

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
    val state: PresenceState = PresenceState.Working,
    val stateAt: Long = 0,
    val beforeRestart: Boolean = false,
)

@Serializable
data class AgentPresencePage(val agents: List<AgentPresence>, val observedAt: Long) {
    fun at(now: Long, freshMillis: Long = FreshMillis, doorMillis: Long = DoorMillis): AgentPresencePage = copy(observedAt = now, agents = agents.map { agent ->
        val connected = agent.connected && now - observedAt < doorMillis
        agent.copy(connected = connected, live = agent.state != PresenceState.Gone && (connected || now >= agent.lastSeen && now - agent.lastSeen < freshMillis))
    })

    companion object {
        const val FreshMillis = 300_000L
        const val DoorMillis = 90_000L
    }
}
