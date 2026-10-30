package dev.shibasis.reaktor.auth

/** Opaque, bounded records in the operating system's credential store. Implementations never log values. */
interface CredentialStore {
    fun read(key: String): ByteArray?
    fun write(key: String, value: ByteArray)
    fun create(key: String, value: ByteArray): Boolean = throw UnsupportedOperationException("Atomic credential creation is unavailable")
    fun delete(key: String)
}

class CredentialAccessException(message: String) : IllegalStateException(message)
