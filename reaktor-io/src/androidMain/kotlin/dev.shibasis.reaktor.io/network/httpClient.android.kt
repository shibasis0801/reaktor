package dev.shibasis.reaktor.io.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

actual val http = HttpClient(OkHttp) {
    middleware()
    engine {
        config {
            followRedirects(true)
            pingInterval(15, TimeUnit.SECONDS)
        }
//        addInterceptor(interceptor)
//        addNetworkInterceptor(interceptor)
//
//        preconfigured = okHttpClientInstance
    }
}

actual val socketHeadersSupported: Boolean = true
