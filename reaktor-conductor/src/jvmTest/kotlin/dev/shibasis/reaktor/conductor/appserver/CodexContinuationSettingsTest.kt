package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class CodexContinuationSettingsTest {
    @Test fun aResumeNeverChangesTransportAfterTheDesktopGuard() = runBlocking {
        val root = Files.createTempDirectory("codex-availability-gap").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val binary = root.resolve("fixture-codex").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }
            var reads = 0
            val runtime = CodexAppServerRuntime(binary.path, scope).apply {
                desktopAvailable = { ++reads == 1 }
                desktopRunning = { true }
                desktopDefaults = { CodexDesktopDefaults("read-only", "never") }
            }
            val request = AgentRequest(AgentSpec(AgentId("codex"), "Codex", RuntimeKind.Codex, ""), "Continue", root.path,
                resume = ProviderSession(RuntimeKind.Codex, "invented-thread"))
            val session = runtime.open(request)
            try { assertTrue(session is CodexDesktopSession, "A resumed desktop thread must never enter a private app-server") }
            finally { session.close() }
            assertEquals(1, reads, "Availability is decided once")
        } finally { scope.cancel(); root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun recordedThreadSettingsRefuseBeforeAnyMessageIsQueued() = runBlocking {
        val root = Files.createTempDirectory("codex-recorded-settings").toFile()
        try {
            root.resolve("sessions/rollout-invented-thread.jsonl").apply {
                parentFile.mkdirs()
                writeText("""{"type":"event_msg","payload":{"type":"thread_settings_applied","thread_settings":{"approval_policy":"on-request","active_permission_profile":{"id":"workspace-write"}}}}"""+"\n")
            }
            val commands = mutableListOf<List<String>>()
            val request = AgentRequest(AgentSpec(AgentId("codex"), "Codex", RuntimeKind.Codex, "", tools = ToolPolicy(allowWrites = true)),
                "Continue the invented thread", root.path, resume = ProviderSession(RuntimeKind.Codex, "invented-thread"))
            val session = CodexDesktopSession(request, root.resolve("fixture-codex").path, this, root, launch = { commands += it; true })
            val events = withTimeoutOrNull(500) { session.events.toList() }.orEmpty()
            session.close()
            assertTrue(commands.isEmpty(), "A mismatched native thread must receive no queue or stop command")
            val failure = events.filterIsInstance<AgentEvent.Finished>().singleOrNull()?.outcome?.failure.orEmpty()
            assertTrue(failure.contains("on-request") && failure.contains("never"), "The refusal must name the recorded settings difference")
        } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun aMissingBinaryNamesWhatThePaneMustRestore() = runBlocking {
        val root = Files.createTempDirectory("codex-missing-binary").toFile()
        try {
            root.resolve("sessions/rollout-invented-thread.jsonl").apply {
                parentFile.mkdirs()
                writeText("""{"type":"session_meta","payload":{"id":"invented-thread"}}""" + "\n")
            }
            val binary = root.resolve("missing-codex").path
            val request = AgentRequest(AgentSpec(AgentId("codex"), "Codex", RuntimeKind.Codex, ""), "Continue", root.path,
                resume = ProviderSession(RuntimeKind.Codex, "invented-thread"))
            val session = CodexDesktopSession(request, binary, this, root)
            val events = withTimeout(2000) { session.events.toList() }
            val failure = events.filterIsInstance<AgentEvent.Finished>().single().outcome.failure.orEmpty()
            assertTrue(failure.contains("binary", true) && failure.contains("missing-codex"))
        } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun aMovedRolloutIsRefusedBeforeQueueing() = runBlocking {
        val root = Files.createTempDirectory("codex-moved-rollout").toFile()
        try {
            val commands = mutableListOf<List<String>>()
            val request = AgentRequest(AgentSpec(AgentId("codex"), "Codex", RuntimeKind.Codex, ""), "Continue", root.path,
                resume = ProviderSession(RuntimeKind.Codex, "moved-thread"))
            val session = CodexDesktopSession(request, "fixture-codex", this, root, launch = { commands += it; true })
            val events = withTimeoutOrNull(500) { session.events.toList() }.orEmpty()
            session.close()
            assertTrue(commands.isEmpty())
            assertTrue(events.filterIsInstance<AgentEvent.Finished>().singleOrNull()?.outcome?.failure.orEmpty().contains("rollout", true))
        } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }
}
