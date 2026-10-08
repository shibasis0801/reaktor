package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.service.GetHandler
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.Service
import dev.shibasis.reaktor.service.ServiceCall
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import kotlinx.serialization.Serializable
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
class RawRouteTraceContextTest {
    @Test fun aRawRouteRetainsTheIncomingCallForNestedTypedEndpoints() = GlobalScope.promise {
        var observed: ServiceCall? = null
        val service = object : Service() {
            val inspect = GetHandler<RawTraceRequest, RawTraceResponse>("/inspect") {
                observed = currentCoroutineContext()[ServiceCall]
                RawTraceResponse()
            }
        }
        val app = Hono().get("/raw") { service.inspect(RawTraceRequest()); text("ok") }
        val request: dynamic = js("new Request('https://fixture.invalid/raw', {headers:{traceparent:'00-11111111111111111111111111111111-2222222222222222-01', baggage:'reaktor-session=raw-session'}})")
        val response = app.fetch(request, js("({})"), js("({waitUntil:function(){}})")).unsafeCast<Promise<dynamic>>().await()
        assertEquals(200, response.status as Int)
        val call = assertNotNull(observed)
        assertEquals("1".repeat(32), call.trace.traceId)
        assertEquals("2".repeat(16), call.parentSpanId)
        assertEquals("raw-session", call.attributes[ServiceCall.SessionAttribute])
    }

    @Test fun malformedIdentityCannotCreateRawRouteContext() = GlobalScope.promise {
        var observed: ServiceCall? = null
        val app = Hono().get("/raw") { observed = currentCoroutineContext()[ServiceCall]; text("ok") }
        val request: dynamic = js("new Request('https://fixture.invalid/raw', {headers:{traceparent:'00-00000000000000000000000000000000-2222222222222222-01', baggage:'reaktor-session=private@example.invalid'}})")
        app.fetch(request, js("({})"), js("({waitUntil:function(){}})")).unsafeCast<Promise<dynamic>>().await()
        assertNull(observed)
    }

    @Test fun concurrentRawCallbacksRetainSeparateSessionsAcrossSuspension() = GlobalScope.promise {
        val seen = mutableMapOf<String, Pair<String, String?>>()
        val app = Hono().get("/raw") {
            val before = assertNotNull(currentCoroutineContext()[ServiceCall])
            delay(1)
            val after = assertNotNull(currentCoroutineContext()[ServiceCall])
            val session = after.attributes[ServiceCall.SessionAttribute] as String
            seen[session] = before.trace.traceId to after.trace.traceId
            text("ok")
        }
        (1..8).map { n -> GlobalScope.async {
            val trace = n.toString(16).padStart(32, '0')
            val header = "00-$trace-2222222222222222-01"
            val session = "raw-$n"
            val request: dynamic = js("new Request('https://fixture.invalid/raw', {headers:{traceparent:header, baggage:'reaktor-session='+session}})")
            app.fetch(request, js("({})"), js("({waitUntil:function(){}})")).unsafeCast<Promise<dynamic>>().await()
        } }.awaitAll()
        assertEquals(8, seen.size)
        for (n in 1..8) assertEquals(n.toString(16).padStart(32, '0') to n.toString(16).padStart(32, '0'), seen["raw-$n"])
    }
}

@Serializable private class RawTraceRequest : Request()
@Serializable private class RawTraceResponse : Response()
