package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely

import dev.shibasis.reaktor.conductor.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import java.nio.file.Files
import kotlin.test.*

class AgentQueueTest {
    @Test fun queuedTurnsRunInOrderAndRestartDoesNotReplayThem() = runBlocking {
        val root = Files.createTempDirectory("queue-root").toFile()
        val data = Files.createTempDirectory("queue-data")
        val release = CompletableDeferred<Unit>()
        val prompts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val runtime = object : AgentRuntime {
            override val kind = RuntimeKind.Echo
            override fun run(request: AgentRequest) = flow {
                prompts += request.prompt
                if (prompts.size == 1) release.await()
                emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "done", true)))
            }
        }
        try {
            AgentWorkspace(root, data, mapOf(runtime.kind to runtime)).use { workspace ->
                val initial = workspace.submit(AgentSubmission("first", RuntimeKind.Echo, "initial"))
                val next = AgentSubmission("second", RuntimeKind.Echo, "next", initial.threadId)
                val queued = workspace.queue(initial.id, next)
                assertEquals(queued, workspace.queue(initial.id, next))
                workspace.queue(initial.id, AgentSubmission("third", RuntimeKind.Echo, "last", initial.threadId))
                release.complete(Unit)
                withTimeout(10000) {
                    while (workspace.queueItems(initial.threadId).any { it.state == "waiting" }) delay(10)
                    val last = workspace.queueItems(initial.threadId).last().runId!!
                    while (workspace.get(last).status == AgentRunStatus.Running) delay(10)
                }
                assertEquals(3, prompts.size)
                assertEquals(listOf("dispatched", "dispatched"), workspace.queueItems(initial.threadId).map { it.state })
            }
            AgentWorkspace(root, data, mapOf(runtime.kind to runtime)).use { delay(50); assertEquals(3, prompts.size) }
        } finally { root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))); data.toFile().deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
    }
    @Test fun usageLimitRetainsQueuedMessagesAcrossOwnerRestartWithoutDispatchingThem() = runBlocking {
        val root = Files.createTempDirectory("queue-limit-root").toFile()
        val data = Files.createTempDirectory("queue-limit-data")
        val release = CompletableDeferred<Unit>()
        val prompts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val runtime = object : AgentRuntime {
            override val kind = RuntimeKind.Codex
            override fun run(request: AgentRequest) = flow {
                prompts += request.prompt
                release.await()
                emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "", false, failure = "Plan limit",
                    usageLimit = AgentUsageLimit(kind, "usageLimitExceeded", 2000000000000L))))
            }
        }
        try {
            lateinit var first: AgentRunRecord
            AgentWorkspace(root, data, mapOf(runtime.kind to runtime), background = true).use { workspace ->
                first = workspace.submit(AgentSubmission("first", runtime.kind, "initial"))
                workspace.queue(first.id, AgentSubmission("next", runtime.kind, "queued follow-up", first.threadId))
                release.complete(Unit)
                withTimeout(10000) { while (workspace.get(first.id).status == AgentRunStatus.Running) delay(10) }
                delay(200)
                assertEquals(AgentRunStatus.Limited, workspace.get(first.id).status)
                assertEquals("waiting", workspace.queueItems(first.threadId).single().state)
            }
            AgentWorkspace(root, data, mapOf(runtime.kind to runtime), background = true).use { workspace ->
                delay(200)
                assertEquals(AgentRunStatus.Limited, workspace.get(first.id).status)
                assertEquals(2000000000000L, workspace.get(first.id).usageLimit?.resetsAtEpochMillis)
                val queued = workspace.queueItems(first.threadId).single()
                assertEquals("waiting", queued.state)
                assertEquals("queued follow-up", queued.submission.prompt)
                assertEquals(1, prompts.size)
            }
        } finally {
            root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir")))
            data.toFile().deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir")))
        }
    }

    @Test fun interruptBlocksQueuedWork() = runBlocking {
        val root = Files.createTempDirectory("queue-stop-root").toFile()
        val data = Files.createTempDirectory("queue-stop-data")
        val started = CompletableDeferred<Unit>()
        val runtime = object : AgentRuntime {
            override val kind = RuntimeKind.Echo
            override fun run(request: AgentRequest) = flow<AgentEvent> { started.complete(Unit); awaitCancellation() }
        }
        try {
            AgentWorkspace(root, data, mapOf(runtime.kind to runtime)).use { workspace ->
                val first = workspace.submit(AgentSubmission("first", RuntimeKind.Echo, "initial"))
                withTimeout(10000) { started.await() }
                workspace.queue(first.id, AgentSubmission("next", RuntimeKind.Echo, "next", first.threadId))
                workspace.cancel(first.id)
                assertEquals("blocked", workspace.queueItems(first.threadId).single().state)
            }
        } finally { root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))); data.toFile().deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
    }
}
