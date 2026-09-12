package dev.shibasis.reaktor.auth.db

import dev.shibasis.reaktor.auth.*
import dev.shibasis.reaktor.auth.Membership
import dev.shibasis.reaktor.auth.kernel.*
import dev.shibasis.reaktor.auth.runtime.ports.*
import dev.shibasis.reaktor.db.service.CrudRepository
import dev.shibasis.reaktor.db.service.ExposedAdapter
import dev.shibasis.reaktor.service.Request
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.greater
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.time.Clock

/** No role names or memberships are trusted from an incoming token. */
class AuthorityRepository(adapter: ExposedAdapter) : CrudRepository(adapter), AuthAuthorityDirectory {
    override suspend fun snapshot(request: Request, principalId: UUID, sessionId: UUID, sourceAppId: UUID): Result<ActiveAuthoritySession?> = request.sql {
        val now = Clock.System.now()
        val principal = (AuthPrincipals innerJoin AuthIdentities).selectAll().where {
            (AuthPrincipals.id eq principalId) and (AuthPrincipals.status eq PrincipalStatus.ACTIVE) and
                (AuthPrincipals.kind eq PrincipalKind.USER) and (AuthIdentities.status eq IdentityStatus.ACTIVE)
        }.singleOrNull()?.let(AuthPrincipals::toDto) ?: return@sql null
        val session = Sessions.selectAll().where {
            (Sessions.id eq sessionId) and (Sessions.principalId eq principalId) and
                (Sessions.appId eq sourceAppId) and (Sessions.expiresAt greater now)
        }.singleOrNull()?.let(Sessions::toDto) ?: return@sql null
        // Family replay revokes refresh tokens; logout also expires the session itself.
        val hasLiveRefresh = RefreshTokens.selectAll().where {
            (RefreshTokens.sessionId eq sessionId) and (RefreshTokens.revokedAt.isNull()) and (RefreshTokens.expiresAt greater now)
        }.limit(1).any()
        if (!hasLiveRefresh) return@sql null
        val principalIds = AuthPrincipals.selectAll().where {
            (AuthPrincipals.identityId eq UUID.fromString(principal.identityId)) and
                (AuthPrincipals.status eq PrincipalStatus.ACTIVE) and (AuthPrincipals.kind eq PrincipalKind.USER)
        }.map { it[AuthPrincipals.id].value }
        val memberships = Memberships.selectAll().where {
            (Memberships.principalId inList principalIds) and (Memberships.status eq MembershipStatus.ACTIVE)
        }.map(Memberships::toDto)
        val tenants = Tenants.selectAll().where { Tenants.status eq MembershipStatus.ACTIVE }.associate { it[Tenants.id].value.toString() to Tenants.toDto(it) }
        val contexts = Contexts.selectAll().associate { it[Contexts.id].value.toString() to it[Contexts.appId].value.toString() }
        fun valid(membership: Membership): Boolean =
            (membership.tenantId == null || tenants[membership.tenantId]?.appId == membership.appId) &&
                (membership.contextId == null || contexts[membership.contextId] == membership.appId)
        if (memberships.none { valid(it) && it.principalId == principal.id && it.appId == session.appId && it.tenantId == session.tenantId && it.contextId == session.contextId }) return@sql null
        val apps = Apps.selectAll().associate { it[Apps.id].value.toString() to it[Apps.name] }
        val grants = memberships.filter { valid(it) && it.tenantId != null }.mapNotNull { membership ->
            val tenant = tenants[membership.tenantId] ?: return@mapNotNull null
            val appId = UUID.fromString(membership.appId)
            val targetPrincipalId = UUID.fromString(membership.principalId)
            val roles = (PrincipalRoles innerJoin Roles).selectAll().where {
                (PrincipalRoles.principalId eq targetPrincipalId) and (Roles.appId eq appId) and roleScope(tenant.id, membership.contextId)
            }.map { it[Roles.name] }.distinct().sorted()
            if (roles.isEmpty()) return@mapNotNull null
            val permissions = (PrincipalRoles innerJoin Roles innerJoin RolePermissions innerJoin Permissions).selectAll().where {
                (PrincipalRoles.principalId eq targetPrincipalId) and (Roles.appId eq appId) and (Permissions.appId eq appId) and roleScope(tenant.id, membership.contextId)
            }.map { it[Permissions.name] }.distinct().sorted()
            ResolvedAuthorityGrant(AuthorityGrant(AuthorityTarget(membership.appId, tenant.id, membership.contextId),
                apps[membership.appId] ?: return@mapNotNull null, tenant.name, roles), permissions, membership.principalId)
        }.distinctBy { it.grant.target to it.principalId }
        ActiveAuthoritySession(principal, session, grants)
    }
}
