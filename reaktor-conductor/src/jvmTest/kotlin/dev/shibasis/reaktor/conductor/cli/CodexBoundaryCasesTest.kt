package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlin.test.*

class CodexBoundaryCasesTest {
    private val now = Instant.parse("2026-11-17T08:00:00Z").toEpochMilli()
    private val started = now - 60_000

    @Test fun appendedCompletionUpdatesTheBoundary() = fixture { file, read ->
        assertEquals(started, read().turnStartedAt)
        file.appendText(event("task_complete"))
        assertEquals(HarnessActivity.Waiting, read().activity)
        assertNull(read().turnStartedAt)
    }
    @Test fun replacementRebuildsTheBoundary() = fixture { file, read ->
        read()
        val next = file.parentFile.resolve("replacement")
        next.writeText(file.readLines().first() + "\n" + event("task_complete"))
        Files.move(next.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        assertEquals(HarnessActivity.Waiting, read().activity)
        assertNull(read().turnStartedAt)
    }
    @Test fun aShrinkRebuildsTheBoundary() = fixture { file, read ->
        file.appendText("{}\n".repeat(1000)); read()
        file.writeText(file.readLines().first() + "\n" + event("task_complete"))
        assertEquals(HarnessActivity.Waiting, read().activity)
        assertNull(read().turnStartedAt)
    }
    @Test fun anIncompleteBoundaryIsAppliedOnlyAfterItsNewline() = fixture { file, read ->
        read()
        val completion = event("task_complete")
        file.appendText(completion.take(completion.length / 2))
        assertEquals(started, read().turnStartedAt)
        file.appendText(completion.drop(completion.length / 2))
        assertEquals(HarnessActivity.Waiting, read().activity)
        assertNull(read().turnStartedAt)
    }
    @Test fun consecutiveWritesKeepTheLatestBoundary() = fixture { file, read ->
        read(); file.appendText(event("task_complete")); read()
        file.appendText(event("task_started"))
        assertEquals(HarnessActivity.Working, read().activity)
        assertEquals(started, read().turnStartedAt)
    }
    @Test fun multibyteTextBeforeABoundaryDoesNotChangeItsOffset() = fixture { file, read ->
        file.appendText("{\"text\":\"" + "界".repeat(30_000) + "\"}\n" + event("task_complete"))
        assertEquals(HarnessActivity.Waiting, read().activity)
        assertNull(read().turnStartedAt)
    }
    @Test fun anInPlaceGrowingRewriteDoesNotKeepTheOldBoundary() = fixture { file, read ->
        read()
        val meta = file.readLines().first().replace("fixture", "rewritten-fixture")
        file.writeText(meta + "\n" + event("task_complete") + "{}\n".repeat(100))
        assertEquals(HarnessActivity.Waiting, read().activity)
        assertNull(read().turnStartedAt)
    }
    @Test fun aGrowingRewriteWithTheSameMetadataDoesNotKeepTheOldBoundary() = fixture { file, read ->
        read()
        val meta = file.readLines().first()
        file.writeText(meta + "\n" + event("task_complete") + "{}\n".repeat(100))
        assertEquals(HarnessActivity.Waiting, read().activity)
        assertNull(read().turnStartedAt)
    }
    @Test fun aCompletionWithALongAnswerStillClosesItsTurn() = fixture { file, read ->
        file.appendText(event("task_complete", ",\"last_agent_message\":\"" + "x".repeat(130_000) + "\""))
        assertEquals(HarnessActivity.Waiting, read().activity)
        assertNull(read().turnStartedAt)
    }
    @Test fun simultaneousPassesKeepTheSameAppendedBoundary() = fixture { file, read ->
        read()
        file.appendText(event("task_complete"))
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val passes = pool.invokeAll(List(2) { java.util.concurrent.Callable { read() } })
            passes.forEach { assertEquals(HarnessActivity.Waiting, it.get().activity); assertNull(it.get().turnStartedAt) }
        } finally { pool.shutdownNow() }
    }
    @Test fun aBoundaryEvictedByOtherSessionsIsFoundAgain() = fixture { file, read ->
        val original = read()
        val processes = object : HarnessProcesses {
            override fun alive(pid: Long) = false
            override fun openTranscripts() = emptySet<String>()
        }
        repeat(2_049) { index ->
            val other = file.parentFile.resolve("rollout-evict-$index.jsonl")
            other.writeText(file.readText().replace("fixture", "evict-$index"))
            other.setLastModified(now - 2000)
            HarnessSessions.refresh(original.copy(id = "evict-$index", transcript = other.path), file.parentFile, now, processes)
        }
        file.appendText(event("task_complete"))
        assertEquals(HarnessActivity.Waiting, read().activity)
        assertNull(read().turnStartedAt)
    }
    @Test fun aBoundaryRewrittenInTheMiddleIsNotHiddenByAnAppend() = fixture { file, read ->
        val padding = "x".repeat(2000)
        file.appendText("""{"padding":"$padding"}""" + "\n" + event("task_started") + """{"padding":"$padding"}""" + "\n")
        assertEquals(HarnessActivity.Working, read().activity)
        val bytes = file.readBytes()
        val text = bytes.toString(Charsets.UTF_8)
        val offset = text.lastIndexOf("task_started")
        java.io.RandomAccessFile(file, "rw").use { input ->
            input.seek(offset.toLong())
            input.write("turn_aborted".toByteArray())
            input.seek(input.length())
            input.write("{}\n".toByteArray())
        }
        assertEquals(HarnessActivity.Waiting, read().activity)
    }

    private fun event(type: String, extra: String = "") = """{"timestamp":"${Instant.ofEpochMilli(started)}","type":"event_msg","payload":{"type":"$type","turn_id":"turn"$extra}}""" + "\n"
    private fun fixture(test: (File, () -> HarnessSession) -> Unit) {
        val home = Files.createTempDirectory("boundary-cases").toFile()
        val workspace = home.resolve("workspace").apply { mkdirs() }.canonicalFile
        val file = home.resolve(".codex/sessions/2026/11/17/rollout-fixture.jsonl").apply { parentFile.mkdirs() }
        file.writeText("""{"type":"session_meta","payload":{"id":"fixture","cwd":"${workspace.path}"}}""" + "\n" + event("task_started"))
        val processes = object : HarnessProcesses {
            override fun alive(pid: Long) = false
            override fun openTranscripts() = setOf(file.path)
        }
        try { test(file) { file.setLastModified(now - 1000); HarnessSessions.read(workspace, home, now, processes).first { it.transcript == file.path } } }
        finally { home.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }
}
