package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.served
import dev.shibasis.reaktor.service.HttpMethod
import dev.shibasis.reaktor.service.contract.*
import dev.shibasis.reaktor.tooling.CallCaller
import dev.shibasis.reaktor.tooling.kernel.KernelApi
import dev.shibasis.reaktor.tooling.operation.OperationGate
import dev.shibasis.reaktor.tooling.operation.OperationJson
import dev.shibasis.reaktor.tooling.operation.Outcome
import java.util.Base64
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.mcp.*
import dev.shibasis.reaktor.tooling.mcp.LoopbackHttpResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.koin.dsl.koinApplication

internal class AgentWorkspaceOperations(private val workspace: AgentWorkspace, tools: List<McpTool>, owner: Graph? = null, projection: Set<String>? = null, directory: java.nio.file.Path = AgentWorkspaceConnection.defaultDirectory(java.io.File(workspace.info().workspaceRoot))) : McpMessageHandler, AutoCloseable {
    private val root = workspace.info().workspaceRoot
    var endpointUrl: String? = null
    var incarnation: String? = null
    private val dependencies = if (owner == null) koinApplication {} else null
    private val graph = owner ?: Graph(dependencyAdapter = KoinDependencyAdapter(requireNotNull(dependencies)))
    val browser = AgentBrowserMcp(directory, graph)
    val node = ToolNode(graph, tools.map { tool ->
        Tool(ToolDefinition(tool.name, tool.description, tool.inputSchema,
            readOnly = tool.readOnly, idempotent = tool.idempotent, destructive = tool.destructive, openWorld = tool.openWorld)) { arguments ->
            val value = tool.execute(arguments)
            if (tool.raw && value is JsonObject) ToolResult(
                value["structuredContent"] ?: buildJsonObject {},
                value["isError"]?.jsonPrimitive?.booleanOrNull == true,
                value["content"] as? JsonArray,
            ) else ToolResult(value)
        }
    }).also(graph::attach)
    private val runtime = TaskGraphRuntime(workspace, graph).also { workspace.graphs = it }
    private val adapter = McpAdapter(node, "reaktor-agent-workspace", "1.0.0",
        "Authenticated local workspace agent control. Source records, provider sessions and retrieved context are distinct. Do not resubmit an uncertain action automatically; inspect the saved run and workspace first.", projection = projection)

    override fun handle(body: String): JsonElement? = runBlocking { adapter.handle(body) }

    fun peerPost(path: String, body: String, headers: Map<String, List<String>>): LoopbackHttpResponse? {
        contractPost(path, body, headers)?.let { return it }
        val provider = peerProvider(path) ?: return null
        return LoopbackHttpResponse(runBlocking { workspace.peer.exchange(provider, body, headers["a2a-version"]?.singleOrNull()) })
    }

    @Suppress("UNCHECKED_CAST")
    private fun contractPost(path: String, body: String, headers: Map<String, List<String>>): LoopbackHttpResponse? {
        val served = graph.served().firstOrNull { it.find(HttpMethod.POST, path) != null } ?: return null
        fun response(outbound: Outbound) = LoopbackHttpResponse(Json.parseToJsonElement(outbound.body), outbound.status, outbound.headers)
        if (headers[KernelApi.WorkspaceHeader]?.singleOrNull() != root)
            return response(Served.failure(409, "The contract belongs to a different workspace"))
        val caller = runCatching {
            val encoded = requireNotNull(headers[KernelApi.CallerHeader]?.singleOrNull())
            require(encoded.length <= 16384)
            OperationJson.decodeFromString(CallCaller.serializer(), Base64.getUrlDecoder().decode(encoded).toString(Charsets.UTF_8))
        }.getOrElse { return response(Served.failure(403, "Authenticated caller required")) }
        val (operation, parameters) = requireNotNull(served.find(HttpMethod.POST, path))
        val fingerprint = headers[Metadata.Contract]?.singleOrNull()
        val handshake = served.service.id == KernelApi.Id && operation.name == "hello"
        if (fingerprint != "${served.service.id}@${served.service.fingerprint}" &&
            !(handshake && fingerprint?.startsWith("${KernelApi.Id}@") == true))
            return response(Served.failure(409, "The owner contract changed; reconnect before calling ${operation.id}",
                OperationJson.encodeToJsonElement(Outcome.serializer(), Outcome.Failed("Owner contract changed", "contract-drift"))))
        val attributes = buildMap<String, Any?> {
            put(OperationGate.CallerAttribute, caller)
            put(KernelApi.IncarnationAttribute, requireNotNull(incarnation))
            headers[Metadata.IdempotencyKey]?.singleOrNull()?.let { put(OperationGate.RequestIdAttribute, it) }
        }
        val inbound = Inbound(pathParams = parameters, headers = headers.filterKeys { !it.equals("Authorization", ignoreCase = true) }.mapValues { it.value.joinToString(", ") },
            body = body, attributes = attributes)
        val call = operation as Call<Any?, Any?>
        try { call.binding.decode(call.request, inbound, OperationJson) }
        catch (invalid: Exception) {
            return response(Served.failure(400, "Invalid arguments for ${call.id}: ${invalid.message}",
                OperationJson.encodeToJsonElement(Outcome.serializer(), Outcome.Failed("Invalid arguments for ${call.id}: ${invalid.message}", "invalid-arguments"))))
        }
        return response(runBlocking { served.dispatch(HttpMethod.POST, path, inbound) })
    }

    fun read(path: String, query: Map<String, String>): LoopbackHttpResponse? {
        if (!path.endsWith("/.well-known/agent-card.json") || query.isNotEmpty()) return null
        val prefix = path.removeSuffix("/.well-known/agent-card.json")
        val provider = peerProvider(prefix) ?: return null
        if (provider !in workspace.info().providers) return null
        return LoopbackHttpResponse(workspace.peer.card(provider, requireNotNull(endpointUrl) + prefix))
    }

    private fun peerProvider(path: String) = when (path) {
        "/a2a/codex" -> dev.shibasis.reaktor.conductor.RuntimeKind.Codex
        "/a2a/claude" -> dev.shibasis.reaktor.conductor.RuntimeKind.ClaudeCode
        else -> null
    }

    fun post(path: String, body: String): LoopbackHttpResponse? {
        if (path != "/operations") return null
        val request = runCatching {
            val value = Json.parseToJsonElement(body).jsonObject
            require(value.keys == setOf("version", "workspaceRoot", "operation", "arguments")) { "Invalid operation request fields" }
            require(value["version"] == JsonPrimitive(1)) { "Unsupported invocation version" }
            require(value["workspaceRoot"] == JsonPrimitive(root)) { "Operation belongs to a different workspace" }
            require(value["operation"] is JsonPrimitive && value.getValue("operation").jsonPrimitive.isString) { "Operation must be a string" }
            require(value["arguments"] is JsonObject) { "Operation arguments must be an object" }
            value
        }.getOrElse { return LoopbackHttpResponse(buildJsonObject { put("error", it.message ?: "Invalid operation request") }, 400) }
        val result = runBlocking { adapter.execute(request.getValue("operation").jsonPrimitive.content, request.getValue("arguments").jsonObject) }
        return LoopbackHttpResponse(buildJsonObject {
            put("data", result.data as? JsonObject ?: buildJsonObject { put("result", result.data) })
            put("isError", result.isError)
            result.content?.let { put("content", it) }
        })
    }

    override fun close() {
        try { if (workspace.graphs === runtime) workspace.graphs = null; runtime.close(); browser.close(); adapter.close(); graph.detach(node) }
        finally { if (dependencies != null) try { graph.close() } finally { dependencies.close() } }
    }
}
