package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.entitlements
import java.nio.file.Files
import java.nio.file.Path

class AgentPresenceStore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val freshnessMillis: Long = AgentPresencePage.FreshMillis,
    private val doorFreshnessMillis: Long = AgentPresencePage.DoorMillis,
    private val file: Path? = null,
) {
    private data class Seen(val identity: AgentIdentity, val at: Long, val action: String = "", val actionAt: Long = 0,
        val state: PresenceState = PresenceState.Working, val stateAt: Long = at, val beforeRestart: Boolean = false) {
        fun since(next: PresenceState, now: Long): Long = if (state == next) stateAt else now
    }
    private val agents = LinkedHashMap<String, Seen>()
    private val doors = HashMap<String, MutableMap<String, Long>>()
    private var savedAt: Long? = null

    init {
        require(freshnessMillis > 0 && doorFreshnessMillis > 0)
        file?.takeIf { Files.isRegularFile(it) }?.let(::restore)
    }

    @Synchronized fun activity(identity: AgentIdentity, action: String) {
        require(action.isNotBlank() && action.length <= 160) { "Name the observed operation" }
        val key = key(identity)
        val now = clock()
        val previous = agents[key]
        agents[key] = Seen(identity, now, action, now, PresenceState.Working, previous?.since(PresenceState.Working, now) ?: now)
        save(now, changed = previous == null || previous.state != PresenceState.Working || previous.beforeRestart)
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
        if (connected) agents[key] = Seen(identity, now, previous?.action.orEmpty(), previous?.actionAt ?: 0,
            previous?.state ?: PresenceState.Working, previous?.stateAt ?: now)
        save(now, changed = !connected || previous == null || previous.beforeRestart)
    }

    @Synchronized fun mark(identity: AgentIdentity, state: PresenceState) {
        val key = key(identity)
        val now = clock()
        val previous = agents[key]
        agents[key] = Seen(identity, now, previous?.action.orEmpty(), previous?.actionAt ?: 0, state, previous?.since(state, now) ?: now)
        if (state == PresenceState.Gone) doors.remove(key)
        save(now, changed = true)
    }

    @Synchronized fun lastSeen(agent: String): Long? = agents[agent]?.at

    @Synchronized fun read(plans: List<WorkPlan> = emptyList()): AgentPresencePage {
        val held = plans.flatMap { plan -> plan.items.filter { it.status == PlanStatus.Doing }.mapNotNull { item ->
            item.holder?.let(PlanIdentity::agent)?.let { it to AgentHeldItem(plan.thread, item.id, item.title) }
        } }.groupBy({ it.first }, { it.second })
        return page(clock(), held)
    }

    private fun page(now: Long, held: Map<String, List<AgentHeldItem>>): AgentPresencePage = AgentPresencePage(agents.map { (agent, seen) ->
        val connected = doors[agent]?.values.orEmpty().any { fresh(now, it, doorFreshnessMillis) }
        AgentPresence(seen.identity.actor, seen.identity.profile.runtime, seen.identity.profile.model, seen.identity.profile.effort,
            seen.identity.profile.runtime.entitlements().map { it.entitlement },
            live = seen.state != PresenceState.Gone && (connected || fresh(now, seen.at, freshnessMillis)),
            connected = connected, lastSeen = seen.at, lastAction = seen.action, lastActionAt = seen.actionAt, held = held[agent].orEmpty(),
            state = seen.state, stateAt = seen.stateAt, beforeRestart = seen.beforeRestart)
    }, now)

    private fun save(now: Long, changed: Boolean) {
        val path = file ?: return
        if (!changed && savedAt?.let { now - it < SaveMillis } == true) return
        savedAt = now
        runCatching { atomicWrite(path, AgentWorkspaceJson.encodeToString(AgentPresencePage.serializer(), page(now, emptyMap()))) }
    }

    private fun restore(path: Path) {
        val page = runCatching { AgentWorkspaceJson.decodeFromString(AgentPresencePage.serializer(), Files.readString(path)) }.getOrNull() ?: return
        page.agents.forEach { seen ->
            val agent = seen.actor.agent ?: return@forEach
            val profile = AgentProfile(seen.actor.profile ?: "native-${seen.actor.session?.substringBefore(':')}", seen.actor.name, seen.runtime,
                seen.configuredModel, seen.configuredEffort)
            agents[agent] = Seen(AgentIdentity(seen.actor, profile), seen.lastSeen, seen.lastAction, seen.lastActionAt, seen.state, seen.stateAt, beforeRestart = true)
        }
    }

    private fun key(identity: AgentIdentity): String = requireNotNull(identity.actor.agent) { "A canonical agent identity is required" }
    private fun fresh(now: Long, at: Long, limit: Long): Boolean = now >= at && now - at < limit

    private companion object {
        const val SaveMillis = 5_000L
    }
}
