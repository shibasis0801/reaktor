package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.cli.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

class WorkReadersOrder16Test {
    private fun fixture(block: (File, File) -> Unit) {
        val home = Files.createTempDirectory("work-readers-16").toFile().canonicalFile
        try {
            val file = home.resolve(".claude/projects/fixture/session.jsonl").apply { parentFile.mkdirs(); writeText(prompt(home, "Current request")) }
            block(home, file)
        } finally { home.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }
    private fun prompt(root: File, text: String) = """{"type":"user","sessionId":"session","cwd":"${root.path}","timestamp":"2026-11-18T01:00:00Z","message":{"content":"$text"}}""" + "\n"
    private fun answer(text: String) = """{"type":"assistant","message":{"id":"answer","stop_reason":"end_turn","content":[{"type":"text","text":"$text"}]}}""" + "\n"
    private fun scans() = WorkLogs.javaClass.getDeclaredField("scans").apply { isAccessible = true }.get(WorkLogs) as Map<*, *>
    private fun entry(file: File) = scans().entries.single { it.key.toString().contains(file.canonicalPath) }
    private fun events(scan: Any) = scan.javaClass.getDeclaredField("events").apply { isAccessible = true }.get(scan) as List<*>

    @Test fun aRailReadRetainsNoClaudeEvents() = fixture { home, file ->
        file.appendText(answer("Saved answer"))
        val processes = object : HarnessProcesses {
            override fun alive(pid: Long) = false
            override fun openTranscripts() = emptySet<String>()
        }
        assertEquals(1, HarnessSessions.read(home, home, file.lastModified(), processes).size)
        assertEquals(0, events(entry(file).value!!).size)
    }

    @Test fun aColdRailReadStaysInsideTheOldHeadAndTailBudget() = fixture { home, file ->
        file.appendText((1..512).joinToString("\n", postfix = "\n") { """{"type":"padding","text":"${"x".repeat(8192)}"}""" })
        file.appendText(answer("Finished answer"))
        val recording = jdk.jfr.Recording().apply { enable("jdk.FileRead").withThreshold(java.time.Duration.ZERO); start() }
        WorkLogs.claude(file, home.path) { it.ended }
        recording.stop()
        val receipt = home.resolve("reads.jfr")
        recording.dump(receipt.toPath()); recording.close()
        val bytes = jdk.jfr.consumer.RecordingFile.readAllEvents(receipt.toPath()).filter {
            it.eventType.name == "jdk.FileRead" && it.getString("path") == file.path
        }.sumOf { it.getLong("bytesRead").coerceAtLeast(0) }
        assertTrue(bytes <= 1024 * 1024 + 2 * 65536, "The rail read $bytes bytes")
    }

    @Test fun aPinnedClaudeScanSurvivesNinetySevenKeysAndConsumedEventsAreReleased() = fixture { home, file ->
        file.appendText(answer("First answer"))
        @Suppress("UNCHECKED_CAST")
        val followers = WorkLogs.javaClass.getDeclaredField("followers").apply { isAccessible = true }.get(WorkLogs) as ConcurrentHashMap<String, ConcurrentHashMap<Long, Long>>
        val key = "${RuntimeKind.ClaudeCode}\u0000${file.canonicalPath}\u0000${home.canonicalPath}\u0000false"
        followers[key] = ConcurrentHashMap<Long, Long>().apply { put(-16L, 0L) }
        try {
            WorkLogs.claudeTurn(file, home.path)
            val original = entry(file)
            repeat(97) { index ->
                home.resolve("pressure-$index.jsonl").apply { writeText(prompt(home, "Other request")) }.let {
                    WorkLogs.read(RuntimeKind.ClaudeCode, it, home.path)
                }
            }
            assertSame(original.value, entry(file).value)
            val snapshot = requireNotNull(WorkLogs.claudeTurn(file, home.path, 0))
            assertEquals("First answer", snapshot.events.filterIsInstance<AgentEvent.Delta>().joinToString("") { it.text })
            followers[key]!![-16L] = snapshot.offset
            WorkLogs.claudeTurn(file, home.path, snapshot.offset)
            assertEquals(0, events(entry(file).value!!).size)
        } finally { followers.remove(key) }
    }

    @Test fun aLongerReplacementRebuildsRatherThanAppendingToThePreviousConversation() = fixture { home, file ->
        file.appendText(answer("Old answer"))
        WorkLogs.read(RuntimeKind.ClaudeCode, file, home.path)
        val replacement = home.resolve("replacement.jsonl").apply {
            writeText(prompt(home, "Replacement request") + answer("Replacement answer that is longer than the original"))
        }
        Files.move(replacement.toPath(), file.toPath(), REPLACE_EXISTING)
        val turn = WorkLogs.read(RuntimeKind.ClaudeCode, file, home.path).turns.single()
        assertEquals("Replacement request", turn.prompt)
        assertEquals(listOf("Replacement answer that is longer than the original"), turn.steps.filterIsInstance<WorkStep.Message>().map { it.text })
    }

    @Test fun aLongerRewriteOnTheSameFileRebuildsTheConversation() = fixture { home, file ->
        file.appendText(answer("Old answer"))
        WorkLogs.read(RuntimeKind.ClaudeCode, file, home.path)
        file.writeText(prompt(home, "Rewritten request") + answer("Rewritten answer that is longer than the original answer"))
        val turn = WorkLogs.read(RuntimeKind.ClaudeCode, file, home.path).turns.single()
        assertEquals("Rewritten request", turn.prompt)
        assertEquals(listOf("Rewritten answer that is longer than the original answer"), turn.steps.filterIsInstance<WorkStep.Message>().map { it.text })
    }

    @Test fun anOversizeCurrentPromptNeverShowsTheOlderPrompt() = fixture { home, file ->
        file.writeText(prompt(home, "Older prompt") + prompt(home, "x".repeat(1536 * 1024)))
        file.appendText((1..1150).joinToString("\n", postfix = "\n") { """{"type":"fixture-padding","value":"${"x".repeat(8192)}"}""" })
        file.appendText(answer("Current answer"))
        val log = WorkLogs.read(RuntimeKind.ClaudeCode, file, home.path)
        assertTrue(log.turns.none { it.prompt == "Older prompt" })
        assertNull(log.turns.first().prompt)
        assertTrue(log.earlierPromptNotShown)
    }

    @Test fun aCamelCaseCodexPromptOutsideTheWindowIsRecognised() = fixture { home, file ->
        file.bufferedWriter().use { out ->
            out.appendLine("""{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"userMessage","content":"Current Codex prompt"}}}""")
            val padding = """{"type":"fixture-padding","text":"${"x".repeat(8192)}"}"""
            repeat(1150) { out.appendLine(padding) }
            out.append(answer("Unrelated shape"))
        }
        assertEquals("Current Codex prompt", WorkLogs.read(RuntimeKind.Codex, file, home.path).turns.first().prompt)
    }

    @Test fun aSliceKeepsTheMissingPromptNoticeOnlyWithItsFirstTurn() {
        val log = WorkLog(listOf(
            WorkTurn("first", null, 10, 20, listOf(WorkStep.Message("a", 15, "First answer"))),
            WorkTurn("second", "Second prompt", 30, 40, listOf(WorkStep.Message("b", 35, "Second answer")))), earlier = true, earlierPromptNotShown = true)
        assertTrue(log.slice(0, 25).earlierPromptNotShown)
        assertFalse(log.slice(25, 50).earlierPromptNotShown)
    }

    @Test fun aMergeKeepsTheMissingPromptNotice() {
        val log = WorkLog(listOf(WorkTurn("first", null, 10, 20, listOf(WorkStep.Message("a", 15, "Answer")))), earlier = true, earlierPromptNotShown = true)
        assertTrue(mergeWorkLogs(listOf(WorkLog.Empty, log)).earlierPromptNotShown)
    }
    @Test fun aCanonicalWorkspaceRootSharesTheRailAndPaneScan() = fixture { home, file ->
        WorkLogs.read(RuntimeKind.ClaudeCode, file, home.resolve("unused/..").path)
        WorkLogs.claude(file, home.canonicalPath) { it.prompts }
        assertEquals(1, scans().keys.count { it.toString().contains(file.canonicalPath) })
        assertEquals("Current request", WorkLogs.read(RuntimeKind.ClaudeCode, file, home.canonicalPath).turns.single().prompt)
    }

    @Test fun aLongLineAtTheWindowBoundaryDoesNotDiscardAValidScanOnAppend() = fixture { home, file ->
        file.bufferedWriter().use { out ->
            out.append(prompt(home, "Boundary request"))
            out.appendLine("""{"type":"padding","text":"${"x".repeat(2 * 1024 * 1024)}"}""")
            val padding = """{"type":"padding","text":"${"x".repeat(8192)}"}"""
            repeat(910) { out.appendLine(padding) }
            out.append(answer("Before append"))
        }
        WorkLogs.read(RuntimeKind.ClaudeCode, file, home.path)
        val original = entry(file).value
        file.appendText(answer("After append"))
        WorkLogs.read(RuntimeKind.ClaudeCode, file, home.path)
        assertSame(original, entry(file).value)
    }

}
