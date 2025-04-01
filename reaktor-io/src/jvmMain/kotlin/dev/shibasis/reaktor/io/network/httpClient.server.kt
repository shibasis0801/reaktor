package dev.shibasis.reaktor.io.network


import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

actual val http = HttpClient(OkHttp) {
    middleware()
    engine {
        pipelining = true
    }
}
