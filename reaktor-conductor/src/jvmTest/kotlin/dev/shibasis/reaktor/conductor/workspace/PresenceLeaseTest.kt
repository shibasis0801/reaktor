package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class PresenceLeaseTest {
    @Test fun aQuietOpenDoorKeepsItsClaimAndAClosedDoorLetsItsClaimExpire() {
        val directory = Files.createTempDirectory("presence-lease")
        try {
            var now = 1_000L
            val presence = AgentPresenceStore(clock = { now })
            val plans = WorkPlanStore(directory, clock = { now }, activity = presence::lastSeen)
            fun identity(session: String) = AgentIdentity(
                BoardActor(BoardActorKind.Agent, session, session, "agent:$session"),
                AgentProfile("heartbeat-test", "Heartbeat test", RuntimeKind.Codex),
            )
            val open = identity("codex:open-door")
            val closed = identity("codex:closed-door")
            for (agent in listOf(open, closed)) {
                val written = plans.write(requireNotNull(agent.actor.session), PlanPatch(
                    title = "Quiet claim",
                    ops = listOf(PlanOp.Add("Work", key = "work"), PlanOp.Status("work", PlanStatus.Doing)),
                ), agent.actor)
                assertTrue(written.refused.isEmpty())
                assertNotNull(written.plan.item("work")?.lease)
                presence.activity(agent, "plan.write")
            }
            presence.heartbeat(open, "open")
            presence.heartbeat(closed, "closed")
            repeat(22) { tick ->
                now += 30_000
                presence.heartbeat(open, "open")
                when (tick) {
                    0 -> presence.heartbeat(closed, "closed")
                    1 -> presence.heartbeat(closed, "closed", connected = false)
                }
            }
            assertEquals(661_000L, now)
            val held = assertNotNull(plans.plan(requireNotNull(open.actor.session)))
            val released = assertNotNull(plans.plan(requireNotNull(closed.actor.session)))
            val page = presence.read(listOf(held, released))
            val active = page.agents.single { it.actor == open.actor }
            assertTrue(active.connected)
            assertTrue(active.live)
            assertEquals(now, active.lastSeen)
            assertEquals("plan.write", active.lastAction)
            assertEquals(1_000L, active.lastActionAt)
            assertEquals(PlanStatus.Doing, held.item("work")?.status)
            assertEquals(open.actor, held.item("work")?.holder)
            assertEquals(listOf(AgentHeldItem(held.thread, "work", "Work")), active.held)
            val inactive = page.agents.single { it.actor == closed.actor }
            assertFalse(inactive.connected)
            assertFalse(inactive.live)
            assertEquals(31_000L, inactive.lastSeen)
            assertTrue(inactive.held.isEmpty())
            assertEquals(PlanStatus.Todo, released.item("work")?.status)
            assertNull(released.item("work")?.lease)
            assertTrue(released.item("work")?.reason.orEmpty().startsWith("Lease expired after quiet activity"))
        } finally {
            directory.toFile().deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }
}

