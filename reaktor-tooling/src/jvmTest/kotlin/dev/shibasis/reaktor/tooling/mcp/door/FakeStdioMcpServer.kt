package dev.shibasis.reaktor.tooling.mcp.door

import dev.shibasis.reaktor.mcp.McpTool
import dev.shibasis.reaktor.mcp.ReaktorMcpServer
import dev.shibasis.reaktor.mcp.emptyObjectSchema
import dev.shibasis.reaktor.mcp.objectSchema
import dev.shibasis.reaktor.mcp.stringSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A stdio MCP server that misbehaves the way real ones do: it chatters on stdout before and between answers. */
fun main() {
    fun text(value: String): JsonObject = buildJsonObject { putJsonArray("content") { addJsonObject { put("type", "text"); put("text", value) } }; put("isError", false) }
    val server = ReaktorMcpServer("fake-stdio", "1", "", listOf(
        McpTool("echo", "Says the value back.", objectSchema(mapOf("value" to stringSchema("Anything")), listOf("value")), true, true, raw = true) {
            text((it["value"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        },
        McpTool("environment", "Lists the variable names this process can see.", emptyObjectSchema(), true, true, raw = true) {
            text(System.getenv().keys.sorted().joinToString(","))
        },
    ))
    println("Starting a Gradle Daemon (subsequent builds will be faster)")
    System.`in`.bufferedReader().forEachLine { line ->
        if (line.isBlank()) return@forEachLine
        System.err.println("fake server read ${line.length} chars")
        server.handle(line)?.let { println(it.toString().replace("\n", "")) }
        println("> Task :noise")
    }
}
