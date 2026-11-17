package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class CodexRealPermissionProfileTest {
    private fun settings(text: String) = Json.parseToJsonElement(text).jsonObject
    private val reader = ToolPolicy()
    private val writer = ToolPolicy(allowWrites = true)

    @Test fun aDisabledProfileWithoutAnIdIsFullAccess() {
        val value = settings("""{"approval_policy":"never","permission_profile":{"type":"disabled"}}""")
        assertNotNull(codexDesktopShortfall(reader, value))
        assertNull(codexDesktopShortfall(writer, value))
    }

    @Test fun aDisabledProfileWithTheRealFullAccessIdIsFullAccess() {
        val value = settings("""{"approval_policy":"never","permission_profile":{"type":"disabled"},"active_permission_profile":{"id":":danger-full-access"}}""")
        assertNotNull(codexDesktopShortfall(reader, value))
        assertNull(codexDesktopShortfall(writer, value))
    }

    @Test fun managedWriteEntriesWithoutAnIdAreWorkspacePermissions() {
        val value = settings("""{"approval_policy":"never","permission_profile":{"type":"managed","file_system":{"type":"restricted","entries":[{"path":{"type":"special","value":{"kind":"root"}},"access":"read"},{"path":{"type":"path","path":"/invented/workspace"},"access":"write"}]},"network":"restricted"}}""")
        assertNotNull(codexDesktopShortfall(reader, value))
        assertNotNull(codexDesktopShortfall(writer, value))
        assertNull(codexDesktopShortfall(ToolPolicy(allowWrites = true, sandboxed = true), value))
    }

    @Test fun managedReadEntriesWithoutAnIdAreReadOnly() {
        val value = settings("""{"approval_policy":"never","permission_profile":{"type":"managed","file_system":{"type":"restricted","entries":[{"path":{"type":"special","value":{"kind":"root"}},"access":"read"}]}},"network":"restricted"}""")
        assertNull(codexDesktopShortfall(reader, value))
        assertNotNull(codexDesktopShortfall(writer, value))
    }

    @Test fun onRequestWithTheRealWorkspaceIdRefusesBothAsks() {
        val value = settings("""{"approval_policy":"on-request","active_permission_profile":{"id":":workspace"}}""")
        assertNotNull(codexDesktopShortfall(reader, value))
        assertNotNull(codexDesktopShortfall(writer, value))
    }

    private fun cannotVerify(raw: String) {
        val problem = codexDesktopShortfall(reader, settings(raw))
        assertTrue(problem.orEmpty().contains("cannot verify", ignoreCase = true))
        assertFalse(problem.orEmpty().contains("JsonPrimitive"))
    }

    @Test fun anUnknownProfileIsRefusedInWords() = cannotVerify(
        """{"approval_policy":"never","permission_profile":{"type":"invented"}}""")

    @Test fun aStructuredApprovalPolicyIsRefusedInWords() = cannotVerify(
        """{"approval_policy":{"mode":"never"},"active_permission_profile":{"id":":danger-full-access"}}""")

    @Test fun aStructuredProfileIdIsRefusedInWords() = cannotVerify(
        """{"approval_policy":"never","active_permission_profile":{"id":{"name":"invented"}}}""")

    @Test fun aRealShapeMismatchQueuesNothingAndNamesHowToContinue() = runBlocking {
        val root = Files.createTempDirectory("real-codex-profile").toFile()
        try {
            val file = root.resolve("sessions/rollout-fixture-thread.jsonl").apply {
                parentFile.mkdirs()
                writeText("""{"type":"event_msg","payload":{"type":"thread_settings_applied","thread_settings":{"approval_policy":"never","permission_profile":{"type":"disabled"}}}}""" + "\n")
            }
            var queued = 0
            val request = AgentRequest(AgentSpec(AgentId("codex"), "Codex", RuntimeKind.Codex, ""), "Continue", root.path,
                resume = ProviderSession(RuntimeKind.Codex, "fixture-thread"))
            val session = CodexDesktopSession(request, "fixture-codex", this, root, launch = { queued++; true })
            val events = withTimeoutOrNull(1000) { session.events.toList() }.orEmpty()
            session.close()
            assertEquals(0, queued)
            val reason = events.filterIsInstance<AgentEvent.Finished>().singleOrNull()?.outcome?.failure.orEmpty()
            assertTrue(reason.contains("danger-full-access") && reason.contains("read-only"))
            assertTrue(reason.contains("app") && reason.contains("Nothing was queued"))
            assertEquals(reason, codexRecordedSettingsProblem(reader, file))
        } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun aBoundedSettingsSearchNeverGrantsUnknownPermissions() {
        val root = Files.createTempDirectory("codex-settings-cap").toFile()
        try {
            val file = root.resolve("rollout.jsonl")
            val row = ("""{"type":"fixture-padding","text":"${"x".repeat(1024 * 1024)}"}""" + "\n").toByteArray()
            file.outputStream().buffered().use { out -> repeat(20) { out.write(row) } }
            assertTrue(codexRecordedSettingsProblem(reader, file).orEmpty().contains("cannot verify"))
        } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun theRequestCannotOverrideTheBinarySearchPath() = runBlocking {
        val root = Files.createTempDirectory("codex-spawn-path").toFile()
        try {
            assertTrue(System.getenv("PATH").split(File.pathSeparatorChar).any { File(it, "false").canExecute() })
            root.resolve("sessions/rollout-fixture-thread.jsonl").apply { parentFile.mkdirs(); writeText("{}\n") }
            val request = AgentRequest(AgentSpec(AgentId("codex"), "Codex", RuntimeKind.Codex, ""), "Continue", root.path,
                resume = ProviderSession(RuntimeKind.Codex, "fixture-thread"), executionId = "PATH=/invented/empty:fixture")
            assertFalse(request.environment.containsKey("PATH"))
            assertEquals(setOf(AgentRequest.RunVariable), request.environment.keys)
            val session = CodexDesktopSession(request, "false", this, root)
            val events = withTimeout(2000) { session.events.toList() }
            assertEquals("Codex could not queue the prompt", events.filterIsInstance<AgentEvent.Finished>().single().outcome.failure)
        } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun anInTurnShortfallReportsWaitingWithoutWaitingForCompletion() = runBlocking {
        val root = Files.createTempDirectory("codex-permission-wait").toFile()
        try {
            val file = root.resolve("sessions/rollout-fixture-thread.jsonl").apply {
                parentFile.mkdirs()
                writeText("""{"type":"event_msg","payload":{"type":"thread_settings_applied","thread_settings":{"approval_policy":"never","permission_profile":{"type":"disabled"}}}}""" + "\n")
            }
            val launched = mutableListOf<List<String>>()
            val request = AgentRequest(AgentSpec(AgentId("codex"), "Codex", RuntimeKind.Codex, "", tools = writer), "Start fixture", root.path,
                resume = ProviderSession(RuntimeKind.Codex, "fixture-thread"))
            val session = CodexDesktopSession(request, "fixture-codex", this, root, launch = { command ->
                launched += command
                if (command.lastOrNull() == request.prompt) file.appendText(
                    """{"type":"event_msg","payload":{"type":"task_started","turn_id":"fixture-turn"}}""" + "\n" +
                    """{"type":"event_msg","payload":{"type":"thread_settings_applied","thread_settings":{"approval_policy":"on-request","active_permission_profile":{"id":":workspace"}}}}""" + "\n")
                true
            })
            val notice = withTimeoutOrNull(1000) { session.events.first { it is AgentEvent.Activity && it.item.status == ActivityStatus.Waiting } }
            session.close()
            assertNotNull(notice, "A permissions shortfall needs an immediate waiting notice")
            assertTrue((notice as AgentEvent.Activity).item.output.orEmpty().contains("on-request"))
            assertTrue(launched.none { it.lastOrNull()?.startsWith("Stop now") == true })
        } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }
}
