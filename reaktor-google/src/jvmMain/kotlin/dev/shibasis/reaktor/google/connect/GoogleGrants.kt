package dev.shibasis.reaktor.google.connect

import dev.shibasis.reaktor.crypto.Sealed
import dev.shibasis.reaktor.crypto.crypto
import java.time.Instant
import java.util.Base64

data class GrantKey(val service: String, val subject: String)

data class Held(val hash: String, val key: GrantKey, val sealed: String, val createdAt: Instant)

interface GoogleGrantStore {
    suspend fun read(key: GrantKey): String?
    suspend fun write(key: GrantKey, sealed: String)
    suspend fun delete(key: GrantKey): Boolean
    suspend fun holdConsent(consent: Held, staleBefore: Instant)
    suspend fun takeConsent(stateHash: String): Held?
    suspend fun holdPending(pending: Held)
    suspend fun takePending(handleHash: String): Held?
    suspend fun pendingBefore(cutoff: Instant): List<Held>
}

class GrantSealer(secret: String) {
    private val material = secret.trim().encodeToByteArray()

    init {
        require(material.size >= MIN_SECRET_BYTES) { "The connector key needs at least $MIN_SECRET_BYTES characters." }
    }

    suspend fun seal(key: GrantKey, purpose: String, plain: String): String {
        val label = label(key, purpose)
        val sealed = crypto().seal(crypto().deriveKey(material, label, SALT), plain.encodeToByteArray(), label)
        return PREFIX + encoder.encodeToString(sealed.toBytes())
    }

    suspend fun open(key: GrantKey, purpose: String, sealed: String): String? {
        if (!sealed.startsWith(PREFIX)) return null
        val bytes = runCatching { decoder.decode(sealed.removePrefix(PREFIX)) }.getOrNull() ?: return null
        val parts = Sealed.fromBytes(bytes) ?: return null
        val label = label(key, purpose)
        return crypto().open(crypto().deriveKey(material, label, SALT), parts, label)?.decodeToString()
    }

    private fun label(key: GrantKey, purpose: String): ByteArray =
        "reaktor-google/connect/$purpose\u0000${key.service}\u0000${key.subject}".encodeToByteArray()

    companion object {
        const val MIN_SECRET_BYTES = 32
        private const val PREFIX = "v1."
        private val SALT = "reaktor-google/connect/v1".encodeToByteArray()
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()
    }
}
