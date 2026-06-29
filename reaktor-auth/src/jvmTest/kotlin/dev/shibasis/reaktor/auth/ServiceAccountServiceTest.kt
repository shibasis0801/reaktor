package dev.shibasis.reaktor.auth

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.shibasis.reaktor.auth.jwt.JwtMinter
import dev.shibasis.reaktor.auth.jwt.JwtVerifier
import dev.shibasis.reaktor.auth.jwt.SigningKeys
import dev.shibasis.reaktor.auth.kernel.AuthDefaults
import dev.shibasis.reaktor.auth.services.CLIENT_ASSERTION_TYPE_JWT_BEARER
import dev.shibasis.reaktor.auth.services.ServiceAccountService
import java.security.MessageDigest
import java.util.Base64
import java.util.Date
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Flow-B server-to-server auth against in-memory H2: client_credentials with a shared secret and with
 * RFC 7523 `private_key_jwt`, plus audience-binding and scope down-scoping.
 */
class ServiceAccountServiceTest {
    private val signingKeys = SigningKeys("", "")
    private val minter = JwtMinter(signingKeys)
    private val verifier = JwtVerifier(emptyList(), signingKeys)
    private val service = ServiceAccountService(minter)

    @BeforeTest
    fun setup() = AuthDbFixture.ensure()

    private fun sha256(value: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    private fun ecKey(kid: String): ECKey =
        ECKeyGenerator(Curve.P_256).keyID(kid).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.ES256).generate()

    private fun clientAssertion(clientId: String, key: ECKey, audience: String = AuthDefaults.ISSUER): String {
        val now = Date()
        val claims = JWTClaimsSet.Builder()
            .issuer(clientId).subject(clientId).audience(audience)
            .jwtID(UUID.randomUUID().toString())
            .issueTime(now).expirationTime(Date(now.time + 60_000))
            .build()
        val header = JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.keyID).type(JOSEObjectType("JWT")).build()
        return SignedJWT(header, claims).apply { sign(ECDSASigner(key)) }.serialize()
    }

    @Test
    fun clientCredentialsWithSecretMintsAudienceBoundScopeIntersectedToken() {
        val (appId, _) = AuthDbFixture.seedUser()
        val clientId = "svc-${UUID.randomUUID()}"
        val secret = "s3cr3t-value"
        AuthDbFixture.seedServiceAccount(
            clientId, appId, authMethod = "SECRET", secretHash = sha256(secret),
            scopes = "telemetry:write telemetry:read", allowedAudiences = """["telemetry"]""",
        )

        val result = service.issueClientCredentialsToken(
            clientId = clientId, clientSecret = secret, clientAssertion = null, clientAssertionType = null,
            audience = "telemetry", requestedScopes = listOf("telemetry:write"), ttlSeconds = 300,
        )
        assertNotNull(result)
        assertEquals(listOf("telemetry:write"), result.scopes, "requested ∩ granted")

        val claims = verifier.verifyReaktorToken(result.accessToken, listOf("telemetry")).getOrNull()
        assertNotNull(claims)
        assertEquals("service", claims.getStringClaim("principal_type"))
    }

    @Test
    fun wrongSecretIsRejected() {
        val (appId, _) = AuthDbFixture.seedUser()
        val clientId = "svc-${UUID.randomUUID()}"
        AuthDbFixture.seedServiceAccount(clientId, appId, authMethod = "SECRET", secretHash = sha256("right"))
        assertNull(
            service.issueClientCredentialsToken(clientId, "wrong", null, null, "manna-mcp", emptyList(), 300)
        )
    }

    @Test
    fun disallowedAudienceIsRejected() {
        val (appId, _) = AuthDbFixture.seedUser()
        val clientId = "svc-${UUID.randomUUID()}"
        AuthDbFixture.seedServiceAccount(
            clientId, appId, authMethod = "SECRET", secretHash = sha256("s"),
            allowedAudiences = """["allowed-aud"]""",
        )
        assertNull(
            service.issueClientCredentialsToken(clientId, "s", null, null, "other-aud", emptyList(), 300)
        )
    }

    @Test
    fun privateKeyJwtAuthenticatesAgainstStoredJwks() {
        val (appId, _) = AuthDbFixture.seedUser()
        val clientId = "svc-${UUID.randomUUID()}"
        val key = ecKey("client-1")
        AuthDbFixture.seedServiceAccount(
            clientId, appId, authMethod = "PRIVATE_KEY_JWT",
            publicJwks = JWKSet(key.toPublicJWK()).toString(), scopes = "a b",
        )

        val result = service.issueClientCredentialsToken(
            clientId = null, clientSecret = null,
            clientAssertion = clientAssertion(clientId, key),
            clientAssertionType = CLIENT_ASSERTION_TYPE_JWT_BEARER,
            audience = "manna-mcp", requestedScopes = listOf("a"), ttlSeconds = 300,
        )
        assertNotNull(result, "a valid client assertion authenticates")
        assertEquals(listOf("a"), result.scopes)
    }

    @Test
    fun privateKeyJwtWithAForgedKeyIsRejected() {
        val (appId, _) = AuthDbFixture.seedUser()
        val clientId = "svc-${UUID.randomUUID()}"
        val storedKey = ecKey("kid-shared")
        AuthDbFixture.seedServiceAccount(
            clientId, appId, authMethod = "PRIVATE_KEY_JWT",
            publicJwks = JWKSet(storedKey.toPublicJWK()).toString(),
        )
        // Same kid, different private key → signature must not verify against the stored public key.
        val forgedKey = ecKey("kid-shared")
        assertNull(
            service.issueClientCredentialsToken(
                clientId = null, clientSecret = null,
                clientAssertion = clientAssertion(clientId, forgedKey),
                clientAssertionType = CLIENT_ASSERTION_TYPE_JWT_BEARER,
                audience = "manna-mcp", requestedScopes = emptyList(), ttlSeconds = 300,
            )
        )
    }
}
