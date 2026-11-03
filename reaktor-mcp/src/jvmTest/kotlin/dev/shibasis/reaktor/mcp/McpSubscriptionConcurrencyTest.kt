package dev.shibasis.reaktor.mcp

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.serialization.json.*

class McpSubscriptionConcurrencyTest {
    @Test fun publishingWhileConnectionsChangeKeepsEveryNotificationOnItsWire() {
        val server = ReaktorMcpServer("workspace", "1", "", emptyList())
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val counts = Array(8) { ConcurrentLinkedQueue<JsonElement>() }
        val workers = counts.indices.map { index -> thread {
            try {
                val connection = server.openConnection { notification ->
                    val uri = notification.jsonObject.getValue("params").jsonObject.getValue("uri").jsonPrimitive.content
                    check(uri == "plan://$index")
                    counts[index].add(notification)
                }
                start.await()
                repeat(100) { revision ->
                    connection.handle(buildJsonObject {
                        put("jsonrpc", "2.0"); put("id", revision); put("method", "subscriptions/listen")
                        putJsonObject("params") {
                            put("subscriptionId", "same-id")
                            putJsonArray("resourceSubscriptions") { add("plan://$index") }
                        }
                    }.toString())
                    server.notifyResourceUpdated("plan://$index")
                }
                connection.close()
            } catch (failure: Throwable) { failures.add(failure) }
        } }
        start.countDown()
        workers.forEach { it.join(10_000) }
        assertTrue(workers.none { it.isAlive })
        assertTrue(failures.isEmpty(), failures.joinToString())
        counts.forEach { assertEquals(100, it.size) }
        repeat(8) { assertTrue(server.notifyResourceUpdated("plan://$it").isEmpty()) }
    }
}

