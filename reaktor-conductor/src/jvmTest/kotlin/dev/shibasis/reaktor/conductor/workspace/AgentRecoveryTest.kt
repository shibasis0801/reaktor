package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import java.nio.file.Files
import kotlin.test.*

class AgentRecoveryTest {
    @Test fun completedStagesSurviveFailureAndAreNotInvokedAgain() = runBlocking {
        val agent = AgentSpec(AgentId("worker"), "Worker", RuntimeKind.Echo, "")
        var checkpoint: ProtocolCheckpoint? = null
        val calls = mutableListOf<String>()
        var fail = true
        val runtime = EchoRuntime { request ->
            val step = if (request.prompt.contains("SECOND")) "second" else "first"
            calls += step
            if (step == "second" && fail) throw CancellationException("owner exited")
            "$step result"
        }
        val protocol = Protocol.Pipeline(listOf(Stage(agent.id, "FIRST"), Stage(agent.id, "SECOND")))
        val conductor = Conductor(mapOf(RuntimeKind.Echo to runtime))
        val thread = ThreadDocument(ThreadId("task"), "Recovery", listOf(agent))
        assertFailsWith<CancellationException> { conductor.run(thread, "work", protocol, ".", onProgress = { checkpoint = it }) }
        assertEquals(1, checkpoint?.completed?.size)
        assertEquals(1, checkpoint?.inFlight?.size)
        fail = false
        val restored = conductor.run(thread, "work", protocol, ".", resumeFrom = checkpoint)
        assertEquals(listOf("first", "second", "second"), calls)
        assertEquals(1, restored.thread.events.count { it.kind == EventKind.Prompt })
        assertTrue(restored.answer!!.text.contains("second result"))
    }

    @Test fun uncertainNativeWorkRequiresReviewAndQueuedTurnsSurviveRecovery() = runBlocking {
        val root = Files.createTempDirectory("agent-recovery-root").toFile()
        val data = Files.createTempDirectory("agent-recovery-data")
        val started = CompletableDeferred<Unit>()
        val waiting = object : AgentRuntime {
            override val kind = RuntimeKind.Echo
            override fun run(request: AgentRequest) = flow<AgentEvent> {
                emit(AgentEvent.Activity(request.agent.id, AgentActivityItem("effect", ActivityKind.Tool, "External operation", ActivityStatus.Started)))
                started.complete(Unit)
                awaitCancellation()
            }
        }
        try {
            val owner = AgentWorkspace(root, data, mapOf(RuntimeKind.Echo to waiting), background = true)
            val run = owner.submit(AgentSubmission("interrupted", RuntimeKind.Echo, "original"))
            withTimeout(10000) { started.await() }
            owner.queue(run.id, AgentSubmission("followup", RuntimeKind.Echo, "queued", run.threadId))
            owner.suspendAndClose()
            var calls = 0
            AgentWorkspace(root, data, mapOf(RuntimeKind.Echo to EchoRuntime { calls++; "done" }), background = true).use { reopened ->
                reopened.recoverInBackground()
                withTimeout(10000) { while (reopened.get(run.id).recovery == AgentRecovery.Pending) delay(20) }
                assertEquals(AgentRecovery.NeedsReview, reopened.get(run.id).recovery)
                assertEquals(0, calls)
                assertEquals("waiting", reopened.queueItems(run.threadId).single().state)
                assertFailsWith<IllegalArgumentException> {
                    reopened.submit(AgentSubmission("overwrite", RuntimeKind.Echo, "new turn", run.threadId))
                }
                reopened.resume(run.id)
                withTimeout(10000) { while (reopened.queueItems(run.threadId).single().state == "waiting") delay(20) }
                val nextId = reopened.queueItems(run.threadId).single().runId!!
                withTimeout(10000) { while (reopened.get(nextId).status == AgentRunStatus.Running) delay(20) }
                assertEquals(AgentRunStatus.Completed, reopened.get(run.id).status)
                assertEquals(2, reopened.get(run.id).attempt)
                assertEquals(2, calls)
            }
        } finally { root.deleteRecursively(); data.toFile().deleteRecursively() }
    }

    @Test fun crashAfterStageCheckpointRecoversWithoutCallingTheHarnessAgain() = runBlocking {
        val root = Files.createTempDirectory("completed-stage-root").toFile()
        val data = Files.createTempDirectory("completed-stage-data")
        try {
            val completed = AgentWorkspace(root, data, mapOf(RuntimeKind.Echo to EchoRuntime { "saved answer" }), background = true).use { owner ->
                val run = owner.submit(AgentSubmission("checkpoint", RuntimeKind.Echo, "work"))
                withTimeout(10000) { while (owner.get(run.id).status == AgentRunStatus.Running) delay(20) }
                owner.get(run.id)
            }
            atomicWrite(data.resolve("recent.json"), "[]")
            atomicWrite(data.resolve("runs/${completed.id}.json"), ConductorJson.encodeToString(AgentRunRecord.serializer(), completed.copy(status = AgentRunStatus.Running)))
            AgentWorkspace(root, data, mapOf(RuntimeKind.Echo to EchoRuntime { error("Completed stage must be reused") }), background = true).use { owner ->
                owner.recoverInBackground()
                withTimeout(10000) { while (owner.get(completed.id).recovery != AgentRecovery.None) delay(20) }
                assertEquals(AgentRunStatus.Completed, owner.get(completed.id).status)
                assertEquals("saved answer", owner.get(completed.id).output)
                assertEquals(1, owner.transcript(completed.threadId).events.count { it.kind == EventKind.Prompt })
                assertEquals(AgentRunStatus.Completed, owner.cancel(completed.id).status, "A late stop must not overwrite a terminal receipt")
            }
        } finally { root.deleteRecursively(); data.toFile().deleteRecursively() }
    }

    @Test fun stoppingATaskPreventsRestartRecovery() = runBlocking {
        val root = Files.createTempDirectory("agent-stop-root").toFile()
        val data = Files.createTempDirectory("agent-stop-data")
        val started = CompletableDeferred<Unit>()
        val runtime = object : AgentRuntime {
            override val kind = RuntimeKind.Echo
            override fun run(request: AgentRequest) = flow<AgentEvent> { started.complete(Unit); awaitCancellation() }
        }
        try {
            val runId = AgentWorkspace(root, data, mapOf(runtime.kind to runtime), background = true).use { owner ->
                val run = owner.submit(AgentSubmission("cancel", runtime.kind, "work"))
                withTimeout(10000) { started.await() }
                owner.cancel(run.id)
                run.id
            }
            AgentWorkspace(root, data, mapOf(RuntimeKind.Echo to EchoRuntime { error("Must not restart") }), background = true).use { owner ->
                owner.recoverInBackground()
                assertEquals(AgentRunStatus.Interrupted, owner.get(runId).status)
                assertEquals(AgentRecovery.None, owner.get(runId).recovery)
            }
        } finally { root.deleteRecursively(); data.toFile().deleteRecursively() }
    }
}
