package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class KeepWorkingRegistrationTest {
    private val person = BoardActor(BoardActorKind.Person, "Owner")
    private val plan = "hangar:launch"

    @Test fun interactiveCodexRegistersBeforeDispatchWithoutReadingPromptInstructions() = fixture { root, directory ->
        val runtime = WaitingInteractive()
        owner(root, directory, runtime).use { workspace ->
            val run = workspace.submit(AgentSubmission("new", RuntimeKind.Codex, "Work on hangar:untrusted"))
            val thread = BoardRefs.Hangar + run.id
            assertEquals(BoardRefs.Hangar + run.threadId, KeepWorkingStore(directory).read(thread)!!.plan)
            withTimeout(10000) { runtime.started.await() }
            assertEquals(AgentTransport.Interactive, run.transport)
        }
    }

    @Test fun canonicalPlanItemBindsTheManagedSeatToItsPlan() = fixture { root, directory ->
        owner(root, directory, WaitingInteractive()).use { workspace ->
            createPlan(workspace)
            val run = workspace.submit(AgentSubmission("item", RuntimeKind.Codex, "Work", planItem = "plan:$plan#task"))
            assertEquals(plan, KeepWorkingStore(directory).read(BoardRefs.Hangar + run.id)!!.plan)
        }
    }

    @Test fun attachedPlanIsBoundWithoutParsingTheGeneratedBrief() = fixture { root, directory ->
        owner(root, directory, WaitingInteractive()).use { workspace ->
            createPlan(workspace)
            workspace.profiles.save(AgentProfile("writer", "Writer", RuntimeKind.Codex, access = TaskAccess.Write), person)
            val attached = workspace.attachPlan(plan, listOf("task"), "writer", "Keep working", person)
            val seat = KeepWorkingStore(directory).read(BoardRefs.Hangar + attached.run.id)!!
            assertEquals(plan, seat.plan)
            assertFalse(seat.paused)
            assertEquals(attached.run.id, attached.seat.run)
        }
    }

    @Test fun pauseAndRebindingSurviveWorkspaceRestartAndResume() = fixture { root, directory ->
        val runtime = WaitingInteractive()
        val workspace = owner(root, directory, runtime)
        val run = workspace.submit(AgentSubmission("resume", RuntimeKind.Codex, "Work"))
        withTimeout(10000) { runtime.started.await() }
        val thread = BoardRefs.Hangar + run.id
        val paused = KeepWorkingStore(directory).configure(thread, "hangar:other", true)
        workspace.suspendAndClose()
        owner(root, directory, WaitingInteractive()).use { reopened ->
            assertNotEquals(AgentRecovery.None, reopened.get(run.id).recovery)
            val resumed = reopened.resume(run.id)
            assertEquals(2, resumed.attempt)
            assertEquals(paused, KeepWorkingStore(directory).read(thread))
        }
    }

    @Test fun otherProvidersAndBatchCodexDoNotRegister() = fixture { root, directory ->
        owner(root, directory, WaitingInteractive(RuntimeKind.ClaudeCode)).use { workspace ->
            val run = workspace.submit(AgentSubmission("claude", RuntimeKind.ClaudeCode, "Work"))
            assertNull(KeepWorkingStore(directory).read(BoardRefs.Hangar + run.id))
        }
        val batch = object : AgentRuntime {
            override val kind = RuntimeKind.Codex
            override fun run(request: AgentRequest) = flow<AgentEvent> { awaitCancellation() }
        }
        owner(root, directory, batch).use { workspace ->
            val run = workspace.submit(AgentSubmission("batch", RuntimeKind.Codex, "Work"))
            assertEquals(AgentTransport.Batch, run.transport)
            assertNull(KeepWorkingStore(directory).read(BoardRefs.Hangar + run.id))
        }
    }

    @Test fun concurrentStoreInstancesKeepEveryRegistrationAndPreserveOwnerSettings() = fixture { _, directory ->
        val thread = "codex:owner"
        val paused = KeepWorkingStore(directory).configure(thread, "hangar:other", true)
        coroutineScope {
            (1..32).map { index -> async(Dispatchers.Default) {
                KeepWorkingStore(directory).register("codex:seat-$index", plan)
                assertEquals(paused, KeepWorkingStore(directory).register(thread, plan))
            } }.awaitAll()
        }
        val store = KeepWorkingStore(directory)
        (1..32).forEach { assertEquals(plan, store.read("codex:seat-$it")!!.plan) }
        assertEquals(paused, store.read(thread))
    }

    private fun createPlan(workspace: AgentWorkspace) {
        workspace.plans.write(plan, PlanPatch(title = "Launch", ops = listOf(
            PlanOp.Add("Work", key = "task", refs = listOf("file:src/work.kt")),
        )), person)
    }

    private fun owner(root: java.io.File, directory: Path, runtime: AgentRuntime) = AgentWorkspace(
        root, directory, mapOf(runtime.kind to runtime), background = false, discover = { ProviderCapability(it) },
    )

    private fun fixture(test: suspend (java.io.File, Path) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("keep-working-registration").toFile()
        val directory = root.toPath().resolve("state")
        try { test(root, directory) }
        finally { root.deleteTreeSafely(within = root.parentFile) }
    }

    private class WaitingInteractive(override val kind: RuntimeKind = RuntimeKind.Codex) : InteractiveAgentRuntime {
        override val interactive = Qualification.qualified("KeepWorkingRegistrationTest")
        val started = CompletableDeferred<Unit>()
        override fun run(request: AgentRequest) = flow<AgentEvent> { error("Interactive session required") }
        override suspend fun open(request: AgentRequest): AgentSession = object : AgentSession {
            override val activeTurn = "turn-1"
            override val events = flow {
                emit(AgentEvent.Started(request.agent.id, ProviderSession(kind, "fixture-thread")))
                started.complete(Unit)
                awaitCancellation()
            }
            override suspend fun steer(text: String, expectedTurn: String?) = CommandOutcome.Accepted
            override suspend fun resolve(requestId: String, decision: AgentDecision) = CommandOutcome.Stale(requestId, activeTurn)
            override suspend fun interrupt() = CommandOutcome.Accepted
            override fun close() = Unit
        }
    }
}
