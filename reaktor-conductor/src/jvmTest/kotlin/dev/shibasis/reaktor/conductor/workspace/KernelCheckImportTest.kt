package dev.shibasis.reaktor.conductor.workspace

import com.sun.net.httpserver.HttpServer
import dev.shibasis.reaktor.conductor.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.*

class KernelCheckImportTest {
    private fun git(root: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
    }

    @Test fun aCheckIsImportedFromTheKernelPortTheDiscoveryFileNames() = runBlocking {
        val root = Files.createTempDirectory("check-import-root").toFile()
        val directory = Files.createTempDirectory("check-import-state")
        val discovery = AgentWorkspaceConnection.defaultDirectory(root)
        var server: HttpServer? = null
        try {
            git(root, "init", "-q")
            File(root, "Source.kt").writeText("initial")
            git(root, "add", ".")
            git(root, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "initial")
            val stopped = CoroutineScope(SupervisorJob().also { it.cancel() } + Dispatchers.IO)
            AgentWorkspace(root, directory, mapOf(RuntimeKind.Echo to EchoRuntime()), scope = stopped).use { workspace ->
                val task = workspace.submit(AgentSubmission("check-import", RuntimeKind.Echo, "build it")).threadId
                val candidate = workspace.captureCandidate(task)
                val requested = mutableListOf<String>()
                server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                    createContext("/workspace/identity") { exchange ->
                        val bytes = buildJsonObject { put("workspaceRoot", root.canonicalPath) }.toString().toByteArray()
                        exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
                    }
                    createContext("/mcp") { exchange ->
                        val call = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject.getValue("params").jsonObject
                        requested += call.getValue("name").jsonPrimitive.content
                        val bytes = buildJsonObject {
                            put("jsonrpc", "2.0"); put("id", "check-import")
                            putJsonObject("result") { putJsonObject("structuredContent") {
                                put("taskId", "build")
                                put("result", AgentWorkspaceJson.encodeToJsonElement(CheckResult.serializer(),
                                    CheckResult(listOf("build"), CheckOutcome.Passed, revision = candidate.id)))
                            } }
                        }.toString().toByteArray()
                        exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
                    }
                    start()
                }
                Files.createDirectories(discovery)
                Files.writeString(discovery.resolve("graph-connection.json"), buildJsonObject {
                    put("workspaceRoot", root.canonicalPath); put("url", "http://127.0.0.1:${server!!.address.port}/mcp")
                }.toString())
                val imported = workspace.collectKernelCheck(task, "run-1")
                assertEquals(listOf("check_result"), requested)
                assertEquals(listOf("build" to candidate.id), imported.checks.map { it.id to it.candidateId })
                assertEquals(CheckOutcome.Passed, imported.checks.single().result.outcome)
            }
        } finally {
            server?.stop(0)
            root.deleteRecursively(); directory.toFile().deleteRecursively(); discovery.toFile().deleteRecursively()
        }
    }
}
