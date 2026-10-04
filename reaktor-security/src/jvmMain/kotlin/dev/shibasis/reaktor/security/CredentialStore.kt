package dev.shibasis.reaktor.security

/** Opaque, bounded records in the operating system's credential store. Implementations never log values. */
interface CredentialStore {
    fun read(key: String): ByteArray?
    fun write(key: String, value: ByteArray)
    fun delete(key: String)
}

class CredentialAccessException(message: String) : IllegalStateException(message)
