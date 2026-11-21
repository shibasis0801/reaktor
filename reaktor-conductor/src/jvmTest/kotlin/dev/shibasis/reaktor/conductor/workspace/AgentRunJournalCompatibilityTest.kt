package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class AgentRunJournalCompatibilityTest {
    @Test fun aSaveAfterATornJournalCompactsBeforeAppendingAgain() = runBlocking {
        val root = Files.createTempDirectory("run-torn-append").toFile()
        val directory = root.toPath().resolve("state")
        val stopped = CoroutineScope(SupervisorJob().also { it.cancel() } + Dispatchers.IO)
        try {
            AgentWorkspace(root, directory, mapOf(RuntimeKind.Echo to EchoRuntime()), scope = stopped,
                background = false, discover = { ProviderCapability(it) }).use { workspace ->
                val first = workspace.submit(AgentSubmission("torn-append", RuntimeKind.Echo, "Keep the new complete save"))
                val path = directory.resolve("runs/${first.id}.json")
                val journal = path.resolveSibling("${first.id}.journal")
                Files.writeString(journal, "{\"runDelta\":")
                val next = first.copy(output = "The complete update after the torn tail", revision = first.revision + 1)
                val persist = AgentWorkspace::class.java.getDeclaredMethod("persist", AgentRunRecord::class.java).apply { isAccessible = true }
                persist.invoke(workspace, next)
                assertEquals(next, AgentRunTransfer.read(path))
                assertEquals(0, Files.size(journal))
            }
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }

    @Test fun aNewerBaseIgnoresOlderAndEqualJournalRevisions() = runBlocking {
        val root = Files.createTempDirectory("run-stale-journal").toFile()
        val stopped = CoroutineScope(SupervisorJob().also { it.cancel() } + Dispatchers.IO)
        try {
            AgentWorkspace(root, root.toPath().resolve("state"), mapOf(RuntimeKind.Echo to EchoRuntime()), scope = stopped,
                discover = { ProviderCapability(it) }).use { workspace ->
                val first = workspace.submit(AgentSubmission("stale-journal", RuntimeKind.Echo, "Replay only the later revision"))
                val path = root.toPath().resolve("state/runs/${first.id}.json")
                val records = (4L..10L).map { revision -> first.copy(output = "revision-$revision", revision = revision) }
                val base = records.single { it.revision == 9L }
                Files.writeString(path, ConductorJson.encodeToString(AgentRunRecord.serializer(), base) + "\n")
                val lines = records.zipWithNext().map { (before, after) ->
                    JsonObject(AgentRunTransfer.delta(after, AgentRunTransfer.held(before), Int.MAX_VALUE) + ("revision" to JsonPrimitive(after.revision))).toString()
                }
                val journal = path.resolveSibling("${first.id}.journal")
                Files.writeString(journal, lines.take(5).joinToString("\n", postfix = "\n"))
                assertEquals(base, AgentRunTransfer.read(path), "The equal revision must not replay")
                Files.writeString(journal, lines.joinToString("\n", postfix = "\n"))
                assertEquals(records.last(), AgentRunTransfer.read(path))
            }
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }

    @Test fun journalLinesWithoutAChangedHeaderReplayAllStreams() = runBlocking {
        val root = Files.createTempDirectory("run-journal-builders").toFile()
        val directory = root.toPath().resolve("state")
        val stopped = CoroutineScope(SupervisorJob().also { it.cancel() } + Dispatchers.IO)
        try {
            AgentWorkspace(root, directory, mapOf(RuntimeKind.Echo to EchoRuntime()), scope = stopped,
                background = false, discover = { ProviderCapability(it) }).use { workspace ->
                val first = workspace.submit(AgentSubmission("builders", RuntimeKind.Echo, "Replay each stream"))
                val path = directory.resolve("runs/${first.id}.json")
                Files.writeString(path, ConductorJson.encodeToString(AgentRunRecord.serializer(), first) + "\n")
                val journal = path.resolveSibling("${first.id}.journal")
                var previous = first
                repeat(600) { index ->
                    val next = first.copy(output = previous.output + "結果-$index ".repeat(40),
                        reasoning = previous.reasoning + "Check $index. ")
                    val delta = AgentRunTransfer.delta(next, AgentRunTransfer.held(previous), Int.MAX_VALUE)
                    Files.writeString(journal, JsonObject(delta - "record").toString() + "\n",
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
                    previous = next
                }
                assertEquals(previous, AgentRunTransfer.read(path))
            }
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }

    @Test fun repeatedSavesCompactTheJournalAndLeaveACompleteRecordAtTheEnd() = runBlocking {
        val root = Files.createTempDirectory("run-journal-compaction").toFile()
        val directory = root.toPath().resolve("state")
        val stopped = CoroutineScope(SupervisorJob().also { it.cancel() } + Dispatchers.IO)
        try {
            AgentWorkspace(root, directory, mapOf(RuntimeKind.Echo to EchoRuntime()), scope = stopped,
                background = false, discover = { ProviderCapability(it) }).use { workspace ->
                val first = workspace.submit(AgentSubmission("compaction", RuntimeKind.Echo, "Compact the journal"))
                val persist = AgentWorkspace::class.java.getDeclaredMethod("persist", AgentRunRecord::class.java).apply { isAccessible = true }
                var next = first
                repeat(130) { index ->
                    next = next.copy(output = next.output + "sample-$index ".repeat(1024), revision = next.revision + 1)
                    persist.invoke(workspace, next)
                }
                val path = directory.resolve("runs/${first.id}.json")
                val compacted = ConductorJson.decodeFromString(AgentRunRecord.serializer(), Files.readString(path))
                assertTrue(compacted.revision > first.revision)
                assertEquals(next, AgentRunTransfer.read(path))
                val journal = path.resolveSibling("${first.id}.journal")
                assertTrue(Files.readAllLines(journal).size < 64)
                next = next.copy(status = AgentRunStatus.Completed, revision = next.revision + 1)
                persist.invoke(workspace, next)
                assertEquals(next, ConductorJson.decodeFromString(AgentRunRecord.serializer(), Files.readString(path)))
                assertEquals(0, Files.size(journal))
            }
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }

    @Test fun oneSaveLeavesASingleCompleteRecordForThePreviousReader() = runBlocking {
        val root = Files.createTempDirectory("run-record-compatibility").toFile()
        val directory = root.toPath().resolve("state")
        val stopped = CoroutineScope(SupervisorJob().also { it.cancel() } + Dispatchers.IO)
        try {
            AgentWorkspace(root, directory, mapOf(RuntimeKind.Echo to EchoRuntime()), scope = stopped,
                background = false, discover = { ProviderCapability(it) }).use { workspace ->
                val saved = workspace.submit(AgentSubmission("compatibility", RuntimeKind.Echo, "Saved once"))
                val source = Files.readString(directory.resolve("runs/${saved.id}.json"))
                val record = ConductorJson.decodeFromString(AgentRunRecord.serializer(), source)
                assertEquals(saved.id, record.id)
                assertEquals(workspace.get(saved.id), AgentRunTransfer.read(directory.resolve("runs/${saved.id}.json")))
            }
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }
    @Test fun retainedSaveRecordsHaveAByteBoundAndEvictedRunsStillAppendCorrectly() = runBlocking {
        val root = Files.createTempDirectory("run-save-cache").toFile()
        val directory = root.toPath().resolve("state")
        val stopped = CoroutineScope(SupervisorJob().also { it.cancel() } + Dispatchers.IO)
        try {
            AgentWorkspace(root, directory, mapOf(RuntimeKind.Echo to EchoRuntime()), scope = stopped,
                background = false, discover = { ProviderCapability(it) }).use { workspace ->
                val persist = AgentWorkspace::class.java.getDeclaredMethod("persist", AgentRunRecord::class.java).apply { isAccessible = true }
                val records = List(10) { index ->
                    val run = workspace.submit(AgentSubmission("cache-$index", RuntimeKind.Echo, "Bound saved records $index"))
                    run.copy(output = "x".repeat(2_000_000), revision = run.revision + 1).also { persist.invoke(workspace, it) }
                }
                val field = AgentWorkspace::class.java.getDeclaredField("savedRuns").apply { isAccessible = true }
                val retained = field.get(workspace) as Map<*, *>
                assertTrue(retained.size < records.size, "Saved-run reuse must be bounded by answer bytes")
                val next = records.first().copy(output = records.first().output + "after eviction", revision = records.first().revision + 1)
                persist.invoke(workspace, next)
                assertEquals(next, AgentRunTransfer.read(directory.resolve("runs/${next.id}.json")))
            }
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }

}
