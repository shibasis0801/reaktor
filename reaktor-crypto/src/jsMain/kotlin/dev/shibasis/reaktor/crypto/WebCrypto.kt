package dev.shibasis.reaktor.crypto

import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import kotlin.js.Promise

/**
 * The browser and Worker implementation, on WebCrypto.
 *
 * This is the target that decided the shape of [Crypto]. WebCrypto is asynchronous with no
 * synchronous escape hatch, which is why the whole interface suspends; and it implements P-256,
 * HKDF and AES-GCM natively while X25519 support is still uneven across browsers, which is why
 * those are the algorithms.
 *
 * `crypto.subtle` exists in browsers, in Workers and in Node 16 and later, so one implementation
 * covers the web shell and the server without a second binding.
 */
private external interface CryptoKey

private external interface SubtleCrypto {
    fun generateKey(algorithm: dynamic, extractable: Boolean, usages: Array<String>): Promise<dynamic>
    fun importKey(
        format: String,
        keyData: dynamic,
        algorithm: dynamic,
        extractable: Boolean,
        usages: Array<String>,
    ): Promise<CryptoKey>

    fun exportKey(format: String, key: CryptoKey): Promise<ArrayBuffer>
    fun deriveBits(algorithm: dynamic, baseKey: CryptoKey, length: Int): Promise<ArrayBuffer>
    fun encrypt(algorithm: dynamic, key: CryptoKey, data: dynamic): Promise<ArrayBuffer>
    fun decrypt(algorithm: dynamic, key: CryptoKey, data: dynamic): Promise<ArrayBuffer>
    fun digest(algorithm: String, data: dynamic): Promise<ArrayBuffer>
}

private external interface WebCryptoApi {
    val subtle: SubtleCrypto
    fun getRandomValues(array: Uint8Array): Uint8Array
}

private val web: WebCryptoApi = js("crypto").unsafeCast<WebCryptoApi>()

private class WebCryptoImpl : Crypto {

    override suspend fun randomBytes(count: Int): ByteArray {
        val buffer = Uint8Array(count)
        web.getRandomValues(buffer)
        return ByteArray(count) { buffer[it] }
    }

    override suspend fun generateKeyPair(): KeyPair {
        val algorithm = js("({})")
        algorithm.name = "ECDH"
        algorithm.namedCurve = "P-256"

        val pair = web.subtle.generateKey(algorithm, true, arrayOf("deriveBits")).await()

        // SPKI and PKCS#8, the same encodings the JCA emits, so a key made on a phone opens on a
        // laptop and vice versa. That interoperability is the whole point of pinning the format.
        val publicKey = web.subtle.exportKey("spki", pair.publicKey.unsafeCast<CryptoKey>()).await()
        val privateKey = web.subtle.exportKey("pkcs8", pair.privateKey.unsafeCast<CryptoKey>()).await()

        return KeyPair(PublicKey(publicKey.toByteArray()) to PrivateKey(privateKey.toByteArray()))
    }

    override suspend fun agree(privateKey: PrivateKey, peer: PublicKey): SharedSecret {
        val algorithm = js("({})")
        algorithm.name = "ECDH"
        algorithm.namedCurve = "P-256"

        val ownKey = web.subtle.importKey(
            "pkcs8",
            privateKey.bytes.toUint8Array(),
            algorithm,
            false,
            arrayOf("deriveBits"),
        ).await()

        val peerKey = web.subtle.importKey(
            "spki",
            peer.bytes.toUint8Array(),
            algorithm,
            false,
            emptyArray(),
        ).await()

        val derive = js("({})")
        derive.name = "ECDH"
        derive.public = peerKey

        // 256 bits: the x-coordinate, which is what the JCA's `generateSecret` returns too.
        return SharedSecret(web.subtle.deriveBits(derive, ownKey, 256).await().toByteArray())
    }

    override suspend fun deriveKey(
        material: ByteArray,
        info: ByteArray,
        salt: ByteArray,
        length: Int,
    ): SecretKey {
        val hkdfKey = web.subtle.importKey(
            "raw",
            material.toUint8Array(),
            "HKDF",
            false,
            arrayOf("deriveBits"),
        ).await()

        val algorithm = js("({})")
        algorithm.name = "HKDF"
        algorithm.hash = "SHA-256"
        algorithm.salt = salt.toUint8Array()
        algorithm.info = info.toUint8Array()

        return SecretKey(web.subtle.deriveBits(algorithm, hkdfKey, length * 8).await().toByteArray())
    }

    override suspend fun seal(
        key: SecretKey,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): Sealed {
        val nonce = randomBytes(NONCE_BYTES)
        val algorithm = gcm(nonce, associatedData)
        val aesKey = importAes(key, "encrypt")
        val ciphertext = web.subtle.encrypt(algorithm, aesKey, plaintext.toUint8Array()).await()
        return Sealed(nonce, ciphertext.toByteArray())
    }

    override suspend fun open(
        key: SecretKey,
        sealed: Sealed,
        associatedData: ByteArray,
    ): ByteArray? = runCatching {
        val algorithm = gcm(sealed.nonce, associatedData)
        val aesKey = importAes(key, "decrypt")
        web.subtle.decrypt(algorithm, aesKey, sealed.ciphertext.toUint8Array()).await().toByteArray()
    }.getOrNull()

    override suspend fun sha256(bytes: ByteArray): ByteArray =
        web.subtle.digest("SHA-256", bytes.toUint8Array()).await().toByteArray()

    private suspend fun importAes(key: SecretKey, usage: String): CryptoKey {
        val algorithm = js("({})")
        algorithm.name = "AES-GCM"
        return web.subtle.importKey("raw", key.bytes.toUint8Array(), algorithm, false, arrayOf(usage))
            .await()
    }

    private fun gcm(nonce: ByteArray, associatedData: ByteArray): dynamic {
        val algorithm = js("({})")
        algorithm.name = "AES-GCM"
        algorithm.iv = nonce.toUint8Array()
        algorithm.tagLength = 128
        if (associatedData.isNotEmpty()) algorithm.additionalData = associatedData.toUint8Array()
        return algorithm
    }
}

private fun ByteArray.toUint8Array(): Uint8Array {
    val out = Uint8Array(size)
    // Through Int8Array: JS bytes are signed, exactly as Kotlin's are, so this reinterprets rather
    // than converts and a 0x80 byte survives the trip.
    val signed = out.unsafeCast<Int8Array>()
    forEachIndexed { index, byte -> signed.asDynamic()[index] = byte }
    return out
}

private fun ArrayBuffer.toByteArray(): ByteArray {
    val view = Uint8Array(this)
    return ByteArray(view.length) { view[it] }
}

private val instance: Crypto by lazy { WebCryptoImpl() }

actual fun crypto(): Crypto = instance
