package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class GeminiFollowerRetentionTest {
    @Test fun eventsSurviveOnlyUntilTheOldestFollowerConsumesThem() {
        val root = Files.createTempDirectory("gemini-followers").toFile()
        try {
            val file = root.resolve("transcript.jsonl")
            file.writeText("""{"step_index":0,"type":"USER_INPUT","content":"Invented task"}""" + "\n" +
                """{"step_index":1,"type":"PLANNER_RESPONSE","content":"First"}""" + "\n")
            WorkLogs.followGemini(file, root.path).use { fast ->
                WorkLogs.followGemini(file, root.path).use { slow ->
                    assertEquals("First", fast.read().events.filterIsInstance<AgentEvent.Delta>().joinToString("") { it.text })
                    assertTrue(retained(file) > 0)
                    file.appendText("""{"step_index":2,"type":"PLANNER_RESPONSE","content":"Second"}""" + "\n")
                    assertEquals("\n\nSecond", fast.read().events.filterIsInstance<AgentEvent.Delta>().joinToString("") { it.text })
                    assertEquals("First\n\nSecond", slow.read().events.filterIsInstance<AgentEvent.Delta>().joinToString("") { it.text })
                    assertEquals(0, retained(file))
                    assertTrue(fast.read().events.isEmpty())
                    assertTrue(slow.read().events.isEmpty())
                }
            }
            WorkLogs.followGemini(file, root.path).use { replay ->
                assertEquals("First\n\nSecond", replay.read().events.filterIsInstance<AgentEvent.Delta>().joinToString("") { it.text })
            }
            assertEquals(0, retained(file))
        } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    private fun retained(file: File): Int {
        val scans = WorkLogs.javaClass.getDeclaredField("scans").apply { isAccessible = true }.get(WorkLogs) as Map<*, *>
        val scan = scans.entries.single { it.key.toString().contains(file.canonicalPath) }.value!!
        return (scan.javaClass.getDeclaredField("events").apply { isAccessible = true }.get(scan) as List<*>).size
    }
}
