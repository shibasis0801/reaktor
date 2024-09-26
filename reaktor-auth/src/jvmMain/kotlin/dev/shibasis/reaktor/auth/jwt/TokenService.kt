package dev.shibasis.reaktor.auth.jwt

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import dev.shibasis.reaktor.core.framework.Adapter
import java.util.Date


data class AuthToken(
    val claims: Map<String, String>,
    val issuedAt: Date,
    val expiresAt: Date,
)

class TokenService(
    secret: String = "SECRET",
    private val expiresInSeconds: Long
): Adapter<Unit>(Unit) {
    private val algorithm = Algorithm.HMAC256(secret)
    private val verifier = JWT.require(algorithm).build()

    fun generateToken(vararg claims: Pair<String, String>): AuthToken {
        val now = Date()
        val expiresAt = Date(now.time + expiresInSeconds * 1000)
        return AuthToken(claims.toMap(), now, expiresAt)
    }

    fun generateJWT(token: AuthToken): String {
        return JWT.create()
            .apply {
                token.claims.forEach { (key, value) -> withClaim(key, value) }
            }
            .withIssuedAt(token.issuedAt)
            .withExpiresAt(token.expiresAt)
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