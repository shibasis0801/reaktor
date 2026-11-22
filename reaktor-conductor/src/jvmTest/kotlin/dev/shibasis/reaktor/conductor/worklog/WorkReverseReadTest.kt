package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class WorkReverseReadTest {
    private fun withFile(block: (File) -> Unit) {
        val root = Files.createTempDirectory("bounded-reverse-read").toFile()
        try { block(root.resolve("fixture.jsonl")) }
        finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    private val prompt = """{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"UserMessage","content":"Earlier fixture prompt"}}}"""

    @Test fun aReversePromptLookupStopsAfterSixteenMebibytes() = withFile { file ->
        file.bufferedWriter().use { out ->
            out.appendLine(prompt)
            val padding = """{"type":"fixture_padding","text":"${"x".repeat(8192)}"}"""
            repeat(5200) { out.appendLine(padding) }
        }
        val read = WorkLogs.reverseRecord(file, file.length(), "UserMessage") { true }
        assertTrue(read.bytes <= 16L shl 20)
        assertTrue(read.capped)
        assertNull(read.record, "A prompt more than 40 MiB back exceeds the reverse-read byte budget")
        file.appendText("""{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"AgentMessage","id":"answer","text":"Last saved text"}}}""" + "\n")
        val log = WorkLogs.read(RuntimeKind.Codex, file, file.parent)
        assertTrue(log.earlier)
        assertTrue(log.earlierPromptNotShown)
        assertNull(log.turns.last().prompt)
    }

    @Test fun aReverseLookupAlsoHasARecordBudget() = withFile { file ->
        file.bufferedWriter().use { out ->
            out.appendLine(prompt)
            repeat(20000) { out.appendLine("""{"type":"fixture_padding"}""") }
        }
        val read = WorkLogs.reverseRecord(file, file.length(), "UserMessage") { true }
        assertNull(read.record)
        assertEquals(10_000, read.lines)
        assertTrue(read.capped)
    }
}
