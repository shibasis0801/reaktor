package dev.shibasis.reaktor.graph.service

import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.io.serialization.TextSerializer
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.CoRouterFunctionDsl
import org.springframework.web.reactive.function.server.RequestPredicates
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait

fun HttpMethod.toSpring(): org.springframework.http.HttpMethod = org.springframework.http.HttpMethod.valueOf(name)
fun StatusCode.toHttpStatus(): HttpStatus = HttpStatus.valueOf(code)

private val textSerializer = TextSerializer()

fun Service.toRouter(): RouterFunction<ServerResponse> {
    val builder = RouterFunctions.route()
    handlers.forEach {
        @Suppress("UNCHECKED_CAST")
        val handler = it as RequestHandler<Request, Response>

        val predicate = RequestPredicates.method(handler.method.toSpring())
            .and(RequestPredicates.path(handler.route))
            .and(RequestPredicates.accept(MediaType.APPLICATION_JSON))

        builder.route(predicate) { req ->
            mono {
                val body = runCatching { req.bodyToMono(String::class.java).awaitSingleOrNull() }.getOrNull() ?: "{}"
                val request: Request = textSerializer.deserialize(handler.requestSerializer, body)

                request.pathParams.putAll(req.pathVariables())

                req.queryParams().forEach { (key, values) ->
                    values.firstOrNull()?.let { value -> request.queryParams[key] = value }
                }

                req.headers().asHttpHeaders().forEach { key, values ->
                    val value = values.firstOrNull() ?: return@forEach

                    if (key == Environment.Header)
                        request.environment = Environment(value)

                    request.headers[key] = value
                }

                val response = handler(request)

                ServerResponse.status(response.transportStatusCode.toHttpStatus())
                    .headers { httpHeaders -> response.transportHeaders.forEach(httpHeaders::add) }
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValueAndAwait(textSerializer.serialize(handler.responseSerializer, response))
            }
        }
    }
    return builder.build()
}

fun CoRouterFunctionDsl.nest(route: String, service: Service) {
    route.nest {
        add(service.toRouter())
    }
}
