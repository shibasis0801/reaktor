package dev.shibasis.reaktor.cloudflare

import kotlinx.coroutines.await
import kotlinx.serialization.json.JsonElement
import kotlin.js.JsExport
import kotlin.js.Promise

@JsExport
class WorkerService internal constructor(
    private val raw: RawServiceBinding,
) {
    @JsExport.Ignore
    suspend fun fetch(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): CloudflareResponse =
        CloudflareResponse(raw.fetch(url, requestInit(method, body, headers)).await())

    fun fetchAsync(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Any? = null,
    ): Promise<CloudflareResponse> = promiseOf {
        fetch(url, method, body, anyToStringMap(headers))
    }

    @JsExport.Ignore
    suspend fun text(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String = fetch(url, method, body, headers).text()

    fun textAsync(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Any? = null,
    ): Promise<String> = promiseOf {
        text(url, method, body, anyToStringMap(headers))
    }

    @JsExport.Ignore
    suspend fun jsonElement(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): JsonElement = fetch(url, method, body, headers).jsonElement()

    fun jsonTextAsync(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Any? = null,
    ): Promise<String> = promiseOf {
        jsonElement(url, method, body, anyToStringMap(headers)).toJsonText()
    }

    @JsExport.Ignore
    suspend inline fun <reified T> getJson(url: String): T =
        fetch(url = url).decode()
}
