package dev.shibasis.reaktor.devtools

import dev.shibasis.reaktor.io.network.HttpExchange
import dev.shibasis.reaktor.io.network.SocketEventKind
import dev.shibasis.reaktor.io.network.SocketObservation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpTapCapturePolicyTest {
    @Test
    fun httpFactsRetainMetadataWithoutBodiesOrCredentialLocations() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val stream = FactStream(AgentCapability.Traffic)
        stream.start(scope)
        try {
            HttpTap(stream) { 150 }.observe(HttpExchange(
                method = "POST",
                url = "https://private-user:private-pass@example.test/chat/send?opaque=private-query#private-fragment",
                requestHeaders = listOf(
                    "Authorization" to "Bearer private-header",
                    "Cookie" to "private-cookie",
                    "X-API-Key" to "private-key",
                    "X-User-Id" to "private-user-id",
                    "Content-Type" to "application/json",
                    DevToolsProtocol.CorrelationHeader to "request-one",
                ),
                requestBody = "{\"text\":\"private-request\"}",
                requestBytes = 123,
                statusCode = 503,
                responseHeaders = listOf("Set-Cookie" to "private-response-cookie", "Content-Length" to "456"),
                responseBody = "private-response",
                responseBytes = 456,
                startedAtMillis = 100,
                respondedAtMillis = 120,
                finishedAtMillis = 140,
                failure = "Authorization: Basic private-basic\nprivate-second-line",
            ))
            val fact = stream.latest().facts.single() as AgentFact.Traffic
            assertEquals("https://example.test/chat/send", fact.url)
            assertEquals("/chat/send", fact.operation)
            assertEquals("request-one", fact.correlationId)
            assertEquals(503, fact.statusCode)
            assertEquals(123L, fact.requestBytes)
            assertEquals(456L, fact.responseBytes)
            assertEquals(40L, fact.durationMillis)
            assertTrue(fact.respondedNanos!! > fact.startedNanos!!)
            assertEquals("application/json", fact.requestHeaders["Content-Type"])
            assertEquals("456", fact.responseHeaders["Content-Length"])
            assertEquals("Authorization: ***", fact.failure)
            assertNull(fact.requestBody)
            assertNull(fact.responseBody)
            assertFalse("private-" in fact.toString())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun socketPayloadsAreOmittedEvenWithExplicitPortValueWatches() = runBlocking {
        val publisher = SocketObservation::class.java.methods.single {
            it.name.startsWith("publish") && it.parameterCount == 8
        }
        for (captureValues in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val agent = DevToolsAgent("test.capture", "Capture", policy = AgentPolicy(captureValues = captureValues), recordVitals = false)
            agent.traffic.start(scope)
            val undo = agent.instrumentHttp()
            try {
                publisher.invoke(SocketObservation, "ws-one",
                    "wss://private-user:private-pass@example.test/chat?cursor=private-query#private-fragment",
                    SocketEventKind.Sent, "private-payload", 321L, false, true, 1000)
                val fact = agent.traffic.latest().facts.single() as AgentFact.Socket
                assertEquals("wss://example.test/chat", fact.url)
                assertEquals("ws-one", fact.connection)
                assertEquals("sent", fact.event)
                assertEquals(321L, fact.bytes)
                assertEquals(1000, fact.code)
                assertTrue(fact.heartbeat)
                assertNull(fact.text)
                assertFalse("private-" in fact.toString())
            } finally {
                undo()
                agent.stop()
                scope.cancel()
            }
        }
    }

    @Test
    fun directLogsAndPlatformMirrorsApplyTheSameRedaction() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val stream = FactStream(AgentCapability.Logs)
        stream.start(scope)
        val mirrored = mutableListOf<String>()
        try {
            LogSink(stream, mirror = { _, _, message -> mirrored += message }).log(
                LogLevel.Error, "network",
                "GET https://private-user:private-pass@example.test/route?opaque=private-query#private-fragment\nprivate-body",
                fields = mapOf("Authorization" to "private-header", "requestBody" to "private-payload",
                    "sessionId" to "private-session", "status" to "503", "route" to "/route?token=private-token"),
                throwable = IllegalStateException("password=\"private multi word value\""),
                correlationId = "request-one",
            )
            val fact = stream.latest().facts.single() as AgentFact.Log
            assertEquals("GET https://example.test/route", fact.message)
            assertEquals(listOf(fact.message), mirrored)
            assertEquals("***", fact.fields["Authorization"])
            assertEquals("***", fact.fields["requestBody"])
            assertEquals("***", fact.fields["sessionId"])
            assertEquals("503", fact.fields["status"])
            assertEquals("request-one", fact.correlationId)
            assertTrue(fact.throwable!!.contains("IllegalStateException"))
            assertFalse("private-" in fact.toString())
            assertFalse("private multi word value" in fact.toString())
        } finally {
            scope.cancel()
        }
    }
}
