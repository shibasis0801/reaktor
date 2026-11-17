package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class AgentTaskSubscriptionTest {
    @Test fun fourTaskWaitsAllRemainPendingAndAnswerOnTheNextChange() = runBlocking {
        fixture { workspace, _ ->
            val tool = agentWorkspaceTools(workspace).single { it.name == "agent_tasks_wait" }
            val revision = workspace.tasksPage().revision
            val entered = CountDownLatch(4)
            supervisorScope {
                val calls = List(4) {
                    async(Dispatchers.IO) {
                        entered.countDown()
                        tool.execute(buildJsonObject { put("revision", revision); put("timeoutMillis", 5000) }).jsonObject
                    }
                }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                delay(100)
                assertTrue(calls.none { it.isCompleted }, "All four callers must wait for a change")
                workspace.submit(AgentSubmission("task-wait", RuntimeKind.Echo, "Wait fixture"))
                withTimeout(3000) { calls.awaitAll() }.forEach {
                    assertTrue(it.getValue("revision").jsonPrimitive.long > revision)
                    assertEquals(1, it.getValue("runs").jsonArray.size)
                }
            }
        }
    }

    @Test fun theInProcessSubscriptionPushesTheInitialPageAndEachNewRevisionWithoutOutputBodies() = runBlocking {
        fixture { workspace, releases ->
            val pages = Channel<AgentTasksPage>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { workspace.taskChanges().collect { pages.send(it) } }
            try {
                val first = withTimeout(3000) { pages.receive() }
                assertTrue(first.runs.isEmpty())
                workspace.submit(AgentSubmission("task-push", RuntimeKind.Echo, "Push fixture"))
                var page = withTimeout(3000) { pages.receive() }
                assertTrue(page.revision > first.revision)
                assertEquals(1, page.runs.size)
                val previous = page.revision
                releases.send(Unit)
                withTimeout(3000) {
                    do { page = pages.receive() } while (page.revision <= previous || !page.runs.single().outputTruncated)
                }
                assertEquals("", page.runs.single().output)
                assertEquals("", page.runs.single().reasoning)
                assertTrue(page.runs.single().participants.values.all { it.output.isEmpty() && it.reasoning.isEmpty() })
                assertTrue(AgentWorkspaceJson.encodeToString(AgentTasksPage.serializer(), page).encodeToByteArray().size < 65536)
            } finally { collector.cancelAndJoin(); pages.close() }
        }
    }

    private suspend fun fixture(block: suspend (AgentWorkspace, Channel<Unit>) -> Unit) {
        val root = Files.createTempDirectory("task-subscription").toFile()
        val releases = Channel<Unit>(Channel.UNLIMITED)
        val runtime = object : AgentRuntime {
            override val kind = RuntimeKind.Echo
            override fun run(request: AgentRequest) = flow {
                emit(AgentEvent.Started(request.agent.id, null))
                releases.receive()
                emit(AgentEvent.Delta(request.agent.id, "Measured output"))
                releases.receive()
            }
        }
        try {
            AgentWorkspace(root, root.toPath().resolve("state"), mapOf(RuntimeKind.Echo to runtime), background = false,
                discover = { ProviderCapability(it) }).use { block(it, releases) }
        } finally { releases.close(); root.deleteTreeSafely(within = root.parentFile) }
    }
}
