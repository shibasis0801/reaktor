package dev.shibasis.reaktor.security

import dev.shibasis.reaktor.core.framework.toByteArray
import dev.shibasis.reaktor.core.framework.toUint8Array
import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import kotlin.io.encoding.Base64
import kotlin.js.Promise

class FrankingAuthority(private val currentKeyId: String, keys: Map<String, ByteArray>) {
    private val keys = keys.mapValues { it.value.copyOf() }
    private val crypto: dynamic get() = js("globalThis.crypto")

    init {
        require(currentKeyId in keys && keys.size in 1..8)
        require(keys.all { (id, key) -> id.matches(Regex("[A-Za-z0-9_-]{1,64}")) && key.size in 32..128 })
    }

    suspend fun tag(commitment: ByteArray, envelope: FrankingEnvelope): String {
        val key = imported(keys.getValue(currentKeyId), "sign")
        val bytes = crypto.subtle.sign("HMAC", key, envelope.bytes(commitment).toUint8Array())
            .unsafeCast<Promise<ArrayBuffer>>().await().toByteArray()
        return currentKeyId + "." + Base64.UrlSafe.encode(bytes)
    }

    suspend fun verifyTag(commitment: ByteArray, envelope: FrankingEnvelope, tag: String): Boolean = runCatching {
        require(tag.length <= 160)
        val parts = tag.split('.')
        require(parts.size == 2)
        val key = imported(keys[parts[0]] ?: return false, "verify")
        val signature = Base64.UrlSafe.decode(parts[1])
        require(signature.size == 32)
        crypto.subtle.verify("HMAC", key, signature.toUint8Array(), envelope.bytes(commitment).toUint8Array())
            .unsafeCast<Promise<Boolean>>().await()
    }.getOrDefault(false)

    suspend fun verifyReport(content: ByteArray, frankingKey: ByteArray, commitment: ByteArray,
        envelope: FrankingEnvelope, tag: String): Boolean {
        if (content.size > 1024 * 1024 || frankingKey.size != 32 || commitment.size != 32) return false
        if (!verifyTag(commitment, envelope, tag)) return false
        val key = imported(frankingKey, "verify")
        return crypto.subtle.verify("HMAC", key, commitment.toUint8Array(), content.toUint8Array())
            .unsafeCast<Promise<Boolean>>().await()
    }

    private suspend fun imported(bytes: ByteArray, usage: String): dynamic = crypto.subtle.importKey(
        "raw", bytes.toUint8Array(), js("({ name: 'HMAC', hash: 'SHA-256' })"), false, arrayOf(usage))
        .unsafeCast<Promise<dynamic>>().await()
}
