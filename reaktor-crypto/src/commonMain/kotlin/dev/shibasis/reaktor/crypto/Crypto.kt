package dev.shibasis.reaktor.crypto

import kotlin.jvm.JvmInline

/**
 * The primitives, and only the primitives.
 *
 * Four things: randomness, a key agreement, a key derivation, and an authenticated cipher. Enough
 * to build end-to-end encryption on, and deliberately not enough to be a protocol — this module has
 * no opinion about what is being encrypted, where keys are stored, or who is allowed to hold one.
 *
 * **Everything is `suspend`.** Not because the JVM needs it, but because the browser does: WebCrypto
 * is asynchronous and there is no way to block on it. A synchronous API would have been possible on
 * three of four targets and impossible on the fourth, which is the same as impossible.
 *
 * **Algorithms are not configurable.** P-256, HKDF-SHA-256 and AES-256-GCM, chosen because they are
 * the intersection of what WebCrypto, the JCA and every Apple platform implement natively — X25519
 * is the better curve and its browser support is still uneven. A `Crypto` with a cipher parameter
 * is a `Crypto` that will eventually be handed a bad one.
 */
interface Crypto {

    /** Bytes from the platform CSPRNG. Never a seeded generator. */
    suspend fun randomBytes(count: Int): ByteArray

    /** A fresh P-256 key pair for key agreement. */
    suspend fun generateKeyPair(): KeyPair

    /**
     * ECDH against someone else's public key.
     *
     * Returns the raw shared secret, which must not be used as a key directly — it is a curve point
     * with structure, not uniform bytes. Put it through [deriveKey] first. That is why this returns
     * a [SharedSecret] rather than a `ByteArray`: the type is the reminder.
     */
    suspend fun agree(privateKey: PrivateKey, peer: PublicKey): SharedSecret

    /**
     * HKDF-SHA-256.
     *
     * [info] separates uses of the same key material. Two purposes deriving from one agreement must
     * pass different `info`, or they are the same key wearing two names.
     *
     * Bytes rather than a `String`, because `info` is not always text — a protocol that binds a
     * derivation to a public key or a counter puts those bytes here, and a String parameter would
     * have made that expressible only by accident, through whatever UTF-8 did to them. The
     * [deriveKey] overloads taking a label are the ergonomic form for the common case.
     */
    suspend fun deriveKey(
        material: ByteArray,
        info: ByteArray,
        salt: ByteArray = ByteArray(0),
        length: Int = SECRET_KEY_BYTES,
    ): SecretKey

    /**
     * AES-256-GCM.
     *
     * The nonce is generated here and returned inside [Sealed], because a nonce reused under one key
     * loses GCM its confidentiality *and* its authenticity — it is not a parameter a caller should
     * be trusted with, or tempted by.
     *
     * [associatedData] is authenticated and not encrypted: the right place for headers a relay must
     * read, so that tampering with them fails the open rather than going unnoticed.
     */
    suspend fun seal(
        key: SecretKey,
        plaintext: ByteArray,
        associatedData: ByteArray = ByteArray(0),
    ): Sealed

    /** Returns null when the ciphertext, the nonce or the associated data has been altered. */
    suspend fun open(
        key: SecretKey,
        sealed: Sealed,
        associatedData: ByteArray = ByteArray(0),
    ): ByteArray?

    /** SHA-256. Here so callers need not reach for a second dependency to hash a public key. */
    suspend fun sha256(bytes: ByteArray): ByteArray
}

/** HKDF from an agreement, with a text label. */
suspend fun Crypto.deriveKey(
    secret: SharedSecret,
    info: String,
    salt: ByteArray = ByteArray(0),
    length: Int = SECRET_KEY_BYTES,
): SecretKey = deriveKey(secret.bytes, info.encodeToByteArray(), salt, length)

/** HKDF from raw key material, with a text label. */
suspend fun Crypto.deriveKey(
    material: ByteArray,
    info: String,
    salt: ByteArray = ByteArray(0),
    length: Int = SECRET_KEY_BYTES,
): SecretKey = deriveKey(material, info.encodeToByteArray(), salt, length)

/** HKDF from an agreement, with byte info. */
suspend fun Crypto.deriveKey(
    secret: SharedSecret,
    info: ByteArray,
    salt: ByteArray = ByteArray(0),
    length: Int = SECRET_KEY_BYTES,
): SecretKey = deriveKey(secret.bytes, info, salt, length)

/** 32 bytes: AES-256. */
const val SECRET_KEY_BYTES: Int = 32

/** 12 bytes, the size GCM is defined for and the only size that avoids an internal rehash. */
const val NONCE_BYTES: Int = 12

/**
 * A public key, in the uncompressed SEC1 form P-256 uses on the wire.
 *
 * Kept as bytes rather than as a platform key object so it can be stored, sent and compared without
 * knowing which platform produced it — a device is going to receive these from other devices.
 */
@JvmInline
value class PublicKey(val bytes: ByteArray)

/**
 * A private key, in PKCS#8.
 *
 * Serialisable on purpose: a device has to keep its identity key across launches, and every store
 * available is a byte store. Whoever holds these bytes is the device, so they belong wherever the
 * platform keeps secrets and nowhere else.
 */
@JvmInline
value class PrivateKey(val bytes: ByteArray)

@JvmInline
value class KeyPair(val pair: Pair<PublicKey, PrivateKey>) {
    val publicKey: PublicKey get() = pair.first
    val privateKey: PrivateKey get() = pair.second
}

/**
 * The raw output of a key agreement.
 *
 * A distinct type from [SecretKey] so that using one where the other belongs does not compile. It
 * is not uniform and must be run through a KDF before it encrypts anything.
 */
@JvmInline
value class SharedSecret(val bytes: ByteArray)

/** Key material ready to encrypt with. */
@JvmInline
value class SecretKey(val bytes: ByteArray)

/**
 * A nonce and a ciphertext, which always travel together.
 *
 * One type rather than two returns, because a ciphertext without its nonce is unopenable and a
 * caller that has to remember to keep both will eventually not.
 */
class Sealed(val nonce: ByteArray, val ciphertext: ByteArray) {
    /** `nonce || ciphertext`, for the callers that need one blob to store or send. */
    fun toBytes(): ByteArray = nonce + ciphertext

    companion object {
        fun fromBytes(bytes: ByteArray): Sealed? {
            if (bytes.size <= NONCE_BYTES) return null
            return Sealed(bytes.copyOfRange(0, NONCE_BYTES), bytes.copyOfRange(NONCE_BYTES, bytes.size))
        }
    }
}

/** The platform's implementation. */
expect fun crypto(): Crypto

/**
 * Compare two byte arrays without leaking where they differ.
 *
 * `contentEquals` returns on the first mismatch, so how long it took says how much of a value was
 * guessed correctly. That matters wherever the thing being compared is a secret — a token, a MAC, a
 * short authentication string — and it is cheap enough to be the default everywhere.
 */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var difference = 0
    for (index in a.indices) difference = difference or (a[index].toInt() xor b[index].toInt())
    return difference == 0
}

/** Lowercase hex. */
fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    forEach { byte ->
        val value = byte.toInt() and 0xFF
        out.append(digits[value shr 4]).append(digits[value and 0x0F])
    }
    return out.toString()
}

/** Reads what [toHex] wrote. Returns null on anything malformed. */
fun hexToBytes(hex: String): ByteArray? {
    if (hex.length % 2 != 0) return null
    val out = ByteArray(hex.length / 2)
    for (index in out.indices) {
        val high = Character(hex[index * 2]) ?: return null
        val low = Character(hex[index * 2 + 1]) ?: return null
        out[index] = ((high shl 4) or low).toByte()
    }
    return out
}

@Suppress("FunctionName")
private fun Character(char: Char): Int? = when (char) {
    in '0'..'9' -> char - '0'
    in 'a'..'f' -> char - 'a' + 10
    in 'A'..'F' -> char - 'A' + 10
    else -> null
}
