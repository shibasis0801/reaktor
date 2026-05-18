package dev.shibasis.reaktor.security

import kotlinx.coroutines.flow.Flow
import kotlin.jvm.JvmInline

@JvmInline
value class TenantId(val value: String)

@JvmInline
value class AppId(val value: String)

@JvmInline
value class UserId(val value: String)

@JvmInline
value class DeviceId(val value: String)

@JvmInline
value class ConversationId(val value: String)

@JvmInline
value class MessageId(val value: String)

enum class MlsCipherSuiteId(val code: Int) {
    MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519(0x0001),
    MLS_128_DHKEMP256_AES128GCM_SHA256_P256(0x0002),
    MLS_128_DHKEMX25519_CHACHA20POLY1305_SHA256_Ed25519(0x0003)
}

enum class SecureContentType {
    Text,
    AttachmentMetadata,
    AppEvent
}

enum class SecureContentKind {
    Application,
    Proposal,
    Commit,
    Welcome,
    KeyPackage
}

enum class DeviceRevocationReason {
    Logout,
    LostDevice,
    AdminRevoked,
    KeyCompromise
}

enum class RotationReason {
    Manual,
    Policy,
    PostCompromise,
    MemberChanged
}

data class AuthContext(
    val tenantId: TenantId,
    val appId: AppId,
    val userId: UserId,
    val authToken: String,
    val authKeyId: String
)

data class DeviceCredentialV1(
    val tenantId: TenantId,
    val appId: AppId,
    val userId: UserId,
    val deviceId: DeviceId,
    val mlsSignaturePublicKey: ByteArray,
    val supportedMlsCipherSuites: List<MlsCipherSuiteId>,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long?,
    val credentialVersion: Int = 1,
    val authKeyId: String,
    val signatureByReaktorAuth: ByteArray
)

data class DeviceCryptoIdentity(
    val credential: DeviceCredentialV1,
    val credentialBytes: ByteArray,
    val fingerprint: String
)

data class KeyPackagePublishResult(
    val deviceId: DeviceId,
    val publishedCount: Int,
    val availableCount: Int
)

data class SecureConversation(
    val conversationId: ConversationId,
    val mlsGroupId: ByteArray,
    val epoch: Long,
    val memberUserIds: Set<UserId>
)

data class SecurityCommitResult(
    val conversationId: ConversationId,
    val epoch: Long,
    val commitEnvelope: SecureEnvelopeV1,
    val welcomeEnvelopes: List<SecureEnvelopeV1>
)

data class ApplicationAad(
    val bytes: ByteArray
) {
    companion object {
        val Empty = ApplicationAad(ByteArray(0))
    }
}

data class SecureEnvelopeV1(
    val tenantId: TenantId,
    val appId: AppId,
    val conversationId: ConversationId,
    val mlsGroupId: ByteArray,
    val mlsEpoch: Long?,
    val senderUserId: UserId,
    val senderDeviceId: DeviceId,
    val messageId: MessageId,
    val contentKind: SecureContentKind,
    val aad: ByteArray,
    val payload: ByteArray,
    val magic: String = "RSEC",
    val version: Int = 1,
    val protocol: String = "MLS"
)

sealed interface IncomingSecurityResult {
    data class ApplicationMessage(
        val envelope: SecureEnvelopeV1,
        val plaintext: ByteArray,
        val contentType: SecureContentType
    ) : IncomingSecurityResult

    data class HandshakeProcessed(
        val conversationId: ConversationId,
        val newEpoch: Long
    ) : IncomingSecurityResult

    data class Queued(
        val conversationId: ConversationId,
        val reason: String
    ) : IncomingSecurityResult
}

interface ReaktorSecurity {
    val devices: SecureDeviceManager
    val conversations: SecureConversationManager
    val messages: SecureMessageManager
    val attachments: SecureAttachmentManager
    val verification: SecureVerificationManager
    val recovery: SecureRecoveryManager
}

interface SecureDeviceManager {
    suspend fun ensureDeviceRegistered(authContext: AuthContext): DeviceCryptoIdentity
    suspend fun publishKeyPackages(desiredAvailableCount: Int = 20): KeyPackagePublishResult
    suspend fun revokeCurrentDevice(reason: DeviceRevocationReason)
}

interface SecureConversationManager {
    suspend fun createDirectConversation(peerUserId: UserId): SecureConversation
    suspend fun createGroupConversation(memberUserIds: Set<UserId>): SecureConversation
    suspend fun addMembers(
        conversationId: ConversationId,
        userIds: Set<UserId>
    ): SecurityCommitResult

    suspend fun removeMembers(
        conversationId: ConversationId,
        userIds: Set<UserId>
    ): SecurityCommitResult

    suspend fun rotate(
        conversationId: ConversationId,
        reason: RotationReason
    ): SecurityCommitResult
}

interface SecureMessageManager {
    suspend fun encrypt(
        conversationId: ConversationId,
        plaintext: ByteArray,
        contentType: SecureContentType,
        aad: ApplicationAad = ApplicationAad.Empty
    ): SecureEnvelopeV1

    suspend fun processIncoming(envelope: SecureEnvelopeV1): IncomingSecurityResult

    fun incoming(conversationId: ConversationId): Flow<IncomingSecurityResult>
}

interface SecureAttachmentManager {
    suspend fun encryptAttachment(
        conversationId: ConversationId,
        plaintext: ByteArray,
        mediaTypeHint: String? = null
    ): EncryptedAttachmentRefV1
}

interface SecureVerificationManager {
    suspend fun fingerprint(deviceId: DeviceId): String
    suspend fun markVerified(userId: UserId, deviceId: DeviceId)
}

interface SecureRecoveryManager {
    suspend fun startDeviceToDeviceRecovery(newDeviceId: DeviceId)
}

data class EncryptedAttachmentRefV1(
    val objectId: String,
    val encryptedSize: Long,
    val chunkSize: Int,
    val algorithm: String,
    val ciphertextSha256: ByteArray?,
    val mediaTypeHint: String?
)
