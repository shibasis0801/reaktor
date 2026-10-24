package dev.shibasis.reaktor.service.contract

import dev.shibasis.reaktor.service.HttpMethod
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.springframework.http.HttpStatusCode
import org.springframework.web.reactive.function.server.RequestPredicates
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import reactor.core.publisher.Mono

fun Served<*>.toRouter(): RouterFunction<ServerResponse> {
    val served = this
    if (served.calls.isEmpty()) return Unrouted
    val builder = RouterFunctions.route()
    served.calls.forEach { call ->
        val predicate = RequestPredicates.method(call.binding.method.toSpring()).and(RequestPredicates.path(call.binding.path))
        builder.route(predicate) { request ->
            mono {
                val headers = mutableMapOf<String, String>()
                request.headers().asHttpHeaders().forEach { name, values -> values.firstOrNull()?.let { headers[name] = it } }
                val inbound = Inbound(
                    pathParams = request.pathVariables(),
                    query = request.queryParams().mapValues { it.value.toList() },
                    headers = headers,
                    body = runCatching { request.bodyToMono(String::class.java).awaitSingleOrNull() }.getOrNull(),
                )
                val outbound = served.handle(call, inbound)
                ServerResponse.status(HttpStatusCode.valueOf(outbound.status))
                    .headers { response -> outbound.headers.forEach { (name, value) -> response.set(name, value) } }
                    .bodyValueAndAwait(outbound.body)
            }
        }
    }
    return builder.build()
}

fun Iterable<Served<*>>.toRouter(): RouterFunction<ServerResponse> =
    map { it.toRouter() }.reduceOrNull { all, next -> all.and(next) } ?: Unrouted

private val Unrouted = RouterFunction<ServerResponse> { Mono.empty() }

private fun HttpMethod.toSpring(): org.springframework.http.HttpMethod = org.springframework.http.HttpMethod.valueOf(name)
