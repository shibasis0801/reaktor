package dev.shibasis.reaktor.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

const val REAKTOR_MCP_PROTOCOL_VERSION: String = "2025-11-25"
val REAKTOR_MCP_PROTOCOL_VERSIONS: Set<String> = setOf("2025-06-18", REAKTOR_MCP_PROTOCOL_VERSION)

data class McpReadTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject = emptyObjectSchema(),
    val execute: (JsonObject) -> JsonElement,
)

data class McpReadResource(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String = "application/json",
    val read: () -> String,
)

fun interface McpMessageHandler {
    fun handle(body: String): JsonElement?
}

/** Effectful tools require an authenticated host and explicit effect annotations. */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val readOnly: Boolean,
    val idempotent: Boolean,
    val destructive: Boolean = false,
    val openWorld: Boolean = false,
    val execute: (JsonObject) -> JsonElement,
)

/**
 * Transport-independent, read-only MCP registry shared by Desktop and JVM hosts.
 *
 * Mutations deliberately do not fit this API. An operation host may expose a planning tool, but
 * execution and approval must remain inside its control-plane policy boundary.
 */
class ReaktorMcpReadServer(
    name: String,
    version: String,
    instructions: String,
    tools: List<McpReadTool>,
    resources: List<McpReadResource> = emptyList(),
) : McpMessageHandler {
    private val server = ReaktorMcpServer(name, version, instructions, tools.map {
        McpTool(it.name, it.description, it.inputSchema, readOnly = true, idempotent = true, execute = it.execute)
    }, resources)
    override fun handle(body: String): JsonElement? = server.handle(body)
}

class ReaktorMcpServer(
    private val name: String,
    private val version: String,
    private val instructions: String,
    tools: List<McpTool>,
    resources: List<McpReadResource> = emptyList(),
) : McpMessageHandler {
    private val json = Json { encodeDefaults = true; explicitNulls = false; prettyPrint = true }
    private val tools = tools.associateBy(McpTool::name)
    private val resources = resources.associateBy(McpReadResource::uri)

    init { require(this.tools.size == tools.size) { "Duplicate tool names" } }

    /**
     * What this server actually serves.
     *
     * A prompt that names a tool the server does not expose costs the model a failed call and then
     * a detour around a capability it was promised. Generating that claim from here instead of
     * writing it out keeps the two from drifting apart.
     */
    val toolNames: List<String> get() = this.tools.keys.toList()

    override fun handle(body: String): JsonElement? {
        val parsed = runCatching { Json.parseToJsonElement(body) }.getOrNull()
            ?: return rpcError(JsonNull, -32700, "Parse error")
        return when (parsed) {
            is JsonArray -> if (parsed.isEmpty()) {
                rpcError(JsonNull, -32600, "Invalid Request: empty batch")
            } else {
                parsed.mapNotNull(::handleSingle).let { if (it.isEmpty()) null else JsonArray(it) }
            }
            else -> handleSingle(parsed)
        }
    }

    private fun handleSingle(message: JsonElement): JsonElement? {
        val request = message as? JsonObject ?: return rpcError(JsonNull, -32600, "Invalid Request")
        val id = request["id"]
        val responseId = id?.takeUnless { it is JsonNull }
        val method = (request["method"] as? JsonPrimitive)?.contentOrNull
            ?: return responseId?.let { rpcError(it, -32600, "Invalid Request: missing method") }
        if (responseId == null) return null
        return when (method) {
            "initialize" -> initializeRequest(responseId, request["params"] as? JsonObject)
            "tools/list" -> rpcResult(responseId, listTools())
            "tools/call" -> callTool(responseId, request["params"] as? JsonObject)
            "resources/list" -> rpcResult(responseId, listResources())
            "resources/read" -> readResource(responseId, request["params"] as? JsonObject)
            "ping" -> rpcResult(responseId, buildJsonObject {})
            else -> rpcError(responseId, -32601, "Method not found: $method")
        }
    }

    private fun initializeRequest(id: JsonElement, params: JsonObject?): JsonObject {
        val requested = (params?.get("protocolVersion") as? JsonPrimitive)?.contentOrNull
            ?: return rpcError(id, -32602, "Missing protocolVersion")
        return rpcResult(id, initialize(requested.takeIf { it in REAKTOR_MCP_PROTOCOL_VERSIONS } ?: REAKTOR_MCP_PROTOCOL_VERSION))
    }

    private fun initialize(protocolVersion: String): JsonObject = buildJsonObject {
        put("protocolVersion", protocolVersion)
        putJsonObject("capabilities") {
            putJsonObject("tools") { put("listChanged", false) }
            putJsonObject("resources") { put("listChanged", false) }
        }
        putJsonObject("serverInfo") {
            put("name", name)
            put("version", version)
        }
        put("instructions", instructions)
    }

    private fun listTools(): JsonObject = buildJsonObject {
        putJsonArray("tools") {
            tools.values.forEach { tool ->
                addJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("inputSchema", tool.inputSchema)
                    putJsonObject("annotations") {
                        put("readOnlyHint", tool.readOnly)
                        put("destructiveHint", tool.destructive)
                        put("idempotentHint", tool.idempotent)
                        put("openWorldHint", tool.openWorld)
                    }
                }
            }
        }
    }

    private fun callTool(id: JsonElement, params: JsonObject?): JsonObject {
        val toolName = (params?.get("name") as? JsonPrimitive)?.contentOrNull
            ?: return rpcError(id, -32602, "Missing tool name")
        val tool = tools[toolName] ?: return rpcError(id, -32602, "Unknown tool: $toolName")
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val result = runCatching { tool.execute(arguments) }
            .getOrElse { error -> return toolText(id, error.message ?: "Tool failed", isError = true) }
        return rpcResult(id, buildJsonObject {
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", json.encodeToString(JsonElement.serializer(), result))
                }
            }
            put("structuredContent", result as? JsonObject ?: buildJsonObject { put("result", result) })
            put("isError", false)
        })
    }

    private fun listResources(): JsonObject = buildJsonObject {
        putJsonArray("resources") {
            resources.values.forEach { resource ->
                addJsonObject {
                    put("uri", resource.uri)
                    put("name", resource.name)
                    put("description", resource.description)
                    put("mimeType", resource.mimeType)
                }
            }
        }
    }

    private fun readResource(id: JsonElement, params: JsonObject?): JsonObject {
        val uri = (params?.get("uri") as? JsonPrimitive)?.contentOrNull
            ?: return rpcError(id, -32602, "Missing resource URI")
        val resource = resources[uri] ?: return rpcError(id, -32602, "Unknown resource: $uri")
        val text = runCatching(resource.read)
            .getOrElse { error -> return rpcError(id, -32603, error.message ?: "Resource read failed") }
        return rpcResult(id, buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("uri", resource.uri)
                    put("mimeType", resource.mimeType)
                    put("text", text)
                }
            }
        })
    }

    private fun rpcResult(id: JsonElement, result: JsonElement): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun rpcError(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

    private fun toolText(id: JsonElement, text: String, isError: Boolean): JsonObject =
        rpcResult(id, buildJsonObject {
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            }
            put("isError", isError)
        })
}

fun objectSchema(
    properties: Map<String, JsonObject>,
    required: List<String> = emptyList(),
): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") { properties.forEach { (key, value) -> put(key, value) } }
    if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it) } }
    put("additionalProperties", false)
}

fun emptyObjectSchema(): JsonObject = objectSchema(emptyMap())

fun stringSchema(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

fun enumSchema(description: String, values: List<String>): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    putJsonArray("enum") { values.forEach { add(it) } }
}

fun jsonArray(items: Iterable<JsonElement>): JsonArray = buildJsonArray { items.forEach(::add) }
