package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.core.framework.kSerializer
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise

class CloudflareHeaders internal constructor(
    private val raw: RawHeaders,
) {
    operator fun get(name: String): String? = raw.get(name)
}

class CloudflareFile internal constructor(
    private val raw: RawFile,
) {
    val name: String
        get() = raw.name

    val type: String
        get() = raw.type

    suspend fun bytes(): ByteArray = arrayBufferToByteArray(raw.arrayBuffer().await())

    suspend fun text(): String = raw.text().await()
}

class CloudflareFormData internal constructor(
    private val raw: RawFormData,
) {
    fun text(name: String): String? {
        val value = raw.get(name) ?: return null
        return if (jsTypeOf(value) == "string") value.unsafeCast<String>() else null
    }

    fun file(name: String): CloudflareFile? {
        val value = raw.get(name) ?: return null
        val isFile = js("typeof value === 'object' && value !== null && typeof value.arrayBuffer === 'function'") as Boolean
        return if (isFile) CloudflareFile(value.unsafeCast<RawFile>()) else null
    }
}

class CloudflareHttpRequest internal constructor(
    private val raw: RawWorkerRequest,
) {
    val method: String
        get() = raw.method ?: "GET"

    val url: String
        get() = raw.url

    val path: String
        get() = urlPath(raw.url)

    val headers: CloudflareHeaders = CloudflareHeaders(raw.headers)

    val contentType: String?
        get() = headers["content-type"] ?: headers["Content-Type"]

    suspend fun text(): String = raw.text().await()

    suspend fun bytes(): ByteArray = arrayBufferToByteArray(raw.arrayBuffer().await())

    suspend fun formData(): CloudflareFormData = CloudflareFormData(raw.formData().await())
}

class CloudflareRouteContext internal constructor(
    hono: HonoContext,
) {
    val request: CloudflareHttpRequest = CloudflareHttpRequest(hono.req.raw.unsafeCast<RawWorkerRequest>())
    val cloudflare: CloudflareContext = CloudflareContext(hono.env, hono.executionCtx, hono)

    inline fun <reified T> json(
        value: T,
        status: Int = 200,
        headers: Map<String, String> = emptyMap(),
    ): Any = workerResponse(
        body = json.encodeToString(kSerializer<T>(), value),
        status = status,
        headers = headers + ("Content-Type" to "application/json; charset=utf-8"),
    )

    fun jsonDynamic(
        value: dynamic,
        status: Int = 200,
        headers: Map<String, String> = emptyMap(),
    ): Any = workerResponse(
        body = js("JSON.stringify(value)"),
        status = status,
        headers = headers + ("Content-Type" to "application/json; charset=utf-8"),
    )

    fun text(
        value: String,
        status: Int = 200,
        headers: Map<String, String> = emptyMap(),
    ): Any = workerResponse(
        body = value,
        status = status,
        headers = headers + ("Content-Type" to "text/plain; charset=utf-8"),
    )

    fun bytes(
        value: ByteArray,
        status: Int = 200,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/octet-stream",
    ): Any = workerResponse(
        body = value.toUint8Array(),
        status = status,
        headers = headers + ("Content-Type" to contentType),
    )
}

@OptIn(DelicateCoroutinesApi::class)
fun Hono.handle(
    method: String,
    path: String,
    handler: suspend CloudflareRouteContext.() -> Any,
): Hono = on(method, path) { context ->
    GlobalScope.promise {
        handler(CloudflareRouteContext(context))
    }
}

fun Hono.get(
    path: String,
    handler: suspend CloudflareRouteContext.() -> Any,
): Hono = handle("GET", path, handler)

fun Hono.post(
    path: String,
    handler: suspend CloudflareRouteContext.() -> Any,
): Hono = handle("POST", path, handler)

@PublishedApi
internal fun workerResponse(
    body: dynamic,
    status: Int = 200,
    headers: Map<String, String> = emptyMap(),
): dynamic {
    val jsHeaders = js("({})")
    headers.forEach { (name, value) ->
        jsHeaders[name] = value
    }
    return js("new Response(body, { status: status, headers: jsHeaders })")
}

private fun ByteArray.toUint8Array(): dynamic {
    val length = size
    val view = js("new Uint8Array(length)")
    for (index in indices) {
        view[index] = this[index].toInt() and 0xFF
    }
    return view
}

private fun arrayBufferToByteArray(buffer: dynamic): ByteArray {
    val view = js("new Uint8Array(buffer)")
    val bytes = ByteArray(view.length as Int)
    for (index in bytes.indices) {
        bytes[index] = (view[index] as Int).toByte()
    }
    return bytes
}

private fun urlPath(url: String): String =
    js("new URL(arguments[0]).pathname") as String
