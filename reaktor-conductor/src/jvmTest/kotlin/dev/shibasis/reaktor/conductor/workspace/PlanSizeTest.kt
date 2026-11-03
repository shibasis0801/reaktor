package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import dev.shibasis.reaktor.conductor.RuntimeKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanSizeTest {
    private val session = "codex:01a192a1-203c-7d30-9c2c-1b770d5f6c66"
    private val agent = "agent:codex:01a192a1-203c-7d30-9c2c-1b770d5f6c66"
    private val engine = BoardActor(BoardActorKind.Agent, "(codex)", session)
    private val bound = AgentProfile("bpo-engine", "Engine", RuntimeKind.Codex, model = "gpt-6.1-sol", effort = "xhigh", revision = 3)
    private val identity: (BoardActor) -> BoardActor = { actor -> PlanIdentity.actor(actor, profile = bound.takeIf { actor.session == session }) }
    private val thread = "hangar:size"

    private fun fixture(test: (Path) -> Unit) {
        val root = Files.createTempDirectory("plan-size")
        try { test(root) } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }

    private fun file(root: Path): Path = root.resolve("${thread.replace(':', '-')}.json")

    private fun stored(root: Path): WorkPlan = ConductorJson.decodeFromString(WorkPlan.serializer(), Files.readString(file(root)))

    private fun seed(root: Path) {
        val raw = WorkPlanStore(root, clock = { 10 })
        (0 until 644).chunked(PlanRules.MaxOps).forEachIndexed { batch, keys ->
            val applied = raw.write(thread, PlanPatch(title = "Launch".takeIf { batch == 0 },
                ops = keys.map { PlanOp.Add("Item $it", key = "i$it", status = if (it < 150) PlanStatus.Todo else PlanStatus.Done) }), engine)
            require(applied.refused.isEmpty()) { applied.refused.joinToString { it.reason } }
        }
    }

    @Test fun aRawSixHundredFortyFourItemPlanDoesNotGrowWhenAnIdentifiedStoreSavesItTwice() = fixture { root ->
        seed(root)
        val before = Files.size(file(root))
        val store = WorkPlanStore(root, identity = identity, clock = { 20 })
        store.write(thread, PlanPatch(ops = listOf(PlanOp.Update("i0", priority = PlanPriority.Urgent))), engine)
        val once = Files.size(file(root))
        store.write(thread, PlanPatch(ops = listOf(PlanOp.Update("i1", priority = PlanPriority.Urgent))), engine)
        val twice = Files.size(file(root))
        assertTrue(once - before < 1_000, "the first identified save grew by ${once - before} bytes")
        assertTrue(twice - once < 1_000, "the second identified save grew by ${twice - once} bytes")
        assertTrue(stored(root).items.all { it.by == engine })
        val read = requireNotNull(store.plan(thread))
        assertTrue(read.items.all { it.by == BoardActor(BoardActorKind.Agent, "(codex, xhigh, sol 6.1, engine)", session, agent, "bpo-engine", 3) })
    }

    @Test fun earlierChangesKeepWhatWasWrittenAndANewChangeKeepsItsProfileRevision() = fixture { root ->
        seed(root)
        val seeded = stored(root).changes
        val store = WorkPlanStore(root, identity = identity, clock = { 20 })
        store.write(thread, PlanPatch(ops = listOf(PlanOp.Status("i2", PlanStatus.Done))), engine)
        val changes = stored(root).changes
        assertEquals(seeded, changes.dropLast(1))
        assertEquals(BoardActor(BoardActorKind.Agent, "(codex, xhigh, sol 6.1, engine)", session, profile = "bpo-engine", profileRevision = 3), changes.last().by)
    }

    @Test fun aLeaseStoredWithoutItsAgentStillExtendsWithThatAgentsActivity() = fixture { root ->
        seed(root)
        var now = 20L
        val store = WorkPlanStore(root, identity = identity, clock = { now }, activity = { if (it == agent) now else null })
        val item = requireNotNull(store.next(thread, engine, take = true) { bound }.candidate).item.id
        assertEquals(engine, stored(root).item(item)?.lease?.by)
        now += PlanLease.DurationMillis * 3
        val held = requireNotNull(store.plan(thread)?.item(item))
        assertEquals(PlanStatus.Doing, held.status)
        assertEquals(now + PlanLease.DurationMillis, held.lease?.expiresAt)
        assertEquals(agent, held.lease?.by?.agent)
    }

    @Test fun aStoreWhoseIdentityRecomputesNothingStoresActorsUnchanged() = fixture { root ->
        val full = BoardActor(BoardActorKind.Agent, "Falcon", "codex:a", "agent:codex:a", "worker", 2)
        val store = WorkPlanStore(root, clock = { 10 })
        store.write(thread, PlanPatch(title = "Work", ops = listOf(PlanOp.Add("Build", key = "build"))), full)
        assertEquals(full, stored(root).item("build")?.by)
    }
}
