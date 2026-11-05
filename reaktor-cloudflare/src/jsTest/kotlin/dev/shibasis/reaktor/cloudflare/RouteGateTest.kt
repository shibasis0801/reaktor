package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.auth.fga.AccessDecisions
import dev.shibasis.reaktor.auth.fga.AccessMode
import dev.shibasis.reaktor.auth.graph.RouteKind
import dev.shibasis.reaktor.auth.graph.ServedRoute
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.TraceContext
import dev.shibasis.reaktor.service.contract.Service
import dev.shibasis.reaktor.service.contract.Served
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
class RouteGateTest {
    private val prober = AuthContext(principal = PrincipalRef("prober", PrincipalKind.SERVICE), appId = "fixture", audience = "fixture",
        method = AuthMethod.SERVICE_CREDENTIAL)

    private val person = AuthContext(principal = PrincipalRef("ada", PrincipalKind.USER), appId = "fixture", audience = "fixture",
        method = AuthMethod.ACCESS_TOKEN)

    private fun gate(caller: AuthContext?) = RouteGate.Caller("prober only", { _, _ -> caller }) { it.principal.kind == PrincipalKind.SERVICE }

    private suspend fun Hono.answer(path: String, method: String = "GET"): dynamic {
        val request: dynamic = js("new Request('https://fixture.invalid' + path, {method: method})")
        return fetch(request, js("({})"), js("({waitUntil:function(){}})")).unsafeCast<Promise<dynamic>>().await()
    }

    @Test fun aGateRefusesAnUnknownCallerWith401AndRecordsTheRefusal() = GlobalScope.promise {
        AccessDecisions.clear()
        var ran = false
        val response = Hono().get("/_data/probe", gate(null)) { ran = true; text("ok") }.answer("/_data/probe")
        assertEquals(401, response.status as Int)
        assertFalse(ran)
        val decision = AccessDecisions.latest.single()
        assertEquals("user:anonymous", decision.subject)
        assertEquals("can_invoke", decision.relation)
        assertEquals("route:worker/GET/_data/probe", decision.target)
        assertFalse(decision.allowed)
        assertEquals(AccessMode.Enforce, decision.mode)
        assertEquals("prober only", decision.rule)
    }

    @Test fun aKnownCallerTheGateDoesNotAdmitGets403() = GlobalScope.promise {
        AccessDecisions.clear()
        val response = Hono().post("/_data/probe", gate(person)) { text("ok") }.answer("/_data/probe", "POST")
        assertEquals(403, response.status as Int)
        assertEquals("user:ada", AccessDecisions.latest.single().subject)
    }

    @Test fun anAdmittedCallerReachesTheHandlerAndTheDecisionLandsOnTheIncomingCall() = GlobalScope.promise {
        AccessDecisions.clear()
        var seen: AuthContext? = null
        val app = Hono().get("/_reaktor/operations/:operation", gate(prober)) { seen = caller; text(requireParam("operation")) }
        val access = RouteAccess()
        val call = ServiceCall(TraceContext.root(), null, mapOf(ServiceCall.IslandAttribute to "data-probe", RouteAccess.Attribute to access))
        val request: dynamic = js("new Request('https://fixture.invalid/_reaktor/operations/catalog')")
        val response = withContext(call) {
            withRequestContext(request.unsafeCast<Any>()) {
                app.fetch(request, js("({})"), js("({waitUntil:function(){}})")).unsafeCast<Promise<dynamic>>().await()
            }
        }
        assertEquals(200, response.status as Int)
        assertEquals("catalog", response.text().unsafeCast<Promise<String>>().await())
        assertEquals(prober, seen)
        val decision = access.decision
        assertEquals("route:data-probe/GET/_reaktor/operations/:operation", decision?.target)
        assertEquals("service_account:prober", decision?.subject)
        assertTrue(decision?.allowed == true)
        assertEquals(call.trace.traceId, AccessDecisions.latest.single().trace)
    }

    @Test fun aPublicRouteAnswersEveryoneAndSaysWhyItIsPublic() = GlobalScope.promise {
        AccessDecisions.clear()
        val response = Hono().get("/health", RouteGate.Public("liveness")) { text("ok") }.answer("/health")
        assertEquals(200, response.status as Int)
        val decision = AccessDecisions.latest.single()
        assertTrue(decision.allowed)
        assertEquals("liveness", decision.rule)
        assertEquals("user:anonymous", decision.subject)
        assertEquals("route:worker/GET/health", decision.target)
    }

    @Test fun aRawHonoHandlerBehindAGateRunsOnlyOnceAdmitted() = GlobalScope.promise {
        var ran = 0
        val app = Hono()
            .on("POST", "/callback", gate(null)) { ran++; js("new Response('ok')") }
            .on("GET", "/callback", RouteGate.Public("the provider sends the browser back here")) { ran++; js("new Response('thanks')") }
        assertEquals(401, app.answer("/callback", "POST").status as Int)
        assertEquals(200, app.answer("/callback").status as Int)
        assertEquals(1, ran)
    }

    @Test fun servedRoutesNameEveryGateAndShowARouteThatHasNone() = GlobalScope.promise {
        val nested = Hono().get("/inside", RouteGate.Public("nested")) { text("ok") }
        val app = Hono()
            .cors(CorsPolicy(listOf("GET"), emptyList(), 60))
            .mount(Served(GateInspect()))
            .get("/_data/probe", gate(prober)) { text("ok") }
            .route("/api", nested)
            .on("GET", "/open") { js("new Response('open')") }
            .otherwise(dev.shibasis.reaktor.core.network.StatusCode.NOT_FOUND)
        val served = app.served()
        assertEquals(listOf(RouteKind.Middleware), served.filter { it.kind == RouteKind.Middleware }.map { it.kind })
        assertEquals(listOf("GET /inspect"), served.filter { it.kind == RouteKind.Contract }.map { "${it.method} ${it.path}" })
        assertEquals(ServedRoute("GET", "/_data/probe", gate = "prober only"), served.single { it.path == "/_data/probe" })
        assertEquals(ServedRoute("GET", "/api/inside", gate = "nested", public = true), served.single { it.path == "/api/inside" })
        assertEquals(listOf(ServedRoute("GET", "/open")), served.filterNot { it.gated })
        assertNull(served.single { it.path == "/open" }.gate)
        assertEquals(RouteKind.Fallback, served.last().kind)
    }
}

@Serializable private class GateInspectRequest : Request()
@Serializable private class GateInspectResponse : Response()

private class GateInspect : Service("test.gate-inspect") {
    val inspect by get<GateInspectRequest, GateInspectResponse>("/inspect")
}
