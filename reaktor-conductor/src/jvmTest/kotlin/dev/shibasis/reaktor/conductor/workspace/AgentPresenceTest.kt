package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
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

    @Test fun stopWaitsTheNextActionWorksAndTheEndIsGone() {
        var now = 10L
        val store = AgentPresenceStore({ now })
        store.activity(identity, "plan.write")
        assertEquals(PresenceState.Working, store.read().agents.single().state)
        now = 20
        store.mark(identity, PresenceState.Waiting)
        store.read().agents.single().let {
            assertEquals(PresenceState.Waiting, it.state)
            assertEquals(20L, it.stateAt)
            assertTrue(it.live)
            assertEquals("plan.write", it.lastAction)
        }
        now = 30
        store.activity(identity, "atlas.query")
        store.read().agents.single().let { assertEquals(PresenceState.Working, it.state); assertEquals(30L, it.stateAt) }
        store.heartbeat(identity, "door-a")
        now = 40
        store.mark(identity, PresenceState.Gone)
        store.read().agents.single().let {
            assertEquals(PresenceState.Gone, it.state)
            assertFalse(it.live)
            assertFalse(it.connected)
        }
    }

    @Test fun presenceIsKeptOnDiskAndMarkedBeforeARestart() {
        val directory = Files.createTempDirectory("agent-presence")
        try {
            val file = directory.resolve("presence.json")
            var now = 10L
            AgentPresenceStore({ now }, file = file).apply {
                activity(identity, "plan.write")
                heartbeat(identity, "door-a")
                now = 20
                mark(identity, PresenceState.Waiting)
            }
            val written = AgentWorkspaceJson.decodeFromString(AgentPresencePage.serializer(), Files.readString(file)).agents.single()
            assertEquals(PresenceState.Waiting, written.state)
            assertTrue(written.connected)
            now = 50
            val restarted = AgentPresenceStore({ now }, file = file)
            restarted.read().agents.single().let {
                assertTrue(it.beforeRestart)
                assertFalse(it.connected)
                assertEquals(PresenceState.Waiting, it.state)
                assertEquals(20L, it.stateAt)
                assertEquals("plan.write", it.lastAction)
                assertEquals(actor, it.actor)
                assertEquals("configured", it.configuredModel)
            }
            assertEquals(20L, restarted.lastSeen(actor.agent!!))
            restarted.heartbeat(identity, "door-b")
            assertFalse(restarted.read().agents.single().beforeRestart)
            assertFalse(AgentWorkspaceJson.decodeFromString(AgentPresencePage.serializer(), Files.readString(file)).agents.single().beforeRestart)
        } finally { directory.toFile().deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun aPageReadLaterLosesItsDoorsAndThenItsLife() {
        val page = AgentPresencePage(listOf(AgentPresence(actor, RuntimeKind.Codex, live = true, connected = true, lastSeen = 1_000)), 1_000)
        assertTrue(page.at(1_000 + AgentPresencePage.DoorMillis - 1).agents.single().connected)
        page.at(1_000 + AgentPresencePage.DoorMillis).agents.single().let { assertFalse(it.connected); assertTrue(it.live) }
        assertFalse(page.at(1_000 + AgentPresencePage.FreshMillis).agents.single().live)
        assertFalse(page.copy(agents = page.agents.map { it.copy(state = PresenceState.Gone) }).at(1_000).agents.single().live)
    }
}
