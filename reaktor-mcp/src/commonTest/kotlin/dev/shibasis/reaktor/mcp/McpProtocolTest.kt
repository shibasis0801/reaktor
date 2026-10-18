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

    private fun server(structuredContent: Boolean = true) = ReaktorMcpServer("test", "1", "", listOf(
        McpTool("session_log", "Reads a session.", emptyObjectSchema(), readOnly = true, idempotent = true) { log },
    ), structuredContent = structuredContent)

    private fun call(server: ReaktorMcpServer) =
        server.handle("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"session_log","arguments":{}}}""")!!
            .jsonObject.getValue("result").jsonObject

    private fun JsonObject.text() = getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
}
