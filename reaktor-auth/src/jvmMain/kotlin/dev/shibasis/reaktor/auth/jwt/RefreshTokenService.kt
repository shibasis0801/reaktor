package dev.shibasis.reaktor.auth.jwt

import dev.shibasis.reaktor.auth.Session
import dev.shibasis.reaktor.auth.db.Contexts
import dev.shibasis.reaktor.auth.db.Users
import dev.shibasis.reaktor.auth.db.rbac.SessionRepository
import dev.shibasis.reaktor.auth.framework.fail
import dev.shibasis.reaktor.auth.framework.now
import dev.shibasis.reaktor.auth.framework.succeed
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinInstant
import kotlinx.datetime.toLocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.UUID
fun Date.toKotlinDateTime(): LocalDateTime = toInstant().toKotlinInstant().toLocalDateTime(TimeZone.currentSystemDefault())
fun LocalDateTime.toJavaDate(): Date = toJavaLocalDateTime().atZone(ZoneId.systemDefault()).toInstant().let { Date.from(it) }

inline fun <T, R : Any> Result<T>.chain(transform: (T) -> Result<R>): Result<R> =
    map { transform(it).getOrThrow() }

class RefreshTokenService(
    private val sessionRepository: SessionRepository,
    private val maxSessions: Int = 5
) {
    fun newSession(
        tokenUtil: TokenUtil,
        userId: Long,
        contextId: Long
    ): Result<String> {
        return sessionRepository.findByUserIdAndContext(userId, contextId)
            .chain { sessions ->
                if (sessions.size > maxSessions) {
                    return fail("Max session limit reached $maxSessions")
                }

                val expiresAt = tokenUtil.calculateExpiry()
                sessionRepository.create {
                    it.userId = Users.entityId(userId)
                    it.contextId = Contexts.entityId(contextId)
                    it.expiresAt = expiresAt.toKotlinDateTime()
                }
            }.map { session ->
                val jwt = tokenUtil.generateJwt(session.expiresAt.toJavaDate()) {
                    withClaim("id", session.toString())
                    withClaim("contextId", session.contextId.value)
                    withClaim("userId", session.userId.value)
                }

                return succeed(jwt)
            }
    }

    fun fetchSession(
        refreshTokenUtil: TokenUtil,
        refreshToken: String,
        appId: Long
    ): Result<Session> {
        val decodedJWT = refreshTokenUtil.validateToken(refreshToken) ?: return fail("Invalid Token")
        val id = UUID.fromString(decodedJWT.getClaim("id").asString())

        if (appId != decodedJWT.getClaim("appId").asLong()) return fail("Invalid App ID")

        val session = sessionRepository.find(id).getOrNull() ?: return fail("No session found")

        if (session.expiresAt < LocalDateTime.now()) {
            return succeed(session.toDto())
        } else {
            sessionRepository.delete(id)
            return fail("Expired Token")
        }
    }
}