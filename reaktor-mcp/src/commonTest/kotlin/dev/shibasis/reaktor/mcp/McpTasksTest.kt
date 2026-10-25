package dev.shibasis.reaktor.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class McpTasksTest {
    private val metadata = buildJsonObject {
        put(MCP_REQUEST_VERSION, MCP_TASKS_VERSION)
        put("traceparent", "00-fixture")
        putJsonObject(MCP_REQUEST_CAPABILITIES) { putJsonObject("extensions") { putJsonObject(MCP_TASKS_EXTENSION) {} } }
    }

    @Test fun onlyModernOptedInRequestsUseTasksAndKeepTheirMetadata() {
        var direct = 0
        var taskCalls = 0
        val host = object : McpTasks {
            override fun call(tool: String, arguments: JsonObject, metadata: JsonObject): JsonObject {
                taskCalls++
                assertEquals("kernel", tool)
                assertEquals("00-fixture", metadata.getValue("traceparent").jsonPrimitive.content)
                return buildJsonObject { put("resultType", "task"); put("taskId", "one") }
            }
            override fun request(method: String, params: JsonObject) = buildJsonObject { put("resultType", "complete") }
        }
        val server = ReaktorMcpServer("test", "1", "", listOf(
            McpTool("kernel", "Kernel", emptyObjectSchema(), false, false) { direct++; JsonObject(emptyMap()) }), tasks = host)
        fun call(meta: JsonObject) = result(server, "tools/call", buildJsonObject {
            put("name", "kernel"); putJsonObject("arguments") {}; put("_meta", meta)
        })
        assertNull(call(JsonObject(emptyMap()))["resultType"])
        assertEquals("complete", call(JsonObject(metadata - MCP_REQUEST_CAPABILITIES))["resultType"]?.jsonPrimitive?.content)
        assertNull(call(JsonObject(metadata + (MCP_REQUEST_VERSION to JsonPrimitive("2025-11-25"))))["resultType"])
        assertEquals("task", call(metadata).getValue("resultType").jsonPrimitive.content)
        assertEquals(3, direct)
        assertEquals(1, taskCalls)
    }

    @Test fun discoveryAdvertisesOnlyConfiguredTasksAndToolsKeepTheirOrder() {
        val host = object : McpTasks {
            override fun call(tool: String, arguments: JsonObject, metadata: JsonObject): JsonObject? = null
            override fun request(method: String, params: JsonObject) = buildJsonObject { put("resultType", "complete"); put("method", method) }
        }
        fun server(tasks: McpTasks?) = ReaktorMcpServer("test", "1", "", listOf("z", "a", "m").map { name ->
            McpTool(name, name, emptyObjectSchema(), true, true) { JsonNull } }, tasks = tasks)
        val server = server(host)
        assertTrue(MCP_TASKS_EXTENSION in result(server, "server/discover").getValue("capabilities").jsonObject.getValue("extensions").jsonObject)
        assertNull(result(server(null), "server/discover").getValue("capabilities").jsonObject["extensions"])
        repeat(2) { assertEquals(listOf("z", "a", "m"), result(server, "tools/list").getValue("tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }) }
        assertEquals("tasks/get", result(server, "tasks/get", buildJsonObject { put("taskId", "one") }).getValue("method").jsonPrimitive.content)
    }

    @Test fun asynchronousToolsUseTheModernEnvelopeOnlyForModernRequests() = runTest {
        val server = ReaktorMcpServer("test", "1", "", emptyList())
        val definitions = listOf(ToolDefinition("kernel", "Kernel"))
        for (method in listOf("tools/list", "tools/call")) for (modern in listOf(false, true)) {
            val response = server.handleAsync(buildJsonObject {
                put("jsonrpc", "2.0"); put("id", 1); put("method", method)
                putJsonObject("params") {
                    put("name", "kernel")
                    if (modern) put("_meta", metadata)
                }
            }.toString(), definitions) { _, _ -> ToolResult(JsonObject(emptyMap())) }!!
                .jsonObject.getValue("result").jsonObject
            assertEquals(if (modern) JsonPrimitive("complete") else null, response["resultType"])
        }
    }

    private fun result(server: ReaktorMcpServer, method: String, params: JsonObject = JsonObject(emptyMap())) = server.handle(
        buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", method); put("params", params) }.toString())!!
        .jsonObject.getValue("result").jsonObject
}
