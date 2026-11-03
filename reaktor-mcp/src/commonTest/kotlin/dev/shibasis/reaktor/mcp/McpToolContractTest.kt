package dev.shibasis.reaktor.mcp

import kotlinx.serialization.json.*
import kotlin.test.*

class McpToolContractTest {
    private val schema = objectSchema(mapOf("answer" to stringSchema("Answer")), listOf("answer"))

    @Test fun declaredOutputSurvivesListingAndForcesStructuredContent() {
        val output = buildJsonObject { put("answer", "typed") }
        val tool = McpTool("typed", "Typed answer", emptyObjectSchema(), true, true,
            title = "Read a typed answer", outputSchema = schema) { output }
        val server = ReaktorMcpServer("contracts", "1", "", listOf(tool), structuredContent = false)
        val listing = rpc(server, "tools/list").getValue("result").jsonObject
        val declared = listing.getValue("tools").jsonArray.single().jsonObject
        assertEquals(schema, declared["outputSchema"])
        assertEquals("Read a typed answer", declared.getValue("title").jsonPrimitive.content)
        assertTrue(declared.getValue("annotations").jsonObject.getValue("readOnlyHint").jsonPrimitive.boolean)
        assertFalse(declared.getValue("annotations").jsonObject.getValue("destructiveHint").jsonPrimitive.boolean)
        val called = rpc(server, "tools/call", buildJsonObject { put("name", "typed") }).getValue("result").jsonObject
        assertEquals(output, called["structuredContent"])
        assertContains(called.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content, "typed")
    }

    @Test fun rawToolsKeepTheirTypedBodyAndReadableContent() {
        val output = buildJsonObject { put("answer", "typed") }
        val content = buildJsonArray { addJsonObject { put("type", "text"); put("text", "Readable evidence") } }
        val tool = McpTool("raw", "Raw typed answer", emptyObjectSchema(), true, true, raw = true, outputSchema = schema) {
            buildJsonObject { put("content", content); put("structuredContent", output); put("isError", false) }
        }
        val server = ReaktorMcpServer("contracts", "1", "", listOf(tool))
        val result = rpc(server, "tools/call", buildJsonObject { put("name", "raw") }).getValue("result").jsonObject
        assertEquals(output, result["structuredContent"])
        assertEquals(content, result["content"])
    }

    @Test fun typedRawApprovalsUseTheExistingHumanInputProtocol() {
        val approval = buildJsonObject { put("type", "approval-required"); put("approval", "person-7"); put("message", "Approve this exact change"); put("confirmationPhrase", "APPLY fingerprint") }
        val raw = buildJsonObject {
            putJsonArray("content") { addJsonObject { put("type", "text"); put("text", "Waiting for a person") } }
            put("structuredContent", approval); put("isError", true)
        }
        val tool = McpTool("kernel", "Owner call", emptyObjectSchema(), false, false, raw = true) { raw }
        val server = ReaktorMcpServer("contracts", "1", "", listOf(tool))
        fun call(version: String) = rpc(server, "tools/call", buildJsonObject {
            put("name", "kernel"); putJsonObject("arguments") { put("operation", "change.apply") }
            putJsonObject("_meta") { put(MCP_REQUEST_VERSION, version) }
        }).getValue("result").jsonObject
        val modern = call(MCP_TASKS_VERSION)
        assertEquals("input_required", modern.getValue("resultType").jsonPrimitive.content)
        assertEquals("person-7", modern.getValue("inputRequests").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("change.apply", modern.getValue("requestState").jsonObject.getValue("operation").jsonPrimitive.content)
        val input = modern.getValue("inputRequests").jsonArray.single().jsonObject
        assertContains(input.getValue("prompt").jsonPrimitive.content, "APPLY fingerprint")
        assertTrue("confirmationPhrase" in input.getValue("schema").jsonObject.getValue("properties").jsonObject)
        assertEquals(raw, call("2025-11-25"))
    }

    @Test fun legacyToolsRetainTheirExistingTextOnlyChoice() {
        val server = ReaktorMcpServer("contracts", "1", "", listOf(McpTool("legacy", "Legacy", emptyObjectSchema(), true, true) {
            buildJsonObject { put("answer", "legacy") }
        }), structuredContent = false)
        val listed = rpc(server, "tools/list").getValue("result").jsonObject.getValue("tools").jsonArray.single().jsonObject
        assertFalse("outputSchema" in listed)
        val result = rpc(server, "tools/call", buildJsonObject { put("name", "legacy") }).getValue("result").jsonObject
        assertFalse("structuredContent" in result)
    }

    private fun rpc(server: ReaktorMcpServer, method: String, params: JsonObject = JsonObject(emptyMap())) =
        server.handle(buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", method); put("params", params) }.toString())!!.jsonObject
}
