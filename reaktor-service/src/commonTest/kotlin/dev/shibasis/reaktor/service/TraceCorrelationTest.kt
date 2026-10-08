package dev.shibasis.reaktor.service

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TraceCorrelationTest {
    @Test
    fun baggageAcceptsOnlyOneBoundedSessionIdentity() {
        assertEquals("abc-123", ServiceCall.sessionFromBaggage("other=ignored,reaktor-session=abc-123"))
        for (value in listOf(null, "reaktor-session=", "reaktor-session=a;private=x", "reaktor-session=a,reaktor-session=b",
            "reaktor-session=token@private", "reaktor-session=" + "x".repeat(97), "other=" + "x".repeat(513))) {
            assertNull(ServiceCall.sessionFromBaggage(value), value)
        }
    }

    @Test
    fun concurrentRequestsKeepTheirOwnSessionAndTrace() = runTest {
        val spans = SpanBuffer()
        val service = object : Service() {
            val ping = GetHandler<CorrelationRequest, CorrelationResponse>("/ping") { request ->
                delay(if (request.session == "one") 20 else 1)
                CorrelationResponse()
            }
            init { use(SpanRecorder("test", spans, mapOf(ServiceCall.EnvironmentAttribute to "dev"))) }
        }
        val parents = listOf(TraceContext.root(), TraceContext.root())
        listOf("one", "two").mapIndexed { index, session ->
            async { service.ping(CorrelationRequest(session).apply {
                headers[TraceContext.Header] = parents[index].traceparent
                headers["baggage"] = "reaktor-session=$session,reaktor.environment=production"
            }) }
        }.awaitAll()
        val recorded = spans.drain().associateBy { it.applicationSession }
        assertEquals(setOf("one", "two"), recorded.keys)
        listOf("one", "two").forEachIndexed { index, session ->
            assertEquals(parents[index].traceId, recorded.getValue(session).traceId)
            assertEquals(parents[index].spanId, recorded.getValue(session).parentId)
            assertEquals("dev", recorded.getValue(session).environment)
        }
    }

    @Test
    fun trustedRequestResourcesAreCapturedBeforeConcurrentWork() = runTest {
        val spans = SpanBuffer()
        val service = object : Service() {
            val ping = GetHandler<CorrelationRequest, CorrelationResponse>("/ping") { request ->
                request.attributes[ServiceCall.EnvironmentAttribute] = "spoofed"
                delay(10)
                CorrelationResponse()
            }
            init { use(SpanRecorder("test", spans,
                resourceAttributes = mapOf(ServiceCall.BuildAttribute to "revision"),
                requestResourceAttributes = { request -> mapOf(
                    ServiceCall.EnvironmentAttribute to (request as CorrelationRequest).session,
                ) },
            )) }
        }
        listOf("dev", "prod").map { tier -> async { service.ping(CorrelationRequest(tier)) } }.awaitAll()
        assertEquals(setOf("dev", "prod"), spans.drain().also { recorded ->
            assertEquals(setOf("revision"), recorded.map { it.build }.toSet())
        }.map { it.environment }.toSet())
    }

    @Test
    fun oldSpanPayloadsDecodeWithoutInventedIdentity() = runTest {
        val spans = SpanBuffer()
        val service = object : Service() {
            val ping = GetHandler<CorrelationRequest, CorrelationResponse>("/ping") { CorrelationResponse() }
            init { use(SpanRecorder("test", spans)) }
        }
        service.ping(CorrelationRequest())
        val encoded = Json.encodeToJsonElement(spans.drain().single()) as JsonObject
        val decoded = Json.decodeFromJsonElement<ServiceSpan>(JsonObject(encoded.filterKeys {
            it !in setOf("application_session", "environment", "build", "node", "graph_digest")
        }))
        assertEquals("", decoded.applicationSession)
        assertEquals("", decoded.build)
        assertEquals("", decoded.graphDigest)
    }
}

@Serializable
private data class CorrelationRequest(val session: String = "") : Request()

@Serializable
private class CorrelationResponse : Response()
