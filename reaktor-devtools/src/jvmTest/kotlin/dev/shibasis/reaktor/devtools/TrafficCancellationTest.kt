package dev.shibasis.reaktor.devtools

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.io.network.HttpExchange
import dev.shibasis.reaktor.service.*
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.PortCapabilityImpl
import dev.shibasis.reaktor.portgraph.port.addInterceptor
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class TrafficCancellationTest {
    @Test fun graphPortCapturePreservesCancellationAndLegacyWireDefaults() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val stream = FactStream(AgentCapability.PortEvents).apply { start(scope) }
        val consumer = PortCapabilityImpl().registerConsumer<String>("load")
        val provider = PortCapabilityImpl().registerProvider<String>("load", "value")
        connect(consumer, provider).getOrThrow()
        consumer.addInterceptor(GraphTap(stream))
        try {
            for (failure in listOf(CancellationException("left composition"), java.io.IOException("cancelled by upstream proxy"),
                java.io.IOException("Authorization: Bearer source-secret\nsecond-line"))) {
                assertSame(failure, runCatching { consumer { throw failure } }.exceptionOrNull())
                assertSame(failure, runCatching { consumer.suspended { throw failure } }.exceptionOrNull())
                val facts = stream.latest().facts.takeLast(2).map { it as AgentFact.Port }
                assertTrue(facts.all { it.cancelled == (failure is CancellationException) })
                assertTrue(facts.all { it.durationNanos != null && it.portKey == "load" })
                assertTrue(facts.none { it.failure.orEmpty().contains("source-secret") || it.failure.orEmpty().contains("second-line") })
                val encoded = json.encodeToJsonElement(AgentFact.serializer(), facts.last())
                assertEquals(facts.last().cancelled, (json.decodeFromJsonElement(AgentFact.serializer(), encoded) as AgentFact.Port).cancelled)
                val legacy = JsonObject(encoded.jsonObject - "cancelled")
                assertFalse((json.decodeFromJsonElement(AgentFact.serializer(), legacy) as AgentFact.Port).cancelled)
            }
        } finally { scope.cancel() }
    }

    @Test fun serviceCancellationSurvivesCaptureAndProtocolWithoutTextHeuristics() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val stream = FactStream(AgentCapability.Traffic).apply { start(scope) }
        val tap = TrafficTap(stream, newCorrelationId = { "call" })
        val handler = GetHandler("/circles", requestSerializer = Request.serializer(), responseSerializer = Response.serializer()) { Response() }
        try {
            for (failure in listOf(CancellationException("leaving composition"), java.io.IOException("cancelled by upstream proxy"))) {
                val caught = runCatching {
                    runInterceptorChain(ServiceExecutionPhase.CLIENT, InterceptorStage.CLIENT_APPLICATION,
                        handler, Request(), listOf(tap)) { throw failure }
                }.exceptionOrNull()
                assertSame(failure, caught)
                val fact = stream.latest().facts.last() as AgentFact.Traffic
                assertEquals(failure is CancellationException, fact.cancelled)
                val encoded = json.encodeToJsonElement(AgentFact.serializer(), fact)
                assertEquals(fact.cancelled, (json.decodeFromJsonElement(AgentFact.serializer(), encoded) as AgentFact.Traffic).cancelled)
                val legacy = JsonObject(encoded.jsonObject - "cancelled")
                assertFalse((json.decodeFromJsonElement(AgentFact.serializer(), legacy) as AgentFact.Traffic).cancelled)
            }
        } finally { scope.cancel() }
    }

    @Test fun httpCaptureRetainsCancellationWhileOmittingPayloads() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val stream = FactStream(AgentCapability.Traffic).apply { start(scope) }
        try {
            HttpTap(stream) { 10 }.observe(HttpExchange("GET", "https://example.test/circles", emptyList(),
                "private body", 0, null, emptyList(), "private response", 0, 0, 0, 10,
                "leaving composition", cancelled = true))
            val fact = stream.latest().facts.single() as AgentFact.Traffic
            assertTrue(fact.cancelled)
            assertNull(fact.requestBody)
            assertNull(fact.responseBody)
        } finally { scope.cancel() }
    }
}
