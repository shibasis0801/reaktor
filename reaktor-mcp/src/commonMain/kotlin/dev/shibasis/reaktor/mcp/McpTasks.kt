package dev.shibasis.reaktor.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

const val MCP_TASKS_EXTENSION = "io.modelcontextprotocol/tasks"
const val MCP_REQUEST_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities"
const val MCP_REQUEST_VERSION = "io.modelcontextprotocol/protocolVersion"
const val MCP_CLIENT_INFO = "io.modelcontextprotocol/clientInfo"
const val MCP_SERVER_INFO = "io.modelcontextprotocol/serverInfo"
const val MCP_LOG_LEVEL = "io.modelcontextprotocol/logLevel"
const val MCP_SUBSCRIPTION_ID = "io.modelcontextprotocol/subscriptionId"
const val MCP_ALWAYS_LOAD = "antigravity/alwaysLoad"
const val MCP_TASKS_VERSION = "2026-07-28"

const val MCP_ERROR_HEADER_MISMATCH: Int = -32020
const val MCP_ERROR_MISSING_CAPABILITY: Int = -32021
const val MCP_ERROR_UNSUPPORTED_VERSION: Int = -32022

val MCP_LONG_RUNNING_TOOLS: Set<String> = setOf("check_run")
val MCP_LONG_RUNNING_OPERATIONS: Set<String> = setOf("check.run", "checks.run", "build.run", "ship.plan")

fun isMcpLongRunningCall(tool: String, arguments: JsonObject): Boolean {
    if (tool in MCP_LONG_RUNNING_TOOLS) return true
    if (tool != "kernel") return false
    val operation = (arguments["operation"] as? JsonPrimitive)?.contentOrNull ?: return false
    return operation in MCP_LONG_RUNNING_OPERATIONS
}

interface McpTasks {
    fun call(tool: String, arguments: JsonObject, metadata: JsonObject): JsonObject?
    fun request(method: String, params: JsonObject): JsonObject
    fun unsolicited(tool: String, arguments: JsonObject, metadata: JsonObject): Boolean = false
}
