package dev.shibasis.reaktor.io.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HttpMetadataInterceptorTest {
    @Test fun metadataHookDoesNotActivateBodyCaptureAndRemovalRestoresTransport() = runTest {
        val calls = arrayListOf<String>()
        val client = HttpClient(MockEngine { request ->
            calls += request.headers["x-metadata"].orEmpty()
            respond("private response", headers = headersOf("Content-Type", "text/plain"))
        }) { observation() }
        val remove = HttpObservation.intercept { request, proceed ->
            request.headers.append("x-metadata", "present")
            proceed(request)
        }
        try {
            assertFalse(HttpObservation.active)
            assertEquals("private response", client.get("https://example.invalid/one").bodyAsText())
            remove()
            remove()
            assertEquals("private response", client.get("https://example.invalid/two").bodyAsText())
            assertEquals(listOf("present", ""), calls)
            assertFalse(HttpObservation.active)
        } finally { remove(); client.close() }
    }

    @Test fun metadataHooksComposeAndExistingConditionsStillApply() = runTest {
        val seen = arrayListOf<String>()
        val client = HttpClient(MockEngine { request ->
            seen += request.headers.getAll("x-order").orEmpty()
            respond("ok")
        }) { observation() }
        val first = HttpObservation.intercept { request, proceed ->
            request.headers.append("x-order", "first")
            proceed(request)
        }
        val second = HttpObservation.intercept { request, proceed ->
            request.headers.append("x-order", "second")
            proceed(request)
        }
        val previous = HttpObservation.conditions.value
        try {
            assertEquals("ok", client.get("https://example.invalid/").bodyAsText())
            assertEquals(listOf("first", "second"), seen)
            HttpObservation.conditions.value = HttpConditions(offline = true)
            val failed = runCatching { client.get("https://example.invalid/") }
            assertEquals(true, failed.isFailure)
            assertEquals(2, seen.size)
        } finally { HttpObservation.conditions.value = previous; first(); second(); client.close() }
    }
}
