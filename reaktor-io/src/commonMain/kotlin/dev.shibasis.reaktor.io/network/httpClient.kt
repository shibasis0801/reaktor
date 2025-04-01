package dev.shibasis.reaktor.io.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

expect val http: HttpClient

// todo Take a Authenticator interface as dependency, use io.ktor:ktor-client-auth
fun<T : HttpClientEngineConfig> HttpClientConfig<T>.middleware() {
    install(ContentNegotiation) {
        json(Json { classDiscriminator = "type" })
    }
    install(Logging) {
        logger = Logger.DEFAULT
        level = LogLevel.HEADERS
        sanitizeHeader { header -> header == HttpHeaders.Authorization }
    }
    defaultRequest {
        contentType(ContentType.Application.Json)
    }
}


val HttpResponse.ok: Boolean
    get() = status == HttpStatusCode.OK


fun HeadersBuilder.replace(key: String, value: String) {
    remove(key)
    append(key, value)
}

suspend inline fun<reified I, reified O> HttpClient.postJson(
    url: String,
    body: I
): Result<O> = runCatching {
    http.post(url) {
        setBody(body)
    }.body()
}

suspend inline fun<reified O> HttpClient.getJson(
    url: String
): Result<O> = runCatching {
    http.get(url).body()
}