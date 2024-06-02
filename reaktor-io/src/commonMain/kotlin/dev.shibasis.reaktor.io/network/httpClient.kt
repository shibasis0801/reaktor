package dev.shibasis.reaktor.io.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

expect val httpClient: HttpClient

fun<T : HttpClientEngineConfig> HttpClientConfig<T>.middleware() {
    install(ContentNegotiation) {
        json()
    }
    install(Logging) {
        logger = Logger.DEFAULT
        level = LogLevel.HEADERS
        sanitizeHeader { header -> header == HttpHeaders.Authorization }
    }
}


suspend inline fun<reified T> HttpResponse.toResult(): Result<T> {
    return if (status == HttpStatusCode.OK) {
        Result.success(Json.decodeFromString<T>(bodyAsText()))
    } else {
        val err = Error(status.description)
        Result.failure(err)
    }
}

suspend inline fun HttpResponse.toJsonElementResult(): Result<JsonElement> {
    return if (status == HttpStatusCode.OK) {
        Result.success(Json.decodeFromString<JsonElement>(bodyAsText()))
    } else {
        val err = Error(status.description)
        Result.failure(err)
    }
}


val HttpResponse.ok: Boolean
    get() = status == HttpStatusCode.OK


fun HeadersBuilder.replace(key: String, value: String) {
    remove(key)
    append(key, value)
}