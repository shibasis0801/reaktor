package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.cli.AntigravityApp
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class NativeAppContinuationTest {
    @Test fun aClosedCodexAppIsRefusedBeforeQueueing() {
        val reason = codexContinuationProblem(ToolPolicy(allowWrites = true), true, false, CodexDesktopDefaults("danger-full-access", "never"))
        assertTrue(reason.orEmpty().contains("Open the Codex desktop app"))
    }
    @Test fun nativeCodexPermissionsMustMatchBeforeQueueing() {
        assertNotNull(codexContinuationProblem(ToolPolicy(allowWrites = true), true, true, CodexDesktopDefaults("read-only", "never")))
        assertNotNull(codexContinuationProblem(ToolPolicy(allowWrites = true), true, true, CodexDesktopDefaults("danger-full-access", "on-request")))
        assertNull(codexContinuationProblem(ToolPolicy(allowWrites = true), true, true, CodexDesktopDefaults("danger-full-access", "never")))
    }

    @Test fun aCodexContinuationNeverFallsBackToAPrivateServer() = runBlocking {
        val root = Files.createTempDirectory("native-codex-guard").toFile()
        try {
            val request = AgentRequest(AgentSpec(AgentId("codex"), "Codex", RuntimeKind.Codex, ""), "Continue", root.path,
                resume = ProviderSession(RuntimeKind.Codex, "invented-thread"))
            val failure = runCatching { CodexAppServerRuntime(binary = root.resolve("missing-codex").path, scope = this).open(request) }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure.message.orEmpty().contains("Codex desktop app"), "Native continuation must refuse the private-server path")
        } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun anAntigravityContinuationNeverFallsBackToTheCli() = runBlocking {
        val root = Files.createTempDirectory("native-agy-guard").toFile()
        try {
            var starts = 0
            val headless = object : InteractiveAgentRuntime {
                override val kind = RuntimeKind.Gemini
                override val interactive = Qualification(true, true, true)
                override fun run(request: AgentRequest) = emptyFlow<AgentEvent>()
                override suspend fun open(request: AgentRequest): AgentSession {
                    starts++
                    return object : AgentSession {
                        override val events = emptyFlow<AgentEvent>()
                        override val activeTurn: String? = null
                        override suspend fun steer(text: String, expectedTurn: String?) = CommandOutcome.Accepted
                        override suspend fun resolve(requestId: String, decision: AgentDecision) = CommandOutcome.Accepted
                        override suspend fun interrupt() = CommandOutcome.Accepted
                        override fun close() = Unit
                    }
                }
            }
            val request = AgentRequest(AgentSpec(AgentId("gemini"), "Antigravity", RuntimeKind.Gemini, ""), "Continue", root.path,
                resume = ProviderSession(RuntimeKind.Gemini, "invented-conversation"))
            val failure = runCatching { AntigravityAppRuntime(AntigravityApp(root, root.resolve("Missing.app")), this, headless = headless).open(request) }.exceptionOrNull()
            assertNotNull(failure, "A native continuation must refuse an unavailable app")
            assertEquals(0, starts)
        } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }
}
