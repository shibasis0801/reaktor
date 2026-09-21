package dev.shibasis.reaktor.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The same tests run on every target, which is the point.
 *
 * Two implementations sit behind [crypto] — the JCA and WebCrypto — and the whole value of the
 * module is that a key made by one opens on the other. The published vectors below are what
 * establishes that: they pin both implementations to the same standard rather than to each other,
 * so neither can drift and still pass.
 */
class HkdfTest {

    /**
     * RFC 5869, appendix A.1.
     *
     * The JVM implementation is HKDF by hand, because the JCA has none before Java 25 and Android
     * has none at all. A hand-rolled KDF is exactly the thing that must be checked against a
     * published vector rather than against the other implementation.
     */
    @Test
    fun matchesTheRfcVector() = runTest {
        val material = ByteArray(22) { 0x0b }
        val salt = hexToBytes("000102030405060708090a0b0c")!!
        val info = ByteArray(10) { (0xf0 + it).toByte() }

        val derived = crypto().deriveKey(material, info, salt, length = 42)

        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
            derived.bytes.toHex(),
        )
    }

    /** RFC 5869, appendix A.3 — no salt, no info. */
    @Test
    fun matchesTheRfcVectorWithoutSaltOrInfo() = runTest {
        val material = ByteArray(22) { 0x0b }

        val derived = crypto().deriveKey(material, info = "", salt = ByteArray(0), length = 42)

        assertEquals(
            "8da4e775a563c18f715f802a063c5a31" +
                "b8a11f5c5ee1879ec3454e5f3c738d2d" +
                "9d201395faa4b61a96c8",
            derived.bytes.toHex(),
        )
    }

    @Test
    fun differentInfoGivesDifferentKeys() = runTest {
        val material = ByteArray(32) { 7 }
        val one = crypto().deriveKey(material, "cairn:room")
        val two = crypto().deriveKey(material, "cairn:wrap")

        assertNotEquals(one.bytes.toHex(), two.bytes.toHex())
    }
}

class CipherTest {

    @Test
    fun sealedTextComesBack() = runTest {
        val key = SecretKey(crypto().randomBytes(SECRET_KEY_BYTES))
        val message = "ssh -L 8080:localhost:8080 deploy@edge-03".encodeToByteArray()

        val sealed = crypto().seal(key, message)
        val opened = crypto().open(key, sealed)

        assertEquals(message.toHex(), opened?.toHex())
    }

    @Test
    fun theSameMessageSealsDifferentlyEveryTime() = runTest {
        val key = SecretKey(crypto().randomBytes(SECRET_KEY_BYTES))
        val message = "the same thing twice".encodeToByteArray()

        val first = crypto().seal(key, message)
        val second = crypto().seal(key, message)

        // A fresh nonce per call, which is why the caller is not allowed to supply one: a repeat
        // under one key costs GCM both its confidentiality and its authenticity.
        assertNotEquals(first.nonce.toHex(), second.nonce.toHex())
        assertNotEquals(first.ciphertext.toHex(), second.ciphertext.toHex())
    }

    @Test
    fun anAlteredCiphertextDoesNotOpen() = runTest {
        val key = SecretKey(crypto().randomBytes(SECRET_KEY_BYTES))
        val sealed = crypto().seal(key, "untouched".encodeToByteArray())

        val tampered = Sealed(
            sealed.nonce,
            sealed.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() },
        )

        assertNull(crypto().open(key, tampered))
    }

    @Test
    fun anAlteredNonceDoesNotOpen() = runTest {
        val key = SecretKey(crypto().randomBytes(SECRET_KEY_BYTES))
        val sealed = crypto().seal(key, "untouched".encodeToByteArray())

        val tampered = Sealed(
            sealed.nonce.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() },
            sealed.ciphertext,
        )

        assertNull(crypto().open(key, tampered))
    }

    @Test
    fun theWrongKeyDoesNotOpen() = runTest {
        val sealed = crypto().seal(SecretKey(crypto().randomBytes(SECRET_KEY_BYTES)), "no".encodeToByteArray())
        assertNull(crypto().open(SecretKey(crypto().randomBytes(SECRET_KEY_BYTES)), sealed))
    }

    /**
     * Associated data is authenticated and not encrypted.
     *
     * This is what lets a relay read the headers it needs to route by while still being unable to
     * change them unnoticed — the property the whole envelope design leans on.
     */
    @Test
    fun alteredAssociatedDataDoesNotOpen() = runTest {
        val key = SecretKey(crypto().randomBytes(SECRET_KEY_BYTES))
        val header = """{"epoch":0}""".encodeToByteArray()

        val sealed = crypto().seal(key, "body".encodeToByteArray(), header)

        assertEquals("body", crypto().open(key, sealed, header)?.decodeToString())
        assertNull(crypto().open(key, sealed, """{"epoch":1}""".encodeToByteArray()))
    }

    @Test
    fun anEmptyMessageStillAuthenticates() = runTest {
        val key = SecretKey(crypto().randomBytes(SECRET_KEY_BYTES))
        val sealed = crypto().seal(key, ByteArray(0))

        assertEquals(0, crypto().open(key, sealed)?.size)
        assertTrue(sealed.ciphertext.isNotEmpty(), "the tag is still there")
    }

    @Test
    fun aSealedBlobSurvivesBeingFlattenedAndParsed() = runTest {
        val key = SecretKey(crypto().randomBytes(SECRET_KEY_BYTES))
        val sealed = crypto().seal(key, "round trip".encodeToByteArray())

        val parsed = Sealed.fromBytes(sealed.toBytes())

        assertEquals("round trip", crypto().open(key, parsed!!)?.decodeToString())
        assertNull(Sealed.fromBytes(ByteArray(NONCE_BYTES)), "a blob with no ciphertext is not sealed")
    }
}

class AgreementTest {

    @Test
    fun bothSidesReachTheSameSecret() = runTest {
        val alice = crypto().generateKeyPair()
        val bob = crypto().generateKeyPair()

        val fromAlice = crypto().agree(alice.privateKey, bob.publicKey)
        val fromBob = crypto().agree(bob.privateKey, alice.publicKey)

        assertEquals(fromAlice.bytes.toHex(), fromBob.bytes.toHex())
        assertEquals(32, fromAlice.bytes.size, "P-256 agreement is the 32-byte x-coordinate")
    }

    @Test
    fun aThirdPartyReachesSomethingElse() = runTest {
        val alice = crypto().generateKeyPair()
        val bob = crypto().generateKeyPair()
        val eve = crypto().generateKeyPair()

        val real = crypto().agree(alice.privateKey, bob.publicKey)
        val eavesdropped = crypto().agree(eve.privateKey, bob.publicKey)

        assertNotEquals(real.bytes.toHex(), eavesdropped.bytes.toHex())
    }

    @Test
    fun everyKeyPairIsNew() = runTest {
        val one = crypto().generateKeyPair()
        val two = crypto().generateKeyPair()
        assertNotEquals(one.publicKey.bytes.toHex(), two.publicKey.bytes.toHex())
    }

    /** The end-to-end shape Cairn uses: agree, derive, wrap a key, unwrap it on the other side. */
    @Test
    fun aKeyCanBeHandedOverThroughAnUntrustedRelay() = runTest {
        val sender = crypto().generateKeyPair()
        val receiver = crypto().generateKeyPair()
        val roomKey = crypto().randomBytes(SECRET_KEY_BYTES)

        val wrapping = crypto().deriveKey(
            crypto().agree(sender.privateKey, receiver.publicKey),
            info = "cairn:room-key-wrap",
        )
        val wrapped = crypto().seal(wrapping, roomKey)

        // Everything the relay sees: two public keys and a sealed blob.
        val unwrapping = crypto().deriveKey(
            crypto().agree(receiver.privateKey, sender.publicKey),
            info = "cairn:room-key-wrap",
        )
        val unwrapped = crypto().open(unwrapping, wrapped)

        assertEquals(roomKey.toHex(), unwrapped?.toHex())

        // And what an eavesdropper with its own key gets.
        val eve = crypto().generateKeyPair()
        val guess = crypto().deriveKey(
            crypto().agree(eve.privateKey, sender.publicKey),
            info = "cairn:room-key-wrap",
        )
        assertNull(crypto().open(guess, wrapped))
    }
}

class DigestTest {

    /** The empty-string SHA-256, which every implementation agrees on or is broken. */
    @Test
    fun matchesTheKnownDigest() = runTest {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            crypto().sha256(ByteArray(0)).toHex(),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            crypto().sha256("abc".encodeToByteArray()).toHex(),
        )
    }
}

class EncodingTest {

    @Test
    fun hexRoundTrips() = runTest {
        val bytes = crypto().randomBytes(64)
        assertEquals(bytes.toHex(), hexToBytes(bytes.toHex())!!.toHex())
    }

    @Test
    fun highBytesSurvive() = runTest {
        val bytes = byteArrayOf(0x00, 0x7f, 0x80.toByte(), 0xff.toByte())
        assertEquals("007f80ff", bytes.toHex())
        assertEquals("007f80ff", hexToBytes("007f80ff")!!.toHex())
    }

    @Test
    fun malformedHexIsRejected() = runTest {
        assertNull(hexToBytes("abc"))
        assertNull(hexToBytes("zz"))
    }

    @Test
    fun constantTimeEqualsAgreesWithContentEquals() = runTest {
        val a = crypto().randomBytes(32)
        assertTrue(constantTimeEquals(a, a.copyOf()))
        assertFalse(constantTimeEquals(a, crypto().randomBytes(32)))
        assertFalse(constantTimeEquals(a, a.copyOf(31)))
    }
}
