package dev.shibasis.reaktor.auth.runtime.ports

import dev.shibasis.reaktor.auth.App
import dev.shibasis.reaktor.auth.AuthAuditEventType
import dev.shibasis.reaktor.auth.AuthAuditOutcome
import dev.shibasis.reaktor.auth.AuthPrincipal
import dev.shibasis.reaktor.auth.PersonalAccessToken
import dev.shibasis.reaktor.auth.ServiceAccount
import dev.shibasis.reaktor.auth.api.LoginRequest
import dev.shibasis.reaktor.auth.db.ResolvedAnonymousPrincipal
import dev.shibasis.reaktor.auth.db.ResolvedAuthPrincipal
import dev.shibasis.reaktor.auth.kernel.AuthProviderKind
import dev.shibasis.reaktor.auth.runtime.DEFAULT_TOKEN_AUDIENCE
import dev.shibasis.reaktor.auth.services.NewSession
import dev.shibasis.reaktor.auth.services.RotatedSession
import dev.shibasis.reaktor.auth.services.ServiceTokenResult
import dev.shibasis.reaktor.service.Request
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.UUID

interface AuthAppCatalog {
    suspend fun findById(request: Request, id: UUID): Result<App?>
    suspend fun findByName(request: Request, name: String): Result<App?>
    suspend fun all(request: Request): Result<List<App>>
}

interface AuthPrincipalDirectory {
    suspend fun resolveAnonymousLogin(
        request: Request,
        app: App,
        profile: JsonElement,
        tenantId: String? = null,
        contextId: String? = null,
    ): Result<ResolvedAnonymousPrincipal>

    suspend fun resolveExternalLogin(
        request: Request,
        app: App,
        provider: AuthProviderKind,
        issuer: String,
        subject: String,
        email: String?,
        emailVerified: Boolean,
        profile: JsonElement,
        tenantId: String? = null,
        contextId: String? = null,
    ): Result<ResolvedAuthPrincipal>

    suspend fun getPrincipalPermissions(request: Request, principalId: UUID, appId: UUID): Result<List<String>>
    suspend fun getPrincipalRoles(request: Request, principalId: UUID, appId: UUID): Result<List<String>>
    suspend fun getPrincipal(request: Request, principalId: UUID): Result<AuthPrincipal?>
}

interface AuthExternalIdentityVerifier {
    suspend fun verify(request: LoginRequest): Result<dev.shibasis.reaktor.auth.jwt.AuthenticatedUser>
}

interface AuthPersonalTokens {
    suspend fun createPersonalAccessToken(
        principalId: String?,
        name: String,
        scopes: List<String>,
        contextId: String? = null,
        appId: String? = null,
        allowedAudiences: List<String> = listOf(DEFAULT_TOKEN_AUDIENCE),
        expiresInDays: Int? = null,
    ): Pair<PersonalAccessToken, String>

    suspend fun verifyPersonalAccessToken(rawToken: String): PersonalAccessToken?
    fun createAccessToken(pat: PersonalAccessToken, audience: String, ttlSeconds: Int): String
    suspend fun revokePersonalAccessToken(tokenId: String): Boolean
}

interface AuthSessionLifecycle {
    fun createSession(
        principalId: String,
        appId: String,
        tenantId: String? = null,
        contextId: String? = null,
    ): NewSession

    fun rotate(rawRefresh: String): RotatedSession?
    fun revokeAllForPrincipal(principalId: String): Int
    fun revokeByRefreshToken(rawRefresh: String): Boolean
}

interface AuthServiceAccounts {
    fun audienceAllowed(serviceAccount: ServiceAccount, audience: String): Boolean
    fun scopesFor(serviceAccount: ServiceAccount, requested: List<String>): List<String>
    fun subjectOf(serviceAccount: ServiceAccount): String
    fun authenticateClient(
        clientId: String?,
        clientSecret: String?,
        clientAssertion: String?,
        clientAssertionType: String?,
    ): ServiceAccount?

    fun issueClientCredentialsToken(
        clientId: String?,
        clientSecret: String?,
        clientAssertion: String?,
        clientAssertionType: String?,
        audience: String,
        requestedScopes: List<String>,
        ttlSeconds: Int,
    ): ServiceTokenResult?
}

data class AuthAuditEventDraft(
    val eventType: AuthAuditEventType,
    val outcome: AuthAuditOutcome,
    val actorPrincipalId: String? = null,
    val subjectPrincipalId: String? = null,
    val appId: String? = null,
    val tenantId: String? = null,
    val contextId: String? = null,
    val sessionId: String? = null,
    val credentialType: String? = null,
    val grantType: String? = null,
    val tokenId: String? = null,
    val audience: String? = null,
    val scopes: JsonElement = JsonArray(emptyList()),
    val reason: String? = null,
    val data: JsonElement = JsonObject(emptyMap()),
)

interface AuthAuditSink {
    suspend fun record(request: Request, event: AuthAuditEventDraft): Result<Unit>
}
