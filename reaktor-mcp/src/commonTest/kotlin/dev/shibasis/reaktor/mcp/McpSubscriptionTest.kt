package dev.shibasis.reaktor.mcp

import kotlinx.serialization.json.*
import kotlin.test.*

class McpSubscriptionTest {
    private fun request(method: String, id: String? = "shared", uri: String? = null, agent: String = "falcon") = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", 1); put("method", method)
        putJsonObject("params") {
            id?.let { put("subscriptionId", it) }
            if (method == "subscriptions/listen") {
                if (uri == null) put("resourceSubscriptions", true)
                else putJsonArray("resourceSubscriptions") { add(uri) }
                put("messages", true); put("memory", true)
            }
            putJsonObject("_meta") { put(MCP_REQUEST_VERSION, MCP_TASKS_VERSION); put("agent", agent) }
        }
    }.toString()

    @Test fun equalIdsOnTwoConnectionsHaveIndependentLifetimes() {
        val server = ReaktorMcpServer("workspace", "1", "", emptyList())
        val first = mutableListOf<JsonElement>()
        val second = mutableListOf<JsonElement>()
        val a = server.openConnection(first::add)
        val b = server.openConnection(second::add)
        a.handle(request("subscriptions/listen", uri = "plan://first"))
        b.handle(request("subscriptions/listen", uri = "plan://second"))
        server.notifyResourceUpdated("plan://first")
        assertEquals(1, first.size)
        assertTrue(second.isEmpty())
        server.notifyResourceUpdated("plan://second")
        assertEquals(1, second.size)
        a.handle(request("subscriptions/unlisten"))
        server.notifyResourceUpdated("plan://first")
        server.notifyResourceUpdated("plan://second")
        assertEquals(1, first.size)
        assertEquals(2, second.size)
        assertTrue(server.activeSubscriptions().isEmpty())
        a.close(); a.close()
        assertFailsWith<IllegalStateException> { a.handle(request("server/discover")) }
        b.close()
        assertTrue(server.notifyResourceUpdated("plan://second").isEmpty())
    }

    @Test fun recipientFiltersUseCapturedMetadataWithoutPublishingIt() {
        val server = ReaktorMcpServer("workspace", "1", "", emptyList())
        val first = mutableListOf<JsonElement>()
        val second = mutableListOf<JsonElement>()
        val a = server.openConnection(first::add)
        val b = server.openConnection(second::add)
        a.handle(request("subscriptions/listen", agent = "falcon"))
        b.handle(request("subscriptions/listen", agent = "tower"))
        val recipient: (JsonObject) -> Boolean = { it.getValue("agent").jsonPrimitive.content == "falcon" }
        server.notifyResourceUpdated("message://falcon", visibleTo = recipient)
        server.notifyMessageChanged(buildJsonObject { put("revision", 3) }, recipient)
        server.notifyMemoryChanged(visibleTo = recipient)
        assertEquals(3, first.size)
        assertTrue(second.isEmpty())
        assertTrue(first.all { it.jsonObject.getValue("params").jsonObject["_meta"]?.jsonObject?.keys == setOf(MCP_SUBSCRIPTION_ID) })
        server.notifyResourceUpdated("message://falcon", visibleTo = { error("Identity unavailable") })
        assertEquals(3, first.size)
        a.close(); b.close()
    }

    @Test fun aBrokenEmitterDoesNotInterruptOtherConnectionsAndDetaches() {
        val server = ReaktorMcpServer("workspace", "1", "", emptyList())
        var attempts = 0
        val first = server.openConnection { attempts++; error("Wire closed") }
        val received = mutableListOf<JsonElement>()
        val second = server.openConnection(received::add)
        first.handle(request("subscriptions/listen"))
        second.handle(request("subscriptions/listen"))
        server.notifyResourceUpdated("plan://launch")
        server.notifyResourceUpdated("plan://launch")
        assertEquals(1, attempts)
        assertEquals(2, received.size)
        first.close(); second.close()
        assertTrue(server.notifyResourceUpdated("plan://launch").isEmpty())
    }

    @Test fun aConnectionHasBoundedSubscriptionsAndGeneratedIdsAvoidClientIds() {
        val server = ReaktorMcpServer("workspace", "1", "", emptyList())
        server.handle(request("subscriptions/listen", id = "sub-1"))
        val automatic = server.handle(request("subscriptions/listen", id = null))!!.jsonObject.getValue("result").jsonObject
        assertEquals("sub-2", automatic.getValue("subscriptionId").jsonPrimitive.content)
        repeat(126) { server.handle(request("subscriptions/listen", id = "item-$it")) }
        assertEquals(128, server.activeSubscriptions().size)
        val full = server.handle(request("subscriptions/listen", id = "overflow"))!!.jsonObject
        assertEquals(-32602, full.getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
        server.handle(request("subscriptions/listen", id = "sub-1", uri = "plan://replacement"))
        assertEquals(128, server.activeSubscriptions().size)
        server.handle(request("subscriptions/unlisten", id = "sub-1"))
        assertNotNull(server.handle(request("subscriptions/listen", id = "overflow"))!!.jsonObject["result"])
    }
}

