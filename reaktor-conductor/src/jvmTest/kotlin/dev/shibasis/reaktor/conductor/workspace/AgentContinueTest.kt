package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.EchoRuntime
import dev.shibasis.reaktor.conductor.ProviderCapability
import dev.shibasis.reaktor.conductor.ProviderSession
import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.WorkflowDefinition
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentContinueTest {
    @Test fun aSingleTurnResumesTheNativeThreadItNamesAndRefusesAnotherProvidersSession() = runBlocking {
        val root = Files.createTempDirectory("continue-root").toFile()
        val directory = Files.createTempDirectory("continue-state")
        val runtime = EchoRuntime(RuntimeKind.Codex) { request -> "resumed ${request.resume?.sessionId ?: "nothing"}" }
        try {
            AgentWorkspaceConnection.open(root, directory, mapOf(runtime.kind to runtime), discover = { ProviderCapability(it) }).use { owner ->
                var fresh = owner.submit(AgentSubmission("fresh", RuntimeKind.Codex, "Start over"))
                withTimeout(10000) { while (fresh.status == AgentRunStatus.Running) fresh = owner.wait(fresh.id, fresh.revision) }
                assertEquals("resumed nothing", fresh.output)
                val native = ProviderSession(RuntimeKind.Codex, "019a2b3c-native-codex-thread")
                var continued = owner.submit(AgentSubmission("continue", RuntimeKind.Codex, "Carry on", continues = native))
                withTimeout(10000) { while (continued.status == AgentRunStatus.Running) continued = owner.wait(continued.id, continued.revision) }
                assertEquals("resumed ${native.sessionId}", continued.output)
                listOf(
                    AgentSubmission("foreign", RuntimeKind.Codex, "Carry on", continues = ProviderSession(RuntimeKind.ClaudeCode, "claude-session")),
                    AgentSubmission("compare", RuntimeKind.Codex, "Carry on", collaboration = AgentCollaboration.Compare,
                        partner = AgentPartner(RuntimeKind.ClaudeCode), continues = native),
                    AgentSubmission("workflow", RuntimeKind.Codex, "Carry on", workflow = WorkflowDefinition("flow", "Flow", emptyList(), emptyList()), continues = native),
                ).forEach { refused ->
                    val failure = assertFailsWith<Exception>(refused.requestId) { owner.submit(refused) }
                    assertTrue(failure.message.orEmpty().contains("continues a native session"), "${refused.requestId}: ${failure.message}")
                }
            }
        } finally {
            val temporary = File(System.getProperty("java.io.tmpdir"))
            root.deleteTreeSafely(within = temporary)
            directory.toFile().deleteTreeSafely(within = temporary)
        }
    }
}
