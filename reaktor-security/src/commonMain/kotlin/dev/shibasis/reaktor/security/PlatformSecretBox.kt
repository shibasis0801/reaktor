package dev.shibasis.reaktor.security

enum class SecretRecordType {
    LocalRootKey,
    MlsDeviceIdentity,
    MlsGroupStateSnapshot,
    KeyPackagePrivateMaterial,
    PendingCommit,
    RecoveryMetadata,
    AttachmentCache
}

data class SecretAccessOptions(
    val requireUserPresence: Boolean = false
) {
    companion object {
        val Default = SecretAccessOptions()
    }
}

data class SealedSecret(
    val version: Int,
    val algorithm: String,
    val keyAlias: String,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val createdAtEpochMs: Long
)

interface PlatformSecretBox {
    suspend fun seal(
        recordType: SecretRecordType,
        recordId: String,
        plaintext: ByteArray,
        aad: ByteArray,
        options: SecretAccessOptions = SecretAccessOptions.Default
    ): SealedSecret

    suspend fun open(
        recordType: SecretRecordType,
        recordId: String,
        sealed: SealedSecret,
        aad: ByteArray,
        options: SecretAccessOptions = SecretAccessOptions.Default
    ): ByteArray
}
