package dev.shibasis.reaktor.core.network


import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js

actual val network = HttpClient(Js)