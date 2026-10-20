package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.dependeasy.model.BuildIdentity
import dev.shibasis.dependeasy.model.BuildModel
import dev.shibasis.dependeasy.model.BuildModelReader
import dev.shibasis.dependeasy.model.Producer
import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.cli.antigravityArgv
import dev.shibasis.reaktor.conductor.cli.claudeArgv
import dev.shibasis.reaktor.conductor.cli.codexArgv
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IncludedBuildsTest {
    @Test fun includedBuildsOutsideTheWorkspaceCollapseToTheirOutermostRoots() = temporary { parent ->
        val root = parent.resolve("app").apply { mkdirs() }
        listOf("framework/tools", "app/inner").forEach { parent.resolve(it).mkdirs() }
        declare(root, ".", "../framework", "../framework/tools", "inner", "../missing")
        assertEquals(listOf(parent.resolve("framework").canonicalPath), includedBuildRoots(root))
        assertEquals(emptyList(), includedBuildRoots(parent.resolve("framework")), "A build with no exported model names no extra roots")
    }

    @Test fun everyHarnessTurnIsGivenTheIncludedBuilds() = temporary { parent ->
        val root = parent.resolve("app").apply { mkdirs() }
        val framework = parent.resolve("framework").apply { mkdirs() }.canonicalPath
        declare(root, ".", "../framework")
        val harness = Recording(RuntimeKind.ClaudeCode)
        AgentWorkspaceConnection.open(root, parent.resolve("state").toPath(), mapOf(harness.kind to harness)).use { owner ->
            for (allowWrites in listOf(false, true)) {
                owner.finish(owner.submit(AgentSubmission("included-$allowWrites", harness.kind, "Read the framework sources", allowWrites = allowWrites)))
            }
        }
        assertEquals(2, harness.requests.size)
        harness.requests.forEach { request ->
            assertEquals(listOf(framework), request.agent.tools.additionalDirectories)
            listOf(claudeArgv(request), codexArgv(request), antigravityArgv(request)).forEach { argv ->
                assertTrue(argv.windowed(2).any { it == listOf("--add-dir", framework) }, argv.joinToString(" "))
            }
        }
    }

    private fun temporary(test: suspend (File) -> Unit) = runBlocking {
        val parent = Files.createTempDirectory("included-builds").toFile()
        try { test(parent) } finally { parent.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    private fun declare(root: File, vararg directories: String) = root.resolve(BuildModelReader.RelativePath).apply { parentFile.mkdirs() }
        .writeText(BuildModelReader.encode(BuildModel(producer = Producer(version = "fixture"),
            builds = directories.mapIndexed { index, directory -> BuildIdentity("build-$index", directory) }, nodes = emptyList())))

    private suspend fun AgentWorkspaceConnection.finish(run: AgentRunRecord): AgentRunRecord = withTimeout(15000) {
        var record = run
        while (record.status == AgentRunStatus.Running) record = wait(record.id, record.revision, 5000)
        record
    }

    private class Recording(override val kind: RuntimeKind) : AgentRuntime {
        val requests = CopyOnWriteArrayList<AgentRequest>()
        override fun run(request: AgentRequest) = flow {
            requests.add(request)
            emit(AgentEvent.Started(request.agent.id, null))
            emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "read", true)))
        }
    }
}
