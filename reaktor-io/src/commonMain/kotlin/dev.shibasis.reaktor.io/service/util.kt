package dev.shibasis.reaktor.io.service

import io.ktor.http.HttpMethod as KtorMethod

import kotlin.js.JsExport

@JsExport
enum class HttpMethod {
    GET, POST, PUT, DELETE, PATCH, OPTIONS, HEAD;

    fun toKtorMethod() = when (this) {
        GET -> KtorMethod.Get
        POST -> KtorMethod.Post
        PUT -> KtorMethod.Put
        DELETE -> KtorMethod.Delete
        PATCH -> KtorMethod.Patch
        OPTIONS -> KtorMethod.Options
        HEAD -> KtorMethod.Head
    }
}

@JsExport
enum class Environment {
    STAGE, PROD;
    companion object {
        val Header = "X-Environment"
        operator fun invoke(value: String) =
            if (value.lowercase() == "prod") PROD else STAGE
    }
}
