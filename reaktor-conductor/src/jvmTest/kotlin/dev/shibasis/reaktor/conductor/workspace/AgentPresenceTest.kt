package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentPresenceTest {
    private val actor = BoardActor(BoardActorKind.Agent, "Falcon", "codex:one", "agent:codex:one")
    private val identity = AgentIdentity(actor, AgentProfile("falcon", "Falcon", RuntimeKind.Codex, model = "configured"))

    @Test fun activityExpiresAndHeartbeatsPreserveTheLastAction() {
        var now = 10L
        val store = AgentPresenceStore({ now }, freshnessMillis = 100, doorFreshnessMillis = 20)
        store.activity(identity, "plan.write")
        now = 60
        store.heartbeat(identity, "door-a")
        val seen = store.read().agents.single()
        assertTrue(seen.live)
        assertTrue(seen.connected)
        assertEquals("plan.write", seen.lastAction)
        assertEquals(10L, seen.lastActionAt)
        assertEquals("configured", seen.configuredModel)
        assertEquals("Remaining quota is unknown", seen.quota)
        now = 80
        assertFalse(store.read().agents.single().connected)
        now = 160
        assertFalse(store.read().agents.single().live)
        assertEquals(60L, store.lastSeen(actor.agent!!))
    }

    @Test fun closingOneDoorKeepsTheOtherDoorAndOtherAgentsIndependent() {
        var now = 10L
        val store = AgentPresenceStore({ now }, 100, 20)
        store.heartbeat(identity, "door-a")
        store.heartbeat(identity, "door-b")
        val other = identity.copy(actor = actor.copy(name = "Osprey", session = "codex:two", agent = "agent:codex:two"))
        store.activity(other, "atlas.read")
        now = 15
        store.heartbeat(identity, "door-a", connected = false)
        assertTrue(store.read().agents.first().connected)
        assertFalse(store.read().agents.last().connected)
        store.heartbeat(identity, "door-b", connected = false)
        assertFalse(store.read().agents.first().connected)
        assertEquals("atlas.read", store.read().agents.last().lastAction)
    }

    @Test fun heldItemsComeFromCanonicalPlanClaimsAndRestartIsOffline() {
        val store = AgentPresenceStore({ 42L })
        store.activity(identity, "plan.next")
        val plan = WorkPlan("codex:one", "Work", createdBy = actor, createdAt = 0, updatedAt = 0, items = listOf(
            PlanItem("held", "Build", status = PlanStatus.Doing, by = actor),
            PlanItem("done", "Done", status = PlanStatus.Done, by = actor),
            PlanItem("foreign", "Other", status = PlanStatus.Doing, by = actor.copy(agent = "agent:codex:two")),
        ))
        assertEquals(listOf(AgentHeldItem(plan.thread, "held", "Build")), store.read(listOf(plan)).agents.single().held)
        assertEquals(emptyList(), AgentPresenceStore().read().agents)
    }
}
