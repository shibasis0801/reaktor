package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class TranscriptRetentionTest {
    @Test fun aRecentlyReadScanSurvivesTheNinetySeventhFile() {
        val root = Files.createTempDirectory("transcript-lru").toFile()
        try {
            fun file(index: Int) = root.resolve("$index.jsonl").apply { writeText("{}\n") }
            fun scan(file: File): Any? {
                val scans = WorkLogs.javaClass.getDeclaredField("scans").apply { isAccessible = true }.get(WorkLogs) as Map<*, *>
                return scans.entries.firstOrNull { it.key.toString().contains(file.canonicalPath) }?.value
            }
            val hot = file(0)
            WorkLogs.read(RuntimeKind.Gemini, hot, root.path)
            val first = scan(hot)
            repeat(100) { index ->
                WorkLogs.read(RuntimeKind.Gemini, file(index + 1), root.path)
                WorkLogs.read(RuntimeKind.Gemini, hot, root.path)
            }
            assertSame(first, scan(hot), "The cache cleared a recently used parser instead of evicting the least recently used one")
        } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun discoveryDoesNotRetainAnUnboundedGeminiEventHistory() {
        val root = Files.createTempDirectory("gemini-retention").toFile()
        try {
            val file = root.resolve("transcript.jsonl")
            file.bufferedWriter().use { out ->
                out.appendLine("""{"step_index":0,"type":"USER_INPUT","content":"Invented task"}""")
                repeat(1000) { n -> out.appendLine("""{"step_index":${n + 1},"type":"PLANNER_RESPONSE","content":"Invented paragraph $n"}""") }
            }
            WorkLogs.read(RuntimeKind.Gemini, file, root.path)
            val scans = WorkLogs.javaClass.getDeclaredField("scans").apply { isAccessible = true }.get(WorkLogs) as Map<*, *>
            val scan = scans.entries.single { it.key.toString().contains(file.canonicalPath) }.value!!
            val events = scan.javaClass.getDeclaredField("events").apply { isAccessible = true }.get(scan) as List<*>
            assertTrue(events.isEmpty(), "Discovery retained ${events.size} events without a follower")
        } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }
}
