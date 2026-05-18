package dev.shibasis.reaktor.security

class AndroidPlatformSecretBox(
    private val keyAlias: String
) : PlatformSecretBox {
    override suspend fun seal(
        recordType: SecretRecordType,
        recordId: String,
        plaintext: ByteArray,
        aad: ByteArray,
        options: SecretAccessOptions
    ): SealedSecret {
        TODO("Back with Android Keystore AES-GCM using alias $keyAlias")
    }

    override suspend fun open(
        recordType: SecretRecordType,
        recordId: String,
        sealed: SealedSecret,
        aad: ByteArray,
        options: SecretAccessOptions
    ): ByteArray {
        TODO("Back with Android Keystore AES-GCM using alias $keyAlias")
    }
}
