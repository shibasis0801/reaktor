package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.core.cloudflare.R2Bucket as RawR2Bucket
import dev.shibasis.reaktor.core.cloudflare.R2Object as RawR2Object
import dev.shibasis.reaktor.core.cloudflare.R2ObjectBody as RawR2ObjectBody
import dev.shibasis.reaktor.core.cloudflare.R2Objects as RawR2Objects
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.core.framework.kSerializer
import kotlinx.coroutines.await
import kotlin.js.JsExport
import kotlin.js.Promise

@JsExport
class R2Object internal constructor(
    private val raw: RawR2Object,
) {
    val key: String
        get() = raw.key

    val size: Long
        get() = raw.size.toLong()

    val etag: String
        get() = raw.etag

    val contentType: String?
        get() = raw.httpMetadata?.contentType

    val uploadedAt: String
        get() = raw.uploaded.toISOString()
}

@JsExport
class R2ObjectBody internal constructor(
    private val raw: RawR2ObjectBody,
) {
    val objectInfo: R2Object
        get() = R2Object(raw)

    @JsExport.Ignore
    suspend fun bytes(): ByteArray = arrayBufferToByteArray(raw.arrayBuffer().await())

    /**
     * The object's `ReadableStream`, for serving it onward without buffering.
     *
     * Pair with `CloudflareRouteContext.stream` to hand a large object straight back to the
     * caller; [bytes] is the right choice only when the whole payload is small enough to belong in
     * the isolate.
     */
    val body: dynamic
        get() = raw.asDynamic().body

    @JsExport.Ignore
    suspend fun text(): String = raw.text().await()

    fun textAsync(): Promise<String> = promiseOf { text() }

    fun jsonTextAsync(): Promise<String> = promiseOf {
        text().let { body -> body }
    }

    @JsExport.Ignore
    suspend inline fun <reified T> json(): T =
        dev.shibasis.reaktor.core.framework.json.decodeFromString(text())
}

@JsExport
class R2Bucket internal constructor(
    private val raw: RawR2Bucket,
) {
    /**
     * Keys under a prefix.
     *
     * R2 has no way to ask "does this set of objects exist" other than listing, and a caller that
     * stored one logical thing as many objects — a file in chunks, say — needs exactly that to know
     * what it still has to send. Doing it with one `head` per object would be one round trip per
     * chunk before a byte moves.
     *
     * Paged, because R2 caps a listing at 1000 and silently truncating would make "what is missing"
     * answer wrongly on a large object. The cursor is followed here so callers get the whole set.
     */
    @JsExport.Ignore
    suspend fun list(prefix: String, limit: Int = 1000): List<String> {
        val keys = mutableListOf<String>()
        var cursor: String? = null

        do {
            val options = js("({})")
            options.prefix = prefix
            options.limit = limit
            if (cursor != null) options.cursor = cursor

            val page = raw.asDynamic().list(options).unsafeCast<Promise<dynamic>>().await()
            val objects = page.objects.unsafeCast<Array<dynamic>>()
            objects.forEach { entry -> keys += entry.key.unsafeCast<String>() }

            cursor = if (page.truncated == true) page.cursor.unsafeCast<String?>() else null
        } while (cursor != null)

        return keys
    }

    @JsExport.Ignore
    suspend fun head(key: String): R2Object? = raw.head(key).await()?.let(::R2Object)

    fun headAsync(key: String): Promise<R2Object?> = promiseOf { head(key) }

    @JsExport.Ignore
    suspend fun get(key: String): R2ObjectBody? = raw.get(key).await()?.let(::R2ObjectBody)

    fun getAsync(key: String): Promise<R2ObjectBody?> = promiseOf { get(key) }

    @JsExport.Ignore
    suspend fun put(key: String, value: ByteArray): R2Object =
        R2Object(raw.put(key, value.toUint8Array()).await())

    /**
     * Stores a `ReadableStream` — a request body, or another R2 object's body — without pulling it
     * through the isolate.
     *
     * The [ByteArray] overloads are right for small payloads and wrong for large ones: a 50 MB
     * upload buffered into Kotlin costs 50 MB of isolate memory to achieve nothing, since the
     * bytes are only ever on their way to R2. Take the stream off
     * [CloudflareHttpRequest.body] and pass it here instead.
     */
    @JsExport.Ignore
    suspend fun putStream(
        key: String,
        stream: dynamic,
        contentType: String? = null,
    ): R2Object =
        R2Object(
            raw.asDynamic()
                .put(key, stream, putOptions(contentType))
                .unsafeCast<kotlin.js.Promise<RawR2Object>>()
                .await(),
        )

    @JsExport.Ignore
    suspend fun put(key: String, value: String): R2Object =
        R2Object(raw.put(key, value).await())

    fun putTextAsync(key: String, value: String): Promise<R2Object> = promiseOf { put(key, value) }

    @JsExport.Ignore
    suspend fun put(
        key: String,
        value: ByteArray,
        contentType: String?,
    ): R2Object =
        R2Object(
            raw.asDynamic()
                .put(key, value.toUint8Array(), putOptions(contentType))
                .unsafeCast<kotlin.js.Promise<RawR2Object>>()
                .await(),
        )

    @JsExport.Ignore
    suspend fun put(
        key: String,
        value: String,
        contentType: String?,
    ): R2Object =
        R2Object(
            raw.asDynamic()
                .put(key, value, putOptions(contentType))
                .unsafeCast<kotlin.js.Promise<RawR2Object>>()
                .await(),
        )

    fun putTextWithContentTypeAsync(
        key: String,
        value: String,
        contentType: String?,
    ): Promise<R2Object> = promiseOf { put(key, value, contentType) }

    @JsExport.Ignore
    suspend fun delete(key: String) {
        raw.delete(key).await()
    }

    fun deleteAsync(key: String): Promise<Unit> = promiseOf { delete(key) }

    @JsExport.Ignore
    suspend fun delete(keys: Collection<String>) {
        raw.delete(keys.toTypedArray()).await()
    }

    @JsExport.Ignore
    suspend fun putText(key: String, value: String) {
        put(key, value)
    }

    @JsExport.Ignore
    suspend fun putBytes(key: String, value: ByteArray) {
        put(key, value)
    }

    @JsExport.Ignore
    suspend fun putText(
        key: String,
        value: String,
        contentType: String?,
    ) {
        put(key, value, contentType)
    }

    @JsExport.Ignore
    suspend fun putBytes(
        key: String,
        value: ByteArray,
        contentType: String?,
    ) {
        put(key, value, contentType)
    }

    fun getTextAsync(key: String): Promise<String?> = promiseOf { getText(key) }

    @JsExport.Ignore
    suspend fun getText(key: String): String? = get(key)?.text()

    @JsExport.Ignore
    suspend fun getBytes(key: String): ByteArray? = get(key)?.bytes()

    fun putJsonTextAsync(
        key: String,
        value: String,
        contentType: String? = "application/json",
    ): Promise<Unit> = promiseOf { putText(key, value, contentType) }

    fun getJsonTextAsync(key: String): Promise<String?> = promiseOf { getText(key) }

    @JsExport.Ignore
    suspend inline fun <reified T> putJson(
        key: String,
        value: T,
    ) {
        putText(key, json.encodeToString(kSerializer<T>(), value))
    }

    @JsExport.Ignore
    suspend inline fun <reified T> getJson(key: String): T? =
        getText(key)?.let(dev.shibasis.reaktor.core.framework.json::decodeFromString)

    @JsExport.Ignore
    suspend fun list(prefix: String? = null, limit: Int? = null): R2ListResult {
        val options: dynamic = js("({})")
        if (prefix != null) options.prefix = prefix
        if (limit != null) options.limit = limit
        val result: RawR2Objects = raw.list(options).await()
        val objects = result.objects.map { R2Object(it) }
        return R2ListResult(objects, result.truncated, result.cursor)
    }

    @JsExport.Ignore
    suspend fun listAll(prefix: String): List<R2Object> {
        val all = mutableListOf<R2Object>()
        var cursor: String? = null
        do {
            val options: dynamic = js("({})")
            options.prefix = prefix
            if (cursor != null) options.cursor = cursor
            val result: RawR2Objects = raw.list(options).await()
            all.addAll(result.objects.map { R2Object(it) })
            cursor = result.cursor
        } while (result.truncated && cursor != null)
        return all
    }
}

class R2ListResult(
    val objects: List<R2Object>,
    val truncated: Boolean,
    val cursor: String?,
)

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

private fun putOptions(contentType: String?): dynamic {
    if (contentType == null) {
        return undefined
    }

    val options = js("({})")
    val httpMetadata = js("({})")
    httpMetadata.contentType = contentType
    options.httpMetadata = httpMetadata
    return options
}
