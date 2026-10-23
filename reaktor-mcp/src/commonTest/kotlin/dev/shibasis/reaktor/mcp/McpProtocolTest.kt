package dev.shibasis.reaktor.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class McpProtocolTest {
    private val log = buildJsonObject {
        put("type", "completed")
        putJsonObject("result") { putJsonArray("turns") { addJsonObject { put("prompt", "Read the log") } } }
    }

    @Test
    fun aToolResultTravelsAsCompactTextBesideItsStructuredCopy() {
        val result = call(server())
        assertEquals(log.toString(), result.text())
        assertEquals(log, result.getValue("structuredContent"))
    }

    @Test
    fun aServerForModelsSendsEachResultOnce() {
        val result = call(server(structuredContent = false))
        assertEquals(log, Json.parseToJsonElement(result.text()))
        assertNull(result["structuredContent"])
    }

    @Test
    fun aPromptIsListedAndRenderedOnlyWithItsRequiredArguments() {
        val server = ReaktorMcpServer("test", "1", "", emptyList(), prompts = listOf(
            McpPrompt("hangar", "Plans a request", listOf(McpPromptArgument("request", "What to do", required = true))) { "Plan: ${it.getValue("request")}" }))
        fun answer(body: String) = server.handle(body)!!.jsonObject
        val capabilities = answer("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}""")
            .getValue("result").jsonObject.getValue("capabilities").jsonObject
        assertEquals(setOf("tools", "resources", "prompts"), capabilities.keys)
        val listed = answer("""{"jsonrpc":"2.0","id":2,"method":"prompts/list"}""").getValue("result").jsonObject.getValue("prompts").jsonArray.single().jsonObject
        assertEquals("hangar", listed.getValue("name").jsonPrimitive.content)
        assertEquals("request", listed.getValue("arguments").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        val message = answer("""{"jsonrpc":"2.0","id":3,"method":"prompts/get","params":{"name":"hangar","arguments":{"request":"split the parser"}}}""")
            .getValue("result").jsonObject.getValue("messages").jsonArray.single().jsonObject
        assertEquals("user", message.getValue("role").jsonPrimitive.content)
        assertEquals("Plan: split the parser", message.getValue("content").jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(-32602, answer("""{"jsonrpc":"2.0","id":4,"method":"prompts/get","params":{"name":"hangar"}}""")
            .getValue("error").jsonObject.getValue("code").jsonPrimitive.content.toInt())
        assertNull(ReaktorMcpServer("test", "1", "", emptyList()).handle("""{"jsonrpc":"2.0","id":5,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}""")!!
            .jsonObject.getValue("result").jsonObject.getValue("capabilities").jsonObject["prompts"])
    }

    private fun server(structuredContent: Boolean = true) = ReaktorMcpServer("test", "1", "", listOf(
        McpTool("session_log", "Reads a session.", emptyObjectSchema(), readOnly = true, idempotent = true) { log },
    ), structuredContent = structuredContent)

    private fun call(server: ReaktorMcpServer) =
        server.handle("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"session_log","arguments":{}}}""")!!
            .jsonObject.getValue("result").jsonObject

    private fun JsonObject.text() = getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
}
