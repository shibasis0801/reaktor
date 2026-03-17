package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.core.framework.kSerializer
import kotlinx.coroutines.await
import kotlinx.serialization.json.JsonElement
import kotlin.js.JsExport
import kotlin.js.Promise

@JsExport
class CloudflareResponse internal constructor(
    private val raw: RawWorkerResponse,
) {
    val ok: Boolean
        get() = raw.ok

    val status: Int
        get() = raw.status.toInt()

    @JsExport.Ignore
    suspend fun text(): String = raw.text().await()

    fun textAsync(): Promise<String> = promiseOf { text() }

    @JsExport.Ignore
    suspend fun jsonElement(): JsonElement = dynamicToJsonElement(raw.json().await())

    fun jsonTextAsync(): Promise<String> = promiseOf { jsonElement().toJsonText() }

    @JsExport.Ignore
    suspend inline fun <reified T> decode(): T = json.decodeFromString(text())
}

@JsExport
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

    @JsExport.Ignore
    suspend fun text(): String = raw.text().await()

    fun textAsync(): Promise<String> = promiseOf { text() }

    @JsExport.Ignore
    suspend inline fun <reified T> decode(): T = json.decodeFromString(text())
}

@JsExport
class DurableObjectId internal constructor(
    internal val raw: RawDurableObjectId,
) {
    override fun toString(): String = raw.toString()
}

@JsExport
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

@JsExport
class DurableObjectStub internal constructor(
    private val raw: RawDurableObjectStub,
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

    fun jsonTextAsync(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Any? = null,
    ): Promise<String> = promiseOf {
        fetch(url, method, body, anyToStringMap(headers)).jsonElement().toJsonText()
    }

    @JsExport.Ignore
    suspend inline fun <reified T> getJson(url: String): T =
        fetch(url).decode()

    @JsExport.Ignore
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

@JsExport
class DurableObjectStorage internal constructor(
    private val raw: RawDurableObjectStorage,
) {
    @JsExport.Ignore
    suspend fun value(key: String): String? = raw.get(key).await()?.toString()

    fun valueAsync(key: String): Promise<String?> = promiseOf { value(key) }

    @JsExport.Ignore
    suspend fun putValue(
        key: String,
        value: Any?,
    ) {
        raw.put(key, value).await()
    }

    fun putValueAsync(
        key: String,
        value: Any?,
    ): Promise<Unit> = promiseOf { putValue(key, value) }

    @JsExport.Ignore
    suspend fun text(key: String): String? = value(key)

    fun textAsync(key: String): Promise<String?> = promiseOf { text(key) }

    @JsExport.Ignore
    suspend fun putText(
        key: String,
        value: String,
    ) {
        putValue(key, value)
    }

    fun putTextAsync(
        key: String,
        value: String,
    ): Promise<Unit> = promiseOf { putText(key, value) }

    fun getJsonTextAsync(key: String): Promise<String?> = promiseOf {
        getJson<JsonElement>(key)?.toJsonText()
    }

    fun putJsonTextAsync(
        key: String,
        value: String,
    ): Promise<Unit> = promiseOf {
        putText(key, value)
    }

    @JsExport.Ignore
    suspend inline fun <reified T> getJson(key: String): T? =
        text(key)?.let(json::decodeFromString)

    @JsExport.Ignore
    suspend inline fun <reified T> putJson(
        key: String,
        value: T,
    ) {
        putText(key, json.encodeToString(kSerializer<T>(), value))
    }

    @JsExport.Ignore
    suspend fun int(key: String): Int? = value(key)?.toIntOrNull()

    fun intAsync(key: String): Promise<Int?> = promiseOf { int(key) }

    @JsExport.Ignore
    suspend fun putInt(
        key: String,
        value: Int,
    ) {
        putValue(key, value)
    }

    fun putIntAsync(
        key: String,
        value: Int,
    ): Promise<Unit> = promiseOf { putInt(key, value) }

    @JsExport.Ignore
    suspend fun delete(key: String): Boolean = raw.delete(key).await()

    fun deleteAsync(key: String): Promise<Boolean> = promiseOf { delete(key) }

    @JsExport.Ignore
    suspend fun clear() {
        raw.deleteAll().await()
    }

    fun clearAsync(): Promise<Unit> = promiseOf { clear() }
}

@JsExport
class DurableObjectState internal constructor(
    private val raw: RawDurableObjectState,
) {
    val id: DurableObjectId
        get() = DurableObjectId(raw.id)

    val storage: DurableObjectStorage = DurableObjectStorage(raw.storage)

    fun waitUntil(promise: Promise<Any?>) {
        raw.waitUntil(promise)
    }

    @JsExport.Ignore
    suspend fun <T> blockConcurrencyWhile(block: suspend () -> T): T {
        var result: Result<T>? = null
        raw.blockConcurrencyWhile {
            promiseOf {
                result = runCatching { block() }
                null
            }
        }.await()
        return result!!.getOrThrow()
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
