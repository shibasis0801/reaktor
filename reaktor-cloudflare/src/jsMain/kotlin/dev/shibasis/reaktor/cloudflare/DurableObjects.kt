package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.core.framework.kSerializer
import kotlinx.coroutines.await
import kotlinx.serialization.json.JsonElement

class CloudflareResponse internal constructor(
    private val raw: RawWorkerResponse,
) {
    val ok: Boolean
        get() = raw.ok

    val status: Int
        get() = raw.status.toInt()

    suspend fun text(): String = raw.text().await()

    suspend fun jsonElement(): JsonElement = dynamicToJsonElement(raw.json().await())

    suspend inline fun <reified T> decode(): T = json.decodeFromString(text())
}

class CloudflareWorkerRequest internal constructor(
    private val raw: RawWorkerRequest,
) {
    val method: String
        get() = raw.method ?: "GET"

    val url: String
        get() = raw.url

    val path: String
        get() = urlPath(raw.url)

    fun query(name: String): String? =
        urlQuery(raw.url, name)

    fun requireQuery(name: String): String =
        query(name) ?: error("$name query parameter is required")

    suspend fun text(): String = raw.text().await()

    suspend inline fun <reified T> decode(): T = json.decodeFromString(text())
}

class DurableObjectId internal constructor(
    internal val raw: RawDurableObjectId,
) {
    override fun toString(): String = raw.toString()
}

class DurableObjectNamespace internal constructor(
    private val raw: RawDurableObjectNamespace,
) {
    fun newId(locationHint: String? = null): DurableObjectId =
        DurableObjectId(raw.newUniqueId(durableObjectOptions(locationHint)))

    fun id(name: String): DurableObjectId = DurableObjectId(raw.idFromName(name))

    fun fromString(id: String): DurableObjectId = DurableObjectId(raw.idFromString(id))

    fun get(
        id: DurableObjectId,
        locationHint: String? = null,
    ): DurableObjectStub = DurableObjectStub(raw.get(id.raw, durableObjectOptions(locationHint)))

    fun named(name: String): DurableObjectStub = DurableObjectStub(raw.getByName(name))
}

class DurableObjectStub internal constructor(
    private val raw: RawDurableObjectStub,
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

    suspend inline fun <reified T> getJson(url: String): T =
        fetch(url).decode()

    suspend inline fun <reified In, reified Out> postJson(
        url: String,
        body: In,
    ): Out =
        fetch(
            url = url,
            method = "POST",
            body = json.encodeToString(kSerializer<In>(), body),
            headers = mapOf("Content-Type" to "application/json"),
        ).decode()
}

class DurableObjectStorage internal constructor(
    private val raw: RawDurableObjectStorage,
) {
    suspend fun value(key: String): String? = raw.get(key).await()?.toString()

    suspend fun putValue(
        key: String,
        value: Any?,
    ) {
        raw.put(key, value).await()
    }

    suspend fun text(key: String): String? = value(key)

    suspend fun putText(
        key: String,
        value: String,
    ) {
        putValue(key, value)
    }

    suspend inline fun <reified T> getJson(key: String): T? =
        text(key)?.let(json::decodeFromString)

    suspend inline fun <reified T> putJson(
        key: String,
        value: T,
    ) {
        putText(key, json.encodeToString(kSerializer<T>(), value))
    }

    suspend fun int(key: String): Int? = value(key)?.toIntOrNull()

    suspend fun putInt(
        key: String,
        value: Int,
    ) {
        putValue(key, value)
    }

    suspend fun delete(key: String): Boolean = raw.delete(key).await()

    suspend fun clear() {
        raw.deleteAll().await()
    }
}

internal fun requestInit(
    method: String,
    body: String? = null,
    headers: Map<String, String> = emptyMap(),
): dynamic {
    val init = js("({})")
    init.method = method
    if (headers.isNotEmpty()) {
        val jsHeaders = js("({})")
        headers.forEach { (name, value) ->
            jsHeaders[name] = value
        }
        init.headers = jsHeaders
    }
    if (body != null) {
        init.body = body
    }
    return init
}

private fun durableObjectOptions(locationHint: String?): dynamic {
    if (locationHint == null) {
        return undefined
    }

    val options = js("({})")
    options.locationHint = locationHint
    return options
}

private fun urlPath(url: String): String =
    js("new URL(arguments[0]).pathname") as String

private fun urlQuery(
    url: String,
    name: String,
): String? = js("new URL(arguments[0]).searchParams.get(arguments[1])") as String?
