package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.mcp.*
import dev.shibasis.reaktor.tooling.mcp.LoopbackHttpResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.koin.dsl.koinApplication

internal class AgentWorkspaceOperations(workspace: AgentWorkspace, tools: List<McpTool>) : McpMessageHandler, AutoCloseable {
    private val root = workspace.info().workspaceRoot
    private val dependencies = koinApplication {}
    private val graph = Graph(dependencyAdapter = KoinDependencyAdapter(dependencies))
    private val node = ToolNode(graph, tools.map { tool ->
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
    private val adapter = McpAdapter(node, "reaktor-agent-workspace", "1.0.0",
        "Authenticated local workspace agent control. Source records, provider sessions and retrieved context are distinct. Do not resubmit an uncertain action automatically; inspect the saved run and workspace first.")

    override fun handle(body: String): JsonElement? = runBlocking { adapter.handle(body) }

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
        try { graph.close() } finally { dependencies.close() }
    }
}
