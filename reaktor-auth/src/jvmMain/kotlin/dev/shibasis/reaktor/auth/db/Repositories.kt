package dev.shibasis.reaktor.auth.db

import dev.shibasis.reaktor.auth.App
import dev.shibasis.reaktor.auth.Apps
import dev.shibasis.reaktor.auth.AuthAuditEvent
import dev.shibasis.reaktor.auth.AuthAuditEvents
import dev.shibasis.reaktor.auth.AuthIdentities
import dev.shibasis.reaktor.auth.AuthIdentity
import dev.shibasis.reaktor.auth.AuthPrincipal
import dev.shibasis.reaktor.auth.AuthPrincipals
import dev.shibasis.reaktor.auth.AuthProviderAccount
import dev.shibasis.reaktor.auth.Membership
import dev.shibasis.reaktor.auth.Memberships
import dev.shibasis.reaktor.auth.Permissions
import dev.shibasis.reaktor.auth.PrincipalRoles
import dev.shibasis.reaktor.auth.ProviderAccounts
import dev.shibasis.reaktor.auth.Roles
import dev.shibasis.reaktor.auth.kernel.AuthProviderKind
import dev.shibasis.reaktor.auth.kernel.IdentityStatus
import dev.shibasis.reaktor.auth.kernel.MembershipStatus
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalStatus
import dev.shibasis.reaktor.auth.runtime.ports.AuthAuditEventDraft
import dev.shibasis.reaktor.auth.services.uuid
import dev.shibasis.reaktor.core.framework.EMPTY_JSON
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.db.service.CrudRepository
import dev.shibasis.reaktor.db.service.ExposedAdapter
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.time.Clock

class AppRepository(adapter: ExposedAdapter): CrudRepository(adapter) {
    suspend fun findById(
        request: Request,
        id: UUID
    ) = request.sql {
        Apps.selectAll()
            .where { Apps.id eq id }
            .map { Apps.toDto(it) }
            .firstOrNull()
    }

    suspend fun all(
        request: Request
    ) = request.sql {
        Apps.selectAll()
            .map { Apps.toDto(it) }
    }

    suspend fun findByName(
        request: Request,
        name: String
    ) = request.sql {
        Apps.selectAll()
            .where { Apps.name eq name }
            .map { Apps.toDto(it) }
            .firstOrNull()
    }
}

class AuthAuditRepository(adapter: ExposedAdapter): CrudRepository(adapter) {
    suspend fun record(request: Request, event: AuthAuditEventDraft): Result<Unit> = request.sql {
        val now = Clock.System.now()
        val id = UUID.randomUUID()
        AuthAuditEvents.insert {
            it[AuthAuditEvents.id] = AuthAuditEvents.entityId(id)
            it.fields(
                AuthAuditEvent(
                    id = id.toString(),
                    eventType = event.eventType,
                    outcome = event.outcome,
                    actorPrincipalId = event.actorPrincipalId,
                    subjectPrincipalId = event.subjectPrincipalId,
                    appId = event.appId,
                    tenantId = event.tenantId,
                    contextId = event.contextId,
                    sessionId = event.sessionId,
                    credentialType = event.credentialType,
                    grantType = event.grantType,
                    tokenId = event.tokenId,
                    audience = event.audience,
                    scopes = event.scopes,
                    reason = event.reason,
                    requestId = request.header("X-Request-Id") ?: request.header("CF-Ray"),
                    ipAddress = request.header("CF-Connecting-IP")
                        ?: request.header("X-Forwarded-For")?.substringBefore(",")?.trim(),
                    userAgent = request.header("User-Agent"),
                    data = event.data,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
        Unit
    }
}

private fun Request.header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }

data class ResolvedAuthPrincipal(
    val app: App,
    val identity: AuthIdentity,
    val providerAccount: AuthProviderAccount,
    val principal: AuthPrincipal,
    val membership: Membership,
    val roles: List<String>,
    val permissions: List<String>,
)

data class ResolvedAnonymousPrincipal(
    val app: App,
    val principal: AuthPrincipal,
    val membership: Membership,
    val roles: List<String>,
    val permissions: List<String>,
)

class AuthRepository(adapter: ExposedAdapter): CrudRepository(adapter) {
    suspend fun resolveAnonymousLogin(
        request: Request,
        app: App,
        profile: JsonElement,
        tenantId: String? = null,
        contextId: String? = null,
    ) = request.sql {
        val principal = createAnonymousPrincipal()
        val membership = createMembership(
            principalId = principal.id,
            appId = app.id,
            profile = profile,
            tenantId = tenantId,
            contextId = contextId,
        )
        ResolvedAnonymousPrincipal(
            app = app,
            principal = principal,
            membership = membership,
            roles = getPrincipalRoles(principal.id.uuid(), app.id.uuid()),
            permissions = getPrincipalPermissions(principal.id.uuid(), app.id.uuid()),
        )
    }

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
    ) = request.sql {
        val appId = app.id.uuid()
        val providerAccount = findProviderAccount(provider, issuer, subject)
        val resolved = if (providerAccount == null) {
            createPrincipalForProvider(
                app = app,
                provider = provider,
                issuer = issuer,
                subject = subject,
                email = email,
                emailVerified = emailVerified,
                profile = profile,
                tenantId = tenantId,
                contextId = contextId,
            )
        } else {
            val identity = requireNotNull(findIdentity(providerAccount.identityId.uuid())) {
                "Provider account ${providerAccount.id} points at a missing identity"
            }
            val principal = requireNotNull(
                findUserPrincipalForMembership(identity.id.uuid(), appId, tenantId, contextId)
                    ?: findUserPrincipal(identity.id.uuid())
            ) {
                "Identity ${identity.id} has no user principal"
            }
            val membership = findMembership(principal.id.uuid(), appId, tenantId, contextId)
                ?: createMembership(
                    principalId = principal.id,
                    appId = app.id,
                    profile = profile,
                    tenantId = tenantId,
                    contextId = contextId,
                )
            ResolvedAuthPrincipal(
                app = app,
                identity = identity,
                providerAccount = providerAccount,
                principal = principal,
                membership = membership,
                roles = getPrincipalRoles(principal.id.uuid(), appId),
                permissions = getPrincipalPermissions(principal.id.uuid(), appId),
            )
        }
        resolved
    }

    suspend fun getPrincipalPermissions(
        request: Request,
        principalId: UUID,
        appId: UUID,
    ) = request.sql {
        getPrincipalPermissions(principalId, appId)
    }

    suspend fun getPrincipalRoles(
        request: Request,
        principalId: UUID,
        appId: UUID,
    ) = request.sql {
        getPrincipalRoles(principalId, appId)
    }

    suspend fun getPrincipal(
        request: Request,
        principalId: UUID,
    ) = request.sql {
        findPrincipal(principalId)
    }

    private fun createPrincipalForProvider(
        app: App,
        provider: AuthProviderKind,
        issuer: String,
        subject: String,
        email: String?,
        emailVerified: Boolean,
        profile: JsonElement,
        tenantId: String?,
        contextId: String?,
    ): ResolvedAuthPrincipal {
        val invitedIdentity = if (emailVerified) email?.normalizedEmail()?.let { findIdentityByEmail(it) } else null
        if (invitedIdentity != null) {
            val providerAccount = createProviderAccount(
                identityId = invitedIdentity.id,
                provider = provider,
                issuer = issuer,
                subject = subject,
                email = email?.normalizedEmail(),
                emailVerified = emailVerified,
            )
            val principal = findUserPrincipalForMembership(invitedIdentity.id.uuid(), app.id.uuid(), tenantId, contextId)
                ?: findUserPrincipal(invitedIdentity.id.uuid())
                ?: createUserPrincipal(invitedIdentity.id)
            val membership = findMembership(principal.id.uuid(), app.id.uuid(), tenantId, contextId)
                ?: createMembership(
                    principalId = principal.id,
                    appId = app.id,
                    profile = profile,
                    tenantId = tenantId,
                    contextId = contextId,
                )

            return ResolvedAuthPrincipal(
                app = app,
                identity = invitedIdentity,
                providerAccount = providerAccount,
                principal = principal,
                membership = membership,
                roles = getPrincipalRoles(principal.id.uuid(), app.id.uuid()),
                permissions = getPrincipalPermissions(principal.id.uuid(), app.id.uuid()),
            )
        }

        val identityId = UUID.randomUUID()

        AuthIdentities.insert {
            it[AuthIdentities.id] = AuthIdentities.entityId(identityId)
            it.fields(
                AuthIdentity(
                    id = identityId.toString(),
                    status = IdentityStatus.ACTIVE,
                    primaryEmail = email?.normalizedEmail(),
                    data = EMPTY_JSON,
                )
            )
        }

        val providerAccount = createProviderAccount(
            identityId = identityId.toString(),
            provider = provider,
            issuer = issuer,
            subject = subject,
            email = email?.normalizedEmail(),
            emailVerified = emailVerified,
        )
        val principal = createUserPrincipal(identityId.toString())

        val membership = createMembership(
            principalId = principal.id,
            appId = app.id,
            profile = profile,
            tenantId = tenantId,
            contextId = contextId,
        )

        return ResolvedAuthPrincipal(
            app = app,
            identity = requireNotNull(findIdentity(identityId)),
            providerAccount = providerAccount,
            principal = principal,
            membership = membership,
            roles = emptyList(),
            permissions = emptyList(),
        )
    }

    private fun createProviderAccount(
        identityId: String,
        provider: AuthProviderKind,
        issuer: String,
        subject: String,
        email: String?,
        emailVerified: Boolean,
    ): AuthProviderAccount {
        val providerAccountId = UUID.randomUUID()
        val providerAccount = AuthProviderAccount(
            id = providerAccountId.toString(),
            identityId = identityId,
            provider = provider,
            issuer = issuer,
            subject = subject,
            email = email,
            emailVerified = emailVerified,
            data = EMPTY_JSON,
        )
        ProviderAccounts.insert {
            it[ProviderAccounts.id] = ProviderAccounts.entityId(providerAccountId)
            it.fields(providerAccount)
        }
        return providerAccount
    }

    private fun createUserPrincipal(identityId: String): AuthPrincipal {
        val principalId = UUID.randomUUID()
        val principal = AuthPrincipal(
            id = principalId.toString(),
            kind = PrincipalKind.USER,
            identityId = identityId,
            status = PrincipalStatus.ACTIVE,
            data = EMPTY_JSON,
        )
        AuthPrincipals.insert {
            it[AuthPrincipals.id] = AuthPrincipals.entityId(principalId)
            it.fields(principal)
        }
        return principal
    }

    private fun createAnonymousPrincipal(): AuthPrincipal {
        val principalId = UUID.randomUUID()
        val principal = AuthPrincipal(
            id = principalId.toString(),
            kind = PrincipalKind.USER,
            identityId = null,
            status = PrincipalStatus.ACTIVE,
            data = EMPTY_JSON,
        )
        AuthPrincipals.insert {
            it[AuthPrincipals.id] = AuthPrincipals.entityId(principalId)
            it.fields(principal)
        }
        return principal
    }

    private fun createMembership(
        principalId: String,
        appId: String,
        profile: JsonElement,
        tenantId: String?,
        contextId: String?,
    ): Membership {
        val membershipId = UUID.randomUUID()
        val membership = Membership(
            id = membershipId.toString(),
            principalId = principalId,
            appId = appId,
            tenantId = tenantId,
            contextId = contextId,
            status = MembershipStatus.ACTIVE,
            profile = profile,
            data = EMPTY_JSON,
        )
        Memberships.insert {
            it[Memberships.id] = Memberships.entityId(membershipId)
            it.fields(membership)
        }
        return membership
    }

    private fun findProviderAccount(
        provider: AuthProviderKind,
        issuer: String,
        subject: String,
    ): AuthProviderAccount? =
        ProviderAccounts.selectAll()
            .where {
                (ProviderAccounts.provider eq provider) and
                    (ProviderAccounts.issuer eq issuer) and
                    (ProviderAccounts.subject eq subject)
            }
            .map { ProviderAccounts.toDto(it) }
            .firstOrNull()

    private fun findIdentity(id: UUID): AuthIdentity? =
        AuthIdentities.selectAll()
            .where { AuthIdentities.id eq id }
            .map { AuthIdentities.toDto(it) }
            .firstOrNull()

    private fun findIdentityByEmail(email: String): AuthIdentity? =
        AuthIdentities.selectAll()
            .where { AuthIdentities.primaryEmail eq email }
            .map { AuthIdentities.toDto(it) }
            .firstOrNull()

    private fun findPrincipal(id: UUID): AuthPrincipal? =
        AuthPrincipals.selectAll()
            .where { AuthPrincipals.id eq id }
            .map { AuthPrincipals.toDto(it) }
            .firstOrNull()

    private fun findUserPrincipal(identityId: UUID): AuthPrincipal? =
        AuthPrincipals.selectAll()
            .where {
                (AuthPrincipals.identityId eq identityId) and
                    (AuthPrincipals.kind eq PrincipalKind.USER)
            }
            .map { AuthPrincipals.toDto(it) }
            .firstOrNull()

    private fun findUserPrincipalForMembership(
        identityId: UUID,
        appId: UUID,
        tenantId: String?,
        contextId: String?,
    ): AuthPrincipal? =
        (AuthPrincipals innerJoin Memberships)
            .selectAll()
            .where {
                var predicate = (AuthPrincipals.identityId eq identityId) and
                    (AuthPrincipals.kind eq PrincipalKind.USER) and
                    (Memberships.appId eq appId)
                if (tenantId != null) predicate = predicate and (Memberships.tenantId eq tenantId.uuid())
                if (contextId != null) predicate = predicate and (Memberships.contextId eq contextId.uuid())
                predicate
            }
            .map { AuthPrincipals.toDto(it) }
            .firstOrNull()

    private fun findMembership(
        principalId: UUID,
        appId: UUID,
        tenantId: String?,
        contextId: String?,
    ): Membership? =
        Memberships.selectAll()
            .where {
                var predicate = (Memberships.principalId eq principalId) and (Memberships.appId eq appId)
                if (tenantId != null) predicate = predicate and (Memberships.tenantId eq tenantId.uuid())
                if (contextId != null) predicate = predicate and (Memberships.contextId eq contextId.uuid())
                predicate
            }
            .map { Memberships.toDto(it) }
            .firstOrNull()

    private fun getPrincipalRoles(
        principalId: UUID,
        appId: UUID,
    ): List<String> =
        (PrincipalRoles innerJoin Roles)
            .selectAll()
            .where {
                (PrincipalRoles.principalId eq principalId) and
                    (Roles.appId eq appId)
            }
            .map { it[Roles.name] }
            .distinct()

    private fun getPrincipalPermissions(
        principalId: UUID,
        appId: UUID,
    ): List<String> =
        (PrincipalRoles innerJoin Roles innerJoin dev.shibasis.reaktor.auth.RolePermissions innerJoin Permissions)
            .selectAll()
            .where {
                (PrincipalRoles.principalId eq principalId) and
                    (Roles.appId eq appId) and
                    (Permissions.appId eq appId)
            }
            .map { it[Permissions.name] }
            .distinct()
}

private fun String.normalizedEmail(): String =
    trim().lowercase()
