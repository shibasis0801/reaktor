package dev.shibasis.reaktor.cloudflare

import kotlinx.coroutines.await
import kotlin.js.Promise

internal external interface RawKvNamespace {
    fun get(key: String, type: String = definedExternally): Promise<String?>
    fun put(key: String, value: String, options: dynamic = definedExternally): Promise<Unit>
    fun delete(key: String): Promise<Unit>
}

class KvNamespace internal constructor(private val raw: RawKvNamespace) {
    suspend fun getString(key: String): String? = raw.get(key, "text").await()

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
