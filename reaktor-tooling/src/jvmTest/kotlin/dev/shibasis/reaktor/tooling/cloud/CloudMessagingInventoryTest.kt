package dev.shibasis.reaktor.tooling.cloud

import com.sun.net.httpserver.HttpServer
import dev.shibasis.reaktor.core.framework.lenientJson
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudMessagingInventoryTest {
    @Test
    fun queueConfigurationAndMetricsDoNotInventDeliveryHealth() {
        val builder = CloudflareInventoryBuilder("test", "https://dash.cloudflare.com/test", "fixture", 1_000L)
        builder.queues(listOf(json("""{"queue_id":"q1","queue_name":"events","producers_total_count":2,"consumers_total_count":1,
            "producers":[{"type":"worker","script":"api"},{"type":"r2_bucket","bucket_name":"uploads"}],
            "consumers":[{"type":"worker","script_name":"delivery"}]}""")), mapOf("q1" to json("""{"backlog_count":0,"backlog_bytes":0,"oldest_message_timestamp_ms":0}""")))
        val queue = builder.resources().single()
        assertEquals(ResourceStatus.Unknown, queue.status)
        assertEquals("2", queue.attributes["producers"])
        assertEquals("0", queue.attributes["backlogRecords"])
        assertEquals("approximate", queue.attributes["metricCoverage"])
        assertNull(queue.attributes["oldestMessageAt"])
        assertEquals(0.0, queue.metric("backlog")?.value)
        assertEquals(setOf("cf:worker:api" to "cf:queue:events", "cf:r2:uploads" to "cf:queue:events", "cf:queue:events" to "cf:worker:delivery"),
            builder.relations().map { it.source to it.target }.toSet())
    }

    @Test
    fun paginatedCatalogsKeepExactLagAndUnknownCoverage() = runBlocking {
        val requests = ConcurrentLinkedQueue<String>()
        withReader(requests, { path, query ->
            when {
                path.endsWith("/queues") -> if ("page=2" in query) ok("""{"result":[{"queue_id":"q2","queue_name":"unreadable"}],"result_info":{"total_count":2}}""")
                    else ok("""{"result":[{"queue_id":"q1","queue_name":"events"}],"result_info":{"total_count":2}}""")
                path.endsWith("/q1/metrics") -> ok("""{"result":{"backlog_count":0,"backlog_bytes":0,"oldest_message_timestamp_ms":0}}""")
                path.endsWith("/q2/metrics") -> 403 to """{"success":false,"errors":[{"message":"fixture denied"}]}"""
                path.endsWith("/k2/streams") -> if ("page=2" in query) ok("""{"result":[{"id":"s2","name":"unknown-lag"}],"result_info":{"total_count":2}}""")
                    else ok("""{"result":[{"id":"s1","name":"orders","retention_seconds":3600,"http":{"enabled":true},"worker_binding":{"enabled":false}}],"result_info":{"total_count":2}}""")
                path.endsWith("/s1/subscriptions") -> ok("""{"result":[{"id":"large","name":"billing","lag":{"status":"available","records":"18446744073709551616"}},
                    {"id":"unsupported","lag":{"status":"unsupported","records":null}},{"id":"invalid","lag":{"status":"available","records":"-1"}}]}""")
                path.endsWith("/s2/subscriptions") -> 403 to """{"success":false}"""
                else -> null
            }
        }) { reader ->
            val reading = withTimeout(15_000) { reader.read() }
            val resources = reading.resources.associateBy { it.id }
            assertEquals(ResourceStatus.Degraded, reading.health.status)
            assertTrue("queue metrics q2" in reading.health.detail.orEmpty())
            assertTrue("K2 subscriptions s2" in reading.health.detail.orEmpty())
            assertEquals("0", resources.getValue("cf:queue:events").attributes["backlogRecords"])
            assertNull(resources.getValue("cf:queue:unreadable").attributes["backlogRecords"])
            assertEquals("unavailable", resources.getValue("cf:queue:unreadable").attributes["metricCoverage"])
            assertEquals("18446744073709551616", resources.getValue("cf:k2:s1/subscription:large").attributes["lagRecords"])
            assertNull(resources.getValue("cf:k2:s1/subscription:unsupported").attributes["lagRecords"])
            assertNull(resources.getValue("cf:k2:s1/subscription:invalid").attributes["lagRecords"])
            assertEquals("unavailable", resources.getValue("cf:k2:s2").attributes["subscriptionCoverage"])
            assertTrue(reading.relations.any { it.source == "cf:k2:s1" && it.target == "cf:k2:s1/subscription:large" })
            assertTrue(reading.resources.filter { it.kind == CloudKind.Messaging || it.kind == CloudKind.Queue }.all { it.status == ResourceStatus.Unknown })
            assertTrue(requests.any { "/k2/streams?per_page=100&page=2" in it })
            assertTrue(requests.all { it.startsWith("GET ") || it == "POST /graphql" })
        }
    }

    @Test
    fun failedLaterPagesRetainObservedRowsAndReportIncompleteInventory() = runBlocking {
        withReader(ConcurrentLinkedQueue(), { path, query ->
            when {
                path.endsWith("/queues") && "page=2" in query -> 200 to """{"success":false,"errors":[{"message":"fixture denied page"}]}"""
                path.endsWith("/queues") -> ok("""{"result":[{"queue_id":"q1","queue_name":"observed"}],"result_info":{"total_count":2}}""")
                path.endsWith("/metrics") -> ok("""{"result":null}""")
                path.endsWith("/k2/streams") -> ok("""{"result":{}}""")
                else -> null
            }
        }) { reader ->
            val reading = withTimeout(15_000) { reader.read() }
            assertEquals(ResourceStatus.Degraded, reading.health.status)
            assertEquals(listOf("observed"), reading.resources.filter { it.kind == CloudKind.Queue }.map { it.name })
            assertTrue(listOf("queues", "K2 streams", "queue metrics q1").all { it in reading.health.detail.orEmpty() })
            assertTrue(reading.resources.single { it.kind == CloudKind.Queue }.metrics.isEmpty())
        }
    }

    private fun json(value: String) = lenientJson.parseToJsonElement(value).jsonObject

    private fun ok(body: String) = 200 to body

    private suspend fun withReader(
        requests: ConcurrentLinkedQueue<String>,
        response: (String, String) -> Pair<Int, String>?,
        check: suspend (CloudflareReader) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests.add("${exchange.requestMethod} ${exchange.requestURI}")
            val (status, body) = response(path, exchange.requestURI.rawQuery.orEmpty()) ?: when {
                path == "/graphql" -> ok("""{"data":{"viewer":{"accounts":[{"totals":[],"hourly":[]}]}}}""")
                path.endsWith("/r2/buckets") -> ok("""{"result":{"buckets":[]}}""")
                else -> ok("""{"result":[]}""")
            }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val reader = CloudflareReader("test", CloudflareApiToken("fixture-token"), { 1_791_504_000_000L })
            CloudflareReader::class.java.getDeclaredField("base").apply { isAccessible = true }
                .set(reader, "http://127.0.0.1:${server.address.port}")
            check(reader)
        } finally {
            server.stop(0)
        }
    }
}
