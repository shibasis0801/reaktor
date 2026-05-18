package dev.shibasis.reaktor.security

data class RsecMlsStateSnapshotV1(
    val tenantId: TenantId,
    val appId: AppId,
    val userId: UserId,
    val deviceId: DeviceId,
    val conversationId: ConversationId,
    val cipherSuite: MlsCipherSuiteId,
    val epoch: Long,
    val snapshotCiphertext: SealedSecret
)

/**
 * MLS++ currently does not expose a public durable serializer for State.
 * The native implementation must back this model with a minimal audited
 * MLS++ patch before reaktor-security can be considered production-ready.
 */
interface MlsStateSnapshotStore {
    suspend fun load(conversationId: ConversationId): RsecMlsStateSnapshotV1?
    suspend fun store(snapshot: RsecMlsStateSnapshotV1)
    suspend fun delete(conversationId: ConversationId)
}
