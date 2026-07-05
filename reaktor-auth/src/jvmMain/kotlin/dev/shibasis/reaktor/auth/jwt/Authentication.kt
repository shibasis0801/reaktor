package dev.shibasis.reaktor.auth.jwt

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import dev.shibasis.reaktor.auth.AuthCredentialType
import dev.shibasis.reaktor.auth.UserProvider
import dev.shibasis.reaktor.auth.api.LoginRequest
import dev.shibasis.reaktor.auth.kernel.AuthDefaults
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.core.utils.fail
import kotlinx.serialization.Serializable
import java.net.URI
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class AuthenticatedUser(
    val subject: String,
    val provider: UserProvider,
    val issuer: String,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val scopes: List<String> = emptyList()
) {
    companion object {
        operator fun invoke(jwtClaims: JWTClaimsSet, userProvider: UserProvider) = AuthenticatedUser(
            subject = jwtClaims.subject,
            provider = userProvider,
            issuer = jwtClaims.issuer,
            email = runCatching { jwtClaims.getStringClaim("email") }.getOrNull(),
            emailVerified = runCatching { jwtClaims.getBooleanClaim("email_verified") }.getOrNull() == true,
            scopes = runCatching { jwtClaims.getStringListClaim("scopes") }.getOrNull() ?: emptyList()
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


open class JwtVerifier(
    val userAuthenticationProviders: List<UserAuthenticationProvider>,
    val signingKeys: SigningKeys
) {

    private val cachedProcessors = ConcurrentHashMap<UserAuthenticationProvider, ConfigurableJWTProcessor<SecurityContext>>()

    // Reaktor access tokens are stamped `typ=at+jwt` (RFC 9068). Nimbus's DefaultJWTProcessor otherwise
    // allows only `JWT`/absent and rejects EVERY Reaktor token at verification. Accept at+jwt (+ JWT + absent).
    private val reaktorTokenTypeVerifier = DefaultJOSEObjectTypeVerifier<SecurityContext>(
        JOSEObjectType("at+jwt"), JOSEObjectType.JWT, null
    )
    fun getProcessor(userAuthenticationProvider: UserAuthenticationProvider): ConfigurableJWTProcessor<SecurityContext> {
        var jwtProcessor = cachedProcessors[userAuthenticationProvider]
        if (jwtProcessor != null) return jwtProcessor

        jwtProcessor = DefaultJWTProcessor()

        when (userAuthenticationProvider) {
            is UserAuthenticationProvider.App -> {
                // Reaktor-issued ES256 access tokens: verify locally against our published JWKS
                // (no shared secret, no DB hit). Pins the algorithm, the issuer, and the audience(s).
                jwtProcessor.jwsKeySelector = JWSVerificationKeySelector(
                    signingKeys.algorithm,
                    ImmutableJWKSet(signingKeys.publicJwkSet())
                )
                jwtProcessor.jwsTypeVerifier = reaktorTokenTypeVerifier
                jwtProcessor.jwtClaimsSetVerifier = DefaultJWTClaimsVerifier(
                    userAuthenticationProvider.audiences.toSet(),
                    JWTClaimsSet.Builder().issuer(AuthDefaults.ISSUER).build(),
                    setOf("sub", "iss", "aud", "exp"),
                    setOf()
                )
            }
            is UserAuthenticationProvider.External -> {
                jwtProcessor.jwsKeySelector = JWSVerificationKeySelector(
                    JWSAlgorithm.RS256,
                    JWKSourceBuilder.create<SecurityContext>(URI(userAuthenticationProvider.jwksUrl).toURL()).build()
                )
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


    open suspend operator fun invoke(loginRequest: LoginRequest): Result<AuthenticatedUser> {
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

    /**
     * Verify a Reaktor-issued ES256 access token locally against the published JWKS.
     * Used by the server security filter (and mirrored by the edge WorkerJwtVerifier).
     */
    fun verifyReaktorToken(token: String, audiences: List<String>): Result<JWTClaimsSet> {
        val processor = getProcessor(UserAuthenticationProvider.App(audiences))
        return runCatching { processor.process(token, null) }
    }

    // Signature + issuer + expiry only (NO audience pinning). The security filter uses this to
    // AUTHENTICATE; audience/scope/resource AUTHORIZATION happens per-route via LocalAuthorizer.
    private val reaktorSignatureProcessor: ConfigurableJWTProcessor<SecurityContext> by lazy {
        DefaultJWTProcessor<SecurityContext>().apply {
            jwsKeySelector = JWSVerificationKeySelector(
                signingKeys.algorithm,
                ImmutableJWKSet(signingKeys.publicJwkSet())
            )
            jwsTypeVerifier = reaktorTokenTypeVerifier
            jwtClaimsSetVerifier = DefaultJWTClaimsVerifier(
                null,
                JWTClaimsSet.Builder().issuer(AuthDefaults.ISSUER).build(),
                setOf("sub", "iss", "exp"),
                emptySet()
            )
        }
    }

    fun verifyReaktorSignature(token: String): Result<JWTClaimsSet> =
        runCatching { reaktorSignatureProcessor.process(token, null) }
}

class JwtMinter(
    private val signingKeys: SigningKeys
) {
    private fun mintReaktorToken(
        subject: String,
        audience: String,
        scopes: List<String>,
        roles: List<String> = emptyList(),
        permissions: List<String> = emptyList(),
        ttlSeconds: Int = AuthDefaults.ACCESS_TOKEN_TTL_SECONDS,
        expirationMs: Long = ttlSeconds * 1000L,
        principalKind: PrincipalKind,
        credentialType: String,
        configure: JWTClaimsSet.Builder.() -> Unit = {},
    ): String {
        val now = Date()
        val builder = JWTClaimsSet.Builder()
            .subject(subject)
            .issuer(AuthDefaults.ISSUER)
            .audience(audience)
            .jwtID(UUID.randomUUID().toString())
            .claim("scopes", scopes)
            .claim("scp", scopes)
            .claim("roles", roles)
            .claim("permissions", permissions)
            .claim("principal_type", principalKind.name.lowercase())
            .claim("credential_type", credentialType)
            .issueTime(now)
            .expirationTime(Date(now.time + expirationMs))

        builder.configure()
        return sign(builder.build())
    }

    private fun sign(claimsSet: JWTClaimsSet): String {
        val header = JWSHeader.Builder(signingKeys.algorithm)
            .keyID(signingKeys.kid)
            .type(JOSEObjectType("at+jwt"))
            .build()
        return SignedJWT(header, claimsSet).apply { sign(signingKeys.signer) }.serialize()
    }

    fun mintAccessToken(
        principalId: String,
        appId: String,
        scopes: List<String>,
        roles: List<String> = emptyList(),
        permissions: List<String> = emptyList(),
        sessionId: String? = null,
        identityId: String? = null,
        tenantId: String? = null,
        contextId: String? = null,
        principalKind: PrincipalKind = PrincipalKind.USER,
        credentialType: String = AuthCredentialType.ACCESS_TOKEN.wireName,
        audience: String = appId,
        ttlSeconds: Int = AuthDefaults.ACCESS_TOKEN_TTL_SECONDS,
        expirationMs: Long = ttlSeconds * 1000L
    ): String = mintReaktorToken(
        subject = principalId,
        audience = audience,
        scopes = scopes,
        roles = roles,
        permissions = permissions,
        ttlSeconds = ttlSeconds,
        expirationMs = expirationMs,
        principalKind = principalKind,
        credentialType = credentialType,
    ) {
        if (sessionId != null) claim("sid", sessionId)
        if (identityId != null) claim("identity_id", identityId)
        if (tenantId != null) claim("tid", tenantId)
        if (contextId != null) claim("ctx", contextId)
    }

    /**
     * Mint a service (machine) access token for the `client_credentials` grant (RFC 6749 §4.4).
     * Same ES256 + `kid` + JWKS path as user tokens, so the security filter and edge WorkerJwtVerifier
     * verify it identically. `principal_type=service` lets authorization distinguish machine callers.
     */
    fun mintServiceToken(
        subject: String,
        audience: String,
        scopes: List<String>,
        appId: String? = null,
        ttlSeconds: Int = AuthDefaults.ACCESS_TOKEN_TTL_SECONDS
    ): String = mintReaktorToken(
        subject = subject,
        audience = audience,
        scopes = scopes,
        ttlSeconds = ttlSeconds,
        principalKind = PrincipalKind.SERVICE,
        credentialType = AuthCredentialType.CLIENT_CREDENTIALS.wireName,
    )

    fun mintPatAccessToken(
        patId: String,
        subject: String,
        name: String,
        audience: String,
        scopes: List<String>,
        appId: String? = null,
        contextId: String? = null,
        principalKind: PrincipalKind,
        ttlSeconds: Int,
    ): String = mintReaktorToken(
        subject = subject,
        audience = audience,
        scopes = scopes,
        ttlSeconds = ttlSeconds,
        principalKind = principalKind,
        credentialType = AuthCredentialType.PERSONAL_ACCESS_TOKEN.wireName,
    ) {
        claim("pat_id", patId)
        claim("name", name)
        if (contextId != null) claim("ctx", contextId)
    }

    /**
     * Mint a delegated token for the RFC 8693 token-exchange grant: the effective principal is [subject]
     * (the user), and the calling service is recorded in the nested **`act`** claim (auditable actor chain).
     * Authorization can gate delegated calls via `AuthRequirement.allowDelegation`.
     */
    fun mintDelegatedToken(
        subject: String,
        actorSubject: String,
        audience: String,
        scopes: List<String>,
        ttlSeconds: Int = AuthDefaults.ACCESS_TOKEN_TTL_SECONDS,
        appId: String? = null,
        contextId: String? = null,
    ): String = mintReaktorToken(
        subject = subject,
        audience = audience,
        scopes = scopes,
        ttlSeconds = ttlSeconds,
        principalKind = PrincipalKind.USER,
        credentialType = AuthCredentialType.DELEGATION.wireName,
    ) {
        claim("act", mapOf("sub" to actorSubject)) // RFC 8693 actor chain
        if (contextId != null) claim("ctx", contextId)
    }
}
