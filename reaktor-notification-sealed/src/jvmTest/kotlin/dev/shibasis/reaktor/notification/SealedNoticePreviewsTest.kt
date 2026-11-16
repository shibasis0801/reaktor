package dev.shibasis.reaktor.notification

import dev.shibasis.reaktor.security.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SealedNoticePreviewsTest {
    private class Box : PlatformSecretBox {
        private val records = mutableMapOf<Int, Pair<ByteArray, ByteArray>>()
        override suspend fun seal(recordType: SecretRecordType, recordId: String, plaintext: ByteArray, aad: ByteArray, options: SecretAccessOptions): SealedSecret {
            val id = records.size + 1
            records[id] = aad.copyOf() to plaintext.copyOf()
            return SealedSecret(1, "test", "test", byteArrayOf(), byteArrayOf(id.toByte()), 0)
        }
        override suspend fun open(recordType: SecretRecordType, recordId: String, sealed: SealedSecret, aad: ByteArray, options: SecretAccessOptions): ByteArray {
            val (savedAad, bytes) = records.getValue(sealed.ciphertext.single().toInt())
            check(savedAad.contentEquals(aad))
            return bytes.copyOf()
        }
    }

    private class Engine : ReaktorSecurity {
        override suspend fun sealedKeyRing(protection: PlatformSecretBox?) = SealedSecret(1, "test", "ring", byteArrayOf(), byteArrayOf(127), 0)
        override suspend fun device(): DeviceCryptoIdentity = error("No directory access")
        override suspend fun reconcile(conversationId: String, members: Set<String>): ConversationSecurityState = error("No directory access")
        override suspend fun receive(commit: DirectoryCommit): Unit = error("No directory access")
        override suspend fun receive(welcome: DirectoryWelcome): Unit = error("No directory access")
        override fun state(conversationId: String): Flow<ConversationSecurityState> = error("No directory access")
        override suspend fun seal(conversationId: String, envelope: ByteArray, plaintext: ByteArray, circleGame: Boolean, anonymous: Boolean): SealedMessage = error("No directory access")
        override suspend fun open(conversationId: String, envelope: ByteArray, sealed: SealedMessage, circleGame: Boolean): OpenedMessage = error("No directory access")
    }

    @Test fun hiddenPreferenceSurvivesAnEpochExportAndProcessRestart() = runBlocking {
        val box = Box()
        var disk: String? = null
        fun store() = SealedNoticePreviews(box, { disk }, { disk = it })
        val session = SecuritySession("tenant", "application", "private-user", mapOf("authority" to byteArrayOf(1)))
        store().setHidden(true)
        store().activate(session)
        store().update(session, Engine())
        assertTrue(store().hidden())
        assertFalse(requireNotNull(disk).contains("private-user"))
        store().setHidden(false)
        assertFalse(store().hidden())
        store().clear()
        store().update(session, Engine())
        assertFalse(store().hidden())
        var called = false
        assertNull(store().open(SealedNotice("chat", "message", 1, "sender", "device", "AA==", "AA==", "AA==", "AA==")) { _, _ -> called = true; "body" })
        assertFalse(called)
    }

    @Test fun unavailableAndCorruptSnapshotsKeepTheGenericNotification() = runBlocking {
        var disk: String? = null
        val previews = SealedNoticePreviews(Box(), { disk }, { disk = it })
        assertTrue(previews.hidden())
        disk = "corrupt"
        assertTrue(previews.hidden())
        var called = false
        assertNull(previews.open(SealedNotice("chat", "message", 1, "sender", "device", "AA==", "AA==", "AA==", "AA==")) { _, _ -> called = true; "body" })
        assertFalse(called)
    }
}
