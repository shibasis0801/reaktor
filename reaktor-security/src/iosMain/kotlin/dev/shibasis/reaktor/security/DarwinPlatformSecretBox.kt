package dev.shibasis.reaktor.security

class DarwinPlatformSecretBox(
    private val serviceName: String
) : PlatformSecretBox {
    override suspend fun seal(
        recordType: SecretRecordType,
        recordId: String,
        plaintext: ByteArray,
        aad: ByteArray,
        options: SecretAccessOptions
    ): SealedSecret {
        TODO("Back with Keychain this-device-only records for $serviceName")
    }

    override suspend fun open(
        recordType: SecretRecordType,
        recordId: String,
        sealed: SealedSecret,
        aad: ByteArray,
        options: SecretAccessOptions
    ): ByteArray {
        TODO("Back with Keychain this-device-only records for $serviceName")
    }
}
