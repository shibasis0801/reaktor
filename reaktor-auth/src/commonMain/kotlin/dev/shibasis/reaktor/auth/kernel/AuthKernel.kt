package dev.shibasis.reaktor.auth.kernel

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.js.JsExport
import kotlin.time.Clock
import kotlin.time.Instant

object AuthDefaults {
    const val ISSUER = "https://api.reaktor.build/auth"
    const val WEBSITE_ORIGIN = "https://reaktor.build"
    const val ACCESS_TOKEN_TTL_SECONDS = 15 * 60
}

@Serializable
@JsExport
enum class AuthProviderKind {
    GOOGLE,
    APPLE,
    SUPABASE,
    REAKTOR
}

@Serializable
@JsExport
enum class PlatformKind {
    ANDROID,
    IOS,
    WEB,
    DESKTOP,
    SERVER
}

@Serializable
@JsExport
enum class IdentityStatus {
    ACTIVE,
    DISABLED,
    MERGED
}

@Serializable
data class Identity(
    val id: String,
    val status: IdentityStatus = IdentityStatus.ACTIVE,
    val primaryEmail: String? = null,
    @Contextual val createdAt: Instant = Clock.System.now(),
    @Contextual val updatedAt: Instant = Clock.System.now()
)

@Serializable
data class ProviderAccount(
    val id: String,
    val identityId: String,
    val provider: AuthProviderKind,
    val issuer: String,
    val subject: String,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val providerTenant: String? = null,
    @Contextual val createdAt: Instant = Clock.System.now(),
    @Contextual val updatedAt: Instant = Clock.System.now()
)

@Serializable
@JsExport
enum class PrincipalKind {
    USER,
    SERVICE,
    AGENT,
    ACTOR
}

@Serializable
@JsExport
enum class PrincipalStatus {
    ACTIVE,
    DISABLED,
    SUSPENDED
}

@Serializable
data class Principal(
    val id: String,
    val kind: PrincipalKind,
    val identityId: String? = null,
    val status: PrincipalStatus = PrincipalStatus.ACTIVE
)

@Serializable
data class PrincipalRef(
    val id: String,
    val kind: PrincipalKind
)

@Serializable
@JsExport
enum class MembershipStatus {
    ACTIVE,
    INVITED,
    SUSPENDED,
    REMOVED
}

@Serializable
data class Tenant(
    val id: String,
    val appId: String,
    val name: String,
    val status: MembershipStatus = MembershipStatus.ACTIVE
)

@Serializable
data class Membership(
    val id: String,
    val principalId: String,
    val appId: String,
    val tenantId: String? = null,
    val contextId: String? = null,
    val status: MembershipStatus = MembershipStatus.ACTIVE,
    val profile: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class AuthProviderClient(
    val id: String,
    val appId: String,
    val provider: AuthProviderKind,
    val platform: PlatformKind,
    val clientId: String,
    val clientSecretRef: String? = null,
    val issuer: String,
    val appleTeamId: String? = null,
    val bundleId: String? = null,
    val redirectUris: List<String> = emptyList(),
    val enabled: Boolean = true
)

@Serializable
data class ResourceRef(
    val type: String,
    val id: String? = null,
    val appId: String? = null,
    val tenantId: String? = null,
    val contextId: String? = null
)

@Serializable
data class Action(val name: String)

@Serializable
data class PermissionRef(
    val id: String? = null,
    val name: String
) {
    val value: String
        get() = name
}

@Serializable
data class RoleRef(
    val id: String? = null,
    val name: String? = null
)

@Serializable
@JsExport
enum class AuthMethod {
    ACCESS_TOKEN,
    REFRESH_TOKEN,
    EXTERNAL_LOGIN,
    SERVICE_CREDENTIAL,
    PERSONAL_ACCESS_TOKEN,
    ANONYMOUS
}

@Serializable
data class Delegation(
    val actor: PrincipalRef,
    val subject: PrincipalRef? = null,
    val reason: String? = null
)

@Serializable
data class AuthContext(
    val principal: PrincipalRef,
    val identityId: String? = null,
    val appId: String,
    val tenantId: String? = null,
    val contextId: String? = null,
    val sessionId: String? = null,
    val tokenId: String? = null,
    val credentialId: String? = null,
    val issuer: String = AuthDefaults.ISSUER,
    val audience: String,
    val scopes: Set<PermissionRef> = emptySet(),
    val roles: Set<RoleRef> = emptySet(),
    val permissions: Set<PermissionRef> = emptySet(),
    val method: AuthMethod,
    val actor: PrincipalRef? = null,
    val delegation: Delegation? = null,
    val claims: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class Audience(val value: String)

@Serializable
data class AppConstraint(val appId: String? = null)

@Serializable
data class TenantConstraint(val tenantId: String? = null)

@Serializable
data class AuthRequirement(
    val audience: Audience? = null,
    val app: AppConstraint? = null,
    val tenant: TenantConstraint? = null,
    val anyOf: List<AuthRequirement> = emptyList(),
    val allOf: List<AuthRequirement> = emptyList(),
    val scopes: Set<PermissionRef> = emptySet(),
    val permissions: Set<PermissionRef> = emptySet(),
    val roles: Set<RoleRef> = emptySet(),
    val resource: ResourceRef? = null,
    val principalKinds: Set<PrincipalKind> = setOf(PrincipalKind.USER, PrincipalKind.SERVICE),
    val allowDelegation: Boolean = false
) {
    fun on(resource: ResourceRef) = copy(resource = resource)
    fun forUsers() = copy(principalKinds = setOf(PrincipalKind.USER))
    fun forServices() = copy(principalKinds = setOf(PrincipalKind.SERVICE))
    fun forUsersOrServices() = copy(principalKinds = setOf(PrincipalKind.USER, PrincipalKind.SERVICE))
    fun audience(audience: String) = copy(audience = Audience(audience))
    fun inApp(appId: String) = copy(app = AppConstraint(appId))
    fun inTenant(tenantId: String) = copy(tenant = TenantConstraint(tenantId))
    fun allowDelegatedActor() = copy(allowDelegation = true)
}

@Serializable
enum class AuthDenyReason {
    MISSING_AUTH_CONTEXT,
    INVALID_AUDIENCE,
    INVALID_APP,
    INVALID_TENANT,
    INVALID_PRINCIPAL_KIND,
    MISSING_SCOPE,
    MISSING_PERMISSION,
    MISSING_ROLE,
    RESOURCE_DENIED,
    DELEGATION_DENIED
}

@Serializable
sealed class AuthDecision {
    @Serializable
    data class Allow(val context: AuthContext) : AuthDecision()

    @Serializable
    data class Deny(
        val reason: AuthDenyReason,
        val statusCode: Int,
        val safeMessage: String
    ) : AuthDecision()
}

@Serializable
data class AuthInput(
    val bearerToken: String? = null,
    val appId: String? = null,
    val tenantHint: String? = null,
    val contextHint: String? = null,
    val audience: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val claims: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class ExpectedToken(
    val issuer: String = AuthDefaults.ISSUER,
    val audience: String,
    val appId: String? = null
)

@Serializable
data class TokenClaims(
    val issuer: String,
    val audience: String,
    val subject: String,
    val tokenId: String? = null,
    val sessionId: String? = null,
    val appId: String? = null,
    val tenantId: String? = null,
    val contextId: String? = null,
    val principalKind: PrincipalKind = PrincipalKind.USER,
    val scopes: Set<PermissionRef> = emptySet(),
    @Contextual val issuedAt: Instant? = null,
    @Contextual val expiresAt: Instant? = null,
    val raw: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class AccessTokenRequest(
    val principal: PrincipalRef,
    val appId: String,
    val audience: String,
    val tenantId: String? = null,
    val contextId: String? = null,
    val sessionId: String? = null,
    val credentialId: String? = null,
    val scopes: Set<PermissionRef> = emptySet(),
    val ttlSeconds: Int = AuthDefaults.ACCESS_TOKEN_TTL_SECONDS,
    val actor: PrincipalRef? = null
)

@Serializable
data class IssuedToken(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresInSeconds: Int,
    val tokenId: String? = null,
    val scopes: Set<PermissionRef> = emptySet()
)

@Serializable
data class CreateSessionRequest(
    val principal: PrincipalRef,
    val identityId: String?,
    val appId: String,
    val tenantId: String? = null,
    val contextId: String? = null,
    val method: AuthMethod = AuthMethod.EXTERNAL_LOGIN
)

@Serializable
data class RefreshResult(
    val accessToken: IssuedToken,
    val refreshToken: String?,
    val sessionId: String
)

interface Authenticator {
    suspend fun authenticate(input: AuthInput): Result<AuthContext>
}

interface Authorizer {
    suspend fun authorize(context: AuthContext?, requirement: AuthRequirement): AuthDecision
}

interface TokenVerifier {
    suspend fun verify(token: String, expected: ExpectedToken): Result<TokenClaims>
}

interface TokenIssuer {
    suspend fun issueAccessToken(request: AccessTokenRequest): IssuedToken
    suspend fun issueRefreshToken(sessionId: String): String
}

interface SessionManager {
    suspend fun createSession(request: CreateSessionRequest): String
    suspend fun refresh(rawRefreshToken: String): RefreshResult
    suspend fun revoke(sessionId: String): Boolean
}

interface PermissionResolver {
    suspend fun resolve(context: AuthContext): Set<PermissionRef>
}

interface TenantResolver {
    suspend fun resolve(input: AuthInput, claims: TokenClaims): Pair<String?, String?>
}

object LocalAuthorizer {
    fun authorize(context: AuthContext?, requirement: AuthRequirement): AuthDecision {
        if (context == null) {
            return deny(AuthDenyReason.MISSING_AUTH_CONTEXT, 401, "Authentication required")
        }

        validateContextConstraints(context, requirement)?.let { return it }

        if (requirement.anyOf.isNotEmpty()) {
            val decisions = requirement.anyOf.map { authorize(context, it) }
            decisions.firstOrNull { it is AuthDecision.Allow }?.let { return it }
            return decisions.filterIsInstance<AuthDecision.Deny>().firstOrNull()
                ?: deny(AuthDenyReason.MISSING_PERMISSION, 403, "Authorization denied")
        }

        requirement.allOf.forEach {
            val decision = authorize(context, it)
            if (decision is AuthDecision.Deny) return decision
        }

        val requiredCapabilities = requirement.permissions + requirement.scopes
        if (!hasPermissions(context, requiredCapabilities)) {
            return deny(AuthDenyReason.MISSING_PERMISSION, 403, "Required capability missing")
        }

        if (!hasRoles(context, requirement.roles)) {
            return deny(AuthDenyReason.MISSING_ROLE, 403, "Required role missing")
        }

        if (context.delegation != null && !requirement.allowDelegation) {
            return deny(AuthDenyReason.DELEGATION_DENIED, 403, "Delegation denied")
        }

        return AuthDecision.Allow(context)
    }

    private fun validateContextConstraints(
        context: AuthContext,
        requirement: AuthRequirement
    ): AuthDecision.Deny? {
        val requiredAudience = requirement.audience?.value
        if (requiredAudience != null && context.audience != requiredAudience) {
            return deny(AuthDenyReason.INVALID_AUDIENCE, 401, "Invalid token audience")
        }

        val requiredApp = requirement.app?.appId
        if (requiredApp != null && context.appId != requiredApp) {
            return deny(AuthDenyReason.INVALID_APP, 403, "Application access denied")
        }

        val requiredTenant = requirement.tenant?.tenantId
        if (requiredTenant != null && context.tenantId != requiredTenant) {
            return deny(AuthDenyReason.INVALID_TENANT, 403, "Tenant access denied")
        }

        if (context.principal.kind !in requirement.principalKinds) {
            return deny(AuthDenyReason.INVALID_PRINCIPAL_KIND, 403, "Principal type denied")
        }

        val resource = requirement.resource
        if (resource != null && !resourceMatches(context, resource)) {
            return deny(AuthDenyReason.RESOURCE_DENIED, 403, "Resource access denied")
        }

        return null
    }

    private fun hasPermissions(context: AuthContext, required: Set<PermissionRef>): Boolean {
        if (required.isEmpty()) return true
        val capabilities = context.permissions + context.scopes
        val ids = capabilities.mapNotNull { it.id }.toSet()
        val names = capabilities.map { it.name }.toSet()
        return required.all { requiredPermission ->
            requiredPermission.id?.let { it in ids } ?: (requiredPermission.name in names)
        }
    }

    private fun hasRoles(context: AuthContext, required: Set<RoleRef>): Boolean {
        if (required.isEmpty()) return true
        val ids = context.roles.mapNotNull { it.id }.toSet()
        val names = context.roles.mapNotNull { it.name }.toSet()
        return required.all { role ->
            role.id?.let { it in ids } ?: (role.name != null && role.name in names)
        }
    }

    private fun resourceMatches(context: AuthContext, resource: ResourceRef): Boolean {
        if (resource.appId != null && resource.appId != context.appId) return false
        if (resource.tenantId != null && resource.tenantId != context.tenantId) return false
        if (resource.contextId != null && resource.contextId != context.contextId) return false
        return true
    }

    private fun deny(reason: AuthDenyReason, statusCode: Int, safeMessage: String) =
        AuthDecision.Deny(reason, statusCode, safeMessage)
}

class SimpleAuthorizer : Authorizer {
    override suspend fun authorize(context: AuthContext?, requirement: AuthRequirement): AuthDecision =
        LocalAuthorizer.authorize(context, requirement)
}

@Deprecated("Use permits; auth scope and permission requirements are the same capability model.")
fun requires(scope: String): AuthRequirement =
    permits(scope)

fun requires(action: Action): AuthRequirement =
    AuthRequirement(permissions = setOf(PermissionRef(name = action.name)))

fun permits(permission: String): AuthRequirement =
    AuthRequirement(permissions = setOf(PermissionRef(name = permission)))

fun anyOf(vararg requirements: AuthRequirement): AuthRequirement =
    AuthRequirement(anyOf = requirements.toList())

fun allOf(vararg requirements: AuthRequirement): AuthRequirement =
    AuthRequirement(allOf = requirements.toList())

fun resource(type: String, id: String? = null): ResourceRef =
    ResourceRef(type = type, id = id)
