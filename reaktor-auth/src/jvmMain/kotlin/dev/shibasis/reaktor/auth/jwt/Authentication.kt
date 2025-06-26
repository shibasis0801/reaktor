package dev.shibasis.reaktor.auth.jwt

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.RemoteJWKSet
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier
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
import org.springframework.stereotype.Component
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class AuthenticatedUser(
    val subject: String,
    val provider: UserProvider,
    val email: String? = null
) {
    companion object {
        operator fun invoke(jwtClaims: JWTClaimsSet, userProvider: UserProvider) = AuthenticatedUser(
            jwtClaims.subject,
            userProvider,
            runCatching { jwtClaims.getStringClaim("email") }.getOrNull()
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

                jwtProcessor.jwsTypeVerifier = DefaultJOSEObjectTypeVerifier(JOSEObjectType("JWT"))
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
