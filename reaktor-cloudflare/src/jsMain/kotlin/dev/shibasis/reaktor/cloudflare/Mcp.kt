package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.core.framework.json
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlinx.serialization.json.*

/**
 * MCP (Model Context Protocol) server framework for Cloudflare Workers.
 *
 * Implements the Streamable HTTP transport (POST-based JSON-RPC).
 * Compatible with Claude Desktop, Cursor, and any MCP-compliant client.
 */

// ── Tool Schema Builder ─────────────────────────────────────────────

class McpToolSchema(val name: String, val description: String) {
    private val properties = mutableMapOf<String, JsonObject>()
    private val required = mutableListOf<String>()

    fun string(name: String, description: String, required: Boolean = false) {
        properties[name] = buildJsonObject {
            put("type", "string")
            put("description", description)
        }
        if (required) this.required.add(name)
    }

    fun enum(name: String, description: String, values: List<String>, required: Boolean = false) {
        properties[name] = buildJsonObject {
            put("type", "string")
            put("description", description)
            putJsonArray("enum") { values.forEach(::add) }
        }
        if (required) this.required.add(name)
    }

    fun integer(name: String, description: String, required: Boolean = false) {
        properties[name] = buildJsonObject {
            put("type", "integer")
            put("description", description)
        }
        if (required) this.required.add(name)
    }

    fun boolean(name: String, description: String, required: Boolean = false) {
        properties[name] = buildJsonObject {
            put("type", "boolean")
            put("description", description)
        }
        if (required) this.required.add(name)
    }

    fun array(name: String, description: String, itemType: String = "string", required: Boolean = false) {
        properties[name] = buildJsonObject {
            put("type", "array")
            put("description", description)
            putJsonObject("items") { put("type", itemType) }
        }
        if (required) this.required.add(name)
    }

    internal fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            properties.forEach { (key, value) -> put(key, value) }
        }
        if (required.isNotEmpty()) {
            putJsonArray("required") { required.forEach(::add) }
        }
    }
}

// ── Tool Result ─────────────────────────────────────────────────────

sealed class McpToolResult {
    class Text(val text: String) : McpToolResult()
    class Error(val message: String, val text: String? = null) : McpToolResult()

    internal fun toJson(): JsonObject = when (this) {
        is Text -> buildJsonObject {
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            }
        }
        is Error -> buildJsonObject {
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", text ?: message)
                }
            }
            put("isError", true)
        }
    }
}

fun mcpText(text: String) = McpToolResult.Text(text)

fun mcpJson(value: JsonElement) = McpToolResult.Text(json.encodeToString(value))

fun mcpError(message: String) = McpToolResult.Error(message)

// ── Tool Context (available to handlers) ────────────────────────────

class McpToolContext(
    val arguments: JsonObject,
    val cloudflare: CloudflareContext?,
) {
    fun string(name: String): String? =
        arguments[name]?.jsonPrimitive?.contentOrNull

    fun stringRequired(name: String): String =
        string(name) ?: error("Missing required argument: $name")

    fun int(name: String): Int? =
        arguments[name]?.jsonPrimitive?.intOrNull

    fun boolean(name: String): Boolean? =
        arguments[name]?.jsonPrimitive?.booleanOrNull

    fun stringList(name: String): List<String>? =
        arguments[name]?.jsonArray?.map { it.jsonPrimitive.content }
}

typealias McpToolHandler = suspend McpToolContext.() -> McpToolResult

// ── Server ──────────────────────────────────────────────────────────

private data class RegisteredTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val handler: McpToolHandler,
)

class McpServer(
    private val name: String,
    private val version: String,
    private val instructions: String? = null,
) {
    private val tools = mutableListOf<RegisteredTool>()

    fun tool(
        name: String,
        description: String,
        schema: McpToolSchema.() -> Unit = {},
        handler: McpToolHandler,
    ) {
        val builder = McpToolSchema(name, description)
        builder.schema()
        tools.add(RegisteredTool(name, description, builder.build(), handler))
    }

    /**
     * Handle a single JSON-RPC request body and return the response body.
     * Returns empty string for notifications (no id).
     */
    suspend fun handleRequest(body: String, context: CloudflareContext? = null): String {
        val element = runCatching { json.parseToJsonElement(body) }.getOrElse {
            return jsonRpcError(null, -32700, "Parse error: ${it.message}")
        }

        // Handle batch requests
        if (element is JsonArray) {
            val responses = element.mapNotNull { handleSingle(it.jsonObject, context) }
            return if (responses.isEmpty()) "" else JsonArray(responses).toString()
        }

        val response = handleSingle(element.jsonObject, context) ?: return ""
        return response.toString()
    }

    private suspend fun handleSingle(request: JsonObject, context: CloudflareContext?): JsonObject? {
        val id = request["id"]
        val method = request["method"]?.jsonPrimitive?.contentOrNull
        val params = request["params"]?.jsonObject ?: JsonObject(emptyMap())

        if (method == null) {
            return jsonRpcErrorObject(id, -32600, "Invalid request: missing method")
        }

        // Notifications have no id — don't respond
        if (id == null || id is JsonNull) {
            // Process notification silently (e.g., notifications/initialized)
            return null
        }

        val result = when (method) {
            "initialize" -> handleInitialize()
            "tools/list" -> handleToolsList()
            "tools/call" -> handleToolsCall(params, context)
            "ping" -> buildJsonObject {}
            else -> return jsonRpcErrorObject(id, -32601, "Method not found: $method")
        }

        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }
    }

    private fun handleInitialize(): JsonObject = buildJsonObject {
        put("protocolVersion", "2025-06-18")
        putJsonObject("capabilities") {
            putJsonObject("tools") {
                put("listChanged", false)
            }
        }
        putJsonObject("serverInfo") {
            put("name", name)
            put("version", version)
        }
        if (instructions != null) {
            put("instructions", instructions)
        }
    }

    private fun handleToolsList(): JsonObject = buildJsonObject {
        putJsonArray("tools") {
            for (tool in tools) {
                addJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("inputSchema", tool.inputSchema)
                }
            }
        }
    }

    private suspend fun handleToolsCall(params: JsonObject, context: CloudflareContext?): JsonObject {
        val toolName = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return buildJsonObject {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", "Missing tool name")
                    }
                }
                put("isError", true)
            }

        val arguments = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())

        val tool = tools.find { it.name == toolName }
            ?: return buildJsonObject {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", "Unknown tool: $toolName")
                    }
                }
                put("isError", true)
            }

        val result = runCatching {
            val ctx = McpToolContext(arguments, context)
            tool.handler(ctx)
        }.getOrElse { error ->
            McpToolResult.Error("Tool execution failed: ${error.message}")
        }

        return result.toJson()
    }
}

// ── JSON-RPC Helpers ────────────────────────────────────────────────

private fun jsonRpcError(id: JsonElement?, code: Int, message: String): String =
    jsonRpcErrorObject(id, code, message).toString()

private fun jsonRpcErrorObject(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id ?: JsonNull)
    putJsonObject("error") {
        put("code", code)
        put("message", message)
    }
}

// ── Hono Integration ────────────────────────────────────────────────

@OptIn(DelicateCoroutinesApi::class)
fun Hono.mcp(path: String, server: McpServer): Hono {
    // POST handles JSON-RPC requests (initialize, tools/list, tools/call)
    on("POST", path) { honoContext ->
        GlobalScope.promise {
            val body = runCatching { honoContext.req.text().await() }
                .getOrNull().orEmpty().ifBlank { "{}" }
            val cloudflareContext = CloudflareContext(honoContext.env, honoContext.executionCtx, honoContext)
            val response = server.handleRequest(body, cloudflareContext)

            if (response.isBlank()) {
                workerResponse(body = "", status = 202, headers = mcpHeaders())
            } else {
                workerResponse(
                    body = response,
                    status = 200,
                    headers = mcpHeaders("application/json"),
                )
            }
        }
    }

    // GET returns SSE stream (for server-initiated messages). We return 405 for now.
    on("GET", path) { _ ->
        workerResponse(
            body = """{"error":"SSE not supported. Use POST for JSON-RPC."}""",
            status = 405,
            headers = mcpHeaders("application/json"),
        )
    }

    // OPTIONS for CORS
    on("OPTIONS", path) { _ ->
        workerResponse(body = "", status = 204, headers = mcpHeaders())
    }

    return this
}

private fun mcpHeaders(contentType: String? = null): Map<String, String> = buildMap {
    if (contentType != null) put("Content-Type", contentType)
    put("Access-Control-Allow-Origin", "*")
    put("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    put("Access-Control-Allow-Headers", "Content-Type, Authorization, Mcp-Session-Id")
    put("Access-Control-Expose-Headers", "Mcp-Session-Id")
}
