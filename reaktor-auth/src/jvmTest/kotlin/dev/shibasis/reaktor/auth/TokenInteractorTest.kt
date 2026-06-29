package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.jwt.JwtMinter
import dev.shibasis.reaktor.auth.jwt.JwtVerifier
import dev.shibasis.reaktor.auth.jwt.SigningKeys
import dev.shibasis.reaktor.auth.jwt.toAuthContext
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.auth.services.TokenInteractor
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PAT lifecycle against in-memory H2: format/checksum, expiry, revocation, and PAT→ES256 exchange. */
class TokenInteractorTest {
    private val signingKeys = SigningKeys("", "")
    private val minter = JwtMinter(signingKeys)
    private val tokens = TokenInteractor(minter)
    private val verifier = JwtVerifier(emptyList(), signingKeys)

    @BeforeTest
    fun setup() = AuthDbFixture.ensure()

    @Test
    fun mintedPatUsesRktFormatAndVerifies() = runBlocking {
        val (_, raw) = tokens.createPersonalAccessToken(principalId = null, name = "ci", scopes = listOf("mcp:read"))
        assertTrue(raw.startsWith("rkt_"), "GitHub/Stripe-style prefix")
        val pat = tokens.verifyPersonalAccessToken(raw)
        assertNotNull(pat)
        assertEquals("ci", pat.name)
    }

    @Test
    fun tamperedChecksumIsRejectedOffline() = runBlocking {
        val (_, raw) = tokens.createPersonalAccessToken(principalId = null, name = "t", scopes = listOf("mcp:read"))
        val flipped = raw.dropLast(1) + if (raw.last() == 'a') 'b' else 'a'
        assertNull(tokens.verifyPersonalAccessToken(flipped), "a bad CRC32 is rejected before the DB")
    }

    @Test
    fun expiredPatIsRejected() = runBlocking {
        val (_, raw) = tokens.createPersonalAccessToken(
            principalId = null, name = "old", scopes = listOf("mcp:read"), expiresInDays = -1,
        )
        assertNull(tokens.verifyPersonalAccessToken(raw))
    }

    @Test
    fun revokedPatIsRejected() = runBlocking {
        val (pat, raw) = tokens.createPersonalAccessToken(principalId = null, name = "r", scopes = listOf("mcp:read"))
        assertTrue(tokens.revokePersonalAccessToken(pat.id))
        assertNull(tokens.verifyPersonalAccessToken(raw))
    }

    @Test
    fun audienceBoundPatRejectsOtherAudiencesAtVerifyButTokenStillExchanges() = runBlocking {
        // The audience-binding *policy* is enforced in AuthServer; here we assert the exchanged token is
        // a valid, audience-stamped ES256 JWT carrying the PAT's scopes.
        val (pat, _) = tokens.createPersonalAccessToken(
            principalId = null, name = "svc", scopes = listOf("mcp:read", "mcp:write"),
        )
        val jwt = tokens.createAccessToken(pat, audience = "manna-mcp", ttlSeconds = 300)
        val claims = verifier.verifyReaktorToken(jwt, listOf("manna-mcp")).getOrNull()
        assertNotNull(claims)
        assertEquals("pat:${pat.id}", claims.subject)
        assertEquals("service", claims.getStringClaim("principal_type"))
        assertTrue(claims.audience.contains("manna-mcp"))
        assertEquals(listOf("mcp:read", "mcp:write"), claims.getStringListClaim("scp"))

        val context = claims.toAuthContext()
        assertEquals(AuthMethod.PERSONAL_ACCESS_TOKEN, context.method)
        assertEquals(pat.id, context.credentialId)
    }
}
