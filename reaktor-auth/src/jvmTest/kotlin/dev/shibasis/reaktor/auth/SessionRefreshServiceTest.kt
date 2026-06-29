package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.services.SessionRefreshService
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for the flow-A session lifecycle against in-memory H2 (no production DB touched).
 * The crown jewel is RFC 9700 refresh-token reuse-detection: replaying a rotated token must revoke the
 * whole family.
 */
class SessionRefreshServiceTest {
    private val sessions = SessionRefreshService()

    @BeforeTest
    fun setup() = AuthDbFixture.ensure()

    @Test
    fun loginIssuesRotatingRefreshAndRotatesForward() {
        val (appId, principalId) = AuthDbFixture.seedUser()
        val created = sessions.createSession(principalId, appId)
        assertTrue(created.rawRefreshToken.startsWith("rkr_"), "refresh tokens are prefixed")

        val rotated = sessions.rotate(created.rawRefreshToken)
        assertNotNull(rotated, "a fresh token rotates")
        assertNotEquals(created.rawRefreshToken, rotated.rawRefreshToken, "rotation issues a NEW token")
        assertEquals(created.sessionId, rotated.session.id, "rotation stays within the session")

        // The successor rotates again (the chain continues).
        assertNotNull(sessions.rotate(rotated.rawRefreshToken))
    }

    @Test
    fun replayingARotatedTokenRevokesTheWholeFamily() {
        val (appId, principalId) = AuthDbFixture.seedUser()
        val created = sessions.createSession(principalId, appId)

        val successor = sessions.rotate(created.rawRefreshToken)
        assertNotNull(successor)

        // Replay the already-used ORIGINAL token → theft signal → family revoked.
        assertNull(sessions.rotate(created.rawRefreshToken), "a replayed token is rejected")
        // The legitimately-issued successor is now dead too (the whole family was revoked).
        assertNull(sessions.rotate(successor.rawRefreshToken), "reuse revokes the entire family")
    }

    @Test
    fun logoutRevokesTheRefreshToken() {
        val (appId, principalId) = AuthDbFixture.seedUser()
        val created = sessions.createSession(principalId, appId)
        assertTrue(sessions.revokeByRefreshToken(created.rawRefreshToken))
        assertNull(sessions.rotate(created.rawRefreshToken))
    }

    @Test
    fun logoutAllRevokesEverySessionOfThePrincipal() {
        val (appId, principalId) = AuthDbFixture.seedUser()
        val s1 = sessions.createSession(principalId, appId)
        val s2 = sessions.createSession(principalId, appId)

        assertEquals(2, sessions.revokeAllForPrincipal(principalId), "both sessions revoked")
        assertNull(sessions.rotate(s1.rawRefreshToken))
        assertNull(sessions.rotate(s2.rawRefreshToken))
    }

    @Test
    fun unknownRefreshTokenIsRejected() {
        AuthDbFixture.seedUser() // ensure schema/seed path is exercised
        assertNull(sessions.rotate("rkr_this-token-was-never-issued"))
    }
}
