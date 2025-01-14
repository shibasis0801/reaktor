package dev.shibasis.reaktor.auth.jwt

import com.auth0.jwt.JWT
import dev.shibasis.reaktor.auth.Session
import dev.shibasis.reaktor.auth.now
import dev.shibasis.reaktor.auth.repositories.SessionRepository
import dev.shibasis.reaktor.auth.toKotlinDateTime
import dev.shibasis.reaktor.auth.utils.fail
import dev.shibasis.reaktor.auth.utils.succeed
import kotlinx.datetime.LocalDateTime
import java.util.UUID

class RefreshTokenService(
    private val sessionRepository: SessionRepository,
    private val maxSessions: Int = 5
) {
    fun newSession(
        tokenUtil: TokenUtil,
        userId: Long,
        appId: Long
    ): Result<String> {
        val sessionCount = sessionRepository.getSessionCount(userId, appId).getOrNull() ?: return fail("Session DB Error")
        if (sessionCount > maxSessions) {
            return fail("Max session limit reached $maxSessions")
        }

        val expiresAt = tokenUtil.calculateExpiry()
        val refreshTokenId = sessionRepository.createSession(userId, appId, expiresAt.toKotlinDateTime())
        val jwt = tokenUtil.generateJwt(expiresAt) {
            withClaim("id", refreshTokenId.toString())
            withClaim("appId", appId)
        }

        return succeed(jwt)
    }

    fun fetchSession(
        refreshTokenUtil: TokenUtil,
        refreshToken: String,
        appId: Long
    ): Result<Session> {
        val decodedJWT = refreshTokenUtil.validateToken(refreshToken) ?: return fail("Invalid Token")
        val id = UUID.fromString(decodedJWT.getClaim("id").asString())

        if (appId != decodedJWT.getClaim("appId").asLong()) return fail("Invalid App ID")

        val session = sessionRepository.getSession(id).getOrNull() ?: return fail("No session found")

        if (session.expiresAt < LocalDateTime.now()) {
            return succeed(session)
        } else {
            sessionRepository.deleteSession(id)
            return fail("Expired Token")
        }
    }
}