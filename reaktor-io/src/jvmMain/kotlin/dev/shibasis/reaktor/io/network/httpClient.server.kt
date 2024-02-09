package dev.shibasis.reaktor.io.network


import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java

actual val httpClient = HttpClient(Java) {
    engine {
        pipelining = true
    }
}