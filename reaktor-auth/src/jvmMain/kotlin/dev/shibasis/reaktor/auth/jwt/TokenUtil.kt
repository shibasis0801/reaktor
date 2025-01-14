package dev.shibasis.reaktor.auth.jwt

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTCreator
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import java.util.Date

class TokenUtil(
    secret: String = "SECRET",
    private val expiresInSeconds: Long
) {
    private val algorithm = Algorithm.HMAC256(secret)
    private val verifier = JWT.require(algorithm).build()

    fun calculateExpiry() = Date(Date().time + expiresInSeconds * 1000)

    fun generateJwt(expiresAt: Date = calculateExpiry(), claimBuilder: JWTCreator.Builder.() -> Unit): String {
        return JWT.create()
            .apply { claimBuilder() }
            .withIssuedAt(Date())
            .withExpiresAt(expiresAt)
            .sign(algorithm)
    }

    fun validateToken(token: String): DecodedJWT? {
        return try {
            verifier.verify(token)
        } catch (e: JWTVerificationException) {
            null
        }
    }
}