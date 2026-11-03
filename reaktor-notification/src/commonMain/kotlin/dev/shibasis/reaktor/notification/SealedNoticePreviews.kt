package dev.shibasis.reaktor.notification

import dev.shibasis.reaktor.security.PlatformSecretBox
import dev.shibasis.reaktor.security.ReaktorSecurity
import dev.shibasis.reaktor.security.SealedKeyRing
import dev.shibasis.reaktor.security.SealedMessage
import dev.shibasis.reaktor.security.SealedSecret
import dev.shibasis.reaktor.security.SecretRecordType
import dev.shibasis.reaktor.security.SecuritySession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

class SealedNoticePreviews(
    private val protection: PlatformSecretBox,
    private val read: () -> String?,
    private val write: (String?) -> Unit,
) {
    suspend fun activate(session: SecuritySession) = lock.withLock {
        val previous = record()
        if (previous?.session?.samePrincipal(session) != true) {
            save(PreviewRecord(session = session, hidden = previous?.hidden ?: false))
        }
    }

    suspend fun update(session: SecuritySession, security: ReaktorSecurity) = lock.withLock {
        val previous = record()
        if (previous?.session?.samePrincipal(session) != true) return@withLock
        save(PreviewRecord(session, security.sealedKeyRing(protection), previous.hidden))
    }

    private fun SecuritySession.samePrincipal(other: SecuritySession): Boolean =
        tenantId == other.tenantId && appId == other.appId && userId == other.userId

    suspend fun clear() = lock.withLock {
        val hidden = record()?.hidden ?: true
        write(null)
        runCatching { save(PreviewRecord(hidden = hidden)) }
        Unit
    }

    suspend fun hidden(): Boolean = lock.withLock { record()?.hidden ?: true }

    suspend fun setHidden(hidden: Boolean) = lock.withLock {
        save((record() ?: PreviewRecord()).copy(hidden = hidden))
    }

    suspend fun open(notice: SealedNotice, body: (SealedNotice, ByteArray) -> String?): String? = runCatching {
        if (notice.encode() == null) return null
        val saved = lock.withLock { record() } ?: return null
        if (saved.hidden) return null
        val session = saved.session ?: return null
        val keys = saved.keys ?: return null
        val reader = SealedKeyRing.restore(session, keys, protection)
        try {
            val sealed = SealedMessage(notice.epoch, notice.senderDeviceId, Base64.decode(notice.header), Base64.decode(notice.commitment), Base64.decode(notice.ciphertext))
            val opened = reader.open(notice.conversationId, Base64.decode(notice.envelope), sealed)
            try {
                if (!opened.authenticated || opened.sender?.userId?.value != notice.senderUserId) return null
                body(notice, opened.plaintext)?.takeIf { it.isNotBlank() && it.length <= 2048 }
            } finally {
                opened.plaintext.fill(0)
                opened.frankingKey.fill(0)
            }
        } finally {
            reader.close()
        }
    }.getOrNull()

    private suspend fun record(): PreviewRecord? = runCatching {
        val value = read()?.takeIf { it.length <= MaximumRecordBytes } ?: return null
        val sealed = json.decodeFromString<SealedSecret>(value)
        val bytes = protection.open(SecretRecordType.MlsGroupStateSnapshot, RecordId, sealed, Aad)
        try {
            json.decodeFromString<PreviewRecord>(bytes.decodeToString())
        } finally {
            bytes.fill(0)
        }
    }.getOrNull()

    private suspend fun save(record: PreviewRecord) {
        val bytes = json.encodeToString(record).encodeToByteArray()
        try {
            require(bytes.size <= MaximumRecordBytes)
            val sealed = protection.seal(SecretRecordType.MlsGroupStateSnapshot, RecordId, bytes, Aad)
            write(json.encodeToString(sealed))
        } finally {
            bytes.fill(0)
        }
    }

    @Serializable
    private data class PreviewRecord(val session: SecuritySession? = null, val keys: SealedSecret? = null, val hidden: Boolean = false)

    private companion object {
        val lock = Mutex()
        val json = Json { ignoreUnknownKeys = true }
        const val RecordId = "reaktor/notification/previews/v1"
        val Aad = RecordId.encodeToByteArray()
        const val MaximumRecordBytes = 8 * 1024 * 1024
    }
}
