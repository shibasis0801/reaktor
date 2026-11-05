package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.auth.fga.AccessDecision
import dev.shibasis.reaktor.auth.fga.AccessDecisions
import dev.shibasis.reaktor.auth.fga.AccessMode
import dev.shibasis.reaktor.auth.graph.CodeAccess
import dev.shibasis.reaktor.auth.graph.RouteKind
import dev.shibasis.reaktor.auth.graph.ServedRoute
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.service.ServiceCall
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

sealed interface RouteGate {
    val rule: String

    class Public(override val rule: String) : RouteGate

    class Caller(
        override val rule: String,
        val authenticate: suspend (request: Any, env: Any) -> AuthContext?,
        val admits: (AuthContext) -> Boolean,
    ) : RouteGate
}

class RouteAccess {
    var decision: AccessDecision? = null
        internal set

    companion object {
        const val Attribute = "reaktor.route_access"
    }
}

class RouteAdmission internal constructor(val decision: AccessDecision, val caller: AuthContext?) {
    val refusal: Int? get() = when {
        decision.allowed -> null
        caller == null -> 401
        else -> 403
    }
}

suspend fun RouteGate.admit(request: Any, env: Any, method: String, template: String): RouteAdmission {
    val call = currentCoroutineContext()[ServiceCall]
    val caller = when (this) {
        is RouteGate.Public -> null
        is RouteGate.Caller -> try {
            authenticate(request, env)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }
    val allowed = when (this) {
        is RouteGate.Public -> true
        is RouteGate.Caller -> caller != null && admits(caller)
    }
    val island = call?.attributes?.get(ServiceCall.IslandAttribute) as? String ?: UnnamedIsland
    val decision = AccessDecision(CodeAccess.subject(caller).toString(), CodeAccess.Invoke, CodeAccess.route(island, method, template).toString(),
        allowed, AccessMode.Enforce, rule, tenant = caller?.tenantId, trace = call?.trace?.traceId, span = call?.trace?.spanId,
        atMillis = Clock.System.now().toEpochMilliseconds())
    (call?.attributes?.get(RouteAccess.Attribute) as? RouteAccess)?.decision = decision
    runCatching { AccessDecisions.record(decision) }
    return RouteAdmission(decision, caller)
}

fun RouteGate.serves(method: String, path: String): ServedRoute = ServedRoute(method.uppercase(), path, gate = rule, public = this is RouteGate.Public)

fun Hono.served(): List<ServedRoute> = (asDynamic().routes as? Array<dynamic>).orEmpty().map { route ->
    val handler = route.handler
    val marked = handler[ComposedHandler] ?: handler
    ServedRoute(route.method as String, route.path as String,
        (marked[KindMark] as? String)?.let(RouteKind::valueOf) ?: RouteKind.Raw,
        marked[GateMark] as? String, marked[PublicMark] == true)
}

internal fun <H> H.marked(kind: RouteKind, gate: RouteGate? = null): H = also {
    val handler = asDynamic()
    handler[KindMark] = kind.name
    if (gate != null) {
        handler[GateMark] = gate.rule
        handler[PublicMark] = gate is RouteGate.Public
    }
}

internal fun refusedResponse(status: Int): dynamic = workerResponse(
    body = if (status == 401) """{"error":"Unauthorized"}""" else """{"error":"Forbidden"}""",
    status = status,
    headers = mapOf("Content-Type" to "application/json; charset=utf-8"),
)

private const val UnnamedIsland = "worker"
private const val KindMark = "reaktorRouteKind"
private const val GateMark = "reaktorRouteGate"
private const val PublicMark = "reaktorRoutePublic"
private const val ComposedHandler = "__COMPOSED_HANDLER"
