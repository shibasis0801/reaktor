package dev.shibasis.reaktor.notification

import dev.shibasis.reaktor.core.framework.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class SealedNotice(
    val conversationId: String,
    val messageId: String,
    val epoch: Long,
    val senderUserId: String,
    val senderDeviceId: String,
    val envelope: String,
    val header: String,
    val commitment: String,
    val ciphertext: String,
) {
    fun encode(): String? = takeIf { valid() }?.let { json.encodeToString(it) }
        ?.takeIf { it.encodeToByteArray().size <= MaximumBytes }

    private fun valid(): Boolean = epoch >= 0 &&
        listOf(conversationId, messageId, senderUserId, senderDeviceId).all { it.isNotBlank() && it.length <= 256 } &&
        listOf(envelope, header, commitment, ciphertext).all { it.isNotBlank() }

    companion object {
        const val DataKey = "sealed_notice"
        const val MaximumBytes = 2500

        fun decode(value: String): SealedNotice? = value.takeIf { it.length <= MaximumBytes && it.encodeToByteArray().size <= MaximumBytes }
            ?.let { runCatching { json.decodeFromString<SealedNotice>(it) }.getOrNull() }
            ?.takeIf { it.valid() }
    }
}
