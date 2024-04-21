package dev.shibasis.reaktor.io.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

expect val httpClient: HttpClient

fun<T : HttpClientEngineConfig> HttpClientConfig<T>.middleware() {
    install(ContentNegotiation) {
        json()
    }
}
