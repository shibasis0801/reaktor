package dev.shibasis.reaktor.service

import dev.shibasis.reaktor.service.contract.Metadata
import dev.shibasis.reaktor.service.contract.Spans
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
import dev.shibasis.reaktor.service.contract.Service
import dev.shibasis.reaktor.service.contract.Served

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
        val served = Served(Correlated { request ->
            delay(if (request.session == "one") 20 else 1)
        }).intercept(Spans("test", spans) { mapOf(ServiceCall.EnvironmentAttribute to "dev") })
        val parents = listOf(TraceContext.root(), TraceContext.root())
        listOf("one", "two").mapIndexed { index, session ->
            async { served.call(Correlation().ping, CorrelationRequest(session), Metadata(mapOf(
                TraceContext.Header to parents[index].traceparent,
                Metadata.Baggage to "reaktor-session=$session,reaktor.environment=production",
            ))) }
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
        val served = Served(Correlated { request ->
            request.attributes[ServiceCall.EnvironmentAttribute] = "spoofed"
            delay(10)
        }).intercept(Spans("test", spans) { exchange -> mapOf(
            ServiceCall.BuildAttribute to "revision",
            ServiceCall.EnvironmentAttribute to (exchange.request as CorrelationRequest).session,
        ) })
        listOf("dev", "prod").map { tier -> async { served.call(Correlation().ping, CorrelationRequest(tier)) } }.awaitAll()
        assertEquals(setOf("dev", "prod"), spans.drain().also { recorded ->
            assertEquals(setOf("revision"), recorded.map { it.build }.toSet())
        }.map { it.environment }.toSet())
    }

    @Test
    fun oldSpanPayloadsDecodeWithoutInventedIdentity() = runTest {
        val spans = SpanBuffer()
        Served(Correlated {}).intercept(Spans("test", spans)).call(Correlation().ping, CorrelationRequest())
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

private open class Correlation : Service("test.correlation") {
    open val ping by get<CorrelationRequest, CorrelationResponse>("/ping")
}

private class Correlated(private val work: suspend (CorrelationRequest) -> Unit) : Correlation() {
    override val ping = super.ping.handle { request ->
        work(request)
        CorrelationResponse()
    }
}
