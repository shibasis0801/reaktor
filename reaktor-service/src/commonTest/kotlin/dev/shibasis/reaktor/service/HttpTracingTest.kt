package dev.shibasis.reaktor.service

import dev.shibasis.reaktor.io.network.HttpObservation
import dev.shibasis.reaktor.io.network.middleware
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class HttpTracingTest {
    private val origins = { listOf("https://example.invalid") }
    private val resources = mapOf(ServiceCall.SessionAttribute to "raw-session",
        ServiceCall.EnvironmentAttribute to "dev", ServiceCall.BuildAttribute to "fixture.build")

    @Test fun binaryUploadPreservesPayloadAndRecordsOnlyJoinedMetadata() = runTest {
        val bytes = byteArrayOf(0, 1, 2, 3, -1)
        val spans = SpanBuffer()
        var sent: ByteArray? = null
        var header: String? = null
        var baggage: String? = null
        val client = HttpClient(MockEngine { request ->
            sent = (request.body as OutgoingContent.ByteArrayContent).bytes()
            header = request.headers[TraceContext.Header]
            baggage = request.headers["baggage"]
            respond("private response", HttpStatusCode.Created, headersOf("Content-Type", "text/plain"))
        }) { middleware() }
        val remove = installHttpTracing("test-client", spans, { true }, origins) { resources }
        val parent = TraceContext.root()
        try {
            withContext(ServiceCall(parent, null, resources)) {
                assertEquals("private response", client.post("https://example.invalid/private-object?token=secret") {
                    contentType(ContentType.Application.OctetStream)
                    setBody(bytes)
                }.bodyAsText())
            }
            assertContentEquals(bytes, sent)
            val span = spans.snapshot().single()
            assertEquals(parent.traceId, span.traceId)
            assertEquals(parent.spanId, span.parentId)
            assertNotEquals(parent.spanId, span.spanId)
            assertEquals(span.spanId, TraceContext.parse(header)?.spanId)
            assertEquals("reaktor-session=raw-session", baggage)
            assertEquals(201, span.status)
            assertEquals("reaktor.http", span.contract)
            assertEquals("POST.request", span.operation)
            assertEquals("dev", span.environment)
            assertEquals("fixture.build", span.build)
            assertEquals("", span.node)
            assertEquals("", span.graphDigest)
            assertFalse(HttpObservation.active)
            val encoded = Json.encodeToString(span)
            for (privateValue in listOf("private-object", "secret", "private response", "https://")) assertFalse(privateValue in encoded)
            assertNull(currentCoroutineContext()[ServiceCall])
        } finally { remove(); client.close() }
    }

    @Test fun typedServiceAndRepeatedInstallDoNotDuplicateItsAlreadyPropagatedSpan() = runTest {
        val spans = SpanBuffer()
        val client = HttpClient(MockEngine { respond("{}", headers = headersOf("Content-Type", "application/json")) }) { middleware() }
        val service = object : Service("https://example.invalid", client) {
            override val contract = ServiceContract("test.typed")
            val ping = GetHandler<RawTraceRequest, RawTraceResponse>("/ping")
            init { use(TracePropagation, SpanRecorder("test-client", spans, resources)) }
        }
        val first = installHttpTracing("test-client", spans, { true }, origins) { resources }
        val second = installHttpTracing("test-client", spans, { true }, origins) { resources }
        try {
            withContext(Dispatchers.Default) { service.ping(RawTraceRequest()) }
            assertEquals(1, spans.pending)
            assertEquals("test.typed", spans.snapshot().single().contract)
            client.get("https://example.invalid/raw")
            assertEquals(2, spans.pending)
            assertEquals(1, spans.snapshot().count { it.contract == "reaktor.http" })
            val export = object : Service("https://example.invalid", client) {
                override val contract = ServiceContract("bestbuds.analytics")
                val ship = GetHandler<RawTraceRequest, RawTraceResponse>("/analytics")
                init { use(TracePropagation) }
            }
            withContext(Dispatchers.Default) { export.ship(RawTraceRequest()) }
            assertEquals(2, spans.pending)
        } finally { first(); second(); client.close() }
    }

    @Test fun metadataPrecedencePreservesIncomingParentAndVendorBaggageWithoutInventingNodeIdentity() = runTest {
        val spans = SpanBuffer()
        val headers = arrayListOf<Pair<String?, String?>>()
        val client = HttpClient(MockEngine { request ->
            headers += request.headers[TraceContext.Header] to request.headers["baggage"]
            respond("ok")
        }) { middleware() }
        val digest = "a".repeat(64)
        val remove = installHttpTracing("test-client", spans, { true }, origins) { resources + (ServiceCall.GraphAttribute to "b".repeat(64)) }
        val incoming = TraceContext.root().copy(sampled = false)
        val parent = TraceContext.root()
        try {
            client.get("https://example.invalid/") {
                this.headers.append(TraceContext.Header, incoming.traceparent)
                this.headers.append("baggage", "vendor-key=opaque,reaktor-session=old")
            }
            withContext(ServiceCall(parent, null, mapOf(
                ServiceCall.SessionAttribute to "caller-session", ServiceCall.EnvironmentAttribute to "prod",
                ServiceCall.BuildAttribute to "caller.build", ServiceCall.GraphAttribute to digest,
                ServiceCall.NodeAttribute to "random-activation", "private" to "private-value",
            ))) {
                client.get("https://example.invalid/") {
                    this.headers.append("baggage", "vendor-key=opaque,reaktor-session=old")
                }
            }
            val first = spans.snapshot()[0]
            assertEquals(incoming.traceId, first.traceId)
            assertEquals(incoming.spanId, first.parentId)
            assertEquals(false, TraceContext.parse(headers[0].first)?.sampled)
            assertEquals("b".repeat(64), first.graphDigest)
            assertEquals("vendor-key=opaque,reaktor-session=raw-session", headers[0].second)
            val second = spans.snapshot()[1]
            assertEquals(parent.traceId, second.traceId)
            assertEquals(parent.spanId, second.parentId)
            assertEquals("caller-session", second.applicationSession)
            assertEquals(digest, second.graphDigest)
            assertEquals("dev", second.environment)
            assertEquals("fixture.build", second.build)
            assertEquals("", second.node)
            assertEquals("vendor-key=opaque,reaktor-session=caller-session", headers[1].second)
            assertFalse("private-value" in Json.encodeToString(second))
            assertTrue(spans.snapshot().all { it.durationMillis >= 0 && it.durationMillis.isFinite() })
        } finally { remove(); client.close() }
    }

    @Test fun onlyExactDeclaredOriginsReceiveIdentityWhileExternalCallsRemainJoinedLocally() = runTest {
        val spans = SpanBuffer()
        val requests = arrayListOf<Pair<String?, String?>>()
        val payload = byteArrayOf(7, 0, -1)
        val original = TraceContext.root()
        val client = HttpClient(MockEngine { request ->
            requests += request.headers[TraceContext.Header] to request.headers["baggage"]
            assertEquals("caller-value", request.headers["X-Caller"])
            assertContentEquals(payload, (request.body as OutgoingContent.ByteArrayContent).bytes())
            respond("ok")
        }) { middleware() }
        val remove = installHttpTracing("test-client", spans, { true }, origins)
            { resources }
        val parent = TraceContext.root()
        try {
            withContext(ServiceCall(parent, null, resources)) {
                for (address in listOf("https://example.invalid:443/upload", "https://external.invalid/upload",
                    "https://example.invalid.external.invalid/upload", "http://example.invalid/upload",
                    "https://example.invalid:8443/upload")) {
                    assertEquals("ok", client.post(address) {
                        contentType(ContentType.Application.OctetStream)
                        setBody(payload)
                        headers.append(TraceContext.Header, original.traceparent)
                        headers.append("baggage", "vendor-key=opaque,reaktor-session=caller-owned")
                        headers.append("X-Caller", "caller-value")
                    }.bodyAsText())
                }
            }
            assertEquals(5, spans.pending)
            assertNotEquals(original.traceparent, requests[0].first)
            assertEquals("vendor-key=opaque,reaktor-session=raw-session", requests[0].second)
            requests.drop(1).forEach {
                assertEquals(original.traceparent, it.first)
                assertEquals("vendor-key=opaque,reaktor-session=caller-owned", it.second)
            }
            spans.snapshot().forEach {
                assertEquals(parent.traceId, it.traceId)
                assertEquals(parent.spanId, it.parentId)
                assertEquals("raw-session", it.applicationSession)
                assertEquals(200, it.status)
                assertFalse("upload" in Json.encodeToString(it))
            }
        } finally { remove(); client.close() }
    }

    @Test fun trustedRedirectRestoresOwnedHeadersBeforeTheExternalHopAndDoesNotDuplicateSpans() = runTest {
        val spans = SpanBuffer()
        val requests = arrayListOf<Pair<String?, String?>>()
        val client = HttpClient(MockEngine { request ->
            requests += request.headers[TraceContext.Header] to request.headers["baggage"]
            if (request.url.host == "example.invalid")
                respond("", HttpStatusCode.Found, headersOf("Location", "https://external.invalid/media"))
            else respond("media")
        }) { middleware() }
        val first = installHttpTracing("test-client", spans, { true }, origins) { resources }
        val second = installHttpTracing("test-client", spans, { true }, origins) { resources }
        val parent = TraceContext.root()
        val original = TraceContext.root()
        try {
            withContext(ServiceCall(parent, null, resources)) {
                assertEquals("media", client.get("https://example.invalid/media") {
                    headers.append("baggage", "vendor-key=opaque")
                }.bodyAsText())
                assertEquals("media", client.get("https://example.invalid/media") {
                    headers.append(TraceContext.Header, original.traceparent)
                    headers.append("baggage", "vendor-key=opaque,reaktor-session=caller-owned")
                }.bodyAsText())
            }
            assertEquals(4, requests.size)
            assertEquals("vendor-key=opaque,reaktor-session=raw-session", requests[0].second)
            assertTrue(TraceContext.parse(requests[0].first) != null)
            assertNull(requests[1].first)
            assertEquals("vendor-key=opaque", requests[1].second)
            assertNotEquals(original.traceparent, requests[2].first)
            assertEquals("vendor-key=opaque,reaktor-session=raw-session", requests[2].second)
            assertEquals(original.traceparent, requests[3].first)
            assertEquals("vendor-key=opaque,reaktor-session=caller-owned", requests[3].second)
            assertEquals(listOf(302, 200, 302, 200), spans.snapshot().map { it.status })
            assertEquals(4, spans.snapshot().map { it.spanId }.toSet().size)
            spans.snapshot().forEach {
                assertEquals(parent.traceId, it.traceId)
                assertEquals(parent.spanId, it.parentId)
                assertEquals("raw-session", it.applicationSession)
            }
        } finally { first(); second(); client.close() }
    }

    @Test fun invalidMetadataCannotBecomeJournalIdentityAndRemovalStopsRecording() = runTest {
        val spans = SpanBuffer()
        var trace: String? = null
        val client = HttpClient(MockEngine { request -> trace = request.headers[TraceContext.Header]; respond("ok") }) { middleware() }
        val remove = installHttpTracing("test-client", spans, { true }, origins) {
            mapOf(ServiceCall.SessionAttribute to "private@session", ServiceCall.EnvironmentAttribute to "dev?secret",
                ServiceCall.BuildAttribute to "bad build", ServiceCall.GraphAttribute to "runtime-activation")
        }
        try {
            client.get("https://example.invalid/") { headers.append(TraceContext.Header, "malformed") }
            val span = spans.snapshot().single()
            assertTrue(TraceContext.parse(trace) != null)
            assertEquals("", span.parentId)
            assertEquals("", span.applicationSession)
            assertEquals("", span.environment)
            assertEquals("", span.build)
            assertEquals("", span.graphDigest)
            remove()
            remove()
            client.get("https://example.invalid/")
            assertEquals(1, spans.pending)
        } finally { remove(); client.close() }
    }

    @Test fun simultaneousOwnersWithIdenticalResourcesKeepTheirOwnCallsAndLeaveUnrelatedCallsUntouched() = runTest {
        val ownerKey = "reaktor.activation.id"
        for (reverse in listOf(false, true)) {
            val firstSpans = SpanBuffer()
            val secondSpans = SpanBuffer()
            val internalCalls = arrayListOf<ServiceCall?>()
            val sent = arrayListOf<String?>()
            val client = HttpClient(MockEngine { request ->
                sent += request.headers[TraceContext.Header]
                respond("ok")
            }) { middleware() }
            fun first() = installHttpTracing("first-client", firstSpans,
                { call -> call?.attributes?.get(ownerKey) == "owner-first" }, origins) { resources }
            fun second() = installHttpTracing("second-client", secondSpans,
                { call -> call?.attributes?.get(ownerKey) == "owner-second" }, origins) { resources }
            val removeFirst: () -> Unit
            val removeSecond: () -> Unit
            if (reverse) { removeSecond = second(); removeFirst = first() }
            else { removeFirst = first(); removeSecond = second() }
            val removeProbe = HttpObservation.intercept { request, proceed ->
                internalCalls += currentCoroutineContext()[ServiceCall]
                proceed(request)
            }
            val firstParent = TraceContext.root()
            val secondParent = TraceContext.root()
            val firstCall = ServiceCall(firstParent, null, resources + (ownerKey to "owner-first") + ("private" to "private-value"))
            val secondCall = ServiceCall(secondParent, null, resources + (ownerKey to "owner-second") + ("private" to "private-value"))
            try {
                assertEquals("ok", client.get("https://example.invalid/unrelated").bodyAsText())
                withContext(ServiceCall(TraceContext.root(), null, resources + (ownerKey to "unknown-owner"))) {
                    client.get("https://example.invalid/unrelated")
                }
                assertTrue(sent.all { it == null })
                assertEquals(0, firstSpans.pending + secondSpans.pending)
                withContext(secondCall) { withContext(Dispatchers.Default) { client.get("https://example.invalid/owned") } }
                assertEquals(0, firstSpans.pending)
                val secondSpan = secondSpans.snapshot().single()
                assertEquals("second-client", secondSpan.island)
                assertEquals(secondParent.traceId, secondSpan.traceId)
                assertEquals(secondParent.spanId, secondSpan.parentId)
                assertEquals(secondSpan.spanId, TraceContext.parse(sent.last())?.spanId)
                assertEquals("owner-second", internalCalls.last()?.attributes?.get(ownerKey))
                assertEquals("private-value", internalCalls.last()?.attributes?.get("private"))
                assertEquals(secondSpan.spanId, internalCalls.last()?.trace?.spanId)
                withContext(firstCall) { client.get("https://example.invalid/owned") }
                assertEquals("first-client", firstSpans.snapshot().single().island)
                assertEquals(firstParent.spanId, firstSpans.snapshot().single().parentId)
                removeFirst(); removeFirst()
                withContext(secondCall) { client.get("https://example.invalid/still-owned") }
                assertEquals(2, secondSpans.pending)
                withContext(firstCall) { client.get("https://example.invalid/closed-owner") }
                assertNull(sent.last())
                assertEquals(1, firstSpans.pending)
                assertEquals(2, secondSpans.pending)
                (firstSpans.snapshot() + secondSpans.snapshot()).forEach {
                    assertEquals("raw-session", it.applicationSession)
                    assertEquals("dev", it.environment)
                    assertEquals("fixture.build", it.build)
                    assertEquals("", it.node)
                    assertEquals("", it.graphDigest)
                    assertFalse("private-value" in Json.encodeToString(it))
                    assertFalse("owner-first" in Json.encodeToString(it) || "owner-second" in Json.encodeToString(it))
                }
                removeSecond()
                withContext(secondCall) { client.get("https://example.invalid/closed-owner") }
                assertNull(sent.last())
                assertEquals(2, secondSpans.pending)
                assertNull(currentCoroutineContext()[ServiceCall])
            } finally { removeProbe(); removeFirst(); removeSecond(); client.close() }
        }
    }

    @Test fun concurrentRawCallsKeepTheirOwnTraceAndSessionAcrossDispatcherSwitches() = runTest {
        val spans = SpanBuffer()
        val observed = arrayListOf<Pair<String?, String?>>()
        val lock = SynchronizedObject()
        val client = HttpClient(MockEngine { request ->
            delay(2)
            synchronized(lock) { observed += request.headers[TraceContext.Header] to request.headers["baggage"] }
            respond("ok")
        }) { middleware() }
        val remove = installHttpTracing("test-client", spans, { true }, origins) { resources }
        val parents = List(20) { TraceContext.root() }
        try {
            parents.mapIndexed { index, parent -> async {
                withContext(ServiceCall(parent, null, resources + (ServiceCall.SessionAttribute to "session-$index"))) {
                    withContext(Dispatchers.Default) { client.get("https://example.invalid/") }
                }
            } }.awaitAll()
            val rows = spans.snapshot().associateBy { it.applicationSession }
            assertEquals(20, rows.size)
            parents.forEachIndexed { index, parent ->
                val span = rows.getValue("session-$index")
                assertEquals(parent.traceId, span.traceId)
                assertEquals(parent.spanId, span.parentId)
                assertEquals(1, observed.count { TraceContext.parse(it.first)?.spanId == span.spanId && it.second == "reaktor-session=session-$index" })
            }
        } finally { remove(); client.close() }
    }

    @Test fun failuresCancellationAndSinkFailuresKeepTheOriginalTransportOutcome() = runTest {
        for (failure in listOf(IllegalStateException("private transport failure"), CancellationException("private cancel"))) {
            val baselineClient = HttpClient(MockEngine { throw failure }) { middleware() }
            val baseline = try {
                assertNotNull(runCatching { baselineClient.get("https://example.invalid/") }.exceptionOrNull())
            } finally { baselineClient.close() }
            assertSame(failure, generateSequence(baseline) { it.cause }.take(8).last())
            val spans = SpanBuffer()
            val client = HttpClient(MockEngine { throw failure }) { middleware() }
            val remove = installHttpTracing("test-client", spans, { true }, origins) { resources }
            try {
                val observed = assertNotNull(runCatching { client.get("https://example.invalid/") }.exceptionOrNull())
                assertEquals(baseline::class, observed::class)
                assertEquals(baseline.message, observed.message)
                assertSame(failure, generateSequence(observed) { it.cause }.take(8).last())
                assertEquals(if (failure is CancellationException) 499 else 500, spans.snapshot().single().status)
                assertFalse("private" in Json.encodeToString(spans.snapshot().single()))
            } finally { remove(); client.close() }
        }
        val client = HttpClient(MockEngine { respond("ok") }) { middleware() }
        val remove = installHttpTracing("test-client", SpanSink { error("private sink failure") }, { true }, origins) { resources }
        try { assertEquals("ok", client.get("https://example.invalid/").bodyAsText()) }
        finally { remove(); client.close() }
    }

    @Test fun rawTransportDirectionalBurstUsesExistingBoundedSink() = runTest {
        val client = HttpClient(MockEngine { respond("ok") }) { middleware() }
        val baseline = arrayListOf<Double>()
        val observed = arrayListOf<Double>()
        val spans = SpanBuffer(1024)
        try {
            repeat(50) { client.get("https://example.invalid/") }
            repeat(5) {
                val started = TimeSource.Monotonic.markNow()
                repeat(200) { client.get("https://example.invalid/") }
                baseline += started.elapsedNow().inWholeNanoseconds / 1_000_000.0 / 200
            }
            val remove = installHttpTracing("test-client", spans, { true }, origins) { resources }
            try {
                repeat(5) {
                    val started = TimeSource.Monotonic.markNow()
                    repeat(200) { client.get("https://example.invalid/") }
                    observed += started.elapsedNow().inWholeNanoseconds / 1_000_000.0 / 200
                }
            } finally { remove() }
            assertEquals(1000, spans.pending)
            assertEquals(0, spans.dropped)
            println("RAW_HTTP_DIRECTIONAL baseline_ms=$baseline observed_ms=$observed accepted=${spans.pending}")
        } finally { client.close() }
    }
}

@Serializable private class RawTraceRequest : Request()
@Serializable private class RawTraceResponse : Response()
