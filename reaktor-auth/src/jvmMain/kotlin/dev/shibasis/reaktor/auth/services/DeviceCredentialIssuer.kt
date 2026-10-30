package dev.shibasis.reaktor.auth.services

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import dev.shibasis.reaktor.auth.api.DeviceCredentialRequest
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

class DeviceCredentialIssuer(currentJson: String, nextJson: String, reservedKeys: JWKSet) {
    private val current = currentJson.takeIf(String::isNotBlank)?.let(ECKey::parse)
    private val next = nextJson.takeIf(String::isNotBlank)?.let(ECKey::parse)
    val available: Boolean get() = current != null

    init {
        require(current != null || next == null)
        listOfNotNull(current, next).forEach {
            require(it.curve == Curve.P_256 && !it.keyID.isNullOrBlank() && reservedKeys.keys.none { reserved ->
                reserved.keyID == it.keyID || (reserved is ECKey && reserved.x == it.x && reserved.y == it.y)
            })
            identifier(it.keyID)
        }
        require(current == null || current.isPrivate)
        require(next == null || (next.keyID != current?.keyID && next.x != current?.x))
    }

    fun publicJwkSet(): JWKSet = JWKSet(listOfNotNull(current, next).map { it.toPublicJWK() })

    fun issue(tenant: String, user: String, request: DeviceCredentialRequest, nowMs: Long = System.currentTimeMillis()): ByteArray {
        val key = checkNotNull(current) { "Device credential signing key is not configured" }
        require(nowMs > 0)
        require(verifyProof(tenant, user, request)) { "Invalid device proof" }
        val body = encoded {
            writeInt(0x52534331)
            writeShort(1)
            text(tenant)
            text(request.appId)
            text(user)
            text(request.deviceId)
            field(request.signatureKey)
            writeShort(1)
            writeShort(request.cipherSuite)
            writeLong(nowMs)
            writeLong(Math.addExact(nowMs, 365L * 24 * 60 * 60 * 1000))
            text(key.keyID)
        }
        val signature = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initSign(key.toECPrivateKey())
            update(body)
            sign()
        }
        check(signature.size == 64)
        return body + signature
    }

    companion object {
        fun proofBody(tenant: String, user: String, request: DeviceCredentialRequest): ByteArray = encoded {
            require(request.cipherSuite in 1..3)
            require(request.signatureKey.size == if (request.cipherSuite == 2) 65 else 32)
            require(request.cipherSuite != 2 || request.signatureKey[0] == 4.toByte())
            writeInt(0x52535031)
            writeShort(1)
            text(tenant)
            text(request.appId)
            text(user)
            text(request.deviceId)
            field(request.signatureKey)
            writeShort(request.cipherSuite)
        }

        fun verifyProof(tenant: String, user: String, request: DeviceCredentialRequest): Boolean = runCatching {
            val body = proofBody(tenant, user, request)
            require(request.proof.size in 1..144)
            val publicKey = if (request.cipherSuite == 2) {
                val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
                    .getParameterSpec(ECParameterSpec::class.java)
                val point = ECPoint(BigInteger(1, request.signatureKey.copyOfRange(1, 33)),
                    BigInteger(1, request.signatureKey.copyOfRange(33, 65)))
                KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
            } else {
                val prefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0)
                KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(prefix + request.signatureKey))
            }
            Signature.getInstance(if (request.cipherSuite == 2) "SHA256withECDSA" else "Ed25519").run {
                initVerify(publicKey)
                update(body)
                verify(request.proof)
            }
        }.getOrDefault(false)

        private fun identifier(value: String) {
            require(value.isNotEmpty() && value.length <= 4096 && value.all { it.code in 0x21..0x7e })
        }

        private fun DataOutputStream.text(value: String) {
            identifier(value)
            field(value.toByteArray(Charsets.US_ASCII))
        }

        private fun DataOutputStream.field(value: ByteArray) {
            require(value.isNotEmpty() && value.size <= 4096)
            writeInt(value.size)
            write(value)
        }

        private fun encoded(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { it.write() }
            bytes.toByteArray()
        }
    }
}
