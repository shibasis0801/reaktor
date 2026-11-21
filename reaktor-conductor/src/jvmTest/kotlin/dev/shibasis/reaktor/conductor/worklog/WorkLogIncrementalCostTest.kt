package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import java.time.Duration
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import kotlin.test.*

class WorkLogIncrementalCostTest {
    @Test fun appendingOneLineToANinetySixMiBLogReadsOnlyThatLine() {
        val folder = Files.createTempDirectory("incremental-log-cost").toFile()
        val file = folder.resolve("rollout.jsonl")
        try {
            file.outputStream().buffered().use { output ->
                output.write("""{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"UserMessage","content":[{"text":"Keep this question"}]}}}""".toByteArray())
                output.write('\n'.code)
                val block = ("{\"ignored\":\"" + "x".repeat(1000) + "\"}\n").toByteArray()
                repeat((96L * 1024 * 1024 / block.size).toInt()) { output.write(block) }
            }
            assertEquals("Keep this question", WorkLogs.read(RuntimeKind.Codex, file, folder.path, whole = true).turns.last().prompt)
            val line = """{"type":"event_msg","payload":{"type":"item_completed","item":{"id":"answer","type":"AgentMessage","content":[{"text":"Last character!"}]}}}""" + "\n"
            val recording = Recording()
            recording.enable("jdk.FileRead").withThreshold(Duration.ZERO)
            recording.start()
            file.appendText(line)
            assertEquals("Last character!", (WorkLogs.read(RuntimeKind.Codex, file, folder.path, whole = true).turns.last().steps.last() as WorkStep.Message).text)
            recording.stop()
            val receipt = folder.resolve("cost.jfr").toPath()
            recording.dump(receipt)
            recording.close()
            val bytes = RecordingFile.readAllEvents(receipt).filter { it.eventType.name == "jdk.FileRead" && it.getString("path") == file.path }.sumOf { it.getLong("bytesRead") }
            assertEquals(line.toByteArray().size.toLong() + 256, bytes)
        } finally { folder.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }
}
