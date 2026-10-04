package dev.shibasis.reaktor.cloudflare

import kotlinx.coroutines.await
import kotlinx.serialization.json.*
import kotlin.js.Promise

internal external interface RawKvNamespace {
    fun get(key: String, type: String = definedExternally): Promise<String?>
    fun put(key: String, value: String, options: dynamic = definedExternally): Promise<Unit>
    fun delete(key: String): Promise<Unit>
    fun list(options: dynamic): Promise<dynamic>
}

class KvNamespace internal constructor(private val raw: RawKvNamespace) {
    suspend fun getString(key: String): String? = raw.get(key, "text").await()

    suspend fun getBytes(key: String, maxBytes: Int): ByteArray? {
        val stream = raw.asDynamic().get(key, "stream").unsafeCast<Promise<dynamic>>().await() ?: return null
        return readBoundedStream(stream, maxBytes)
    }

    suspend fun list(prefix: String = "", limit: Int = 100, cursor: String? = null): KvListResult {
        require(limit in 1..1000)
        val options = js("({})")
        options.prefix = prefix
        options.limit = limit
        if (cursor != null) options.cursor = cursor
        val result = raw.list(options).await()
        val document = Json.parseToJsonElement(JSON.stringify(result)).jsonObject
        return KvListResult(document.getValue("keys").jsonArray.map { it.jsonObject },
            document.getValue("list_complete").jsonPrimitive.boolean, document["cursor"]?.jsonPrimitive?.contentOrNull)
    }

    suspend fun putString(
        key: String,
        value: String,
        ttlSeconds: Int? = null,
    ) {
        val options = if (ttlSeconds != null) {
            val o = js("({})")
            o.expirationTtl = ttlSeconds
            o
        } else {
            undefined
        }
        raw.put(key, value, options).await()
    }

    suspend fun delete(key: String) {
        raw.delete(key).await()
    }
}

class KvBinding internal constructor(name: String) : Binding<KvNamespace>(name, "KvNamespace") {
    override fun resolve(context: CloudflareContext): KvNamespace? = context.kvOrNull(name)
}

fun kv(name: String): KvBinding = KvBinding(name)

data class KvListResult(val keys: List<JsonObject>, val complete: Boolean, val cursor: String?)
