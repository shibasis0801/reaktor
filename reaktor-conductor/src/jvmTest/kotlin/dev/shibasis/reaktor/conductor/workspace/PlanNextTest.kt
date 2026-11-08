package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.Entitlement
import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanNextTest {
    private val person = BoardActor(BoardActorKind.Person, "owner")
    private val codex = BoardActor(BoardActorKind.Agent, "Codex", "codex:worker")
    private val claude = BoardActor(BoardActorKind.Agent, "Claude", "claude:worker")
    private val profile = AgentProfile("worker", "Worker", RuntimeKind.Codex)

    private fun fixture(test: (AgentWorkspace) -> Unit) {
        val root = Files.createTempDirectory("plan-next").toFile()
        try {
            AgentWorkspace(root, root.toPath().resolve("state"), emptyMap()).use(test)
        } finally {
            root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }

    @Test fun nextReadsAcrossPlansWithoutClaiming() = fixture { workspace ->
        workspace.plans.write("hangar:one", PlanPatch(title = "One", ops = listOf(PlanOp.Add("First", key = "first"))), person)
        workspace.plans.write("hangar:two", PlanPatch(title = "Two", ops = listOf(PlanOp.Add("Urgent", key = "urgent", priority = PlanPriority.Urgent))), person)
        val next = workspace.nextPlan(null, codex)
        assertEquals("hangar:two", next.candidate?.thread)
        assertEquals("urgent", next.candidate?.item?.id)
        assertEquals(PlanStatus.Todo, workspace.plans.plan("hangar:two")?.item("urgent")?.status)
        assertEquals("first", workspace.nextPlan("hangar:one", codex).candidate?.item?.id)
        assertTrue(next.quota.contains("unknown"))
    }

    @Test fun aSeatRestrictsItsAgentToItsOwnItemsAndProfile() = fixture { workspace ->
        val store = workspace.plans
        val chosen = profile.copy(planKinds = setOf(PlanKind.Check), planRefs = listOf("module:engine"))
        store.write("hangar:one", PlanPatch(title = "One", ops = listOf(
            PlanOp.Add("Area", key = "area", kind = PlanKind.Component),
            PlanOp.Add("Write", key = "write", parent = "area", refs = listOf("module:engine")),
            PlanOp.Add("Proof", key = "proof", parent = "area", kind = PlanKind.Check, refs = listOf("module:engine")),
            PlanOp.Add("Other module", key = "other", parent = "area", kind = PlanKind.Check, refs = listOf("module:core")),
            PlanOp.Add("Outside", key = "outside", priority = PlanPriority.Urgent),
        )), person)
        store.attach("hangar:one") { PlanSeat("s1", chosen.id, listOf("area"), person, 1, session = codex.session) }
        assertEquals("proof", store.next(null, codex, profile = { chosen }).candidate?.item?.id)
        assertEquals("outside", store.next(null, claude, profile = { id -> if (id == chosen.id) chosen else profile.copy(id = id) }).candidate?.item?.id)
        assertFailsWith<IllegalArgumentException> { store.next("hangar:one", claude, "s1") { chosen } }
    }

    @Test fun nativeProfileFiltersTheCandidateBeforeTakingALease() = fixture { workspace ->
        val chosen = workspace.profiles.save(profile.copy(planKinds = setOf(PlanKind.Check), planRefs = listOf("module:engine")), person)
        workspace.bindIdentity(codex, chosen.id, workspace.identity(codex).bindingRevision)
        workspace.plans.write("hangar:one", PlanPatch(title = "One", ops = listOf(
            PlanOp.Add("Write", key = "write", priority = PlanPriority.Urgent, refs = listOf("module:engine")),
            PlanOp.Add("Other", key = "other", kind = PlanKind.Check, priority = PlanPriority.Urgent, refs = listOf("module:core")),
            PlanOp.Add("Proof", key = "proof", kind = PlanKind.Check, refs = listOf("module:engine")),
        )), person)
        val taken = workspace.nextPlan("hangar:one", codex, take = true).candidate
        assertEquals("proof", taken?.item?.id)
        assertEquals(chosen.id, taken?.item?.lease?.by?.profile)
        assertEquals(chosen.revision, taken?.item?.lease?.by?.profileRevision)
    }

    @Test fun decisionsRemainForAPerson() = fixture { workspace ->
        workspace.plans.write("hangar:one", PlanPatch(title = "One", ops = listOf(
            PlanOp.Add("Choose", key = "choose", kind = PlanKind.Decision, priority = PlanPriority.Urgent),
            PlanOp.Add("Implement", key = "implement"),
        )), person)
        assertEquals("implement", workspace.nextPlan(null, codex).candidate?.item?.id)
        assertEquals("choose", workspace.nextPlan(null, person).candidate?.item?.id)
    }

    @Test fun pausedQuotaAndAnonymousOrForgedAgentsCannotTakeWork() = fixture { workspace ->
        workspace.plans.write("hangar:one", PlanPatch(title = "One", ops = listOf(PlanOp.Add("Work", key = "work"))), person)
        workspace.entitlements.set(Entitlement.Codex, true, "Owner paused", 0)
        val paused = workspace.nextPlan(null, codex)
        assertNull(paused.candidate)
        assertTrue(paused.reason.contains("Owner paused"))
        assertEquals("work", workspace.nextPlan(null, claude).candidate?.item?.id)
        assertFailsWith<IllegalArgumentException> { workspace.nextPlan(null, codex, agent = "agent:claude:worker") }
        assertFailsWith<IllegalArgumentException> { workspace.nextPlan(null, codex.copy(session = null)) }
    }

    @Test fun doingAndBlockedItemsAreNeverOffered() = fixture { workspace ->
        workspace.plans.write("hangar:one", PlanPatch(title = "One", ops = listOf(
            PlanOp.Add("Held", key = "held", status = PlanStatus.Doing),
            PlanOp.Add("Wait", key = "wait", after = listOf("held"), priority = PlanPriority.Urgent),
        )), claude)
        assertNull(workspace.nextPlan(null, codex).candidate)
        assertTrue(workspace.nextPlan(null, codex).reason.contains("No ready item"))
    }

    @Test fun aNativeHandoffTakesOnlyTheNamedReadyItem() = fixture { workspace ->
        val thread = "hangar:shared"
        val store = workspace.plans
        store.write(thread, PlanPatch(title = "Shared", ops = listOf(
            PlanOp.Add("Prerequisite", key = "first"),
            PlanOp.Add("Handoff", key = "handoff", after = listOf("first")),
            PlanOp.Add("Unrelated", key = "urgent", priority = PlanPriority.Urgent),
        )), person)
        assertNull(workspace.nextPlan(thread, codex, take = true, item = "handoff").candidate)
        assertEquals("first", workspace.nextPlan(thread, claude, take = true, item = "first").candidate?.item?.id)
        assertNull(workspace.nextPlan(thread, claude, take = true, item = "urgent").candidate)
        store.write(thread, PlanPatch(ops = listOf(PlanOp.Status("first", PlanStatus.Done))), claude)
        val handoff = workspace.nextPlan(thread, codex, take = true, item = "handoff").candidate?.item
        assertEquals("handoff", handoff?.id)
        assertEquals(codex.session, handoff?.lease?.by?.session)
        assertNull(workspace.nextPlan(thread, claude, take = true, item = "handoff").candidate)
        assertEquals(PlanStatus.Todo, store.plan(thread)?.item("urgent")?.status)
    }
}
