package dev.shibasis.reaktor.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.shibasis.reaktor.auth.jwt.JwtMinter
import dev.shibasis.reaktor.auth.jwt.JwtVerifier
import dev.shibasis.reaktor.auth.jwt.SigningKeys
import dev.shibasis.reaktor.auth.jwt.toAuthContext
import dev.shibasis.reaktor.auth.kernel.AuthDefaults
import dev.shibasis.reaktor.auth.kernel.PermissionRef
import dev.shibasis.reaktor.auth.kernel.RoleRef
import dev.shibasis.reaktor.auth.kernel.Scope
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure (no-DB) crypto contract for Reaktor-issued ES256 tokens: mint → verify round-trips, audience
 * pinning, the `act` delegation chain, expiry, signature tampering, and RFC 8725 algorithm pinning.
 */
class JwtRoundTripTest {
    private val signingKeys = SigningKeys("", "")
    private val minter = JwtMinter(signingKeys)
    private val verifier = JwtVerifier(emptyList(), signingKeys)

    @Test
    fun accessTokenRoundTripsWithSessionAndScopes() {
        val token = minter.mintAccessToken(
            principalId = "user-1",
            appId = "app-1",
            scopes = listOf("a", "b"),
            roles = listOf("owner"),
            permissions = listOf("project:read"),
            sessionId = "ses-9",
            identityId = "identity-1",
            tenantId = "tenant-1",
            contextId = "context-1",
        )
        val claims = verifier.verifyReaktorToken(token, listOf("app-1")).getOrNull()
        assertNotNull(claims)
        assertEquals("user-1", claims.subject)
        assertEquals("ses-9", claims.getStringClaim("sid"))
        assertEquals("user", claims.getStringClaim("principal_type"))
        assertEquals(listOf("a", "b"), claims.getStringListClaim("scp"))
        assertEquals(listOf("owner"), claims.getStringListClaim("roles"))
        assertEquals(listOf("project:read"), claims.getStringListClaim("permissions"))

        val context = claims.toAuthContext()
        assertEquals("identity-1", context.identityId)
        assertEquals("tenant-1", context.tenantId)
        assertEquals("context-1", context.contextId)
        assertTrue(Scope("a") in context.scopes)
        assertTrue(RoleRef(name = "owner") in context.roles)
        assertTrue(PermissionRef(name = "project:read") in context.permissions)
    }

    @Test
    fun wrongAudienceIsRejectedButSignatureStillValid() {
        val token = minter.mintAccessToken("user-1", "app-1", listOf("a"))
        assertNull(verifier.verifyReaktorToken(token, listOf("different-app")).getOrNull(), "audience pinned")
        assertTrue(verifier.verifyReaktorSignature(token).isSuccess, "signature-only check ignores audience")
    }

    @Test
    fun serviceTokenCarriesServicePrincipal() {
        val token = minter.mintServiceToken("svc:1", "telemetry", listOf("write"), ttlSeconds = 300)
        val claims = verifier.verifyReaktorToken(token, listOf("telemetry")).getOrNull()
        assertNotNull(claims)
        assertEquals("service", claims.getStringClaim("principal_type"))
        assertEquals("svc:1", claims.subject)
    }

    @Test
    fun delegatedTokenCarriesActChain() {
        val token = minter.mintDelegatedToken(
            subject = "user-7", actorSubject = "svc:42", audience = "manna-mcp", scopes = listOf("x"),
        )
        val claims = verifier.verifyReaktorSignature(token).getOrNull()
        assertNotNull(claims)
        assertEquals("user-7", claims.subject)
        assertEquals("delegation", claims.getStringClaim("credential_type"))
        @Suppress("UNCHECKED_CAST")
        val act = claims.getClaim("act") as Map<String, Any?>
        assertEquals("svc:42", act["sub"])
    }

    @Test
    fun expiredTokenIsRejected() {
        // Expire well beyond Nimbus's default 60s clock-skew tolerance.
        val token = minter.mintAccessToken("user-1", "app-1", listOf("a"), expirationMs = -120_000L)
        assertNull(verifier.verifyReaktorToken(token, listOf("app-1")).getOrNull())
        assertFalse(verifier.verifyReaktorSignature(token).isSuccess)
    }

    @Test
    fun tamperedSignatureIsRejected() {
        val token = minter.mintAccessToken("user-1", "app-1", listOf("a"))
        val tampered = token.dropLast(3) + "AAA"
        assertFalse(verifier.verifyReaktorSignature(tampered).isSuccess)
    }

    @Test
    fun hs256ForgeryIsRejectedByAlgorithmPinning() {
        // A token with the right issuer/audience but signed HS256 must NOT verify against the ES256 JWKS.
        val secret = ByteArray(32) { 7 }
        val claims = JWTClaimsSet.Builder()
            .subject("user-1").issuer(AuthDefaults.ISSUER).audience("app-1")
            .expirationTime(Date(System.currentTimeMillis() + 60_000))
            .build()
        val forged = SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims)
            .apply { sign(MACSigner(secret)) }
            .serialize()
        assertFalse(verifier.verifyReaktorSignature(forged).isSuccess, "RFC 8725 alg pinning rejects HS256")
    }
}
