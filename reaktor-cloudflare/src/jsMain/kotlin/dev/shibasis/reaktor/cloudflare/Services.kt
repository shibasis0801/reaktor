package dev.shibasis.reaktor.cloudflare

import kotlinx.coroutines.await
import kotlinx.serialization.json.JsonElement

class WorkerService internal constructor(
    private val raw: RawServiceBinding,
) {
    suspend fun fetch(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): CloudflareResponse =
        CloudflareResponse(raw.fetch(url, requestInit(method, body, headers)).await())

    suspend fun text(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String = fetch(url, method, body, headers).text()

    suspend fun jsonElement(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): JsonElement = fetch(url, method, body, headers).jsonElement()

    suspend inline fun <reified T> getJson(url: String): T =
        fetch(url = url).decode()
}
