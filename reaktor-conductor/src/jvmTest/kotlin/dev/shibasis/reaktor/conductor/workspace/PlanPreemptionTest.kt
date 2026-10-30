package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.Entitlement
import dev.shibasis.reaktor.conductor.ProviderCapability
import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.message.MessageKind
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanPreemptionTest {
    private val owner = BoardActor(BoardActorKind.Person, "Owner")
    private val agent = BoardActor(BoardActorKind.Agent, "Codex", "codex:preempt-test")
    private val other = BoardActor(BoardActorKind.Agent, "Claude", "claude:preempt-test")
    private val thread = "hangar:preemption"

    private fun fixture(test: suspend (AgentWorkspace) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("plan-preempt").toFile()
        try {
            AgentWorkspace(root, root.toPath().resolve("state"), emptyMap(), discover = { ProviderCapability(it) }).use { workspace ->
                workspace.plans.write(thread, PlanPatch(title = "Preemption", ops = listOf(PlanOp.Add("Current", key = "current"))), owner)
                workspace.plans.write(thread, PlanPatch(ops = listOf(PlanOp.Status("current", PlanStatus.Doing))), agent)
                test(workspace)
            }
        } finally { root.deleteRecursively() }
    }

    private suspend fun AgentWorkspace.preempts() = messages.inbox(agent, unreadOnly = false).filter { it.message.request.kind == MessageKind.Preempt }

    @Test fun raisingPrioritySendsOneDurableAdviceAndPreservesTheClaim() = fixture { workspace ->
        workspace.plans.write(thread, PlanPatch(ops = listOf(PlanOp.Add("Next", key = "next", priority = PlanPriority.High))), owner)
        assertTrue(workspace.preempts().isEmpty())
        workspace.plans.write(thread, PlanPatch(ops = listOf(PlanOp.Update("next", priority = PlanPriority.Urgent))), owner)
        val notice = workspace.preempts().single()
        assertEquals("system:scheduler", notice.message.request.from)
        assertTrue(notice.message.request.body.contains("safe checkpoint"))
        assertEquals("plan:$thread#next", notice.message.request.item)
        assertEquals(PlanStatus.Doing, workspace.plans.plan(thread)?.item("current")?.status)
        workspace.plans.write(thread, PlanPatch(message = "Unrelated plan note"), owner)
        assertEquals(listOf(notice.message.id), workspace.preempts().map { it.message.id })
        workspace.plans.write(thread, PlanPatch(message = "Parked at a safe checkpoint", ops = listOf(PlanOp.Status("current", PlanStatus.Todo))), agent)
        assertEquals("next", workspace.nextPlan(thread, agent, take = true).candidate?.item?.id)
    }

    @Test fun newlyAddedUrgentWorkTriggersPreemption() = fixture { workspace ->
        workspace.plans.write(thread, PlanPatch(ops = listOf(PlanOp.Add("Urgent", key = "urgent", priority = PlanPriority.Urgent))), owner)
        assertEquals(1, workspace.preempts().size)
    }

    @Test fun finishingABlockerMakesUrgentWorkEligible() = fixture { workspace ->
        workspace.plans.write(thread, PlanPatch(ops = listOf(PlanOp.Add("Blocker", key = "blocker"))), owner)
        workspace.plans.write(thread, PlanPatch(ops = listOf(PlanOp.Status("blocker", PlanStatus.Doing))), other)
        workspace.plans.write(thread, PlanPatch(ops = listOf(PlanOp.Add("Urgent", key = "urgent", after = listOf("blocker"), priority = PlanPriority.Urgent))), owner)
        assertTrue(workspace.preempts().isEmpty())
        workspace.plans.write(thread, PlanPatch(ops = listOf(PlanOp.Status("blocker", PlanStatus.Done))), owner)
        assertEquals("plan:$thread#urgent", workspace.preempts().single().message.request.item)
    }

    @Test fun decisionsAndAnotherAgentsSeatDoNotPreempt() = fixture { workspace ->
        workspace.plans.write(thread, PlanPatch(ops = listOf(PlanOp.Add("Owner choice", key = "choice", kind = PlanKind.Decision, priority = PlanPriority.Urgent))), owner)
        assertTrue(workspace.preempts().isEmpty())
        workspace.plans.attach(thread) { PlanSeat("s1", "codex", listOf("current"), owner, 1, session = agent.session) }
        workspace.plans.write(thread, PlanPatch(ops = listOf(PlanOp.Add("Outside", key = "outside", priority = PlanPriority.Urgent))), owner)
        assertTrue(workspace.preempts().isEmpty())
    }

    @Test fun pausedQuotaAndProfileMismatchDoNotPreempt() = fixture { workspace ->
        val profile = workspace.profiles.save(AgentProfile("checker", "Checker", RuntimeKind.Codex, planKinds = setOf(PlanKind.Check)), owner)
        workspace.bindIdentity(agent, profile.id, workspace.identity(agent).bindingRevision)
        workspace.plans.write(thread, PlanPatch(ops = listOf(PlanOp.Add("Write", key = "write", priority = PlanPriority.Urgent))), owner)
        assertTrue(workspace.preempts().isEmpty())
        workspace.entitlements.set(Entitlement.Codex, true, "Owner paused", 0)
        workspace.plans.write(thread, PlanPatch(ops = listOf(PlanOp.Add("Check", key = "check", kind = PlanKind.Check, priority = PlanPriority.Urgent))), owner)
        assertTrue(workspace.preempts().isEmpty())
    }

    @Test fun inheritedUrgencyAndOtherAgentsFileClaimsConstrainPreemption() = fixture { workspace ->
        workspace.plans.write(thread, PlanPatch(ops = listOf(
            PlanOp.Add("Blocker", key = "blocker", refs = listOf("file:shared/A.kt")),
            PlanOp.Add("Urgent", key = "urgent", after = listOf("blocker"), priority = PlanPriority.Urgent),
        )), owner)
        assertEquals("plan:$thread#blocker", workspace.preempts().single().message.request.item)
        workspace.plans.write(thread, PlanPatch(ops = listOf(PlanOp.Status("blocker", PlanStatus.Doing))), other)
        workspace.plans.write(thread, PlanPatch(ops = listOf(PlanOp.Add("Conflicting", key = "conflict", priority = PlanPriority.Urgent, refs = listOf("file:shared/A.kt")))), owner)
        assertEquals(1, workspace.preempts().size)
    }
}
