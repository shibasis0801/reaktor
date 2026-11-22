package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class WorkPromptRetentionTest {
    @Test fun aProfileDoesNotChangeTheOwnersTypedRoleParagraph() {
        val typed = "Inspect this example.\n\nYour role, Reader: keep this paragraph exactly."
        assertEquals(typed, WorkPrompts.of(typed).text)
        assertNull(WorkPrompts.of(typed).context)
    }

    @Test fun theCurrentPromptSurvivesBothReadWindows() {
        val root = Files.createTempDirectory("work-prompt-window").toFile()
        try {
            for (whole in listOf(false, true)) {
                val file = root.resolve("$whole.jsonl")
                file.bufferedWriter().use { out ->
                    out.appendLine("""{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"UserMessage","content":"Keep the current prompt"}}}""")
                    val filler = """{"type":"fixture_padding","text":"${"x".repeat(8192)}"}"""
                    repeat(if (whole) 12500 else 1100) { out.appendLine(filler) }
                    out.appendLine("""{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"AgentMessage","id":"answer","text":"Last saved text"}}}""")
                }
                val log = WorkLogs.read(RuntimeKind.Codex, file, root.path, whole)
                assertEquals("Keep the current prompt", log.turns.last().prompt, "whole=$whole")
                file.appendText("""{"type":"event_msg","payload":{"type":"task_complete"}}"""+"\n")
                assertEquals("Keep the current prompt", WorkLogs.read(RuntimeKind.Codex, file, root.path, whole).turns.last().prompt)
            }
        } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }
}
