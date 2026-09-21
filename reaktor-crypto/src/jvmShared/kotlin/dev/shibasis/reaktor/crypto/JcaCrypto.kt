package dev.shibasis.reaktor.crypto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The JVM and Android implementation, on the JCA.
 *
 * Shared between both targets because they are the same platform for this purpose — Android's
 * providers differ from the JVM's in which one wins, not in what P-256, HKDF and AES-GCM do.
 *
 * Everything runs on [Dispatchers.Default]. Key agreement is measured in milliseconds and would be
 * survivable inline, but the interface is `suspend` because WebCrypto forces it, and honouring that
 * here means a caller's assumptions hold on every target rather than on three of four.
 */
private class JcaCrypto : Crypto {

    private val random = SecureRandom()

    override suspend fun randomBytes(count: Int): ByteArray = withContext(Dispatchers.Default) {
        ByteArray(count).also(random::nextBytes)
    }

    override suspend fun generateKeyPair(): KeyPair = withContext(Dispatchers.Default) {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), random)
        val pair = generator.generateKeyPair()
        KeyPair(PublicKey(pair.public.encoded) to PrivateKey(pair.private.encoded))
    }

    override suspend fun agree(privateKey: PrivateKey, peer: PublicKey): SharedSecret =
        withContext(Dispatchers.Default) {
            val factory = KeyFactory.getInstance("EC")
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(factory.generatePrivate(PKCS8EncodedKeySpec(privateKey.bytes)))
            agreement.doPhase(factory.generatePublic(X509EncodedKeySpec(peer.bytes)), true)
            SharedSecret(agreement.generateSecret())
        }

    override suspend fun deriveKey(
        material: ByteArray,
        info: ByteArray,
        salt: ByteArray,
        length: Int,
    ): SecretKey = withContext(Dispatchers.Default) {
        SecretKey(hkdf(material, salt, info, length))
    }

    /**
     * HKDF, by hand.
     *
     * The JCA has no HKDF before Java 25 and Android has none at all, so the choice is this or a
     * dependency. It is RFC 5869 in fifteen lines and is covered by the module's test vectors.
     */
    private fun hkdf(material: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val hashLength = mac.macLength

        require(length <= 255 * hashLength) { "HKDF cannot produce $length bytes" }

        // Extract. An all-zero salt is the RFC's own default when none is supplied.
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(hashLength) else salt, "HmacSHA256"))
        val pseudoRandomKey = mac.doFinal(material)

        // Expand.
        mac.init(SecretKeySpec(pseudoRandomKey, "HmacSHA256"))
        val output = ByteArray(length)
        var block = ByteArray(0)
        var written = 0
        var counter = 1

        while (written < length) {
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            block = mac.doFinal()
            val take = minOf(block.size, length - written)
            block.copyInto(output, written, 0, take)
            written += take
            counter++
        }

        return output
    }

    override suspend fun seal(
        key: SecretKey,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): Sealed = withContext(Dispatchers.Default) {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.bytes, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
        Sealed(nonce, cipher.doFinal(plaintext))
    }

    override suspend fun open(
        key: SecretKey,
        sealed: Sealed,
        associatedData: ByteArray,
    ): ByteArray? = withContext(Dispatchers.Default) {
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key.bytes, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, sealed.nonce),
            )
            if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
            cipher.doFinal(sealed.ciphertext)
        }.getOrNull()
    }

    override suspend fun sha256(bytes: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    }

    private companion object {
        const val GCM_TAG_BITS = 128
    }
}

private val instance: Crypto by lazy { JcaCrypto() }

actual fun crypto(): Crypto = instance
