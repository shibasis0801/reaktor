package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class AgentPendingSummaryTest {
    @Test fun aRunSummaryNamesThePendingIdThatAnswerAcceptsWithoutItsBody() = runBlocking {
        val root = Files.createTempDirectory("pending-summary").toFile()
        val answered = CompletableDeferred<AgentDecision>()
        val runtime = object : InteractiveAgentRuntime {
            override val kind = RuntimeKind.Codex
            override val interactive = Qualification.qualified("Pending summary fixture")
            override fun run(request: AgentRequest) = flow<AgentEvent> { error("Interactive fixture") }
            override suspend fun open(request: AgentRequest) = object : AgentSession {
                override val activeTurn = "turn"
                override val events = flow {
                    emit(AgentEvent.Started(request.agent.id, ProviderSession(kind, "fixture")))
                    emit(AgentEvent.RequestPending(request.agent.id, PendingRequest("approval-id", RequestKind.CommandApproval,
                        "Approval body stays in attach")))
                    val decision = answered.await()
                    emit(AgentEvent.RequestResolved(request.agent.id, "approval-id", decision))
                    emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "Answered", true)))
                }
                override suspend fun steer(text: String, expectedTurn: String?) = CommandOutcome.Accepted
                override suspend fun resolve(requestId: String, decision: AgentDecision): CommandOutcome {
                    if (requestId != "approval-id") return CommandOutcome.Stale(requestId, activeTurn)
                    answered.complete(decision)
                    return CommandOutcome.Accepted
                }
                override suspend fun interrupt() = CommandOutcome.Accepted
                override fun close() = Unit
            }
        }
        try {
            AgentWorkspace(root, root.resolve("state").toPath(), mapOf(runtime.kind to runtime),
                discover = { ProviderCapability(it) }, background = false).use { workspace ->
                val run = workspace.submit(AgentSubmission("pending", runtime.kind, "Ask once"))
                withTimeout(10_000) { while (workspace.get(run.id).pending.isEmpty()) delay(10) }
                val tools = agentWorkspaceTools(workspace)
                val response = tools.single { it.name == "agent_run" }.invoke(buildJsonObject { put("runId", run.id) }).jsonObject
                val ids = response["pendingIds"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
                assertEquals(listOf("approval-id"), ids)
                assertFalse(response.toString().contains("Approval body stays in attach"))
                val result = tools.single { it.name == "agent_answer" }.invoke(buildJsonObject {
                    put("runId", run.id); put("agent", "codex"); put("requestId", ids.single())
                    put("decision", "approve")
                })
                assertEquals(AgentDecision.Approve, withTimeout(5_000) { answered.await() })
                assertTrue(result.toString().contains("Accepted"))
            }
        } finally { root.deleteTreeSafely(root.parentFile) }
    }
}
