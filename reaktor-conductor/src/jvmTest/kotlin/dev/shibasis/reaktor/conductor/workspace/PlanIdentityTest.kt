package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ProviderSession
import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanIdentityTest {
    private val owner = BoardActor(BoardActorKind.Person, "owner")
    private fun run(id: String, thread: String = "conversation", provider: RuntimeKind = RuntimeKind.Codex) =
        AgentRunRecord(id, thread, provider, "request", "Work", startedAt = 1, updatedAt = 1,
            session = ProviderSession(provider, "native-$id"))

    @Test fun twoRunsAndTheirNativeSessionsHaveOneIdentity() {
        val first = run("first")
        val second = run("second")
        val actors = listOf(
            PlanIdentity.actor(BoardActor(BoardActorKind.Agent, "spawned", "hangar:first"), first),
            PlanIdentity.actor(BoardActor(BoardActorKind.Agent, "spawned", "hangar:second"), second),
            PlanIdentity.actor(BoardActor(BoardActorKind.Agent, "Codex", "codex:native-first"), first),
        )
        assertEquals(setOf("agent:codex:conversation"), actors.map { it.agent }.toSet())
        assertEquals("hangar:first", actors.first().session)
        assertTrue(actors.first().same(actors.last()))
        assertFalse(actors.first().same(PlanIdentity.actor(actors.last(), run("other", "other"))))
        assertFalse(actors.first().same(PlanIdentity.actor(actors.last(), run("claude", provider = RuntimeKind.ClaudeCode))))
    }

    @Test fun unknownRunCannotSupplyItsOwnIdentity() {
        val forged = BoardActor(BoardActorKind.Agent, "spawned", "hangar:unknown", "agent:codex:conversation")
        assertNull(PlanIdentity.actor(forged).agent)
        assertEquals("agent:agy:native", PlanIdentity.actor(forged.copy(session = "agy:native")).agent)
        assertNull(PlanIdentity.actor(owner.copy(agent = "agent:codex:conversation")).agent)
    }

    @Test fun aCouncilParticipantKeepsItsOwnProvider() {
        val council = run("council").copy(participants = mapOf("critic" to AgentParticipantRun(
            RuntimeKind.ClaudeCode, session = ProviderSession(RuntimeKind.ClaudeCode, "critic-session"))))
        val actor = PlanIdentity.actor(BoardActor(BoardActorKind.Agent, "critic", "claude:critic-session"), council)
        assertEquals("agent:claude:conversation", actor.agent)
    }

    @Test fun aResumedAgentRetainsItsClaimAndSeatAfterStoreRestart() {
        val directory = Files.createTempDirectory("plan-identity")
        try {
            val runs = listOf(run("first"), run("second"), run("stranger", "elsewhere"))
            val identify: (BoardActor) -> BoardActor = { actor ->
                PlanIdentity.actor(actor, runs.firstOrNull { actor.session == "hangar:${it.id}" })
            }
            val first = BoardActor(BoardActorKind.Agent, "spawned", "hangar:first")
            val second = first.copy(session = "hangar:second")
            val store = WorkPlanStore(directory, identity = identify)
            store.write("hangar:launch", PlanPatch(title = "Launch", ops = listOf(PlanOp.Add("Tree", key = "tree"))), owner)
            store.attach("hangar:launch") { PlanSeat("s1", "implementer", listOf("tree"), owner, 1) }
            assertTrue(store.write("hangar:launch", PlanPatch(seat = "s1", ops = listOf(PlanOp.Status("tree", PlanStatus.Doing))), first).refused.isEmpty())
            val restarted = WorkPlanStore(directory, identity = identify)
            assertFailsWith<IllegalArgumentException> {
                restarted.write("hangar:launch", PlanPatch(seat = "s1", ops = listOf(PlanOp.Status("tree", PlanStatus.Done))), first.copy(session = "hangar:stranger"))
            }
            val finished = restarted.write("hangar:launch", PlanPatch(seat = "s1", ops = listOf(PlanOp.Status("tree", PlanStatus.Done))), second)
            assertTrue(finished.refused.isEmpty())
            assertEquals(PlanStatus.Done, finished.plan.item("tree")?.status)
            assertEquals("agent:codex:conversation", finished.plan.seat("s1")?.agent)
        } finally {
            directory.toFile().deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }
}
