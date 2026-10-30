@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)

package dev.shibasis.reaktor.security

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.test.*

class FrankingAuthorityTest {
    private val content = "Franking report fixture".encodeToByteArray()
    private val reportKey = ByteArray(32) { it.toByte() }
    private val commitment = "0b4f1d4fd39208887de88dfe2ac64453ed514213abb9d1722e1687ad0d9145c7"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val envelope = FrankingEnvelope("message", "chat", "alice", "phone", 1793232000000, 2)
    private val oldKey = ByteArray(32) { (it + 32).toByte() }
    private val nextKey = ByteArray(32) { (it + 64).toByte() }

    @Test fun reportsVerifyAnIndependentHmacFixtureAndRejectContentOrProofChanges() = GlobalScope.promise {
        val authority = FrankingAuthority("old", mapOf("old" to oldKey))
        val tag = authority.tag(commitment, envelope)
        assertTrue(authority.verifyReport(content, reportKey, commitment, envelope, tag))
        assertFalse(authority.verifyReport(content + 0, reportKey, commitment, envelope, tag))
        assertFalse(authority.verifyReport(content, reportKey.reversedArray(), commitment, envelope, tag))
        assertFalse(authority.verifyReport(content, reportKey, commitment.reversedArray(), envelope, tag))
        assertFalse(authority.verifyReport(content, reportKey, commitment, envelope, tag.dropLast(3)))
        assertFalse(authority.verifyReport(content, ByteArray(31), commitment, envelope, tag))
    }

    @Test fun theServerReceiptBindsEveryEnvelopeFieldAndCommitment() = GlobalScope.promise {
        val authority = FrankingAuthority("old", mapOf("old" to oldKey))
        val tag = authority.tag(commitment, envelope)
        val changes = listOf(envelope.copy(messageId = "other"), envelope.copy(conversationId = "other"),
            envelope.copy(senderId = "other"), envelope.copy(senderDeviceId = "other"),
            envelope.copy(createdAtEpochMillis = envelope.createdAtEpochMillis + 1), envelope.copy(editNumber = 3))
        for (changed in changes) assertFalse(authority.verifyTag(commitment, changed, tag))
        assertFalse(authority.verifyTag(commitment.reversedArray(), envelope, tag))
        assertFalse(authority.verifyTag(commitment, envelope, "missing." + tag.substringAfter('.')))
        assertFalse(authority.verifyTag(commitment, envelope, "x".repeat(161)))
    }

    @Test fun rotationKeepsOldReceiptsVerifiableAndCopiesKeyMaterial() = GlobalScope.promise {
        val mutableKey = oldKey.copyOf()
        val previous = FrankingAuthority("old", mapOf("old" to mutableKey))
        mutableKey.fill(0)
        val oldTag = previous.tag(commitment, envelope)
        val rotated = FrankingAuthority("next", mapOf("old" to oldKey, "next" to nextKey))
        assertTrue(rotated.verifyTag(commitment, envelope, oldTag))
        val nextTag = rotated.tag(commitment, envelope)
        assertTrue(nextTag.startsWith("next."))
        assertTrue(rotated.verifyTag(commitment, envelope, nextTag))
        assertFalse(previous.verifyTag(commitment, envelope, nextTag))
    }
}
