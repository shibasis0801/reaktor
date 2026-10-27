package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.TraceContext
import dev.shibasis.reaktor.service.contract.Effect
import kotlinx.coroutines.withContext
import org.springframework.web.reactive.function.server.CoRouterFunctionDsl
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse

class WorkflowRoutes(private val router: CoRouterFunctionDsl, private val workflow: Workflow?, private val prefix: String = "") {
    private fun handler(method: String, path: String, effect: Effect, execute: suspend (ServerRequest) -> ServerResponse): suspend (ServerRequest) -> ServerResponse {
        val workflow = workflow ?: return execute
        val route = "$prefix$path"
        val entry = workflow.activity("$method $route", effect, mapOf("ingress" to "http", "method" to method, "route" to route), execute)
        return { request ->
            val incoming = TraceContext.parse(request.headers().firstHeader(TraceContext.Header))
            val trace = incoming ?: TraceContext.root()
            withContext(ServiceCall(trace, null, mapOf(ServiceCall.IslandAttribute to "reaktor-server"))) { entry(request) }
        }
    }

    fun GET(path: String, effect: Effect = Effect.LiveRead, execute: suspend (ServerRequest) -> ServerResponse) {
        router.GET(path, handler("GET", path, effect, execute))
    }

    fun POST(path: String, effect: Effect = Effect.ProductionReversibleWrite, execute: suspend (ServerRequest) -> ServerResponse) {
        router.POST(path, handler("POST", path, effect, execute))
    }

    fun DELETE(path: String, effect: Effect = Effect.Destructive, execute: suspend (ServerRequest) -> ServerResponse) {
        router.DELETE(path, handler("DELETE", path, effect, execute))
    }
}
