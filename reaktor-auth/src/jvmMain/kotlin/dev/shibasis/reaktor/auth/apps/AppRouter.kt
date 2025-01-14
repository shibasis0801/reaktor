package dev.shibasis.reaktor.auth.apps

import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter

private suspend fun jsonResponse(data: Any) =
    ServerResponse.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValueAndAwait(data)

fun appRouter() = coRouter {
    GET("/hello") {
        jsonResponse(mapOf("message" to "Hello, World!"))
    }
    GET("/goodbye") {
        jsonResponse(mapOf("message" to "Goodbye!"))
    }
    GET("/json") {
        jsonResponse(mapOf("message" to "Hello from JSON"))
    }
}