package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class AgentRunFileRecoveryTest {
    @Test fun aWorkspaceOpensBesideEmptySplitUtf8AndIncompleteFirstSaves() {
        val root = Files.createTempDirectory("torn-run-workspace").toFile()
        val data = root.toPath().resolve("state")
        val runs = data.resolve("runs")
        Files.createDirectories(runs)
        val healthy = listOf(record("healthy-one"), record("healthy-two"))
        val recovered = record("split-utf8")
        val empty = digest("empty")
        val half = digest("half")
        healthy.forEach { Files.writeString(runs.resolve("${it.id}.json"), ConductorJson.encodeToString(AgentRunRecord.serializer(), it)) }
        Files.write(runs.resolve("$empty.json"), byteArrayOf())
        Files.write(runs.resolve("${recovered.id}.json"), (ConductorJson.encodeToString(AgentRunRecord.serializer(), recovered) + "\n{\"output\":\"").encodeToByteArray() + byteArrayOf(0xe7.toByte(), 0x95.toByte()))
        val encoded = ConductorJson.encodeToString(AgentRunRecord.serializer(), record("half"))
        Files.writeString(runs.resolve("$half.json"), encoded.take(encoded.length / 2))
        try {
            AgentWorkspace(root, data, emptyMap(), background = false, discover = { ProviderCapability(it) }).use { workspace ->
                assertEquals((healthy + recovered).map { it.id }.toSet(), workspace.list().map { it.id }.toSet())
                assertEquals(recovered, workspace.get(recovered.id))
                assertTrue(Files.exists(runs.resolve("$empty.torn")))
                assertTrue(Files.exists(runs.resolve("$half.torn")))
                assertTrue(Files.exists(runs.resolve("${recovered.id}.torn")))
                assertEquals(recovered, AgentRunTransfer.read(runs.resolve("${recovered.id}.json")))
            }
            AgentWorkspace(root, data, emptyMap(), background = false, discover = { ProviderCapability(it) }).use { workspace ->
                assertEquals(3, workspace.list().size)
            }
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }

    @Test fun corruptChildrenAndCompactionDoNotPreventHealthyRunsBeingRead() = runBlocking {
        val root = Files.createTempDirectory("torn-run-children").toFile()
        val data = root.toPath().resolve("state")
        try {
            AgentWorkspace(root, data, emptyMap(), background = false, discover = { ProviderCapability(it) }).use { workspace ->
                val parent = record("parent")
                val child = record("child").copy(parentRunId = parent.id)
                Files.writeString(data.resolve("runs/${parent.id}.json"), ConductorJson.encodeToString(AgentRunRecord.serializer(), parent))
                Files.writeString(data.resolve("runs/${child.id}.json"), ConductorJson.encodeToString(AgentRunRecord.serializer(), child))
                Files.writeString(data.resolve("runs/${digest("bad-child")}.json"), "{\"id\":")
                assertEquals(listOf(child), workspace.children(parent.id))
                workspace.compactActivity()
                assertEquals(parent, workspace.get(parent.id))
                assertTrue(Files.exists(data.resolve("runs/${digest("bad-child")}.torn")))
            }
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }

    @Test fun aCompleteRecordFromANewerVersionIsLeftUntouched() {
        val root = Files.createTempDirectory("newer-run-version").toFile()
        val data = root.toPath().resolve("state")
        val runs = data.resolve("runs")
        Files.createDirectories(runs)
        val newer = record("newer-version")
        val path = runs.resolve("${newer.id}.json")
        val text = ConductorJson.encodeToString(AgentRunRecord.serializer(), newer).replace("Completed", "FutureStatus") + "\n"
        Files.writeString(path, text)
        try {
            AgentWorkspace(root, data, emptyMap(), background = false, discover = { ProviderCapability(it) }).use { workspace ->
                assertTrue(workspace.list().isEmpty())
                assertTrue(Files.exists(path), "A complete record from a newer version must not be quarantined")
                assertEquals(text, Files.readString(path))
                assertFalse(Files.exists(runs.resolve("${newer.id}.torn")))
            }
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }

    @Test fun everyTornTailKeepsItsOwnQuarantineFile() {
        val root = Files.createTempDirectory("repeated-torn-tail").toFile()
        val data = root.toPath().resolve("state")
        val runs = data.resolve("runs")
        Files.createDirectories(runs)
        val saved = record("repeated-torn")
        val path = runs.resolve("${saved.id}.json")
        val first = runs.resolve("${saved.id}.torn")
        Files.writeString(first, "first torn sample")
        Files.writeString(path, ConductorJson.encodeToString(AgentRunRecord.serializer(), saved) + "\n{\"id\":")
        try {
            AgentWorkspace(root, data, emptyMap(), background = false, discover = { ProviderCapability(it) }).use { workspace ->
                assertEquals(saved, workspace.get(saved.id))
                assertEquals("first torn sample", Files.readString(first))
                assertTrue(Files.exists(runs.resolve("${saved.id}.torn.1")))
            }
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }

    @Test fun anUnreadableRunPathIsNeverQuarantined() {
        val root = Files.createTempDirectory("unreadable-run").toFile()
        val data = root.toPath().resolve("state")
        val path = data.resolve("runs/${digest("unreadable")}.json")
        Files.createDirectories(path)
        try {
            AgentWorkspace(root, data, emptyMap(), background = false, discover = { ProviderCapability(it) }).use { workspace ->
                assertTrue(workspace.list().isEmpty())
                assertTrue(Files.isDirectory(path))
                assertFalse(Files.exists(path.resolveSibling("${digest("unreadable")}.torn")))
            }
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }

    private fun record(key: String) = AgentRunRecord(digest(key), "thread-$key", RuntimeKind.Echo, "fixture", "Recovery fixture",
        status = AgentRunStatus.Completed, startedAt = 1, updatedAt = 1)
}
