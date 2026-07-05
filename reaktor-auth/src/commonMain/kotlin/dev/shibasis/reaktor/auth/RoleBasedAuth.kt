package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthDefaults
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.auth.kernel.AuthProviderKind
import dev.shibasis.reaktor.auth.kernel.IdentityStatus
import dev.shibasis.reaktor.auth.kernel.MembershipStatus
import dev.shibasis.reaktor.auth.kernel.PermissionRef
import dev.shibasis.reaktor.auth.kernel.PlatformKind
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import dev.shibasis.reaktor.auth.kernel.PrincipalStatus
import dev.shibasis.reaktor.auth.kernel.RoleRef
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.js.JsExport
import kotlin.time.Clock
import kotlin.time.Instant

interface AuditableDto {
    var data: JsonElement
    var createdAt: Instant
    var updatedAt: Instant
}

@JsExport
@Serializable
enum class UserProvider {
    GOOGLE,
    APPLE,
    SUPABASE,
    REAKTOR
}

fun UserProvider.toAuthProviderKind(): AuthProviderKind =
    when (this) {
        UserProvider.GOOGLE -> AuthProviderKind.GOOGLE
        UserProvider.APPLE -> AuthProviderKind.APPLE
        UserProvider.SUPABASE -> AuthProviderKind.SUPABASE
        UserProvider.REAKTOR -> AuthProviderKind.REAKTOR
    }

fun AuthProviderKind.toUserProvider(): UserProvider =
    when (this) {
        AuthProviderKind.GOOGLE -> UserProvider.GOOGLE
        AuthProviderKind.APPLE -> UserProvider.APPLE
        AuthProviderKind.SUPABASE -> UserProvider.SUPABASE
        AuthProviderKind.REAKTOR -> UserProvider.REAKTOR
    }

@Serializable
data class App(
    val id: String,
    val name: String,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@Serializable
data class AuthIdentity(
    val id: String,
    val status: IdentityStatus = IdentityStatus.ACTIVE,
    val primaryEmail: String? = null,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@Serializable
data class AuthProviderAccount(
    val id: String,
    val identityId: String,
    val provider: AuthProviderKind,
    val issuer: String,
    val subject: String,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val providerTenant: String? = null,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@Serializable
data class AuthPrincipal(
    val id: String,
    val kind: PrincipalKind,
    val identityId: String? = null,
    val status: PrincipalStatus = PrincipalStatus.ACTIVE,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@Serializable
data class Tenant(
    val id: String,
    val appId: String,
    val name: String,
    val status: MembershipStatus = MembershipStatus.ACTIVE,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@Serializable
data class Membership(
    val id: String,
    val principalId: String,
    val appId: String,
    val tenantId: String? = null,
    val contextId: String? = null,
    val status: MembershipStatus = MembershipStatus.ACTIVE,
    val profile: JsonElement,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@Serializable
data class PrincipalRole(
    val id: String,
    val principalId: String,
    val roleId: String,
    val contextId: String? = null,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@Serializable
data class AuthProviderClient(
    val id: String,
    val appId: String,
    val provider: AuthProviderKind,
    val platform: PlatformKind,
    val clientId: String,
    val clientSecretRef: String? = null,
    val issuer: String? = null,
    val appleTeamId: String? = null,
    val bundleId: String? = null,
    val redirectUris: JsonElement,
    val enabled: Boolean = true,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@Serializable
data class Role(
    val id: String,
    val name: String,
    val appId: String,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@Serializable
data class Permission(
    val id: String,
    val name: String,
    val appId: String,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@Serializable
data class RolePermission(
    val id: String,
    val roleId: String,
    val permissionId: String,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@Serializable
data class Session(
    val id: String,
    val principalId: String,
    val appId: String,
    val tenantId: String? = null,
    val contextId: String? = null,
    @Contextual val expiresAt: Instant = Clock.System.now(),
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@Serializable
data class Context(
    val id: String,
    val name: String,
    val appId: String,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@Serializable
data class PersonalAccessToken(
    val id: String,
    val principalId: String?,
    val name: String,
    val tokenHash: String,
    val scopes: String,
    val contextId: String?,
    @Contextual val expiresAt: Instant? = null,
    @Contextual val lastUsedAt: Instant? = null,
    @Contextual val revokedAt: Instant? = null,
    val appId: String? = null,
    val allowedAudiences: String? = null,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@Serializable
data class RefreshToken(
    val id: String,
    val sessionId: String,
    val tokenHash: String,
    val familyId: String,
    val principalId: String,
    val previousTokenId: String? = null,
    @Contextual val expiresAt: Instant,
    @Contextual val usedAt: Instant? = null,
    @Contextual val rotatedAt: Instant? = null,
    @Contextual val revokedAt: Instant? = null,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@Serializable
data class ServiceAccount(
    val id: String,
    val appId: String,
    val principalId: String,
    val clientId: String,
    val name: String,
    val authMethod: String = "PRIVATE_KEY_JWT",
    val publicJwks: String? = null,
    val secretHash: String? = null,
    val scopes: String = "",
    val allowedAudiences: String = "[]",
    val status: PrincipalStatus = PrincipalStatus.ACTIVE,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@Serializable
data class SigningKeyRecord(
    val id: String,
    val kid: String,
    val alg: String = "ES256",
    val publicJwk: JsonElement,
    val privateKeyRef: String? = null,
    val status: String = "current",
    @Contextual val rotatedAt: Instant? = null,
    @Contextual val expiresAt: Instant? = null,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@Serializable
enum class AuthAuditEventType {
    TOKEN_MINT,
    TOKEN_EXCHANGE,
    TOKEN_REFRESH,
    TOKEN_REVOKE,
    AUTH_FAILURE
}

@Serializable
enum class AuthAuditOutcome {
    SUCCESS,
    FAILURE
}

@Serializable
data class AuthAuditEvent(
    val id: String,
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
    val requestId: String? = null,
    val ipAddress: String? = null,
    val userAgent: String? = null,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@JsExport
@Serializable
data class AuthContextSnapshot(
    val principalId: String,
    val principalKind: PrincipalKind,
    val identityId: String? = null,
    val appId: String,
    val tenantId: String? = null,
    val contextId: String? = null,
    val sessionId: String? = null,
    val tokenId: String? = null,
    val credentialId: String? = null,
    val issuer: String = AuthDefaults.ISSUER,
    val audience: String,
    val scopes: List<String> = emptyList(),
    val roles: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val method: AuthMethod,
)

typealias AuthContextView = AuthContextSnapshot

fun AuthContext.toSnapshot(): AuthContextSnapshot =
    AuthContextSnapshot(
        principalId = principal.id,
        principalKind = principal.kind,
        identityId = identityId,
        appId = appId,
        tenantId = tenantId,
        contextId = contextId,
        sessionId = sessionId,
        tokenId = tokenId,
        credentialId = credentialId,
        issuer = issuer,
        audience = audience,
        scopes = scopes.map { it.value }.sorted(),
        roles = roles.mapNotNull { it.name ?: it.id }.sorted(),
        permissions = (permissions.map { it.name } + scopes.map { it.value }).distinct().sorted(),
        method = method,
    )

fun AuthContextSnapshot.toAuthContext(): AuthContext =
    AuthContext(
        principal = PrincipalRef(principalId, principalKind),
        identityId = identityId,
        appId = appId,
        tenantId = tenantId,
        contextId = contextId,
        sessionId = sessionId,
        tokenId = tokenId,
        credentialId = credentialId,
        issuer = issuer,
        audience = audience,
        scopes = scopes.map { PermissionRef(name = it) }.toSet(),
        roles = roles.map { RoleRef(name = it) }.toSet(),
        permissions = (permissions + scopes).distinct().map { PermissionRef(name = it) }.toSet(),
        method = method,
        claims = JsonObject(emptyMap()),
    )
