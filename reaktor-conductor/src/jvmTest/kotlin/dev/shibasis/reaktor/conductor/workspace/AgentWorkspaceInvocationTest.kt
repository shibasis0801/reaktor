package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.mcp.McpMessageHandler
import dev.shibasis.reaktor.mcp.McpTool
import dev.shibasis.reaktor.mcp.emptyObjectSchema
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import dev.shibasis.reaktor.tooling.mcp.LoopbackMcpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class AgentWorkspaceInvocationTest {
    @Test fun nativeClientWorksWithoutMcpAndSharesDeduplicationWithMcp() = runBlocking {
        fixture { root, directory, workspace, operations, calls ->
            val token = "n".repeat(32)
            val mcpCalls = AtomicInteger()
            LoopbackMcpServer.start(0, { McpMessageHandler { mcpCalls.incrementAndGet(); error("MCP is unavailable") } }, token,
                post = operations::post).use { server ->
                Files.writeString(directory.resolve("connection.json"), buildJsonObject {
                    put("version", 1); put("workspaceRoot", root.toString()); put("port", server.port()); put("token", token); put("invocationVersion", 1)
                }.toString())
                FileChannel.open(directory.resolve("owner.lock"), CREATE, WRITE).use { channel ->
                    channel.lock().use {
                        AgentWorkspaceConnection.open(root.toFile(), directory, allowStart = false).use { client ->
                            assertFalse(client.ownsService)
                            assertEquals(root.toString(), client.info().workspaceRoot)
                            val submission = AgentSubmission("same-request", RuntimeKind.Codex, "A bounded question")
                            val submitted = client.submit(submission)
                            var run = client.get(submitted.id)
                            while (run.status == AgentRunStatus.Running) run = client.wait(run.id, run.revision)
                            assertEquals(AgentRunStatus.Completed, run.status)
                            val replay = operations.handle(buildJsonObject {
                                put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
                                putJsonObject("params") {
                                    put("name", "agent_submit"); put("arguments", ConductorJson.encodeToJsonElement(AgentSubmission.serializer(), submission))
                                }
                            }.toString())!!.jsonObject.getValue("result").jsonObject.getValue("structuredContent").jsonObject
                            assertEquals(run.id, replay.getValue("id").jsonPrimitive.content)
                            assertEquals(run, client.get(run.id))
                            assertEquals(1, calls.get())
                            assertEquals(0, mcpCalls.get())
                            assertEquals(1, workspace.list().size)
                        }
                    }
                }
            }
        }
    }

    @Test fun nativeBoundaryRejectsInvalidScopeVersionAuthenticationAndShapeBeforeEffects() = runBlocking {
        fixture { root, _, workspace, operations, calls ->
            val token = "n".repeat(32)
            LoopbackMcpServer.start(0, { operations }, token, post = operations::post).use { server ->
                HttpClient.newHttpClient().use { client ->
                    val body = buildJsonObject {
                        put("version", 1); put("workspaceRoot", root.toString()); put("operation", "agent_submit")
                        put("arguments", ConductorJson.encodeToJsonElement(AgentSubmission.serializer(), AgentSubmission("invalid", RuntimeKind.Codex, "Must not run")))
                    }
                    fun send(value: String, credential: String = token): Int = client.send(
                        HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port()}/operations"))
                            .header("Authorization", "Bearer $credential").POST(HttpRequest.BodyPublishers.ofString(value)).build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode()
                    assertEquals(401, send(body.toString(), "wrong"))
                    for ((key, value) in mapOf("workspaceRoot" to JsonPrimitive("/another/workspace"), "version" to JsonPrimitive(2),
                        "arguments" to JsonArray(emptyList()), "unexpected" to JsonPrimitive(true))) {
                        assertEquals(400, send(JsonObject(body + (key to value)).toString()), key)
                    }
                    assertEquals(400, send("[]"))
                    assertEquals(413, send("x".repeat(1_048_577)))
                    assertEquals(0, calls.get())
                    assertTrue(workspace.list().isEmpty())
                }
            }
        }
    }

    @Test fun legacyOwnerUsesMcpAndUnsupportedNativeVersionDoesNotFallBack() = runBlocking {
        fixture { root, directory, _, operations, _ ->
            val token = "n".repeat(32)
            val mcpCalls = AtomicInteger()
            LoopbackMcpServer.start(0, { McpMessageHandler { mcpCalls.incrementAndGet(); operations.handle(it) } }, token).use { server ->
                fun discovery(invocationVersion: Int? = null) = Files.writeString(directory.resolve("connection.json"), buildJsonObject {
                    put("version", 1); put("workspaceRoot", root.toString()); put("port", server.port()); put("token", token)
                    invocationVersion?.let { put("invocationVersion", it) }
                }.toString())
                discovery()
                FileChannel.open(directory.resolve("owner.lock"), CREATE, WRITE).use { channel ->
                    channel.lock().use {
                        AgentWorkspaceConnection.open(root.toFile(), directory, allowStart = false).use { client ->
                            assertEquals(root.toString(), client.info().workspaceRoot)
                            assertEquals(1, mcpCalls.get())
                            discovery(2)
                            assertFailsWith<IllegalStateException> { client.info() }
                            assertEquals(1, mcpCalls.get())
                        }
                    }
                }
            }
        }
    }

    @Test fun rawResultContentErrorsAndOwnerShutdownArePreserved() = runBlocking {
        fixture { root, _, workspace, _, _ ->
            val content = buildJsonArray { addJsonObject { put("type", "image"); put("mimeType", "image/png"); put("data", "fixture") } }
            val data = buildJsonObject { put("artifact", "retained-image") }
            val tools = listOf(
                McpTool("rich_result", "Rich result fixture", emptyObjectSchema(), true, true, raw = true) {
                    buildJsonObject { put("structuredContent", data); put("content", content); put("isError", true) }
                },
                McpTool("failed_result", "Failure fixture", emptyObjectSchema(), true, true) { error("Fixture failure") },
            )
            AgentWorkspaceOperations(workspace, tools).use { operations ->
                val native = operations.post("/operations", buildJsonObject {
                    put("version", 1); put("workspaceRoot", root.toString()); put("operation", "rich_result"); put("arguments", buildJsonObject {})
                }.toString())!!.body.jsonObject
                fun mcp(name: String) = operations.handle(buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
                    putJsonObject("params") { put("name", name); put("arguments", buildJsonObject {}) }
                }.toString())!!.jsonObject
                val projected = mcp("rich_result").getValue("result").jsonObject
                assertEquals(data, native["data"])
                assertEquals(data, projected["structuredContent"])
                assertEquals(content, native["content"])
                assertEquals(content, projected["content"])
                assertEquals(JsonPrimitive(true), native["isError"])
                assertEquals(JsonPrimitive(true), projected["isError"])
                assertEquals(JsonPrimitive(true), mcp("failed_result").getValue("result").jsonObject["isError"])
                assertEquals(JsonPrimitive(-32602), mcp("unknown").getValue("error").jsonObject["code"])
                operations.close()
                assertFailsWith<IllegalStateException> { mcp("rich_result") }
            }
        }
    }

    private suspend fun fixture(block: suspend (java.nio.file.Path, java.nio.file.Path, AgentWorkspace, AgentWorkspaceOperations, AtomicInteger) -> Unit) {
        val root = Files.createTempDirectory("native-workspace-root").toRealPath()
        val directory = Files.createTempDirectory("native-workspace-state").toRealPath()
        val calls = AtomicInteger()
        try {
            AgentWorkspace(root.toFile(), directory, mapOf(RuntimeKind.Codex to EchoRuntime(RuntimeKind.Codex) {
                calls.incrementAndGet(); "Recorded answer"
            }), discover = { ProviderCapability(it) }).use { workspace ->
                AgentWorkspaceOperations(workspace, agentWorkspaceTools(workspace)).use { operations ->
                    block(root, directory, workspace, operations, calls)
                }
            }
        } finally {
            root.toFile().deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir")))
            directory.toFile().deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir")))
        }
    }
}
