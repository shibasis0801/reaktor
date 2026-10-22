package dev.shibasis.reaktor.service

import dev.shibasis.reaktor.io.network.middleware
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

class GraphHttpAttributionTest {
    @Test fun httpChildRetainsCanonicalGraphAttributionAndRejectsPayloadValues() = runTest {
        val spans = SpanBuffer()
        val client = HttpClient(MockEngine { respond("ok") }) { middleware() }
        val remove = installHttpTracing("test", spans, { true }, { listOf("https://example.invalid") }) { emptyMap() }
        try {
            val metadata = mapOf(ServiceCall.NodeAttribute to "manna/services", ServiceCall.GraphAttribute to "a".repeat(64),
                ServiceCall.PortAttribute to "services", ServiceCall.EdgeAttribute to "edge-1", ServiceCall.ActivationAttribute to "activation-1")
            withContext(ServiceCall(TraceContext.root(), null, metadata)) { client.get("https://example.invalid") }
            val span = spans.snapshot().single()
            assertEquals("manna/services", span.node)
            assertEquals("a".repeat(64), span.graphDigest)
            assertEquals("services", span.port)
            assertEquals("edge-1", span.edge)
            assertEquals("activation-1", span.activation)
            withContext(ServiceCall(TraceContext.root(), null, metadata + (ServiceCall.NodeAttribute to "private?token=secret"))) { client.get("https://example.invalid") }
            assertEquals("", spans.snapshot().last().node)
        } finally { remove(); client.close() }
    }
}
