package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class AgentRunControlTransferTest {
    private val answer = "prefix:" + "x".repeat(5_000_000) + ":tail"

    @Test fun legacyRunAndWaitRepliesAreSummariesWithTheLastTwelveThousandCharacters() = runBlocking {
        fixture { workspace, id ->
            listOf("agent_run", "agent_wait").forEach { name ->
                val tool = agentWorkspaceTools(workspace).single { it.name == name }
                val response = tool.invoke(buildJsonObject { put("runId", id); put("timeoutMillis", 0) })
                val run = ConductorJson.decodeFromJsonElement(AgentRunRecord.serializer(), response)
                assertEquals(answer.takeLast(12_000), run.output)
                assertTrue(run.outputTruncated)
                assertTrue(response.toString().encodeToByteArray().size < 100_000)
            }
        }
    }

    @Test fun cancelAndResumeUseBoundedDeltasWithoutLosingTheRetainedAnswer() = runBlocking {
        fixture { workspace, id ->
            val root = java.io.File(workspace.info().workspaceRoot)
            val data = root.resolve("state").toPath()
            workspace.close()
            val waiting = object : AgentRuntime {
                override val kind = RuntimeKind.Echo
                override fun run(request: AgentRequest) = flow<AgentEvent> { awaitCancellation() }
            }
            AgentWorkspaceConnection.open(root, data, mapOf(RuntimeKind.Echo to waiting), discover = { ProviderCapability(it) }).use { pane ->
                val delta = pane.call("agent_resume", buildJsonObject { put("runId", id); put("delta", true) }).jsonObject
                assertEquals(1, delta.getValue("runDelta").jsonPrimitive.int)
                assertTrue(delta.toString().encodeToByteArray().size <= AgentRunTransfer.Budget)
                val current = pane.get(id)
                assertEquals(answer, current.output)
                val stopped = pane.cancel(id, current.revision)
                assertEquals(answer, stopped.output)
                assertEquals(AgentRunStatus.Interrupted, stopped.status)
            }
        }
    }

    private suspend fun fixture(block: suspend (AgentWorkspace, String) -> Unit) {
        val root = Files.createTempDirectory("run-control-transfer").toFile()
        val data = root.resolve("state").toPath()
        val stopped = CoroutineScope(SupervisorJob().apply { cancel() } + Dispatchers.Default)
        try {
            val initial = AgentWorkspace(root, data, mapOf(RuntimeKind.Echo to EchoRuntime()), scope = stopped,
                discover = { ProviderCapability(it) }, background = false)
            val run = initial.submit(AgentSubmission("long-control", RuntimeKind.Echo, "Retained answer"))
            initial.close()
            val retained = run.copy(output = answer, status = AgentRunStatus.Interrupted, recovery = AgentRecovery.NeedsReview,
                participants = mapOf("echo" to AgentParticipantRun(RuntimeKind.Echo, status = AgentRunStatus.Interrupted, output = answer)))
            Files.writeString(data.resolve("runs/${run.id}.json"), ConductorJson.encodeToString(AgentRunRecord.serializer(), retained) + "\n")
            AgentWorkspace(root, data, mapOf(RuntimeKind.Echo to EchoRuntime()), scope = stopped,
                discover = { ProviderCapability(it) }, background = false).use { block(it, run.id) }
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }
}
