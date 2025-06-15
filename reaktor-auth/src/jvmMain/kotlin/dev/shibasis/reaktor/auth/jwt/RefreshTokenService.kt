package dev.shibasis.reaktor.auth.jwt

import dev.shibasis.reaktor.auth.SessionEntity
import dev.shibasis.reaktor.auth.db.SessionRepository
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinInstant
import kotlinx.datetime.toLocalDateTime
import org.springframework.stereotype.Component
import java.time.ZoneId
import java.util.Date
import java.util.UUID


fun Date.toKotlinDateTime(): LocalDateTime = toInstant().toKotlinInstant().toLocalDateTime(TimeZone.currentSystemDefault())
fun LocalDateTime.toJavaDate(): Date = toJavaLocalDateTime().atZone(ZoneId.systemDefault()).toInstant().let { Date.from(it) }

inline fun <T, R : Any> Result<T>.chain(transform: (T) -> Result<R>): Result<R> =
    map { transform(it).getOrThrow() }

@Component
class RefreshTokenService(
    private val sessionRepository: SessionRepository,
    private val maxSessions: Int = 5
) {
    fun newSession(
        tokenUtil: TokenUtil,
        userId: UUID,
        contextId: UUID
    ): Result<String> {
        return fail("Needs fixing")
//        return sessionRepository.findByUserIdAndContext(userId, contextId)
//            .chain { sessions ->
//                if (sessions.size > maxSessions) {
//                    return fail("Max session limit reached $maxSessions")
//                }
//
//                val expiresAt = tokenUtil.calculateExpiry()
//                sessionRepository.create {
//                    it.userId = Users.entityId(userId)
//                    it.contextId = Contexts.entityId(contextId)
//                    it.expiresAt = expiresAt.toKotlinDateTime()
//                }
//            }.map { session ->
//                val jwt = tokenUtil.generateJwt(session.expiresAt.toJavaDate()) {
//                    withClaim("id", session.toString())
//                    withClaim("contextId", session.contextId.string())
//                    withClaim("userId", session.userId.string())
//                }
//
//                return succeed(jwt)
//            }
    }

    fun fetchSession(
        refreshTokenUtil: TokenUtil,
        refreshToken: String,
        appId: UUID
    ): Result<SessionEntity> {
        return fail("Needs fixing")


//        val decodedJWT = refreshTokenUtil.validateToken(refreshToken) ?: return fail("Invalid Token")
//        val id = UUID.fromString(decodedJWT.getClaim("id").toString())
//
//        if (appId.toString() != decodedJWT.getClaim("appId").toString()) return fail("Invalid App ID")
//
//        val session = sessionRepository.find(id).getOrNull() ?: return fail("No session found")
//
//        if (session.expiresAt < LocalDateTime.now()) {
//            return succeed(session.toDto())
//        } else {
//            sessionRepository.delete(id)
//            return fail("Expired Token")
//        }
//
    }
}
