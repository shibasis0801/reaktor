package dev.shibasis.reaktor.security

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import dev.shibasis.reaktor.auth.api.DeviceCredentialRequest
import dev.shibasis.reaktor.auth.services.DeviceCredentialIssuer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthNativeCredentialTest {
    @Test fun nativeProofAndIssuedCredentialsInteroperateForEverySupportedSuite() {
        for (suite in MlsCipherSuiteId.entries) {
            val signing = ECKeyGenerator(Curve.P_256).keyID("device-auth-current").generate()
            val next = ECKeyGenerator(Curve.P_256).keyID("device-auth-next").generate()
            val issuer = DeviceCredentialIssuer(signing.toJSONString(), next.toPublicJWK().toJSONString(), JWKSet())
            val restoredIssuer = DeviceCredentialIssuer(signing.toJSONString(), next.toPublicJWK().toJSONString(), JWKSet())
            val publicKeys = issuer.publicJwkSet().keys.associate { key ->
                val ec = key.toECKey()
                key.keyID to (byteArrayOf(4) + ec.x.decode() + ec.y.decode())
            }
            val policy = EnginePolicy("tenant", "app", publicKeys)
            val chat = "auth-native-${suite.code}"
            NativeSecurityEngine.create(policy, suite).use { alice ->
                NativeSecurityEngine.create(policy, suite).use { bob ->
                    fun enroll(engine: NativeSecurityEngine, user: String, device: String): ByteArray {
                        val request = DeviceCredentialRequest("app", device, engine.publicKey(), suite.code,
                            engine.proveDevice(user, device))
                        assertTrue(DeviceCredentialIssuer.verifyProof("tenant", user, request))
                        assertFalse(DeviceCredentialIssuer.verifyProof("tenant", "other", request))
                        assertFalse(DeviceCredentialIssuer.verifyProof("other", user, request))
                        assertFalse(DeviceCredentialIssuer.verifyProof("tenant", user, request.copy(deviceId = "other")))
                        val credential = restoredIssuer.issue("tenant", user, request)
                        val changed = credential.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                        assertFailsWith<NativeSecurityException> { engine.installCredential(changed) }
                        engine.installCredential(credential)
                        return credential
                    }
                    val aliceCredential = enroll(alice, "alice", "alice-phone")
                    enroll(bob, "bob", "bob-phone")
                    alice.setMembers(chat, setOf("alice", "bob"))
                    bob.setMembers(chat, setOf("alice", "bob"))
                    val created = alice.prepareCreate(chat, listOf(bob.keyPackage()))
                    alice.accept(chat, created.commit)
                    bob.join(chat, created.welcome)
                    assertEquals(2, bob.roster(chat).size)
                    val envelope = "chat/message".encodeToByteArray()
                    val plaintext = "credential interoperability".encodeToByteArray()
                    val sealed = alice.seal(chat, envelope, plaintext)
                    val snapshot = bob.snapshot()
                    try {
                        NativeSecurityEngine.restore(snapshot, policy).use { restarted ->
                            val opened = restarted.open(chat, envelope, sealed)
                            assertContentEquals(plaintext, opened.plaintext)
                            assertContentEquals(aliceCredential, opened.credential)
                            assertTrue(opened.authenticated)
                            assertFalse(opened.duplicate)
                        }
                    } finally { snapshot.fill(0) }
                }
            }
        }
    }
}
