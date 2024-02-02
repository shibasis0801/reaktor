package dev.shibasis.reaktor.flatinvoker

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

actual val httpClient: HttpClient
    get() = HttpClient(Darwin)