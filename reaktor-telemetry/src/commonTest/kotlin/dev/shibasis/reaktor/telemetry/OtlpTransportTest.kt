package dev.shibasis.reaktor.telemetry

import dev.shibasis.reaktor.telemetry.export.HttpOtlpTransport
import dev.shibasis.reaktor.telemetry.export.OtlpEndpoint
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OtlpTransportTest {
    private val endpoint = OtlpEndpoint(
        tracesUrl = "https://otlp-gateway-prod-eu-west-2.grafana.net/otlp/v1/traces",
        instanceId = "123456",
        token = "glc_secret",
    )

    @Test
    fun aBatchIsPostedAsJsonWithBasicAuth() = runTest {
        var seenAuth: String? = null
        var seenType: String? = null
        var seenBody: String? = null
        val client = HttpClient(MockEngine { request ->
            seenAuth = request.headers[HttpHeaders.Authorization]
            seenType = request.headers[HttpHeaders.ContentType] ?: request.body.contentType?.toString()
            seenBody = request.body.toString()
            respondOk()
        })

        assertTrue(HttpOtlpTransport(endpoint, client).send("""{"resourceSpans":[]}"""))
        // base64("123456:glc_secret")
        assertEquals("Basic MTIzNDU2OmdsY19zZWNyZXQ=", seenAuth)
        assertTrue(seenType.orEmpty().contains("application/json"), "got $seenType")
    }

    @Test
    fun theTokenIsNeverInTheStringForm() {
        assertFalse(endpoint.toString().contains("glc_secret"))
        assertTrue(endpoint.toString().contains("redacted"))
    }

    @Test
    fun aRateLimitIsRetriedAndThenSucceeds() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            if (calls < 3) respondError(HttpStatusCode.TooManyRequests) else respondOk()
        })

        assertTrue(HttpOtlpTransport(endpoint, client).send("{}"))
        assertEquals(3, calls)
    }

    @Test
    fun aServerFaultIsRetriedUpToTheAttemptLimitThenCounted() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { calls++; respondError(HttpStatusCode.BadGateway) })
        val transport = HttpOtlpTransport(endpoint, client, maxAttempts = 3)

        assertFalse(transport.send("{}"))
        assertEquals(3, calls)
        assertEquals(1, transport.status.batchesRejected)
        assertFalse(transport.status.healthy)
    }

    @Test
    fun aRefusedPayloadIsNotRetried() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { calls++; respondError(HttpStatusCode.BadRequest) })
        val transport = HttpOtlpTransport(endpoint, client)

        assertFalse(transport.send("{}"))
        assertEquals(1, calls, "a 400 is permanent for this payload and must not be resent")
        assertTrue(transport.status.lastError.orEmpty().contains("not retried"))
    }

    @Test
    fun aNetworkFailureIsCountedRatherThanThrown() = runTest {
        val client = HttpClient(MockEngine { throw RuntimeException("dns failure") })
        val transport = HttpOtlpTransport(endpoint, client, maxAttempts = 2)

        assertFalse(transport.send("{}"))
        assertEquals("dns failure", transport.status.lastError)
        assertEquals(1, transport.status.batchesRejected)
    }

    @Test
    fun statusReportsWhatActuallyLeft() = runTest {
        val client = HttpClient(MockEngine { respondOk() })
        val transport = HttpOtlpTransport(endpoint, client)

        repeat(4) { transport.send("{}") }

        assertEquals(4, transport.status.batchesSent)
        assertEquals(200, transport.status.lastHttpStatus)
        assertTrue(transport.status.healthy)
    }

    @Test
    fun anInsecureEndpointIsRefusedAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            OtlpEndpoint("http://otlp.example.com/v1/traces", "1", "t")
        }
        // A local collector over plain http stays allowed.
        OtlpEndpoint("http://localhost:4318/v1/traces", "1", "t")
    }
}
