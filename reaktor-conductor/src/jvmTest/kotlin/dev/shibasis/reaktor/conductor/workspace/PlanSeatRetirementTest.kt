package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PlanSeatRetirementTest {
    private val person = BoardActor(BoardActorKind.Person, "Shibasis")
    private val agent = BoardActor(BoardActorKind.Agent, "Codex", "codex:worker")
    private val thread = "hangar:retirement"

    @Test fun retiringAnUnusedSeatPersistsItsHistoryAndReleasesOnlyItsBindings() = fixture { store, directory ->
        val initial = attach(store)
        val result = retire(store, initial.revision)
        assertTrue(result.refused.isEmpty())
        val retired = result.plan.seats.single()
        assertEquals(initial.seats.single().copy(retired = PlanSeatRetirement(person, 1_000, "Duplicate attachment")), retired)
        assertNull(result.plan.seat("s1"))
        assertNull(result.plan.item("area")?.seat)
        assertEquals(initial.item("part"), result.plan.item("part"))
        assertEquals(0, result.plan.summary().seats)
        assertFalse(retired.heldBy(agent))
        assertEquals(initial.changes, result.plan.changes.dropLast(1))
        assertContains(result.plan.changes.last().summary.single(), "Retired unused seat s1")
        assertEquals(result.plan, WorkPlanStore(directory).read(thread).plan)
        assertTrue(store.write(thread, PlanPatch(ops = listOf(PlanOp.Status("part", PlanStatus.Doing))), agent).refused.isEmpty())
    }

    @Test fun agentsCannotRetireSeatsEvenWhenTheyOwnTheThread() = fixture { store, _ ->
        val initial = attach(store)
        listOf(agent, agent.copy(session = thread)).forEach { caller ->
            val result = retire(store, initial.revision, caller)
            assertContains(result.refused.single().reason, "Only a person")
            assertEquals(initial, result.plan)
        }
    }

    @Test fun retirementRequiresCurrentReviewAndAnExplicitReason() = fixture { store, _ ->
        val initial = attach(store)
        for ((base, reason) in listOf(null to "Duplicate", initial.revision - 1 to "Duplicate", initial.revision to " ")) {
            val result = store.write(thread, PlanPatch(base = base, ops = listOf(PlanOp.RetireSeat("s1", reason))), person)
            assertEquals(1, result.refused.size)
            assertEquals(initial, result.plan)
        }
        val missing = store.write(thread, PlanPatch(base = initial.revision, ops = listOf(PlanOp.RetireSeat("missing", "Duplicate"))), person)
        assertContains(missing.refused.single().reason, "not a seat")
        assertEquals(initial, missing.plan)
    }

    @Test fun boundRunsSessionsAndCanonicalAgentsAreNeverRetired() = fixture { store, _ ->
        val initial = attach(store)
        for (bound in listOf(initial.seats.single().copy(run = "running"), initial.seats.single().copy(session = agent.session), initial.seats.single().copy(agent = "agent:worker"))) {
            val plan = initial.copy(seats = listOf(bound))
            val result = PlanRules.apply(plan, PlanPatch(base = plan.revision, ops = listOf(PlanOp.RetireSeat("s1", "Duplicate"))), person, 1_000)
            assertContains(result.refused.single().reason, "bound to a run or session")
            assertEquals(plan, result.plan)
        }
    }

    @Test fun retirementRefusesInheritedWorkInProgressButPreservesANewerOverlappingSeat() = fixture { store, _ ->
        attach(store)
        val busy = store.write(thread, PlanPatch(ops = listOf(PlanOp.Status("part", PlanStatus.Doing))), person).plan
        val refused = retire(store, busy.revision)
        assertContains(refused.refused.single().reason, "work in progress")
        assertEquals(busy, refused.plan)
        val newer = store.attach(thread) { PlanSeat("s2", "reviewer", listOf("area"), person, 900) }.first
        val retired = retire(store, newer.revision)
        assertTrue(retired.refused.isEmpty())
        assertEquals("s2", retired.plan.item("area")?.seat)
        assertEquals(newer.item("part"), retired.plan.item("part"))
        assertEquals(newer.seat("s2"), retired.plan.seat("s2"))
        assertEquals(1, retired.plan.summary().seats)
    }

    @Test fun replayIsIdempotentAndRetiredIdsCannotBeClaimedOrReattached() = fixture { store, _ ->
        val initial = attach(store)
        val retired = retire(store, initial.revision).plan
        assertEquals(retired, retire(store, retired.revision).plan)
        assertFailsWith<IllegalArgumentException> { store.bind(thread, "s1", "new-run") }
        assertFailsWith<IllegalStateException> { store.write(thread, PlanPatch(seat = "s1", ops = listOf(PlanOp.Status("part", PlanStatus.Doing))), agent) }
        assertFailsWith<IllegalArgumentException> { store.attach(thread) { PlanSeat("s1", "worker", listOf("area"), person, 1_000) } }
        val next = store.attach(thread) { PlanSeat("s${it.seats.size + 1}", "worker", listOf("area"), person, 1_000) }.first
        assertEquals("s2", next.seat("s2")?.id)
        assertEquals(retired.seats.single(), next.seats.first())
    }

    @Test fun serializedOperationsAndLegacySeatsKeepTheirMeaning() {
        val op = ConductorJson.decodeFromString<PlanOp>("""{"type":"retire-seat","seat":"s1","reason":"Duplicate attachment"}""")
        assertEquals(PlanOp.RetireSeat("s1", "Duplicate attachment"), op)
        val old = """{"id":"s1","profile":"worker","items":["area"],"by":{"kind":"Person","name":"Shibasis"},"at":1}"""
        assertNull(ConductorJson.decodeFromString<PlanSeat>(old).retired)
    }

    private fun attach(store: WorkPlanStore): WorkPlan {
        store.write(thread, PlanPatch(title = "Seat history", ops = listOf(PlanOp.Add("Area", key = "area"), PlanOp.Add("Part", key = "part", parent = "area"))), person)
        return store.attach(thread) { PlanSeat("s1", "worker", listOf("area"), person, 500) }.first
    }

    private fun retire(store: WorkPlanStore, base: Long, by: BoardActor = person) =
        store.write(thread, PlanPatch(base = base, ops = listOf(PlanOp.RetireSeat("s1", "Duplicate attachment"))), by)

    private fun fixture(test: (WorkPlanStore, Path) -> Unit) {
        val directory = Files.createTempDirectory("plan-seat-retire")
        try { test(WorkPlanStore(directory, clock = { 1_000 }), directory) }
        finally { directory.toFile().deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")).canonicalFile) }
    }
}
