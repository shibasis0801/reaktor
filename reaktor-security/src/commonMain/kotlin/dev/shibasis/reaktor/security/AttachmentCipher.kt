package dev.shibasis.reaktor.security

data class SealedAttachment(val key: ByteArray, val digest: ByteArray, val ciphertext: ByteArray)

object AttachmentCipher {
    fun seal(plaintext: ByteArray): SealedAttachment = SecurityEngineFactory.sealAttachment(plaintext)
    fun open(key: ByteArray, digest: ByteArray, ciphertext: ByteArray): ByteArray =
        SecurityEngineFactory.openAttachment(key, digest, ciphertext)
}
