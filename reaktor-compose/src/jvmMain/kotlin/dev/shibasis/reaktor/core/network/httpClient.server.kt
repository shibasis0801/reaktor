package dev.shibasis.reaktor.core.network


import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java

actual val network = HttpClient(Java) {
    engine {
        pipelining = true
    }
}