package dev.shibasis.reaktor.auth

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import dev.shibasis.reaktor.auth.api.AuthService
import dev.shibasis.reaktor.auth.api.DeviceCredentialRequest
import dev.shibasis.reaktor.auth.api.RefreshRequest
import dev.shibasis.reaktor.auth.runtime.AuthRuntimeConfig
import dev.shibasis.reaktor.auth.runtime.AuthRuntimeGraph
import dev.shibasis.reaktor.auth.services.DeviceCredentialIssuer
import dev.shibasis.reaktor.auth.spring.JwksController
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.service.Environment
import dev.shibasis.reaktor.service.contract.LocalLink
import dev.shibasis.reaktor.service.contract.over
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeviceCredentialIntegrationTest {
    @Test fun authenticatedContractIssuesCredentialAndRejectsRevokedSessionsAndWrongBindings() = runBlocking {
        AuthDbFixture.ensure()
        val signing = ECKeyGenerator(Curve.P_256).keyID("device-current").generate()
        val next = ECKeyGenerator(Curve.P_256).keyID("device-next").generate()
        val runtime = AuthRuntimeGraph.create(AuthDbFixture.adapter(), AuthRuntimeConfig(
            deviceCredentialJwkJson = signing.toJSONString(), deviceCredentialNextJwkJson = next.toPublicJWK().toJSONString()))
        val api = AuthService() over LocalLink(runtime.service)
        val (app, user) = AuthDbFixture.seedUser()
        val session = runtime.sessionLifecycle.createSession(user, app, environment = Environment.STAGE)
        val tokens = assertNotNull(api.sessionRefresh(RefreshRequest(session.rawRefreshToken, environment = Environment.STAGE)).tokenSet)
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val unsigned = DeviceCredentialRequest(app, UUID.randomUUID().toString(), keys.public.encoded.takeLast(32).toByteArray(), 3,
            byteArrayOf(), headers = mutableMapOf("Authorization" to "Bearer ${tokens.accessToken}"), environment = Environment.STAGE)
        val proof = Signature.getInstance("Ed25519").run {
            initSign(keys.private)
            update(DeviceCredentialIssuer.proofBody(app, user, unsigned))
            sign()
        }
        val request = unsigned.copy(proof = proof)
        val response = api.deviceCredential(request)
        assertEquals(StatusCode.OK, response.statusCode)
        val credential = assertNotNull(response.credential)
        val body = credential.copyOfRange(0, credential.size - 64)
        assertTrue(Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initVerify(signing.toECPublicKey())
            update(body)
            verify(credential.takeLast(64).toByteArray())
        })
        DataInputStream(ByteArrayInputStream(body)).use {
            assertEquals(0x52534331, it.readInt())
            assertEquals(1, it.readUnsignedShort())
            fun text() = ByteArray(it.readInt()).also(it::readFully).decodeToString()
            assertEquals(app, text())
            assertEquals(app, text())
            assertEquals(user, text())
            assertEquals(request.deviceId, text())
            assertTrue(ByteArray(it.readInt()).also(it::readFully).contentEquals(request.signatureKey))
            assertEquals(1, it.readUnsignedShort())
            assertEquals(3, it.readUnsignedShort())
            val created = it.readLong()
            assertEquals(365L * 24 * 60 * 60 * 1000, it.readLong() - created)
            assertEquals(signing.keyID, text())
            assertEquals(0, it.available())
        }
        val jwks = JWKSet.parse(JwksController(runtime.jwt.signingKeys, runtime.devices.issuer).jwks())
        assertEquals(3, jwks.keys.size)
        assertTrue(jwks.keys.all { !it.isPrivate })
        assertEquals(StatusCode.BAD_REQUEST, api.deviceCredential(request.copy(deviceId = "other-device")).statusCode)
        assertEquals(StatusCode.BAD_REQUEST, api.deviceCredential(request.copy(signatureKey = ByteArray(32))).statusCode)
        assertEquals(StatusCode.UNAUTHORIZED, api.deviceCredential(request.copy(appId = UUID.randomUUID().toString())).statusCode)
        assertEquals(StatusCode.UNAUTHORIZED, api.deviceCredential(request.copy(headers = mutableMapOf())).statusCode)
        runtime.sessionLifecycle.revokeAllForPrincipal(user, Environment.STAGE)
        assertEquals(StatusCode.UNAUTHORIZED, api.deviceCredential(request).statusCode)
    }

    @Test fun proofBindsEveryIdentifierAndConfiguredKeysSurviveIssuerRecreation() {
        val signing = ECKeyGenerator(Curve.P_256).keyID("persistent").generate()
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val unsigned = DeviceCredentialRequest("app", "device", keys.public.encoded.takeLast(32).toByteArray(), 1, byteArrayOf())
        val proof = Signature.getInstance("Ed25519").run {
            initSign(keys.private)
            update(DeviceCredentialIssuer.proofBody("tenant", "user", unsigned))
            sign()
        }
        val request = unsigned.copy(proof = proof)
        assertTrue(DeviceCredentialIssuer.verifyProof("tenant", "user", request))
        assertFalse(DeviceCredentialIssuer.verifyProof("tenant2", "user", request))
        assertFalse(DeviceCredentialIssuer.verifyProof("tenant", "other", request))
        assertFalse(DeviceCredentialIssuer.verifyProof("tenant", "user", request.copy(appId = "other")))
        assertFalse(DeviceCredentialIssuer.verifyProof("tenant", "user", request.copy(cipherSuite = 3)))
        val first = DeviceCredentialIssuer(signing.toJSONString(), "", JWKSet())
        val second = DeviceCredentialIssuer(signing.toJSONString(), "", JWKSet())
        assertEquals(first.publicJwkSet().toString(), second.publicJwkSet().toString())
        assertTrue(second.issue("tenant", "user", request).isNotEmpty())
        assertFalse(DeviceCredentialIssuer("", "", JWKSet()).available)
    }
}
