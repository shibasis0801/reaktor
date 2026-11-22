package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class CodexGateOrder16Test {
    private val full = """{"approval_policy":"never","permission_profile":{"type":"disabled"}}"""
    private val read = """{"approval_policy":"never","permission_profile":{"type":"managed","file_system":{"type":"restricted","entries":[{"path":{"type":"special","value":{"kind":"root"}},"access":"read"}]},"network":"restricted"}}"""
    private fun settings(value: String) = """{"type":"event_msg","payload":{"type":"thread_settings_applied","thread_settings":$value}}""" + "\n"
    private fun fixture(test: (File) -> Unit) {
        val root = Files.createTempDirectory("codex-order16-gate").toFile()
        try { test(root) } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun aNarrowerThreadOnlySuggestsChangingTheThreadsPermissions() = fixture { root ->
        val file = root.resolve("rollout.jsonl").apply { writeText(settings(read)) }
        assertEquals("This Codex thread records read-only permissions; this pane asks for danger-full-access permissions and approval_policy = never. Nothing was queued. Change this thread's permissions in the Codex app.",
            codexRecordedSettingsProblem(ToolPolicy(allowWrites = true), file))
    }

    @Test fun aWiderThreadSuggestsAllowingWritesOrChangingTheThread() = fixture { root ->
        val file = root.resolve("rollout.jsonl").apply { writeText(settings(full)) }
        assertEquals("This Codex thread records danger-full-access permissions; this pane asks for read-only permissions and approval_policy = never. Nothing was queued. Allow writes here if these full-access permissions are intended, or change this thread's permissions in the Codex app.",
            codexRecordedSettingsProblem(ToolPolicy(), file))
    }

    @Test fun aSettingsRecordTenMebibytesBackIsStillRead() = fixture { root ->
        val file = root.resolve("rollout.jsonl")
        file.bufferedWriter().use { out ->
            out.write(settings(read))
            val row = """{"type":"padding","text":"${"x".repeat(4096)}"}""" + "\n"
            repeat((10 * 1024 * 1024) / row.length + 1) { out.write(row) }
        }
        assertNull(codexRecordedSettingsProblem(ToolPolicy(), file))
        assertNotNull(codexRecordedSettingsProblem(ToolPolicy(allowWrites = true), file))
    }

    @Test fun aUserTurnWithoutRecordedSettingsCannotBeQueued() = fixture { root ->
        val file = root.resolve("rollout.jsonl").apply {
            writeText("""{"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"Invented turn"}]}}""" + "\n")
        }
        assertTrue(codexRecordedSettingsProblem(ToolPolicy(), file).orEmpty().contains("cannot verify"))
    }

    @Test fun aRootWriteEntryIsNotWorkspaceWrite() {
        val value = ConductorJson.parseToJsonElement("""{"approval_policy":"never","cwd":"/invented/workspace","runtime_workspace_roots":["/invented/workspace"],"permission_profile":{"type":"managed","file_system":{"type":"restricted","entries":[{"path":{"type":"special","value":{"kind":"root"}},"access":"write"}]},"network":"restricted"}}""")
        val problem = codexDesktopShortfall(ToolPolicy(allowWrites = true, sandboxed = true), value as kotlinx.serialization.json.JsonObject)
        assertTrue(problem.orEmpty().contains("cannot verify"))
    }

    @Test fun enabledNetworkIsNotReadOnlyPermissions() {
        val value = ConductorJson.parseToJsonElement(read) as kotlinx.serialization.json.JsonObject
        val profile = value.getValue("permission_profile") as kotlinx.serialization.json.JsonObject
        val changed = kotlinx.serialization.json.JsonObject(value + ("permission_profile" to kotlinx.serialization.json.JsonObject(profile + ("network" to kotlinx.serialization.json.JsonPrimitive("enabled")))))
        assertTrue(codexDesktopShortfall(ToolPolicy(), changed).orEmpty().contains("cannot verify"))
    }

    @Test fun aPermissionShortfallStaysWaitingAfterTheNextAppend() = runBlocking {
        val root = Files.createTempDirectory("codex-order16-shortfall").toFile()
        try {
            val file = root.resolve("sessions/rollout-fixture-thread.jsonl").apply { parentFile.mkdirs(); writeText(settings(full)) }
            val launched = mutableListOf<List<String>>()
            val request = AgentRequest(AgentSpec(AgentId("codex"), "Codex", RuntimeKind.Codex, "", tools = ToolPolicy(allowWrites = true)), "Invented turn", root.path,
                resume = ProviderSession(RuntimeKind.Codex, "fixture-thread"))
            val waiting = CompletableDeferred<Unit>()
            val session = CodexDesktopSession(request, "fixture-codex", this, root, launch = { command ->
                launched += command
                if (command.lastOrNull() == request.prompt) file.appendText(
                    """{"type":"event_msg","payload":{"type":"task_started","turn_id":"fixture-turn"}}""" + "\n" + settings(read))
                true
            })
            val events = mutableListOf<AgentEvent>()
            val collecting = async {
                session.events.onEach { event ->
                    events += event
                    if (event is AgentEvent.Activity && event.item.status == ActivityStatus.Waiting) waiting.complete(Unit)
                }.toList()
            }
            try {
                withTimeout(2000) { waiting.await() }
                file.appendText("""{"type":"event_msg","payload":{"type":"agent_message","message":"Invented answer"}}""" + "\n" +
                    """{"type":"event_msg","payload":{"type":"task_complete","turn_id":"fixture-turn"}}""" + "\n")
                withTimeout(3000) { collecting.await() }
                val attentionId = events.filterIsInstance<AgentEvent.Activity>().first { it.item.status == ActivityStatus.Waiting }.item.id
                val attention = events.filterIsInstance<AgentEvent.Activity>().filter { it.item.id == attentionId }
                assertEquals(ActivityStatus.Waiting, attention.last().item.status)
                assertEquals(2, launched.size)
            } finally { collecting.cancelAndJoin(); session.close() }
        } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }
}
