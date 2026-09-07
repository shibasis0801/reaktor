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

    /**
     * The request's `ReadableStream`, for bodies that must not be buffered.
     *
     * [bytes] pulls the whole payload into the isolate, which is the wrong shape for an upload
     * that is on its way to R2 — a large file would sit in memory for no reason and can exceed the
     * isolate's limit. Hand this straight to `R2Bucket.put` or to a `Response` instead.
     *
     * Null for requests that carry no body (GET, HEAD).
     */
    val body: dynamic
        get() = raw.asDynamic().body
}

class CloudflareRouteContext internal constructor(
    private val hono: HonoContext,
) {
    val request: CloudflareHttpRequest = CloudflareHttpRequest(hono.req.raw.unsafeCast<RawWorkerRequest>())
    val cloudflare: CloudflareContext = CloudflareContext(hono.env, hono.executionCtx, hono)

    /** A path parameter declared in the route pattern, e.g. `:id` in `/things/:id`. */
    fun param(name: String): String? {
        // Bound to a local first. `hono.req.param()` is already typed `dynamic`, and calling
        // `.asDynamic()` on a dynamic expression does not compile away — it emits a real call to a
        // method JavaScript does not have, and every use of this throws at runtime.
        val params: dynamic = hono.req.param()
        val value = params[name]
        return if (value == null) null else value.toString()
    }

    fun requireParam(name: String): String =
        param(name) ?: error("Route parameter '$name' is missing")

    fun query(name: String): String? {
        // Same as `param`: already dynamic, so `.asDynamic()` would emit a call, not a cast.
        val queries: dynamic = hono.req.query()
        val value = queries[name]
        return if (value == null) null else value.toString()
    }

    /**
     * Streams [body] back without buffering it — the counterpart to
     * [CloudflareHttpRequest.body], for handing an R2 object's stream straight to the client.
     */
    fun stream(
        body: dynamic,
        status: Int = 200,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/octet-stream",
    ): Any = workerResponse(
        body = body,
        status = status,
        headers = headers + ("Content-Type" to contentType),
    ).unsafeCast<Any>()

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

fun Hono.put(
    path: String,
    handler: suspend CloudflareRouteContext.() -> Any,
): Hono = handle("PUT", path, handler)

fun Hono.patch(
    path: String,
    handler: suspend CloudflareRouteContext.() -> Any,
): Hono = handle("PATCH", path, handler)

fun Hono.delete(
    path: String,
    handler: suspend CloudflareRouteContext.() -> Any,
): Hono = handle("DELETE", path, handler)

fun Hono.head(
    path: String,
    handler: suspend CloudflareRouteContext.() -> Any,
): Hono = handle("HEAD", path, handler)

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
