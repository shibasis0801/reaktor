package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.framework.Router
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
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

@Component
class AppRouter(): Router() {
    override fun router() = coRouter {
        GET("/hello") {
            jsonResponse(mapOf("message" to "Hello, World!"))
        }
    }
}
