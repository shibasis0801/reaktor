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

    @Test fun longRunningOperationsReturnUnsolicitedTaskHandlesAndRejectTasksList() {
        val host = object : McpTasks {
            override fun unsolicited(tool: String, arguments: JsonObject, metadata: JsonObject): Boolean =
                isMcpLongRunningCall(tool, arguments)

            override fun call(tool: String, arguments: JsonObject, metadata: JsonObject): JsonObject? {
                if (!isMcpLongRunningCall(tool, arguments)) return null
                return buildJsonObject {
                    put("resultType", "task")
                    put("taskId", "task-$tool")
                    put("status", "working")
                }
            }

            override fun request(method: String, params: JsonObject): JsonObject = buildJsonObject {
                put("resultType", "complete")
                put("method", method)
                put("taskId", params.getValue("taskId"))
            }
        }
        val server = ReaktorMcpServer("reaktor", "1", "", listOf(
            McpTool("check_run", "Run checks", emptyObjectSchema(), false, false) { JsonObject(emptyMap()) },
            McpTool("kernel", "Kernel", emptyObjectSchema(), false, false) { JsonObject(emptyMap()) },
        ), tasks = host)
        val metaWithoutExtension = buildJsonObject { put(MCP_REQUEST_VERSION, MCP_TASKS_VERSION) }
        val checkTask = result(server, "tools/call", buildJsonObject {
            put("name", "check_run")
            putJsonObject("arguments") { put("change", "p1") }
            put("_meta", metaWithoutExtension)
        })
        assertEquals("task", checkTask.getValue("resultType").jsonPrimitive.content)
        assertEquals("task-check_run", checkTask.getValue("taskId").jsonPrimitive.content)

        for (op in listOf("build.run", "ship.plan")) {
            val opTask = result(server, "tools/call", buildJsonObject {
                put("name", "kernel")
                putJsonObject("arguments") { put("operation", op) }
                put("_meta", metaWithoutExtension)
            })
            assertEquals("task", opTask.getValue("resultType").jsonPrimitive.content)
        }

        for (method in listOf("tasks/get", "tasks/update", "tasks/cancel")) {
            val polled = result(server, method, buildJsonObject {
                put("taskId", "task-check_run")
                put("_meta", metaWithoutExtension)
            })
            assertEquals("complete", polled.getValue("resultType").jsonPrimitive.content)
            assertEquals(method, polled.getValue("method").jsonPrimitive.content)
        }

        val tasksList = server.handle(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 9); put("method", "tasks/list")
            putJsonObject("params") { put("_meta", metaWithoutExtension) }
        }.toString())!!.jsonObject.getValue("error").jsonObject
        assertEquals(-32601, tasksList.getValue("code").jsonPrimitive.content.toInt())
    }

    private fun result(server: ReaktorMcpServer, method: String, params: JsonObject = JsonObject(emptyMap())) = server.handle(
        buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", method); put("params", params) }.toString())!!
        .jsonObject.getValue("result").jsonObject
}
