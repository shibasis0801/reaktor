package dev.shibasis.reaktor.auth.jwt

import com.auth0.jwt.interfaces.DecodedJWT

class JwtAdapter(
    accessTokenSecret: String = "AT_SECRET",
    accessTokenExpiryInSeconds: Long = 3600, // 1 hour
    refreshTokenSecret: String = "RT_SECRET",
    refreshTokenExpiryInSeconds: Long = 30 * 24 * 3600, // 30 days,
    private val refreshTokenStorage: RefreshTokenStorage,
) {
    private val accessTokenService = TokenService(accessTokenSecret, accessTokenExpiryInSeconds)
    private val refreshTokenService = TokenService(refreshTokenSecret, refreshTokenExpiryInSeconds)

    fun generateAccessToken(
        refreshTokenId: String,
        userId: Long,
        appId: Long
    ): Result<String> {
        return refreshTokenStorage.fetchSession(refreshTokenId)
            .map {
                val at = accessTokenService.generateToken(
                    "userId" to userId.toString(),
                    "appId" to appId.toString()
                )

                accessTokenService.generateJWT(at)
            }
    }

    fun validateAccessToken(token: String): DecodedJWT? {
        return accessTokenService.validateToken(token)
    }

    fun generateRefreshToken(
        userId: Long,
        appId: Long
    ): Result<String> {
        return refreshTokenStorage
            .newSession(refreshTokenService, userId, appId)
            .map { refreshTokenService.generateJWT(it) }
    }

    fun validateRefreshToken(token: String): Result<DecodedJWT> {
        return refreshTokenStorage.validateSession(refreshTokenService, token)
    }
}
