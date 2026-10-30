package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlin.test.*

class PlanQueuePriorityTest {
    private val owner = BoardActor(BoardActorKind.Person, "Owner")
    private val thread = "hangar:queue"

    private fun fixture(test: suspend (AgentWorkspace, CompletableDeferred<Unit>, List<String>) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("plan-queue").toFile()
        val release = CompletableDeferred<Unit>()
        val prompts = CopyOnWriteArrayList<String>()
        val runtime = object : AgentRuntime {
            override val kind = RuntimeKind.Echo
            override fun run(request: AgentRequest) = flow {
                val prompt = request.prompt.substringAfterLast("## Your task\n").trim()
                prompts += prompt
                if (prompt.startsWith("initial")) release.await()
                emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "done", true)))
            }
        }
        try {
            AgentWorkspace(root, root.toPath().resolve("state"), mapOf(runtime.kind to runtime)).use { workspace ->
                workspace.plans.write(thread, PlanPatch(title = "Queue", ops = listOf(
                    PlanOp.Add("First", key = "first"), PlanOp.Add("Second", key = "second", priority = PlanPriority.High),
                )), owner)
                test(workspace, release, prompts)
            }
        } finally { root.deleteRecursively() }
    }

    private fun AgentWorkspace.enqueue(parent: AgentRunRecord, id: String, item: String) =
        queue(parent.id, AgentSubmission(id, RuntimeKind.Echo, id, parent.threadId, planItem = "plan:$thread#$item"))

    private suspend fun AgentWorkspace.finished(vararg parents: AgentRunRecord) = withTimeout(10000) {
        while (parents.any { parent -> queueItems(parent.threadId).any { it.state == "waiting" || it.runId?.let { get(it).status == AgentRunStatus.Running } == true } }) delay(10)
    }

    @Test fun currentPrioritiesReorderIndependentTurnsWithoutInterruptingParents() = fixture { workspace, release, prompts ->
        val first = workspace.submit(AgentSubmission("initial-a", RuntimeKind.Echo, "initial-a"))
        val second = workspace.submit(AgentSubmission("initial-b", RuntimeKind.Echo, "initial-b"))
        workspace.enqueue(first, "queued-first", "first")
        workspace.enqueue(second, "queued-second", "second")
        workspace.plans.write(thread, PlanPatch(ops = listOf(PlanOp.Update("first", priority = PlanPriority.Urgent))), owner)
        assertEquals(AgentRunStatus.Running, workspace.get(first.id).status)
        assertEquals(AgentRunStatus.Running, workspace.get(second.id).status)
        release.complete(Unit)
        workspace.finished(first, second)
        assertEquals(listOf("queued-first", "queued-second"), prompts.filterNot { it.startsWith("initial") })
    }

    @Test fun inheritedUrgencyOutranksAnExplicitHighPriority() = fixture { workspace, release, prompts ->
        workspace.plans.write(thread, PlanPatch(ops = listOf(PlanOp.Add("Urgent follower", key = "urgent", after = listOf("first"), priority = PlanPriority.Urgent))), owner)
        val first = workspace.submit(AgentSubmission("initial-a", RuntimeKind.Echo, "initial-a"))
        val second = workspace.submit(AgentSubmission("initial-b", RuntimeKind.Echo, "initial-b"))
        workspace.enqueue(second, "queued-second", "second")
        workspace.enqueue(first, "queued-first", "first")
        release.complete(Unit)
        workspace.finished(first, second)
        assertEquals(listOf("queued-first", "queued-second"), prompts.filterNot { it.startsWith("initial") })
    }

    @Test fun equalPrioritiesKeepAdmissionOrderAndSameThreadTurnsKeepCausality() = fixture { workspace, release, prompts ->
        workspace.plans.write(thread, PlanPatch(ops = listOf(PlanOp.Update("second", priority = PlanPriority.Normal), PlanOp.Add("Last", key = "last", priority = PlanPriority.Urgent))), owner)
        val first = workspace.submit(AgentSubmission("initial-a", RuntimeKind.Echo, "initial-a"))
        val second = workspace.submit(AgentSubmission("initial-b", RuntimeKind.Echo, "initial-b"))
        workspace.enqueue(first, "queued-first", "first")
        workspace.enqueue(first, "queued-last", "last")
        workspace.enqueue(second, "queued-second", "second")
        release.complete(Unit)
        workspace.finished(first, second)
        assertEquals(listOf("queued-first", "queued-last", "queued-second"), prompts.filterNot { it.startsWith("initial") })
    }

    @Test fun completingAPlanDependencyWakesAnIdleQueue() = fixture { workspace, release, prompts ->
        workspace.plans.write(thread, PlanPatch(ops = listOf(PlanOp.Connect("second", "first"))), owner)
        val first = workspace.submit(AgentSubmission("initial-a", RuntimeKind.Echo, "initial-a"))
        workspace.enqueue(first, "queued-first", "first")
        release.complete(Unit)
        withTimeout(10000) { while (workspace.get(first.id).status == AgentRunStatus.Running) delay(10) }
        delay(100)
        assertEquals("waiting", workspace.queueItems(first.threadId).single().state)
        workspace.plans.write(thread, PlanPatch(ops = listOf(PlanOp.Status("second", PlanStatus.Done))), owner)
        workspace.finished(first)
        assertTrue("queued-first" in prompts)
    }

    @Test fun malformedAndClosedBindingsNeverDispatch() = fixture { workspace, release, prompts ->
        val first = workspace.submit(AgentSubmission("initial-a", RuntimeKind.Echo, "initial-a"))
        for (reference in listOf("hangar:queue#first", "plan:queue#first", "plan:$thread#missing", "plan:$thread#first#second")) {
            assertFails { workspace.queue(first.id, AgentSubmission(reference, RuntimeKind.Echo, "invalid", first.threadId, planItem = reference)) }
        }
        workspace.enqueue(first, "queued-first", "first")
        workspace.plans.write(thread, PlanPatch(ops = listOf(PlanOp.Status("first", PlanStatus.Done))), owner)
        release.complete(Unit)
        workspace.finished(first)
        assertEquals("blocked", workspace.queueItems(first.threadId).single().state)
        assertFalse("queued-first" in prompts)
    }
}
