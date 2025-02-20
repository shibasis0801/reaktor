package dev.shibasis.reaktor.auth.framework

import dev.shibasis.reaktor.auth.api.json
import kotlinx.serialization.encodeToString
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.CoRouterFunctionDsl
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait

abstract class Router {
    suspend inline fun<reified T> jsonResponse(data: T, status: HttpStatus = HttpStatus.OK) =
        ServerResponse.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValueAndAwait(json.encodeToString<T>(data))

    abstract fun router(): RouterFunction<ServerResponse>
}

fun CoRouterFunctionDsl.nest(route: String, router: Router) {
    route.nest { add(router.router()) }
}