package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class AgentRunWaitCapacityTest {
    @Test fun fourLegacyRunWaitsRemainPendingAndAnswerTogether() = waiters(false)

    @Test fun fourDeltaRunWaitsRemainPendingAndAnswerTogether() = waiters(true)

    private fun waiters(delta: Boolean) = runBlocking {
        val root = Files.createTempDirectory("run-wait-capacity").toFile()
        val release = Channel<Unit>(Channel.UNLIMITED)
        val ready = Channel<Unit>(1)
        val workspace = AtomicReference<AgentWorkspace>()
        val runtime = object : AgentRuntime {
            override val kind = RuntimeKind.Echo
            override fun run(request: AgentRequest) = flow {
                emit(AgentEvent.Started(request.agent.id, null))
                ready.send(Unit)
                release.receive()
                emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "Ready", true)))
            }
        }
        try {
            AgentWorkspaceConnection.open(root, root.resolve("state").toPath(), mapOf(RuntimeKind.Echo to runtime),
                discover = { ProviderCapability(it) }, extraTools = { workspace.set(it); emptyList() }).use { connection ->
                val owner = workspace.get()
                val run = owner.submit(AgentSubmission("wait-capacity", RuntimeKind.Echo, "Wait fixture"))
                withTimeout(3000) { ready.receive() }
                val revision = owner.get(run.id).revision
                supervisorScope {
                    val entered = CountDownLatch(4)
                    val calls = List(4) {
                        async(Dispatchers.IO) {
                            entered.countDown()
                            runCatching {
                                connection.call("agent_wait", buildJsonObject {
                                    put("runId", run.id)
                                    put("afterRevision", revision)
                                    put("timeoutMillis", 5000)
                                    put("delta", delta)
                                }).jsonObject
                            }
                        }
                    }
                    try {
                        assertTrue(entered.await(2, TimeUnit.SECONDS))
                        delay(200)
                        assertTrue(calls.none { it.isCompleted }, "A healthy run accepts all four waiters")
                    } finally {
                        release.send(Unit)
                        withTimeout(3000) { calls.awaitAll() }.forEach { answer ->
                            assertTrue(answer.isSuccess, answer.exceptionOrNull()?.message)
                            val response = answer.getOrThrow()
                            assertTrue(if (delta) response.getValue("runDelta").jsonPrimitive.int == 1
                                else response.getValue("revision").jsonPrimitive.long > revision)
                        }
                    }
                }
            }
        } finally {
            ready.close()
            release.close()
            root.deleteTreeSafely(within = root.parentFile)
        }
    }
}
