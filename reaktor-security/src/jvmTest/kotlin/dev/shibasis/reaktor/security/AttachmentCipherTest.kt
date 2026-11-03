package dev.shibasis.reaktor.security

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AttachmentCipherTest {
    @Test fun nativeFileBindingsAuthenticateEmptyBoundaryAndMultipleChunkFiles() {
        for (size in listOf(0, 17, 65535, 65536, 65537, 262147)) {
            val input = ByteArray(size) { (it % 251).toByte() }
            val sealed = AttachmentCipher.seal(input)
            assertEquals(32, sealed.key.size)
            assertEquals(32, sealed.digest.size)
            assertFalse(sealed.ciphertext.contentEquals(input))
            assertContentEquals(input, AttachmentCipher.open(sealed.key, sealed.digest, sealed.ciphertext))
            assertFalse(sealed.key.contentEquals(AttachmentCipher.seal(input).key))
        }
    }

    @Test fun nativeFileBindingsRefuseAlteredIncompleteAndSubstitutedObjects() {
        val sealed = AttachmentCipher.seal(ByteArray(131079) { 0x65 })
        val changed = sealed.ciphertext.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertFailsWith<NativeSecurityException> { AttachmentCipher.open(sealed.key, sealed.digest, changed) }
        for (size in listOf(0, 16, 65569, sealed.ciphertext.size - 1)) {
            assertFailsWith<NativeSecurityException> {
                AttachmentCipher.open(sealed.key, sealed.digest, sealed.ciphertext.copyOf(size))
            }
        }
        val digest = sealed.digest.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFailsWith<NativeSecurityException> { AttachmentCipher.open(sealed.key, digest, sealed.ciphertext) }
        val key = sealed.key.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFailsWith<NativeSecurityException> { AttachmentCipher.open(key, sealed.digest, sealed.ciphertext) }
        assertContentEquals(ByteArray(131079) { 0x65 }, AttachmentCipher.open(sealed.key, sealed.digest, sealed.ciphertext))
    }
}
