package dev.shibasis.reaktor.auth.services

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import dev.shibasis.reaktor.auth.ServiceAccount
import dev.shibasis.reaktor.auth.ServiceAccounts
import dev.shibasis.reaktor.auth.jwt.JwtMinter
import dev.shibasis.reaktor.auth.kernel.AuthDefaults
import dev.shibasis.reaktor.auth.kernel.PrincipalStatus
import dev.shibasis.reaktor.auth.parseTokenPolicyList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import java.util.Base64

/** RFC 7523 §2.2 client-assertion type for `private_key_jwt`. */
const val CLIENT_ASSERTION_TYPE_JWT_BEARER = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"

data class ServiceTokenResult(
    val accessToken: String,
    val scopes: List<String>,
    val audience: String,
    val expiresInSeconds: Int,
    val serviceAccountId: String,
    val principalId: String,
    val appId: String,
)

/**
 * Server-to-server auth (flow B). A service account authenticates *as itself* (no user) and receives a
 * short-lived ES256 service token (`principal_type=service`), audience-bound and down-scoped.
 *
 * Client-authentication methods, most→least preferred:
 *   - **PRIVATE_KEY_JWT** (RFC 7523) — the client signs a short assertion with its private key; we verify
 *     it against the account's stored `public_jwks`. No shared secret ever leaves the client. *(default)*
 *   - **MTLS** (RFC 8705) — terminated at the proxy/edge (reaktor-server §9.13), not in this process.
 *   - **SECRET** — shared secret, SHA-256 hashed at rest. Discouraged; supported for bootstrap only.
 *
 * Powers both `client_credentials` (RFC 6749 §4.4) and the actor leg of token-exchange (RFC 8693).
 * See the auth-analysis "Service Account Flow" and "Delegation Flow" sections.
 */
class ServiceAccountService(
    private val jwtMinter: JwtMinter
) {
    private fun hash(raw: String): String =
        Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        )

    /** Explicit wildcard allows every audience; otherwise the account must name the audience. */
    fun audienceAllowed(serviceAccount: ServiceAccount, audience: String): Boolean {
        val allowed = parseTokenPolicyList(serviceAccount.allowedAudiences)
        return "*" in allowed || audience in allowed
    }

    /** Down-scope: never grant more than the account holds. `*` means the account may request any scope. */
    fun scopesFor(serviceAccount: ServiceAccount, requested: List<String>): List<String> {
        val granted = parseTokenPolicyList(serviceAccount.scopes)
        return when {
            "*" in granted -> requested.ifEmpty { listOf("*") }
            requested.isEmpty() -> granted
            granted.isEmpty() -> emptyList()
            else -> requested.filter { it in granted }
        }
    }

    /** The subject claim for a service account's minted tokens is always its SERVICE principal. */
    fun subjectOf(serviceAccount: ServiceAccount): String =
        serviceAccount.principalId

    private fun findByClientId(clientId: String): ServiceAccount? =
        ServiceAccounts.selectAll().where { ServiceAccounts.clientId eq clientId }
            .map { ServiceAccounts.toDto(it) }
            .firstOrNull()

    /** Authenticate a client by its configured method. Must be called inside a transaction. */
    private fun authenticateClientTx(
        clientId: String?,
        clientSecret: String?,
        clientAssertion: String?,
        clientAssertionType: String?,
    ): ServiceAccount? {
        // For private_key_jwt the client need not send client_id explicitly — derive it from the assertion's iss.
        val assertionIssuer = clientAssertion?.let {
            runCatching { SignedJWT.parse(it).jwtClaimsSet.issuer }.getOrNull()
        }
        val resolvedClientId = clientId ?: assertionIssuer ?: return null
        val serviceAccount = findByClientId(resolvedClientId) ?: return null
        if (serviceAccount.status != PrincipalStatus.ACTIVE) return null

        val authenticated = when (serviceAccount.authMethod) {
            "PRIVATE_KEY_JWT" ->
                clientAssertionType == CLIENT_ASSERTION_TYPE_JWT_BEARER &&
                    clientAssertion != null &&
                    verifyClientAssertion(serviceAccount, clientAssertion)
            "SECRET" ->
                clientSecret != null && serviceAccount.secretHash != null &&
                    hash(clientSecret) == serviceAccount.secretHash
            else -> false // MTLS terminated upstream; unknown methods rejected
        }
        return if (authenticated) serviceAccount else null
    }

    /** Authenticate a service-account client (private_key_jwt or secret). Returns the account on success. */
    fun authenticateClient(
        clientId: String?,
        clientSecret: String?,
        clientAssertion: String?,
        clientAssertionType: String?,
    ): ServiceAccount? = transaction {
        authenticateClientTx(clientId, clientSecret, clientAssertion, clientAssertionType)
    }

    /**
     * Verify an RFC 7523 client assertion against the account's published JWKS: signature (pinned to the
     * asymmetric families), `iss == sub == client_id`, `aud` = our token endpoint, and `exp`.
     */
    private fun verifyClientAssertion(serviceAccount: ServiceAccount, assertion: String): Boolean {
        val jwks = serviceAccount.publicJwks ?: return false
        return runCatching {
            val processor = DefaultJWTProcessor<SecurityContext>()
            processor.jwsKeySelector = JWSVerificationKeySelector(
                setOf(JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.RS256, JWSAlgorithm.PS256),
                ImmutableJWKSet(JWKSet.parse(jwks))
            )
            processor.jwtClaimsSetVerifier = DefaultJWTClaimsVerifier(
                setOf(AuthDefaults.ISSUER, "${AuthDefaults.ISSUER}/token"),
                JWTClaimsSet.Builder().issuer(serviceAccount.clientId).subject(serviceAccount.clientId).build(),
                setOf("sub", "iss", "aud", "exp"),
                emptySet()
            )
            processor.process(assertion, null)
            true
        }.getOrDefault(false)
    }

    /** RFC 6749 §4.4 client_credentials → an audience-bound, scope-intersected service token. */
    fun issueClientCredentialsToken(
        clientId: String?,
        clientSecret: String?,
        clientAssertion: String?,
        clientAssertionType: String?,
        audience: String,
        requestedScopes: List<String>,
        ttlSeconds: Int
    ): ServiceTokenResult? = transaction {
        val serviceAccount = authenticateClientTx(clientId, clientSecret, clientAssertion, clientAssertionType)
            ?: return@transaction null
        if (!audienceAllowed(serviceAccount, audience)) return@transaction null
        val scopes = scopesFor(serviceAccount, requestedScopes)
        val token = jwtMinter.mintServiceToken(
            subject = subjectOf(serviceAccount),
            audience = audience,
            scopes = scopes,
            appId = serviceAccount.appId,
            ttlSeconds = ttlSeconds,
        )
        ServiceTokenResult(
            accessToken = token,
            scopes = scopes,
            audience = audience,
            expiresInSeconds = ttlSeconds,
            serviceAccountId = serviceAccount.id,
            principalId = serviceAccount.principalId,
            appId = serviceAccount.appId,
        )
    }
}
