package dev.shibasis.reaktor.auth.framework

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.core.network.ErrorMessage
import dev.shibasis.reaktor.core.network.StatusCode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.config.PathMatchConfigurer
import org.springframework.web.reactive.config.WebFluxConfigurer
import org.springframework.web.reactive.function.server.CoRouterFunctionDsl
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait


suspend inline fun<reified T> jsonResponse(data: T, status: HttpStatus = HttpStatus.OK) =
    ServerResponse.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValueAndAwait(json.encodeToString(data))

suspend inline fun<reified T> errorResponse(code: Int, data: T, status: HttpStatus = HttpStatus.BAD_REQUEST) =
    jsonResponse(ErrorMessage(code, json.encodeToString(data)), status)

abstract class Router {
    abstract fun router(): RouterFunction<ServerResponse>
}

fun CoRouterFunctionDsl.nest(route: String, router: Router) {
    route.nest { add(router.router()) }
}