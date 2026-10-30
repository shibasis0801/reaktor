package dev.shibasis.reaktor.security

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val FrankingJson = Json { encodeDefaults = true }

@Serializable
data class FrankingEnvelope(
    val messageId: String,
    val conversationId: String,
    val senderId: String,
    val senderDeviceId: String,
    val createdAtEpochMillis: Long,
    val editNumber: Int = 0,
) {
    internal fun bytes(commitment: ByteArray): ByteArray {
        require(commitment.size == 32)
        require(listOf(messageId, conversationId, senderId, senderDeviceId).all { it.isNotBlank() && it.length <= 256 })
        require(createdAtEpochMillis >= 0 && editNumber >= 0)
        return "reaktor/security/franking/v1:".encodeToByteArray() +
            FrankingJson.encodeToString(serializer(), this).encodeToByteArray() + commitment
    }
}
