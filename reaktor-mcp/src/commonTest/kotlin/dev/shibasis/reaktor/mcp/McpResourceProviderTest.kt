package dev.shibasis.reaktor.mcp

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpResourceProviderTest {
    @Test
    fun resourcesResolveWithEachRequestsMetadataAndKeepStaticResources() {
        var revision = 1
        val observed = mutableListOf<JsonObject>()
        fun resource(agent: String) = McpReadResource("memory://$agent/$revision", agent, "Reviewed memory", ttlMs = 0) { "$agent:$revision" }
        val server = ReaktorMcpServer("memory", "1", "", emptyList(),
            resources = listOf(McpReadResource("static://rules", "Rules", "Workspace rules") { "rules" }),
            resourceList = { meta ->
                observed += meta
                listOf(resource(meta.getValue("agent").jsonPrimitive.content))
            },
            resourceRead = { uri, meta ->
                observed += meta
                resource(meta.getValue("agent").jsonPrimitive.content).takeIf { it.uri == uri }
            })
        fun ask(method: String, agent: String, uri: String? = null) = server.handle(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", method)
            putJsonObject("params") {
                uri?.let { put("uri", it) }
                putJsonObject("_meta") { put("agent", agent) }
            }
        }.toString())!!.jsonObject
        fun listed(agent: String) = ask("resources/list", agent).getValue("result").jsonObject
            .getValue("resources").jsonArray.map { it.jsonObject.getValue("uri").jsonPrimitive.content }
        assertEquals(listOf("static://rules", "memory://falcon/1"), listed("falcon"))
        revision = 2
        assertEquals(listOf("static://rules", "memory://tower/2"), listed("tower"))
        assertTrue("error" in ask("resources/read", "tower", "memory://falcon/2"))
        val read = ask("resources/read", "falcon", "memory://falcon/2").getValue("result").jsonObject
        assertEquals("falcon:2", read.getValue("contents").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(0, read.getValue("ttlMs").jsonPrimitive.int)
        assertEquals("private", read.getValue("cacheScope").jsonPrimitive.content)
        assertFalse("error" in ask("resources/read", "tower", "static://rules"))
        assertEquals(listOf("falcon", "tower", "tower", "falcon"), observed.map { it.getValue("agent").jsonPrimitive.content })
    }

    @Test
    fun resourceProviderFailuresReturnProtocolErrorsAndDoNotBreakTheServer() {
        val server = ReaktorMcpServer("memory", "1", "", emptyList(),
            resourceList = { error("Listing unavailable") },
            resourceRead = { _, _ -> error("Read unavailable") })
        fun ask(method: String) = server.handle(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 2); put("method", method)
            putJsonObject("params") { put("uri", "memory://fact/1") }
        }.toString())!!.jsonObject
        assertEquals(-32603, ask("resources/list").getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
        assertEquals(-32603, ask("resources/read").getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
        assertTrue("result" in ask("server/discover"))
    }
}
