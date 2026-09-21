package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.cli.AntigravityRuntime
import dev.shibasis.reaktor.tooling.SupervisedProcessExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import java.io.File
import java.nio.file.Files
import org.junit.Assume.assumeTrue
import kotlin.test.*

class GeminiLiveTest {
    @Test fun cachedGoogleLoginReadsARealFileThroughAntigravity() = runBlocking {
        assumeTrue(System.getenv("REAKTOR_GEMINI_LIVE") == "1")
        val root = Files.createTempDirectory("gemini-live").toFile()
        File(root, "marker.txt").writeText("GEMINI_REAKTOR_" + java.util.UUID.randomUUID())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val events = SupervisedProcessExecutor(scope = scope).use { executor -> withTimeout(180000) { AntigravityRuntime(executor).run(AgentRequest(AgentSpec(AgentId("gemini"), "Gemini", RuntimeKind.Gemini, ""),
                "Use view_file to read ${root.canonicalPath}/marker.txt. Reply with its exact contents. Do not modify any file or search other directories.", root.path)).toList() } }
            val outcome = events.filterIsInstance<AgentEvent.Finished>().single().outcome
            assertTrue(outcome.ok, outcome.failure)
            assertTrue(outcome.text.contains(File(root, "marker.txt").readText()))
            assertTrue(events.filterIsInstance<AgentEvent.Activity>().any { it.item.kind == ActivityKind.Tool && it.item.status == ActivityStatus.Completed })
            println("Gemini via Antigravity qualified: session=${outcome.session?.sessionId}; usage=${outcome.usage}; completed tool events=${events.filterIsInstance<AgentEvent.Activity>().size}")
        } finally { scope.cancel(); root.deleteRecursively() }
        Unit
    }

    /**
     * The other half of the grant: an editing turn writes, and an inspecting turn is refused.
     *
     * Antigravity auto-allows writes inside the workspace, so the only thing standing between
     * "inspect" and an edited repository is the mode Reaktor asks for. Both run in the same
     * isolated checkout, because the claim worth testing is the difference between them.
     */
    @Test fun anEditingTurnWritesAndTheSameInspectingTurnIsRefused() = runBlocking {
        assumeTrue(System.getenv("REAKTOR_GEMINI_LIVE") == "1")
        val root = Files.createTempDirectory("gemini-write").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val target = File(root, "written.txt")
        fun request(allowWrites: Boolean) = AgentRequest(
            AgentSpec(AgentId("gemini"), "Gemini", RuntimeKind.Gemini, "", tools = ToolPolicy(allowWrites = allowWrites)),
            "Create ${target.canonicalPath} containing exactly the word OK, then stop.", root.path)
        try {
            SupervisedProcessExecutor(scope = scope).use { executor ->
                val runtime = AntigravityRuntime(executor)
                val edited = withTimeout(300000) { runtime.run(request(allowWrites = true)).toList() }
                    .filterIsInstance<AgentEvent.Finished>().single().outcome
                assertTrue(edited.ok, edited.failure)
                assertEquals("OK", target.readText().trim(), "An editing turn actually wrote the file")

                target.delete()
                val inspected = withTimeout(300000) { runtime.run(request(allowWrites = false)).toList() }
                    .filterIsInstance<AgentEvent.Finished>().single().outcome
                assertFalse(target.exists(), "An inspecting turn left the workspace alone")
                assertFalse(inspected.ok, "A refused effect is reported as incomplete, not as success")
                println("Antigravity modes qualified: edit wrote the file; inspect was refused with ${inspected.failure?.take(200)}")
            }
        } finally { scope.cancel(); root.deleteRecursively() }
        Unit
    }
}
