package dev.shibasis.reaktor.auth.jwt

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.RemoteJWKSet
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import dev.shibasis.reaktor.auth.UserProvider
import dev.shibasis.reaktor.auth.api.LoginRequest
import dev.shibasis.reaktor.core.utils.fail
import kotlinx.serialization.Serializable
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.SignedJWT
import java.util.Date

@Serializable
data class AuthenticatedUser(
    val subject: String,
    val provider: UserProvider,
    val email: String? = null,
    val scopes: List<String> = emptyList()
) {
    companion object {
        operator fun invoke(jwtClaims: JWTClaimsSet, userProvider: UserProvider) = AuthenticatedUser(
            jwtClaims.subject,
            userProvider,
            runCatching { jwtClaims.getStringClaim("email") }.getOrNull(),
            runCatching { jwtClaims.getStringListClaim("scopes") }.getOrNull() ?: emptyList()
        )
    }
}

sealed class UserAuthenticationProvider(open val audiences: List<String>) {
    sealed class External(
        val userProvider: UserProvider,
        val issuer: String,
        val jwksUrl: String,
        override val audiences: List<String>
    ): UserAuthenticationProvider(audiences)

    data class App(
        override val audiences: List<String>
    ): UserAuthenticationProvider(audiences)


    data class Google(
        override val audiences: List<String>
    ): External(
        userProvider = UserProvider.GOOGLE,
        issuer = "https://accounts.google.com",
        jwksUrl = "https://www.googleapis.com/oauth2/v3/certs",
        audiences
    )

    data class Apple(
        override val audiences: List<String>
    ): External(
        userProvider = UserProvider.APPLE,
        issuer = "https://appleid.apple.com",
        jwksUrl = "https://appleid.apple.com/auth/keys",
        audiences
    )
}


@Component
open class JwtVerifier(
    val userAuthenticationProviders: List<UserAuthenticationProvider>
) {

    private val cachedProcessors = ConcurrentHashMap<UserAuthenticationProvider, ConfigurableJWTProcessor<SecurityContext>>()
    fun getProcessor(userAuthenticationProvider: UserAuthenticationProvider): ConfigurableJWTProcessor<SecurityContext> {
        var jwtProcessor = cachedProcessors[userAuthenticationProvider]
        if (jwtProcessor != null) return jwtProcessor

        jwtProcessor = DefaultJWTProcessor()

        when (userAuthenticationProvider) {
            is UserAuthenticationProvider.App -> {
                // todo implement jwks, etc
                // todo implement RequiresAuth as a pure kotlin function, but have DI to support spring/workers, then create the annotation.
            }
            is UserAuthenticationProvider.External -> {
                jwtProcessor.jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, RemoteJWKSet(URL(userAuthenticationProvider.jwksUrl)))
                jwtProcessor.jwtClaimsSetVerifier = DefaultJWTClaimsVerifier(
                    userAuthenticationProvider.audiences.toSet(),
                    null,
                    setOf("sub", "email", "iss", "aud"),
                    setOf()
                )

//                jwtProcessor.jwsTypeVerifier = DefaultJOSEObjectTypeVerifier(JOSEObjectType("JWT"))
            }
        }

        cachedProcessors[userAuthenticationProvider] = jwtProcessor
        return jwtProcessor
    }


    suspend operator fun invoke(loginRequest: LoginRequest): Result<AuthenticatedUser> {
        val userProvider = loginRequest.provider
        val userAuthenticationProvider = userAuthenticationProviders
            .find { it is UserAuthenticationProvider.External && it.userProvider == loginRequest.provider }
            ?: return fail("Misconfiguration, need provider for $userProvider")

        val processor = getProcessor(userAuthenticationProvider)
        return runCatching {
            processor.process(loginRequest.idToken, null)
        }.map {
            AuthenticatedUser(it, userProvider)
        }
    }
}

@Component
class JwtMinter(
    @Value("\${reaktor.jwt.secret:dumb_secret_for_testing_1234567890}") 
    private val rawSecret: String
) {
    private val secret = rawSecret.toByteArray().let {
        if (it.size < 32) it + ByteArray(32 - it.size) { 0 } else it
    }
    private val signer: JWSSigner = MACSigner(secret)

    fun mintAccessToken(
        userId: String,
        appId: String,
        scopes: List<String>,
        expirationMs: Long = 1000 * 60 * 60 // Default 1 hour
    ): String {
        val now = Date()
        val claimsSet = JWTClaimsSet.Builder()
            .subject(userId)
            .issuer("https://reaktor.build/")
            .claim("appId", appId)
            .claim("scopes", scopes)
            .issueTime(now)
            .expirationTime(Date(now.time + expirationMs))
            .build()

        val header = JWSHeader(JWSAlgorithm.HS256)
        val signedJWT = SignedJWT(header, claimsSet)
        signedJWT.sign(signer)

        return signedJWT.serialize()
    }
}