package dev.shibasis.reaktor.cloud.http

import dev.shibasis.reaktor.cloud.InfrastructureSession
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.http.HttpRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TransportTest {
    private fun serving(vararg statuses: Int, check: (MockWebServer) -> Unit) = MockWebServer().use { server ->
        statuses.forEach { server.enqueue(MockResponse().setResponseCode(it).setHeader("Retry-After", "0").setBody("{}")) }
        server.start()
        check(server)
    }

    private fun request(server: MockWebServer, method: String): HttpRequest {
        val body = if (method == "GET") HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString("{}")
        return HttpRequest.newBuilder(server.url("/resource").toUri()).method(method, body).build()
    }

    private fun status(server: MockWebServer, method: String, repeatable: Boolean? = null) = runBlocking {
        val request = request(server, method)
        (if (repeatable == null) Transport().send(request) else Transport().send(request, repeatable)).statusCode()
    }

    @Test fun repeatsAReadAfterAServerError() = serving(503, 200) { server ->
        assertEquals(200, status(server, "GET"))
        assertEquals(2, server.requestCount)
    }

    @Test fun neverRepeatsAWriteAfterAServerError() = serving(503, 200) { server ->
        assertEquals(503, status(server, "POST"))
        assertEquals(1, server.requestCount)
    }

    @Test fun repeatsAnyRequestTheProviderThrottled() = serving(429, 200) { server ->
        assertEquals(200, status(server, "POST"))
        assertEquals(2, server.requestCount)
    }

    @Test fun repeatsAPostTheCallerMarksAsARead() = serving(502, 200) { server ->
        assertEquals(200, status(server, "POST", repeatable = true))
        assertEquals(2, server.requestCount)
    }

    @Test fun givesUpAfterThreeAttempts() = serving(503, 503, 503, 200) { server ->
        assertEquals(503, status(server, "GET"))
        assertEquals(3, server.requestCount)
    }

    @Test fun boundedReadsRepeatTheSameWay() = serving(503, 200) { server ->
        InfrastructureSession().use { session ->
            assertEquals("{}", BoundedHttp(session).request(server.url("/resource").toUri()))
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun boundedWritesFailOnTheFirstServerError() = serving(503, 200) { server ->
        InfrastructureSession().use { session ->
            assertEquals(503, assertFailsWith<ProviderHttpFailure> { BoundedHttp(session).request(server.url("/resource").toUri(), body = "{}") }.status)
        }
        assertEquals(1, server.requestCount)
    }
}
