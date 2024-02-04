package dev.shibasis.reaktor.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

actual val network = HttpClient(OkHttp) {
    engine {
        config {
            followRedirects(true)
        }
//        addInterceptor(interceptor)
//        addNetworkInterceptor(interceptor)
//
//        preconfigured = okHttpClientInstance
    }
}