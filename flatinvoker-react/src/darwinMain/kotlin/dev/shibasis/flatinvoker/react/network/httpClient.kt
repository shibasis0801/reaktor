package dev.shibasis.flatinvoker.react.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin


actual val httpClient = HttpClient(Darwin) {

}