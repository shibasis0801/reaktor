package com.myntra.appscore.batcave.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.OkHttpClient

actual val httpClient: HttpClient = HttpClient(OkHttp) {

}