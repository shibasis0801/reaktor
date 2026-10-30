package dev.shibasis.reaktor.security

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import dev.shibasis.reaktor.auth.api.DeviceCredentialRequest
import dev.shibasis.reaktor.auth.services.DeviceCredentialIssuer

internal class SecurityTestCredentials {
    private val key = ECKeyGenerator(Curve.P_256).keyID("device-auth").generate()
    val issuer = DeviceCredentialIssuer(key.toJSONString(), "", JWKSet())
    val policy = EnginePolicy("tenant", "app", mapOf(key.keyID to (byteArrayOf(4) + key.x.decode() + key.y.decode())))
    fun credential(user: String, registration: DeviceRegistration): ByteArray =
        issuer.issue("tenant", user, DeviceCredentialRequest("app", registration.deviceId, registration.signatureKey,
            registration.cipherSuite, registration.proof))
    fun device(user: String): NativeSecurityEngine = NativeSecurityEngine.create(policy).also { engine ->
        engine.installCredential(credential(user, DeviceRegistration(user + "-phone", engine.publicKey(), 3, engine.proveDevice(user, user + "-phone"))))
    }
}

