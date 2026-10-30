package dev.shibasis.reaktor.security

import dev.shibasis.reaktor.auth.CredentialStore
import dev.shibasis.reaktor.auth.MacKeychainCredentialStore

class DesktopPlatformSecretBox(
    private val namespace: String,
    private val credentials: CredentialStore = MacKeychainCredentialStore(namespace)
) : PlatformSecretBox {
    init { require(namespace.isNotBlank() && namespace.length <= 256) }

    private fun root(): ByteArray {
        credentials.read(ROOT)?.let { require(it.size == 32); return it }
        val candidate = SecurityEngineFactory.storageKey()
        try {
            if (credentials.create(ROOT, candidate)) return candidate.copyOf()
            return requireNotNull(credentials.read(ROOT)).also { require(it.size == 32) }
        } finally { candidate.fill(0) }
    }

    override suspend fun seal(recordType: SecretRecordType, recordId: String, plaintext: ByteArray,
        aad: ByteArray, options: SecretAccessOptions): SealedSecret {
        require(!options.requireUserPresence) { "This key is configured for background access" }
        val key = root()
        try { return sealSecret(key, namespace, recordType, recordId, plaintext, aad) }
        finally { key.fill(0) }
    }

    override suspend fun open(recordType: SecretRecordType, recordId: String, sealed: SealedSecret,
        aad: ByteArray, options: SecretAccessOptions): ByteArray {
        require(!options.requireUserPresence) { "This key is configured for background access" }
        val key = requireNotNull(credentials.read(ROOT)) { "The protected root key is unavailable" }
        try { return openSecret(key, namespace, recordType, recordId, sealed, aad) }
        finally { key.fill(0) }
    }

    private companion object { const val ROOT = "security-root-v1" }
}

