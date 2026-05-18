package dev.shibasis.reaktor.security

class DesktopPlatformSecretBox(
    private val namespace: String
) : PlatformSecretBox {
    override suspend fun seal(
        recordType: SecretRecordType,
        recordId: String,
        plaintext: ByteArray,
        aad: ByteArray,
        options: SecretAccessOptions
    ): SealedSecret {
        TODO("Dispatch to DPAPI, Keychain, or Secret Service for $namespace")
    }

    override suspend fun open(
        recordType: SecretRecordType,
        recordId: String,
        sealed: SealedSecret,
        aad: ByteArray,
        options: SecretAccessOptions
    ): ByteArray {
        TODO("Dispatch to DPAPI, Keychain, or Secret Service for $namespace")
    }
}
