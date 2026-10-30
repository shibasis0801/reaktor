package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.entitlements

class AgentPresenceStore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val freshnessMillis: Long = 300_000,
    private val doorFreshnessMillis: Long = 90_000,
) {
    private data class Seen(val identity: AgentIdentity, val at: Long, val action: String = "", val actionAt: Long = 0)
    private val agents = LinkedHashMap<String, Seen>()
    private val doors = HashMap<String, MutableMap<String, Long>>()

    init { require(freshnessMillis > 0 && doorFreshnessMillis > 0) }

    @Synchronized fun activity(identity: AgentIdentity, action: String) {
        require(action.isNotBlank() && action.length <= 160) { "Name the observed operation" }
        val key = key(identity)
        agents[key] = Seen(identity, clock(), action, clock())
    }

    @Synchronized fun heartbeat(identity: AgentIdentity, door: String? = null, connected: Boolean = true) {
        val key = key(identity)
        require(door == null || door.matches(Regex("[a-zA-Z0-9_-]{1,128}"))) { "Invalid door id" }
        val now = clock()
        val previous = agents[key]
        if (door != null) {
            val connections = doors.getOrPut(key) { HashMap() }
            if (connected) connections[door] = now else connections.remove(door)
        }
        if (connected) agents[key] = Seen(identity, now, previous?.action.orEmpty(), previous?.actionAt ?: 0)
    }

    @Synchronized fun lastSeen(agent: String): Long? = agents[agent]?.at

    @Synchronized fun read(plans: List<WorkPlan> = emptyList()): AgentPresencePage {
        val now = clock()
        val held = plans.flatMap { plan -> plan.items.filter { it.status == PlanStatus.Doing }.mapNotNull { item ->
            item.by?.agent?.let { it to AgentHeldItem(plan.thread, item.id, item.title) }
        } }.groupBy({ it.first }, { it.second })
        return AgentPresencePage(agents.map { (agent, seen) ->
            val connected = doors[agent]?.values.orEmpty().any { fresh(now, it, doorFreshnessMillis) }
            AgentPresence(seen.identity.actor, seen.identity.profile.runtime, seen.identity.profile.model, seen.identity.profile.effort,
                seen.identity.profile.runtime.entitlements().map { it.entitlement }, live = connected || fresh(now, seen.at, freshnessMillis),
                connected = connected, lastSeen = seen.at, lastAction = seen.action, lastActionAt = seen.actionAt, held = held[agent].orEmpty())
        }, now)
    }

    private fun key(identity: AgentIdentity): String = requireNotNull(identity.actor.agent) { "A canonical agent identity is required" }
    private fun fresh(now: Long, at: Long, limit: Long): Boolean = now >= at && now - at < limit
}
