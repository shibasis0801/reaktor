package dev.shibasis.reaktor.auth.jwt

import dev.shibasis.reaktor.auth.ExposedAdapter
import dev.shibasis.reaktor.auth.Sessions
import dev.shibasis.reaktor.auth.toKotlinDateTime
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import java.util.UUID

class RefreshTokenStorage(
    database: Database,
    private val maxSessions: Int = 5
): ExposedAdapter(database) {

    fun fetchSession(
        refreshTokenId: String
    ) = sql {

        // should be cron
        Sessions.deleteWhere { expiresAt less System.currentTimeMillis() }

        val session = Sessions
            .selectAll()
            .where { Sessions.refreshToken eq refreshTokenId }
            .map { Sessions.toDto(it) }
            .firstOrNull()

        if (session == null) {
            failure("No session found, please login")
        } else {
            success(session)
        }
    }

    fun newSession(
        tokenService: TokenService,
        userId: Long,
        appId: Long
    ) = sql {
        val sessionCount = Sessions.selectAll().where {
            (Sessions.userId eq userId) and
                    (Sessions.appId eq appId)
        }.count()

        if (sessionCount > maxSessions) {
            return@sql failure("Max session limit reached $maxSessions")
        }

        val refreshTokenId = UUID.randomUUID().toString()
        val authToken = tokenService.generateToken(
            "id" to refreshTokenId,
            "userId" to userId.toString(),
            "appId" to appId.toString()
        )

        Sessions.insert {
            it[Sessions.refreshToken] = refreshTokenId
            it[Sessions.userId] = userId
            it[Sessions.appId] = appId
            it[Sessions.expiresAt] = authToken.expiresAt.toKotlinDateTime()
        }

        success(authToken)
    }

    fun validateSession(
        tokenService: TokenService,
        token: String
    ) = sql {
        val decodedJWT = tokenService.validateToken(token) ?: return@sql failure("Invalid Token")

        val refreshTokenId = decodedJWT.getClaim("id").asString()
        val userId = decodedJWT.getClaim("userId").asString()
        val appId = decodedJWT.getClaim("appId").asString()

        val count = Sessions.selectAll().where {
            (Sessions.refreshToken eq refreshTokenId) and
                    (Sessions.userId eq userId.toLong()) and
                    (Sessions.appId eq appId.toLong()) and
                    (Sessions.expiresAt greater System.currentTimeMillis())
        }.count()

        if (count == 1L) success(decodedJWT) else failure("Invalid Token")
    }
}