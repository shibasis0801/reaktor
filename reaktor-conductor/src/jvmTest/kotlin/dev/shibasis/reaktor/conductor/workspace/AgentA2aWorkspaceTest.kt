package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import dev.shibasis.reaktor.tooling.mcp.LoopbackMcpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class AgentA2aWorkspaceTest {
    @Test fun authenticatedTransportNegotiatesVersionAndReplaysOneNativeTask() = runBlocking {
        fixture { workspace, calls ->
            AgentWorkspaceOperations(workspace, agentWorkspaceTools(workspace)).use { operations ->
                val token = "p".repeat(32)
                LoopbackMcpServer.start(0, { operations }, token, post = operations::post,
                    read = operations::read, postWithHeaders = operations::peerPost).use { server ->
                    operations.endpointUrl = "http://127.0.0.1:${server.port()}"
                    HttpClient.newHttpClient().use { http ->
                        val endpoint = URI("${operations.endpointUrl}/a2a/claude")
                        val body = question("same-question")
                        fun send(auth: String = token, version: String? = "1.0"): HttpResponse<String> {
                            val builder = HttpRequest.newBuilder(endpoint).header("Authorization", "Bearer $auth")
                                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                            version?.let { builder.header("A2A-Version", it) }
                            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
                        }
                        assertEquals(401, send(auth = "wrong").statusCode())
                        assertEquals(-32009, Json.parseToJsonElement(send(version = null).body()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
                        assertEquals(0, calls.get())
                        val result = Json.parseToJsonElement(send().body()).jsonObject.getValue("result").jsonObject.getValue("task").jsonObject
                        assertEquals("TASK_STATE_COMPLETED", result.getValue("status").jsonObject.getValue("state").jsonPrimitive.content)
                        val replay = Json.parseToJsonElement(send().body()).jsonObject.getValue("result").jsonObject.getValue("task").jsonObject
                        assertEquals(result.getValue("id"), replay.getValue("id"))
                        assertEquals(1, calls.get())
                        val card = http.send(HttpRequest.newBuilder(URI("$endpoint/.well-known/agent-card.json"))
                            .header("Authorization", "Bearer $token").GET().build(), HttpResponse.BodyHandlers.ofString())
                        assertEquals(200, card.statusCode())
                        val document = Json.parseToJsonElement(card.body()).jsonObject
                        assertEquals(endpoint.toString(), document.getValue("supportedInterfaces").jsonArray.single().jsonObject.getValue("url").jsonPrimitive.content)
                        assertFalse(document.getValue("capabilities").jsonObject.getValue("streaming").jsonPrimitive.boolean)
                    }
                }
            }
        }
    }

    @Test fun profileRejectsCrossProviderTasksInvalidFieldsAndUnboundedDelegation() = runBlocking {
        fixture { workspace, calls ->
            val parent = complete(workspace, workspace.submit(AgentSubmission("parent", RuntimeKind.Codex, "Review parent")))
            val peer = workspace.peer
            for (index in 0..7) {
                val response = peer.exchange(RuntimeKind.ClaudeCode, question("child-$index", parent.id).toString(), "1.0")
                assertNull(response["error"], response.toString())
            }
            assertEquals(8, workspace.children(parent.id).size)
            assertNotNull(peer.exchange(RuntimeKind.ClaudeCode, question("ninth", parent.id).toString(), "1.0")["error"])
            val child = workspace.children(parent.id).first()
            assertNotNull(peer.exchange(RuntimeKind.Codex, question("recursive", child.id).toString(), "1.0")["error"])
            val foreign = peer.exchange(RuntimeKind.Codex, rpc("GetTask", buildJsonObject { put("id", child.id) }).toString(), "1.0")
            assertEquals(-32001, foreign.getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
            val completed = peer.exchange(RuntimeKind.ClaudeCode, rpc("CancelTask", buildJsonObject { put("id", child.id) }).toString(), "1.0")
            assertEquals(-32002, completed.getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
            assertEquals(9, calls.get())
            val invalid = question("invalid").let { request -> JsonObject(request + ("params" to JsonObject(request.getValue("params").jsonObject +
                ("configuration" to buildJsonObject { put("returnImmediately", "yes") })))) }
            assertNotNull(peer.exchange(RuntimeKind.ClaudeCode, invalid.toString(), "1.0")["error"])
            assertEquals(9, calls.get())
        }
    }

    private fun question(id: String, parent: String? = null) = rpc("SendMessage", buildJsonObject {
        putJsonObject("message") { put("messageId", id); put("role", "ROLE_USER"); putJsonArray("parts") { addJsonObject { put("text", "Review the scoped question") } } }
        if (parent != null) putJsonObject("metadata") { put("parentRunId", parent) }
    })
    private fun rpc(method: String, params: JsonObject) = buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", method); put("params", params) }
    private suspend fun complete(workspace: AgentWorkspace, submitted: AgentRunRecord): AgentRunRecord {
        var run = submitted
        while (run.status == AgentRunStatus.Running) run = workspace.awaitChange(run.id, run.revision, 30000)
        return run
    }
    private suspend fun fixture(block: suspend (AgentWorkspace, AtomicInteger) -> Unit) {
        val root = Files.createTempDirectory("peer-workspace").toRealPath()
        val state = Files.createTempDirectory("peer-state").toRealPath()
        val calls = AtomicInteger()
        try {
            AgentWorkspace(root.toFile(), state, listOf(RuntimeKind.Codex, RuntimeKind.ClaudeCode).associateWith { kind ->
                EchoRuntime(kind) { calls.incrementAndGet(); "Retained answer" }
            }, discover = { ProviderCapability(it) }).use { block(it, calls) }
        } finally {
            root.toFile().deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir")))
            state.toFile().deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir")))
        }
    }
}
