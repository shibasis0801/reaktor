package dev.shibasis.reaktor.security

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class NativeDeviceProofTest {
    @Test fun nativeProofVerifiesOnTheJvmForEverySuiteAndSurvivesRestart() {
        val pin = "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val policy = EnginePolicy("tenant", "app", mapOf("auth-1" to pin))
        for (suite in MlsCipherSuiteId.entries) {
            NativeSecurityEngine.create(policy, suite).use { engine ->
                val key = engine.publicKey()
                val body = ByteArrayOutputStream().use { bytes ->
                    DataOutputStream(bytes).use { out ->
                        out.writeInt(0x52535031)
                        out.writeShort(1)
                        for (field in listOf("tenant".encodeToByteArray(), "app".encodeToByteArray(),
                            "user".encodeToByteArray(), "device".encodeToByteArray(), key)) {
                            out.writeInt(field.size)
                            out.write(field)
                        }
                        out.writeShort(suite.code)
                    }
                    bytes.toByteArray()
                }
                val public = if (suite.code == 2) {
                    val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
                        .getParameterSpec(ECParameterSpec::class.java)
                    KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(
                        ECPoint(BigInteger(1, key.copyOfRange(1, 33)), BigInteger(1, key.copyOfRange(33, 65))), params))
                } else {
                    val prefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0)
                    KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(prefix + key))
                }
                fun verifies(proof: ByteArray): Boolean = Signature.getInstance(if (suite.code == 2) "SHA256withECDSA" else "Ed25519").run {
                    initVerify(public)
                    update(body)
                    verify(proof)
                }
                assertTrue(verifies(engine.proveDevice("user", "device")))
                assertFalse(verifies(engine.proveDevice("other", "device")))
                assertFalse(verifies(engine.proveDevice("user", "other")))
                assertFailsWith<NativeSecurityException> { engine.proveDevice("", "device") }
                val snapshot = engine.snapshot()
                try {
                    NativeSecurityEngine.restore(snapshot, policy).use {
                        assertTrue(verifies(it.proveDevice("user", "device")))
                    }
                } finally { snapshot.fill(0) }
            }
        }
    }
}
