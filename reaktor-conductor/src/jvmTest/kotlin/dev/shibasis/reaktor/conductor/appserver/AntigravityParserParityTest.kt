package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.cli.*
import dev.shibasis.reaktor.conductor.worklog.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import java.time.Duration
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import kotlin.test.*

class AntigravityParserParityTest {
    @Test fun typedResultsSettleTheSameToolInTheRuntimeAndTheSavedLog() {
        val records = listOf(
            """{"step_index":0,"type":"USER_INPUT","content":"Inspect the invented file"}""",
            """{"step_index":1,"type":"PLANNER_RESPONSE","content":"First paragraph","tool_calls":[{"name":"view_file","args":{"AbsolutePath":"/fixture/Example.kt"}}]}""",
            """{"step_index":2,"type":"VIEW_FILE","content":"Invented file text","status":"DONE"}""",
            """{"step_index":3,"type":"PLANNER_RESPONSE","content":"Second paragraph"}""")
        val home = Files.createTempDirectory("gemini-parser-parity").toFile()
        try {
            val file = home.resolve("transcript.jsonl").apply { writeText(records.joinToString("\n", postfix = "\n")) }
            val log = WorkLogs.read(RuntimeKind.Gemini, file, "/fixture")
            assertEquals(StepStatus.Done, log.turns.single().steps.filterIsInstance<WorkStep.Tool>().single().status)
            val reader = AntigravityTranscriptEvents(AgentId("fixture"))
            val events = records.flatMap(reader::line).filterIsInstance<AgentEvent.Activity>()
            assertEquals(ActivityStatus.Completed, events.last { it.item.kind == ActivityKind.Tool }.item.status)
            assertEquals(log.turns.single().steps.filterIsInstance<WorkStep.Message>().joinToString("\n\n") { it.text }, reader.answer)
        } finally { home.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun theHarnessUsesTheIncrementalLogThatThePaneAlreadyParsed() {
        val home = Files.createTempDirectory("gemini-shared-parser").toFile()
        try {
            val root = home.resolve("workspace").apply { mkdirs() }
            val file = home.resolve(".gemini/antigravity-cli/brain/parity/.system_generated/logs/transcript.jsonl").apply {
                parentFile.mkdirs()
                writeText("""{"step_index":0,"type":"USER_INPUT","content":"Inspect"}""" + "\n" +
                    """{"step_index":1,"type":"PLANNER_RESPONSE","content":"Answer","tool_calls":[{"name":"view_file","args":{"AbsolutePath":"${root.path}/Example.kt"}}]}""" + "\n")
            }
            home.resolve(".gemini/antigravity-cli/cache/last_conversations.json").apply { parentFile.mkdirs(); writeText("""{"${root.path}":"parity"}""") }
            val processes = object : HarnessProcesses { override fun alive(pid: Long) = false; override fun openTranscripts() = emptySet<String>() }
            HarnessSessions.read(root, home, System.currentTimeMillis(), processes)
            val recording = Recording().apply { enable("jdk.FileRead").withThreshold(Duration.ZERO); start() }
            try {
                val log = WorkLogs.read(RuntimeKind.Gemini, file, root.path)
                assertEquals("Inspect", log.turns.single().prompt)
                recording.stop()
                val result = home.resolve("reads.jfr")
                recording.dump(result.toPath())
                val bytes = RecordingFile.readAllEvents(result.toPath()).filter { it.eventType.name == "jdk.FileRead" && it.getString("path") == file.path }.sumOf { it.getLong("bytesRead").coerceAtLeast(0) }
                assertEquals(0L, bytes, "The pane re-read a Gemini transcript that the harness already parsed")
            } finally { recording.close() }
        } finally { home.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }
}
