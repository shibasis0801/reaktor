package dev.shibasis.reaktor.auth.kernel

import kotlin.js.JsExport

/** Wire helpers shared by headless hosts; no UI, graph, storage or service dependency. */
@JsExport
object BearerHeaders {
    const val AUTHORIZATION: String = "Authorization"
    const val PREFIX: String = "Bearer "

    fun authorization(token: String): String = PREFIX + token.trim()

    fun tokenFromHeader(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!raw.startsWith(PREFIX, ignoreCase = true)) return null
        return raw.drop(PREFIX.length).trim().takeIf { it.isNotEmpty() }
    }
}
