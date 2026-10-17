package dev.shibasis.reaktor.cloudflare

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
class CorsTracePropagationTest {
    @Test fun browserTracePreflightRetainsTheExistingOriginAndMethods() = GlobalScope.promise {
        val app = Hono().cors(CorsPolicy(listOf("GET", "OPTIONS"), listOf("Content-Type"), 86400, "https://bestbuds.ai"))
        val request: dynamic = js("new Request('https://fixture.invalid/', {method:'OPTIONS', headers:{Origin:'https://bestbuds.ai', 'Access-Control-Request-Method':'GET', 'Access-Control-Request-Headers':'traceparent,baggage'}})")
        val response = app.fetch(request).unsafeCast<Promise<dynamic>>().await()
        val headers = response.headers
        val allowed = (headers.get("access-control-allow-headers") as String).lowercase().split(',').map(String::trim)
        assertEquals(204, response.status as Int)
        assertEquals("https://bestbuds.ai", headers.get("access-control-allow-origin") as String)
        assertEquals("GET,OPTIONS", headers.get("access-control-allow-methods") as String)
        assertEquals("86400", headers.get("access-control-max-age") as String)
        assertTrue(allowed.containsAll(listOf("content-type", "traceparent", "baggage")))
        assertFalse(allowed.contains("x-unapproved"))
        assertFalse(headers.has("access-control-allow-credentials") as Boolean)
    }

    @Test fun existingTraceHeadersAreDeduplicatedWithoutLosingProductHeaders() = GlobalScope.promise {
        val app = Hono().cors(CorsPolicy(listOf("POST", "OPTIONS"), listOf("Authorization", "TraceParent", "BAGGAGE"), 60))
        val request: dynamic = js("new Request('https://fixture.invalid/', {method:'OPTIONS', headers:{Origin:'https://bestbuds.ai', 'Access-Control-Request-Method':'POST', 'Access-Control-Request-Headers':'authorization,traceparent,baggage'}})")
        val response = app.fetch(request).unsafeCast<Promise<dynamic>>().await()
        val allowed = (response.headers.get("access-control-allow-headers") as String).lowercase().split(',').map(String::trim)
        assertEquals(listOf("authorization", "traceparent", "baggage"), allowed)
        assertEquals("*", response.headers.get("access-control-allow-origin") as String)
    }
}
