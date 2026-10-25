package dev.shibasis.reaktor.mcp

import kotlinx.serialization.json.JsonObject

const val MCP_TASKS_EXTENSION = "io.modelcontextprotocol/tasks"
const val MCP_REQUEST_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities"
const val MCP_REQUEST_VERSION = "io.modelcontextprotocol/protocolVersion"
const val MCP_TASKS_VERSION = "2026-07-28"

interface McpTasks {
    fun call(tool: String, arguments: JsonObject, metadata: JsonObject): JsonObject?
    fun request(method: String, params: JsonObject): JsonObject
}
