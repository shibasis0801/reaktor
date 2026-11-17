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

    private fun record(key: String) = AgentRunRecord(digest(key), "thread-$key", RuntimeKind.Echo, "fixture", "Recovery fixture",
        status = AgentRunStatus.Completed, startedAt = 1, updatedAt = 1)
}
