package dev.shibasis.reaktor.auth.apps

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter

val json = Json {
    classDiscriminator = "type"
}

suspend inline fun<reified T> jsonResponse(data: T, status: HttpStatus = HttpStatus.OK) =
    ServerResponse.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValueAndAwait(json.encodeToString<T>(data))

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