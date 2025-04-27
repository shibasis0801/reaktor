package dev.shibasis.reaktor.auth.jwt

import com.auth0.jwt.interfaces.DecodedJWT
import dev.shibasis.reaktor.auth.framework.fail
import dev.shibasis.reaktor.auth.framework.succeed
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class JwtAdapter(
    accessTokenSecret: String = "AT_SECRET",
    accessTokenExpiryInSeconds: Long = 3600,
    refreshTokenSecret: String = "RT_SECRET",
    refreshTokenExpiryInSeconds: Long = 30 * 24 * 3600,
    private val refreshTokenService: RefreshTokenService
) {
    private val accessTokenUtil = TokenUtil(accessTokenSecret, accessTokenExpiryInSeconds)
    private val refreshTokenUtil = TokenUtil(refreshTokenSecret, refreshTokenExpiryInSeconds)

    fun validateAccessToken(token: String): DecodedJWT? {
        return accessTokenUtil.validateToken(token)
    }

    fun generateAccessToken(
        refreshToken: String,
        userId: UUID,
        appId: UUID
    ): Result<String> {
        refreshTokenService
            .fetchSession(refreshTokenUtil, refreshToken, appId)
            .getOrNull() ?: return fail("Not logged in")

        // todo Roles and permission
        return succeed(accessTokenUtil.generateJwt {
            withClaim("userId", userId.toString())
            withClaim("appId", appId.toString())
        })
    }

    fun generateRefreshToken(
        userId: UUID,
        appId: UUID
    ) = refreshTokenService.newSession(refreshTokenUtil, userId, appId)
}
