package dev.shibasis.reaktor.performance

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

class MeasureIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun missingHealthDoesNotBecomeZeroOrPerfectHealth() {
        val metrics = json.decodeFromString<MeasureMetrics>("""{
            "crash_free_sessions":{"crash_free_sessions":0,"no_data":true},
            "anr_free_sessions":{"anr_free_sessions":99.4,"no_data":false},
            "cold_launch":{"p95":923,"no_data":false},
            "warm_launch":{"p95":0,"no_data":true},"sizes":null
        }""")
        val report = metrics.report("app", "2026-09-30T00:00:00Z")
        assertEquals(listOf("measure.anr-free-sessions", "measure.cold-launch.p95"), report.metrics.map { it.name })
        assertEquals(99.4, report.metrics.first().value)
        assertEquals("app", report.metrics.first().scope.attributes["measure.appId"])
        assertTrue(MeasureMetrics().report("app", "now").metrics.isEmpty())
    }

    @Test
    fun dashboardReadsAuthenticateBoundPagesAndEncodeFilters() = runBlocking {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            respond("""{"results":[],"meta":{"next":true,"previous":false}}""", headers = headersOf("Content-Type", "application/json"))
        })
        try {
            val service = MeasureService("http://127.0.0.1:47180", "test-access", client)
            val query = MeasureQuery("2026-09-29T00:00:00.000Z", "2026-09-30T00:00:00.000Z", listOf("1.2 debug"), listOf("120"))
            assertTrue(service.sessions("app-id", query, 50, "error login").meta.next)
            val request = requests.single()
            assertEquals("/apps/app-id/sessions", request.url.encodedPath)
            assertEquals("Bearer test-access", request.headers["Authorization"])
            assertEquals("50", request.url.parameters["limit"])
            assertEquals("50", request.url.parameters["offset"])
            assertEquals("1.2 debug", request.url.parameters["versions"])
            assertEquals("120", request.url.parameters["version_codes"])
            assertNull(request.url.parameters["filter_expr"])
            assertEquals("error login", request.url.parameters["free_text"])
            service.spans("app-id", "Load feed", query)
            assertEquals("(version_name:in:\"1.2 debug\" AND version_code:in:\"120\")", requests.last().url.parameters["filter_expr"])
            assertEquals(9, service.handlers.size)
        } finally { client.close() }
    }

    @Test
    fun freshAppsHaveMissingHealthWithoutQueryingInvalidEmptyVersionTuples() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            assertTrue(request.url.encodedPath.endsWith("/filters"))
            respond("""{"versions":null}""", headers = headersOf("Content-Type", "application/json"))
        })
        try {
            val metrics = MeasureService("http://localhost:47180", "local-token", client).metrics("app")
            assertFalse(metrics.recordingsAvailable)
            assertTrue(metrics.report("app", "now").metrics.isEmpty())
        } finally { client.close() }
    }

    @Test
    fun allVersionHealthExpandsTheServerVersionPairs() = runBlocking {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            respond(if (request.url.encodedPath.endsWith("/filters")) """{"versions":[{"name":"1.0","code":"1"}]}"""
                else """{"cold_launch":{"no_data":false,"p95":10}}""", headers = headersOf("Content-Type", "application/json"))
        })
        try {
            val query = MeasureQuery("2026-09-29T00:00:00.000Z", "2026-09-30T00:00:00.000Z")
            val response = MeasureService("http://localhost:47180", "token", client).metrics("app", query)
            assertEquals(10.0, response.coldLaunch?.p95)
            assertEquals("1.0", requests.last().url.parameters["versions"])
            assertEquals("1", requests.last().url.parameters["version_codes"])
            assertEquals(query.from, requests.last().url.parameters["from"])
        } finally { client.close() }
    }

    @Test
    fun failedReadsDoNotDecodeIntoSuccessfulEmptyResults() = runBlocking {
        val client = HttpClient(MockEngine { respond("""{"error":"sensitive server detail"}""", HttpStatusCode.Unauthorized, headersOf("Content-Type", "application/json")) })
        try {
            val failure = assertFailsWith<MeasureRequestFailed> { MeasureService("http://localhost:47180", "secret-token", client).sessions("app") }
            assertTrue(failure.unauthorized)
            assertTrue(failure.message.orEmpty().contains("rejected the access token"))
            assertFalse(failure.message.orEmpty().contains("secret-token"))
            assertFalse(failure.message.orEmpty().contains("sensitive"))
        } finally { client.close() }
    }

    @Test
    fun aRejectedQuerySaysWhyInsteadOfOnlyItsStatus() = runBlocking {
        val client = HttpClient(MockEngine { respond("""{"error":"`severity` must be any combination of: fatal, unhandled, handled"}""", HttpStatusCode.BadRequest, headersOf("Content-Type", "application/json")) })
        try {
            val failure = assertFailsWith<MeasureRequestFailed> { MeasureService("http://localhost:47180", "token", client).problems("app", MeasureProblem.Handled) }
            assertEquals(400, failure.status)
            assertTrue(failure.message.orEmpty().contains("`severity` must be"))
        } finally { client.close() }
    }

    @Test
    fun problemsAskForTheirKindAndKeepCrashedAndHandledTwinsApart() = runBlocking {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            respond("""{"results":[
                {"app_id":"a","id":"g1","type":"java.lang.IllegalStateException","error_type":"error","severity":"fatal","is_custom":false,
                 "message":"boom","method_name":"load","file_name":"Feed.kt","line_number":42,"count":7,"percentage_contribution":63.64,"updated_at":"2026-09-30T10:00:00Z"},
                {"app_id":"a","id":"g1","type":"java.lang.IllegalStateException","error_type":"error","severity":"handled","message":"boom","count":4,"percentage_contribution":36.36}
            ],"meta":{"next":false}}""", headers = headersOf("Content-Type", "application/json"))
        })
        try {
            val service = MeasureService("http://localhost:47180", "token", client)
            val page = service.problems("app", MeasureProblem.Crashes)
            assertEquals("error", requests.last().url.parameters["type"])
            assertEquals("fatal", requests.last().url.parameters["severity"])
            service.problems("app", MeasureProblem.Anrs)
            assertEquals("anr", requests.last().url.parameters["type"])
            assertNull(requests.last().url.parameters["severity"])
            service.problems("app", MeasureProblem.Handled)
            assertEquals("unhandled,handled", requests.last().url.parameters["severity"])
            assertEquals(2, page.results.map { it.key }.distinct().size)
            assertEquals("load · Feed.kt:42", page.results.first().location)
            assertEquals(63.64, page.results.first().share)
        } finally { client.close() }
    }

    @Test
    fun recordingDetailsAcceptMissingPlatformSampleStreams() {
        assertTrue(json.decodeFromString<MeasurePage<MeasureSession>>("""{"results":null,"meta":{"next":false}}""").results.isEmpty())
        assertTrue(json.decodeFromString<MeasureSpanNames>("""{"results":null}""").results.isEmpty())
        val session = json.decodeFromString<MeasureSessionDetail>("""{"session_id":"s","cpu_usage":null,"memory_usage":null,"memory_usage_absolute":null,"threads":{}}""")
        assertTrue(session.cpuUsage.orEmpty().isEmpty())
        assertTrue(session.memoryUsage.orEmpty().isEmpty())
        val span = json.decodeFromString<MeasureSpan>("""{"span_id":"root","checkpoints":null}""")
        assertTrue(span.checkpoints.orEmpty().isEmpty())
    }

    @Test
    fun tracesPreserveHierarchyOffsetsAndRecordingIdentity() {
        val trace = json.decodeFromString<MeasureTrace>("""{
          "app_id":"app","trace_id":"trace","session_id":"session","app_version":"1.2",
          "start_time":"2026-09-30T00:00:00.000Z","duration":100,
          "spans":[
            {"span_id":"child","parent_id":"root","span_name":"load","start_time":"2026-09-30T00:00:00.020Z","duration":30},
            {"span_id":"root","parent_id":"0000000000000000","span_name":"route","user_defined_attributes":{"reaktor_graph_id":"graph","reaktor_operation":"load"},"start_time":"2026-09-30T00:00:00.000Z","duration":100}
          ]
        }""")
        val report = trace.report("2026-09-30T00:01:00Z")
        assertEquals("route", report.flamegraph.single().name)
        assertEquals(20.0, report.flamegraph.single().children.single().startMs)
        assertEquals(30.0, report.flamegraph.single().children.single().durationMs)
        assertEquals("session", report.profiles.single().scope.attributes["measure.sessionId"])
        assertEquals(2, report.profiles.single().sampleCount)
        assertEquals("graph", report.profiles.single().scope.graphId)
        assertEquals("load", report.profiles.single().scope.operation)
        val cycle = trace.copy(spans = trace.spans.map { it.copy(parentId = if (it.id == "root") "child" else "root") })
        assertFailsWith<IllegalArgumentException> { cycle.report("now") }
    }

    @Test
    fun mobileScopesUseIngestCompatibleBoundedKeysAndReserveGraphIdentity() {
        val scope = ReaktorPerformanceScope(graphId = "graph", operation = "load", attributes =
            (1..110).associate { "custom_$it" to "x".repeat(300) } + mapOf("invalid.key" to "bad", "reaktor_graph_id" to "spoof"))
        val attributes = scope.measureAttributes()
        assertEquals("graph", attributes["reaktor_graph_id"])
        assertEquals("load", attributes["reaktor_operation"])
        assertEquals(100, attributes.size)
        assertFalse(attributes.containsKey("invalid.key"))
        assertTrue(attributes.values.all { it.length <= 256 })
    }

    @Test
    fun invalidRangesCredentialsAndIdentifiersFailBeforeIo() = runBlocking {
        assertFailsWith<IllegalArgumentException> { MeasureQuery(from = "2026-09-30T00:00:00Z") }
        assertFailsWith<IllegalArgumentException> { MeasureQuery(versions = listOf("1.2")) }
        assertFailsWith<IllegalArgumentException> { MeasureService("http://public.example", "token") }
        assertFailsWith<IllegalArgumentException> { MeasureService("https://example.com?token=secret", "token") }
        assertFailsWith<IllegalArgumentException> { MeasureService("https://example.com", "token\nheader") }
        assertFailsWith<IllegalArgumentException> { MeasureService("http://localhost", "token").session("app", "../secret") }
        Unit
    }
}
