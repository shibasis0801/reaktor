package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.appserver.ClaudeTranscriptEvents
import dev.shibasis.reaktor.conductor.appserver.ClaudeApp
import dev.shibasis.reaktor.conductor.cli.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class ClaudeSharedParserTest {
    private val processes = object : HarnessProcesses {
        override fun alive(pid: Long) = false
        override fun openTranscripts() = emptySet<String>()
    }
    private fun fixture(each: (File, File) -> Unit) {
        val home = Files.createTempDirectory("claude-shared-parser").toFile().canonicalFile
        try {
            val file = home.resolve(".claude/projects/fixture/session.jsonl").apply {
                parentFile.mkdirs()
                writeText("""{"type":"user","sessionId":"session","cwd":"${home.path}","timestamp":"2026-11-17T08:00:00Z","message":{"content":"Invented request"}}""" + "\n")
            }
            each(home, file)
        } finally { home.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }
    private fun assistant(id: String, text: String, stop: String = "tool_use") =
        """{"type":"assistant","timestamp":"2026-11-17T08:00:01Z","message":{"id":"$id","model":"fixture-model","stop_reason":"$stop","content":[{"type":"text","text":"$text"}]}}"""

    @Test fun theThreeReadersKeepTextToolsAndEndState() = fixture { home, file ->
        val rows = listOf(
            assistant("a", "First paragraph"),
            """{"type":"assistant","timestamp":"2026-11-17T08:00:02Z","message":{"id":"tool","model":"fixture-model","stop_reason":"tool_use","content":[{"type":"thinking","thinking":"Invented reasoning"},{"type":"tool_use","id":"read","name":"Read","input":{"file_path":"${home.path}/Main.kt"}}]}}""",
            """{"type":"user","timestamp":"2026-11-17T08:00:03Z","message":{"content":[{"type":"tool_result","tool_use_id":"read","content":"Invented file"}]}}""",
            assistant("b", "Second paragraph"), assistant("c", "Third paragraph", "end_turn"))
        val runtime = ClaudeTranscriptEvents(AgentId("claude"), EffortRecord.none)
        file.readLines().forEach(runtime::line)
        rows.forEach { file.appendText(it + "\n"); runtime.line(it) }
        val log = WorkLogs.read(RuntimeKind.ClaudeCode, file, home.path)
        assertEquals("Invented request", log.turns.single().prompt)
        assertEquals(listOf("First paragraph", "Second paragraph", "Third paragraph"), log.turns.single().steps.filterIsInstance<WorkStep.Message>().map { it.text })
        assertEquals(StepStatus.Done, log.turns.single().steps.filterIsInstance<WorkStep.Tool>().single().status)
        assertFalse(log.turns.single().open)
        assertEquals("First paragraph\n\nSecond paragraph\n\nThird paragraph", runtime.answer)
        val session = HarnessSessions.read(home, home, file.lastModified(), processes).single()
        assertEquals(HarnessActivity.Ended, session.activity)
        assertEquals("fixture-model", session.model)
        assertEquals("Main.kt", session.steps.single().detail)
        assertTrue(session.files.isEmpty())
    }

    @Test fun aLongTurnKeepsItsMetadataWhenThePromptIsOutsideTheWindow() = fixture { home, file ->
        file.outputStream().buffered().use { out ->
            out.write(("""{"type":"user","sessionId":"session","cwd":"${home.path}","timestamp":"2026-11-17T08:00:00Z","message":{"content":"Invented request"}}""" + "\n").toByteArray())
            val row = ("""{"type":"fixture-padding","value":"${"x".repeat(1024 * 1024)}"}""" + "\n").toByteArray()
            repeat(35) { out.write(row) }
        }
        val log = WorkLogs.read(RuntimeKind.ClaudeCode, file, home.path)
        assertTrue(log.earlierPromptNotShown)
        val session = HarnessSessions.read(home, home, file.lastModified(), processes).single()
        assertEquals("Invented request", session.title)
        assertEquals(home.path, session.directory)
    }

    @Test fun anAppendIsReadOnceAcrossTheLogAndSessionReader() = fixture { home, file ->
        file.appendText((1..1000).joinToString("\n", postfix = "\n") { """{"type":"fixture-padding","value":"${"x".repeat(1024)}"}""" })
        WorkLogs.read(RuntimeKind.ClaudeCode, file, home.path)
        val session = HarnessSessions.read(home, home, file.lastModified(), processes).single()
        val append = assistant("last", "Appended answer", "end_turn") + "\n"
        file.appendText(append)
        val recording = jdk.jfr.Recording().apply { enable("jdk.FileRead").withThreshold(java.time.Duration.ZERO); start() }
        WorkLogs.read(RuntimeKind.ClaudeCode, file, home.path)
        HarnessSessions.refresh(session, home, file.lastModified(), processes)
        recording.stop()
        val receipt = home.resolve("append.jfr")
        recording.dump(receipt.toPath()); recording.close()
        val bytes = jdk.jfr.consumer.RecordingFile.readAllEvents(receipt.toPath()).filter {
            it.eventType.name == "jdk.FileRead" && it.getString("path") == file.path
        }.sumOf { it.getLong("bytesRead").coerceAtLeast(0) }
        assertTrue(bytes <= append.toByteArray().size.toLong(), "The readers re-read $bytes bytes for a ${append.toByteArray().size}-byte append")
    }
}
