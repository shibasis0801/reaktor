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
import dev.shibasis.reaktor.core.framework.Dispatch
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

sealed class UserProviderAuthentication(open val audiences: List<String>) {
    data class External(
        val userProvider: UserProvider,
        val issuer: String,
        val jwksUrl: String,
        override val audiences: List<String>
    ): UserProviderAuthentication(audiences)

    data class App(
        override val audiences: List<String>
    ): UserProviderAuthentication(audiences)
}


@Component
open class JwtVerifier(
    val userProviderAuthentications: List<UserProviderAuthentication>
) {

    private val cachedProcessors = ConcurrentHashMap<UserProviderAuthentication, ConfigurableJWTProcessor<SecurityContext>>()
    fun getProcessor(userProviderAuthentication: UserProviderAuthentication): ConfigurableJWTProcessor<SecurityContext> {
        var jwtProcessor = cachedProcessors[userProviderAuthentication]
        if (jwtProcessor != null) return jwtProcessor

        jwtProcessor = DefaultJWTProcessor()

        when (userProviderAuthentication) {
            is UserProviderAuthentication.App -> {
                // todo implement jwks, etc
                // todo implement RequiresAuth as a pure kotlin function, but have DI to support spring/workers, then create the annotation.
            }
            is UserProviderAuthentication.External -> {
                jwtProcessor.jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, RemoteJWKSet(URL(userProviderAuthentication.jwksUrl)))
                jwtProcessor.jwtClaimsSetVerifier = DefaultJWTClaimsVerifier(
                    JWTClaimsSet.Builder()
                        .issuer(userProviderAuthentication.issuer)
                        .audience(userProviderAuthentication.audiences)
                        .build(),
                    setOf("sub", "email", "name", "exp", "iat", "iss", "aud")
                )
                jwtProcessor.jwsTypeVerifier = DefaultJOSEObjectTypeVerifier(JOSEObjectType("JWT"))
            }
        }

        cachedProcessors[userProviderAuthentication] = jwtProcessor
        return jwtProcessor
    }



    suspend operator fun invoke(loginRequest: LoginRequest): Result<AuthenticatedUser> {
        val userProvider = loginRequest.provider
        val userProviderAuthentication = userProviderAuthentications
            .find { it is UserProviderAuthentication.External && it.userProvider == loginRequest.provider }
            ?: return fail("Misconfiguration, need provider for $userProvider")

        val processor = getProcessor(userProviderAuthentication)
        return runCatching {
            Dispatch.IO.execute {
                processor.process(loginRequest.idToken, null)
            }
        }.map {
            AuthenticatedUser(it, userProvider)
        }
    }

    suspend operator fun invoke(token: String) {

    }
}
