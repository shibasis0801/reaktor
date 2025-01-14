package dev.shibasis.reaktor.auth.jwt

import com.auth0.jwt.interfaces.DecodedJWT
import dev.shibasis.reaktor.auth.utils.fail
import dev.shibasis.reaktor.auth.utils.succeed

class JwtAdapter(
    accessTokenSecret: String = "AT_SECRET",
    accessTokenExpiryInSeconds: Long = 3600, // 1 hour
    refreshTokenSecret: String = "RT_SECRET",
    refreshTokenExpiryInSeconds: Long = 30 * 24 * 3600, // 30 days,
    private val refreshTokenService: RefreshTokenService
) {
    private val accessTokenUtil = TokenUtil(accessTokenSecret, accessTokenExpiryInSeconds)
    private val refreshTokenUtil = TokenUtil(refreshTokenSecret, refreshTokenExpiryInSeconds)

    fun validateAccessToken(token: String): DecodedJWT? {
        return accessTokenUtil.validateToken(token)
    }

    fun generateAccessToken(
        refreshToken: String,
        userId: Long,
        appId: Long
    ): Result<String> {
        refreshTokenService
            .fetchSession(refreshTokenUtil, refreshToken, appId)
            .getOrNull() ?: return fail("Not logged in")

        // todo Roles and permission
        return succeed(accessTokenUtil.generateJwt {
            withClaim("userId", userId)
            withClaim("appId", appId)
        })
    }

    fun generateRefreshToken(
        userId: Long,
        appId: Long
    ) = refreshTokenService.newSession(refreshTokenUtil, userId, appId)
}
