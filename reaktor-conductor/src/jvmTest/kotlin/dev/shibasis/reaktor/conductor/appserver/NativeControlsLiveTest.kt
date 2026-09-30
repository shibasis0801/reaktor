package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import kotlin.test.*

class NativeControlsLiveTest {
    @Test fun claudeQuestionAndPermissionAreAnsweredThroughTheNativeHost() = runBlocking(Dispatchers.IO) {
        assumeTrue(System.getenv("REAKTOR_NATIVE_CONTROLS_TEST") == "1")
        val root = Files.createTempDirectory("claude-controls").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val agent = AgentSpec(AgentId("claude"), "Control probe", RuntimeKind.ClaudeCode, "Follow the requested probe exactly.",
                tools = ToolPolicy(allowWrites = true), harnessArgs = listOf("--permission-mode", "default", "--settings", """{"permissions":{"ask":["Write"]}}"""))
            val session = ClaudeCodeSessionRuntime(scope = scope).open(AgentRequest(agent,
                "First call AskUserQuestion to ask which label to use, with options Alpha and Beta. After the answer, use Write to create probe.txt with that label. If Write is denied, do not retry or use another tool: report the denial and stop.", root.path))
            val kinds = mutableListOf<RequestKind>()
            try {
                withTimeout(120000) { session.events.collect { event ->
                    if (event is AgentEvent.RequestPending) {
                        kinds += event.request.kind
                        val decision = if (event.request.kind == RequestKind.Input)
                            AgentDecision.Answers(event.request.questions.associate { it.id to listOf("Alpha") }) else AgentDecision.Deny("Probe denies file creation")
                        assertIs<CommandOutcome.Accepted>(session.resolve(event.request.id, decision))
                        assertIs<CommandOutcome.Stale>(session.resolve(event.request.id, decision))
                    }
                } }
                assertTrue(RequestKind.Input in kinds, "No native question was received")
                assertTrue(RequestKind.Permission in kinds, "No native permission request was received")
                assertFalse(java.io.File(root, "probe.txt").exists())
            } finally { session.close() }
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun codexCommandApprovalCanBeDeniedAndCannotBeReplayed() = runBlocking(Dispatchers.IO) {
        assumeTrue(System.getenv("REAKTOR_NATIVE_CONTROLS_TEST") == "1")
        val root = Files.createTempDirectory("codex-controls").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val agent = AgentSpec(AgentId("codex"), "Control probe", RuntimeKind.Codex, "Follow the requested probe exactly.", effort = NativeEffort("low"))
            val session = CodexAppServerRuntime(scope = scope).open(AgentRequest(agent,
                "Call exec_command to run printf 'probe' > probe.txt in this directory. Set sandbox_permissions=require_escalated and explain that this is a harmless permission integration probe. If permission is declined, do not retry: report the denial and stop.", root.path))
            var pending = 0
            try {
                withTimeout(120000) { session.events.collect { event ->
                    if (event is AgentEvent.RequestPending) {
                        pending++
                        assertIs<CommandOutcome.Accepted>(session.resolve(event.request.id, AgentDecision.Deny()))
                        assertIs<CommandOutcome.Stale>(session.resolve(event.request.id, AgentDecision.Deny()))
                    }
                } }
                assertTrue(pending > 0, "No native approval request was received")
                assertFalse(java.io.File(root, "probe.txt").exists())
            } finally { session.close() }
        } finally { scope.cancel(); root.deleteRecursively() }
    }
}
