package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.core.cloudflare.R2Bucket as RawR2Bucket
import dev.shibasis.reaktor.core.cloudflare.R2Object as RawR2Object
import dev.shibasis.reaktor.core.cloudflare.R2ObjectBody as RawR2ObjectBody
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.core.framework.kSerializer
import kotlinx.coroutines.await

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

class R2ObjectBody internal constructor(
    private val raw: RawR2ObjectBody,
) {
    val objectInfo: R2Object
        get() = R2Object(raw)

    suspend fun text(): String = raw.text().await()

    suspend inline fun <reified T> json(): T =
        dev.shibasis.reaktor.core.framework.json.decodeFromString(text())
}

class R2Bucket internal constructor(
    private val raw: RawR2Bucket,
) {
    suspend fun head(key: String): R2Object? = raw.head(key).await()?.let(::R2Object)

    suspend fun get(key: String): R2ObjectBody? = raw.get(key).await()?.let(::R2ObjectBody)

    suspend fun put(key: String, value: String): R2Object =
        R2Object(raw.put(key, value).await())

    suspend fun delete(key: String) {
        raw.delete(key).await()
    }

    suspend fun delete(keys: Collection<String>) {
        raw.delete(keys.toTypedArray()).await()
    }

    suspend fun putText(key: String, value: String) {
        put(key, value)
    }

    suspend fun getText(key: String): String? = get(key)?.text()

    suspend inline fun <reified T> putJson(
        key: String,
        value: T,
    ) {
        putText(key, json.encodeToString(kSerializer<T>(), value))
    }

    suspend inline fun <reified T> getJson(key: String): T? =
        getText(key)?.let(dev.shibasis.reaktor.core.framework.json::decodeFromString)
}
