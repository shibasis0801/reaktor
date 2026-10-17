package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import dev.shibasis.reaktor.conductor.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class KernelCheckImportTest {
    private fun git(root: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
    }

    @Test fun importedChecksRequireTheExactCapturedCandidate() = runBlocking {
        val root = Files.createTempDirectory("check-import-root").toFile()
        val directory = Files.createTempDirectory("check-import-state")
        try {
            git(root, "init", "-q")
            File(root, "Source.kt").writeText("initial")
            git(root, "add", ".")
            git(root, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "initial")
            val stopped = CoroutineScope(SupervisorJob().also { it.cancel() } + Dispatchers.IO)
            var candidateId = ""
            val requested = mutableListOf<String>()
            AgentWorkspace(root, directory, mapOf(RuntimeKind.Echo to EchoRuntime()), scope = stopped,
                kernelRead = { name, arguments ->
                    assertEquals("run-1", arguments.getValue("runId").jsonPrimitive.content)
                    requested += name
                    buildJsonObject {
                        put("taskId", "build")
                        put("result", ConductorJson.encodeToJsonElement(CheckResult.serializer(),
                            CheckResult(listOf("build"), CheckOutcome.Passed, revision = candidateId)))
                    }
                }).use { workspace ->
                val task = workspace.submit(AgentSubmission("check-import", RuntimeKind.Echo, "build it")).threadId
                candidateId = workspace.captureCandidate(task).id
                val imported = workspace.collectKernelCheck(task, "run-1")
                assertEquals(listOf("check_result"), requested)
                assertEquals(listOf("build" to candidateId), imported.checks.map { it.id to it.candidateId })
                assertEquals(CheckOutcome.Passed, imported.checks.single().result.outcome)
                candidateId = "unknown-candidate"
                assertFailsWith<IllegalArgumentException> { workspace.collectKernelCheck(task, "run-1") }
                assertEquals(1, workspace.taskEvidence(task).checks.size)
            }
        } finally {
            root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
            directory.toFile().deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }
}
