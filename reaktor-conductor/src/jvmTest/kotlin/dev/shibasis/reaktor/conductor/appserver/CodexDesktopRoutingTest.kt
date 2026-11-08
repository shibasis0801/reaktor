package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import dev.shibasis.reaktor.conductor.ActivityStatus
import dev.shibasis.reaktor.conductor.AgentEvent
import dev.shibasis.reaktor.conductor.AgentId
import dev.shibasis.reaktor.conductor.AgentRequest
import dev.shibasis.reaktor.conductor.AgentSpec
import dev.shibasis.reaktor.conductor.ProviderSession
import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.ToolPolicy
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexDesktopRoutingTest {
    private val writer = ToolPolicy(allowWrites = true)
    private val reader = ToolPolicy()

    private fun config(text: String) = File.createTempFile("codex-config", ".toml").apply { deleteOnExit(); writeText(text) }

    private fun applied(profile: String, approvals: String) = Json.parseToJsonElement(
        """{"approval_policy":"$approvals","approvals_reviewer":"user","permission_profile":{"type":"managed"},"active_permission_profile":{"id":"$profile"}}""",
    ).jsonObject

    @Test fun onlyTopLevelKeysSetTheAppDefaults() {
        val defaults = codexDesktopDefaults(config("model = \"gpt\"\napproval_policy = \"never\"\n\n[profiles.fast]\nsandbox_mode = \"danger-full-access\"\n"))
        assertEquals(CodexDesktopDefaults(sandbox = null, approvals = "never"), defaults)
    }

    @Test fun aMissingConfigGivesNoDefaults() {
        assertEquals(CodexDesktopDefaults(null, null), codexDesktopDefaults(File("/nonexistent/codex/config.toml")))
    }

    @Test fun runsGoToTheAppOnlyWhenItAppliesWhatTheyAskForWithoutApprovals() {
        assertFalse(codexDesktopHonours(writer, CodexDesktopDefaults(null, null)))
        assertFalse(codexDesktopHonours(writer, CodexDesktopDefaults("danger-full-access", "on-request")))
        assertFalse(codexDesktopHonours(writer, CodexDesktopDefaults("workspace-write", "never")))
        assertTrue(codexDesktopHonours(writer, CodexDesktopDefaults("danger-full-access", "never")))
        assertFalse(codexDesktopHonours(reader, CodexDesktopDefaults("danger-full-access", "never")))
        assertTrue(codexDesktopHonours(reader, CodexDesktopDefaults("read-only", "never")))
    }

    @Test fun aQuietThreadSaysWhetherItsTurnStarted() {
        assertTrue("has not started its turn" in desktopStallNotice("t", started = false))
        assertTrue("waiting for an approval" in desktopStallNotice("t", started = true))
    }

    @Test fun aSettingsShortfallKeepsTheRunUntilTheThreadEnds() = runBlocking {
        val home = Files.createTempDirectory("codex-home").toFile().apply { deleteOnExit() }
        val thread = "01a19999-0000-7000-8000-000000000001"
        val rollout = home.resolve("sessions/2026/10/31/rollout-2026-10-31T00-00-00-$thread.jsonl").apply {
            parentFile.mkdirs()
            writeText("""{"type":"session_meta","payload":{"id":"$thread"}}""" + "\n")
            deleteOnExit()
        }
        fun event(payload: String) = rollout.appendText("""{"type":"event_msg","payload":$payload}""" + "\n")
        val launched = mutableListOf<List<String>>()
        val request = AgentRequest(AgentSpec(AgentId("codex"), "Codex", RuntimeKind.Codex, "", tools = writer), "Build it", home.path,
            resume = ProviderSession(RuntimeKind.Codex, thread))
        val session = CodexDesktopSession(request, "codex", this, home, launch = { command ->
            launched += command
            when (command.lastOrNull()) {
                "Build it" -> {
                    event("""{"type":"thread_settings_applied","thread_settings":{"approval_policy":"on-request","active_permission_profile":{"id":":workspace"}}}""")
                    event("""{"type":"task_started","turn_id":"turn-1"}""")
                }
                else -> if (command.last().startsWith("Stop now")) event("""{"type":"task_complete","last_agent_message":"Stopped."}""")
            }
            true
        })
        val events = withTimeout(10_000) { session.events.toList() }
        val outcome = events.filterIsInstance<AgentEvent.Finished>().single().outcome
        assertFalse(outcome.ok)
        assertEquals("Stopped.", outcome.text)
        assertTrue(":workspace permissions" in outcome.failure.orEmpty())
        assertEquals(ActivityStatus.Failed, events.filterIsInstance<AgentEvent.Activity>().single { it.item.id == "desktop-permissions" }.item.status)
        assertTrue(launched.any { it.take(2) == listOf("codex", "queue") && it.last().startsWith("Stop now") })
    }


    @Test fun desktopRolloutHooksKeepTheirDiagnostics() = runBlocking {
        val home = Files.createTempDirectory("codex-hook-home").toFile()
        try {
            val thread = "01a19999-0000-7000-8000-000000000002"
            val rollout = home.resolve("sessions/2026/11/07/rollout-2026-11-07T00-00-00-$thread.jsonl").apply {
                parentFile.mkdirs()
                writeText("""{"type":"session_meta","payload":{"id":"$thread"}}""" + "\n")
            }
            fun event(payload: String) = rollout.appendText("""{"type":"event_msg","payload":$payload}""" + "\n")
            val request = AgentRequest(AgentSpec(AgentId("codex"), "Codex", RuntimeKind.Codex, "", tools = reader),
                "Hook probe", home.path, resume = ProviderSession(RuntimeKind.Codex, thread))
            val session = CodexDesktopSession(request, "codex", this, home, launch = { command ->
                if (command.take(2) == listOf("codex", "queue")) {
                    event("""{"type":"task_started","turn_id":"turn-1"}""")
                    event("""{"type":"hook_started","turn_id":"turn-1","run":{"id":"desktop-stop","event_name":"stop","status":"running","source":"plugin","source_path":"/hooks/desktop.json","entries":[]}}""")
                    event("""{"type":"hook_completed","turn_id":"turn-1","run":{"id":"desktop-stop","event_name":"stop","status":"failed","status_message":"Timed out","duration_ms":10000,"source":"plugin","source_path":"/hooks/desktop.json","entries":[{"kind":"error","text":"hook timed out after 10s"}]}}""")
                    event("""{"type":"task_complete","last_agent_message":"Done."}""")
                }
                true
            })
            val events = withTimeout(10000) { session.events.toList() }
            val hooks = events.filterIsInstance<AgentEvent.Activity>().map { it.item }.filter { it.id == "hook:desktop-stop" }
            assertEquals(listOf(ActivityStatus.Started, ActivityStatus.Failed), hooks.map { it.status })
            assertTrue(hooks.last().input.orEmpty().contains("/hooks/desktop.json"))
            assertTrue(hooks.last().output.orEmpty().contains("hook timed out after 10s"))
            assertTrue(hooks.last().output.orEmpty().contains("10000"))
            assertEquals(listOf("/hooks/desktop.json"), hooks.last().subjectRefs)
            assertTrue(events.filterIsInstance<AgentEvent.Finished>().single().outcome.ok)
        } finally {
            home.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }

    @Test fun appliedSettingsNameWhatFallsShort() {
        assertEquals(":workspace permissions where it asked for danger-full-access and on-request approvals",
            codexDesktopShortfall(writer, applied(":workspace", "on-request")))
        assertEquals("on-request approvals", codexDesktopShortfall(writer, applied(":danger-full-access", "on-request")))
        assertNull(codexDesktopShortfall(writer, applied(":danger-full-access", "never")))
        assertNull(codexDesktopShortfall(writer, Json.parseToJsonElement("{}").jsonObject))
    }
}
