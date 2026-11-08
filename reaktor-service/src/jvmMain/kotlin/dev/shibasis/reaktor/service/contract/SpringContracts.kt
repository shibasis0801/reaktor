package dev.shibasis.reaktor.service.contract

import dev.shibasis.reaktor.service.HttpMethod
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter
import org.springframework.web.reactive.function.server.RequestPredicate
import org.springframework.web.reactive.function.server.RequestPredicates
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyAndAwait
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer

fun Served<*>.toRouter(): RouterFunction<ServerResponse> {
    val served = this
    if (served.calls.isEmpty() && served.streams.isEmpty()) return Unrouted
    val builder = RouterFunctions.route()
    served.calls.forEach { call ->
        builder.route(call.binding.predicate()) { request ->
            mono { served.handle(call, request.inbound()).respond() }
        }
    }
    served.streams.forEach { stream ->
        builder.route(stream.binding.predicate()) { request ->
            mono {
                when (val opened = served.open(stream, request.inbound())) {
                    is Opened.Refused -> opened.outbound.respond()
                    is Opened.Streaming -> ServerResponse.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                        .bodyAndAwait(opened.elements.map { element -> ServerSentEvent.builder(element).build() }
                            .onStart { emit(ServerSentEvent.builder<String>().comment("open").build()) })
                }
            }
        }
    }
    return builder.build()
}

fun Iterable<Served<*>>.toRouter(): RouterFunction<ServerResponse> =
    map { it.toRouter() }.reduceOrNull { all, next -> all.and(next) } ?: Unrouted

class Listening internal constructor(private val server: DisposableServer) : AutoCloseable {
    val port: Int get() = server.port()
    override fun close() = server.disposeNow()
}

fun RouterFunction<ServerResponse>.listen(host: String, port: Int): Listening =
    Listening(HttpServer.create().host(host).port(port).handle(ReactorHttpHandlerAdapter(RouterFunctions.toHttpHandler(this))).bindNow())

private suspend fun ServerRequest.inbound(): Inbound {
    val headers = mutableMapOf<String, String>()
    headers().asHttpHeaders().forEach { name, values -> values.firstOrNull()?.let { headers[name] = it } }
    return Inbound(
        pathParams = pathVariables(),
        query = queryParams().mapValues { it.value.toList() },
        headers = headers,
        body = runCatching { bodyToMono(String::class.java).awaitSingleOrNull() }.getOrNull(),
    )
}

private suspend fun Outbound.respond(): ServerResponse {
    val response = ServerResponse.status(HttpStatusCode.valueOf(status)).headers { carried -> headers.forEach { (name, value) -> carried.set(name, value) } }
    return if (status in Bodiless && body == "null") response.buildAndAwait() else response.bodyValueAndAwait(body)
}

private fun Route.predicate(): RequestPredicate = RequestPredicates.method(method.toSpring()).and(RequestPredicates.path(path))

private val Bodiless = setOf(202, 204)

private val Unrouted = RouterFunction<ServerResponse> { Mono.empty() }

private fun HttpMethod.toSpring(): org.springframework.http.HttpMethod = org.springframework.http.HttpMethod.valueOf(name)
