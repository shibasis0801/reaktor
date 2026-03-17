package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.core.cloudflare.R2Bucket as RawR2Bucket
import dev.shibasis.reaktor.core.cloudflare.R2Object as RawR2Object
import dev.shibasis.reaktor.core.cloudflare.R2ObjectBody as RawR2ObjectBody
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
    @JsExport.Ignore
    suspend fun head(key: String): R2Object? = raw.head(key).await()?.let(::R2Object)

    fun headAsync(key: String): Promise<R2Object?> = promiseOf { head(key) }

    @JsExport.Ignore
    suspend fun get(key: String): R2ObjectBody? = raw.get(key).await()?.let(::R2ObjectBody)

    fun getAsync(key: String): Promise<R2ObjectBody?> = promiseOf { get(key) }

    @JsExport.Ignore
    suspend fun put(key: String, value: ByteArray): R2Object =
        R2Object(raw.put(key, value.toUint8Array()).await())

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
