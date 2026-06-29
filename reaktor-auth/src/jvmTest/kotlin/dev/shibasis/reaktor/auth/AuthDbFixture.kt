package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.kernel.AuthProviderKind
import dev.shibasis.reaktor.auth.kernel.IdentityStatus
import dev.shibasis.reaktor.auth.kernel.MembershipStatus
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalStatus
import dev.shibasis.reaktor.core.framework.EMPTY_JSON
import dev.shibasis.reaktor.db.service.ExposedAdapter
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

/**
 * Test-only Exposed harness backed by an ephemeral in-memory H2 database.
 */
object AuthDbFixture {
    @Volatile
    private var ready = false
    private lateinit var database: Database

    @Synchronized
    fun ensure() {
        if (ready) return
        database = Database.connect(
            "jdbc:h2:mem:reaktor_auth_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.create(
                Apps, AuthIdentities, ProviderAccounts, AuthPrincipals,
                Contexts, Tenants, Roles, Permissions, RolePermissions,
                Memberships, PrincipalRoles, AuthProviderClients, Sessions,
                RefreshTokens, ServiceAccounts, PersonalAccessTokens, SigningKeyRows,
                AuthAuditEvents,
            )
        }
        ready = true
    }

    fun adapter(): ExposedAdapter {
        ensure()
        return ExposedAdapter(database, database)
    }

    fun seedApp(name: String = "test-app-${UUID.randomUUID()}"): String = transaction {
        val appId = UUID.randomUUID()
        Apps.insert {
            it[Apps.id] = Apps.entityId(appId)
            it.fields(App(id = appId.toString(), name = name, data = EMPTY_JSON))
        }
        appId.toString()
    }

    fun seedUser(): Pair<String, String> {
        val appId = seedApp()
        val principalId = seedUser(appId = appId)
        return appId to principalId
    }

    fun seedUser(
        appId: String,
        socialId: String = "sub-${UUID.randomUUID()}",
        provider: UserProvider = UserProvider.GOOGLE,
        identityStatus: IdentityStatus = IdentityStatus.ACTIVE,
        principalStatus: PrincipalStatus = PrincipalStatus.ACTIVE,
        membershipStatus: MembershipStatus = MembershipStatus.ACTIVE,
        profile: JsonObject = JsonObject(emptyMap()),
    ): String = transaction {
        val identityId = UUID.randomUUID()
        val providerAccountId = UUID.randomUUID()
        val principalId = UUID.randomUUID()
        val membershipId = UUID.randomUUID()

        AuthIdentities.insert {
            it[AuthIdentities.id] = AuthIdentities.entityId(identityId)
            it.fields(
                AuthIdentity(
                    id = identityId.toString(),
                    status = identityStatus,
                    primaryEmail = "$socialId@example.test",
                    data = EMPTY_JSON,
                )
            )
        }
        ProviderAccounts.insert {
            it[ProviderAccounts.id] = ProviderAccounts.entityId(providerAccountId)
            it.fields(
                AuthProviderAccount(
                    id = providerAccountId.toString(),
                    identityId = identityId.toString(),
                    provider = provider.toAuthProviderKind(),
                    issuer = issuerFor(provider.toAuthProviderKind()),
                    subject = socialId,
                    email = "$socialId@example.test",
                    emailVerified = true,
                    data = EMPTY_JSON,
                )
            )
        }
        AuthPrincipals.insert {
            it[AuthPrincipals.id] = AuthPrincipals.entityId(principalId)
            it.fields(
                AuthPrincipal(
                    id = principalId.toString(),
                    kind = PrincipalKind.USER,
                    identityId = identityId.toString(),
                    status = principalStatus,
                    data = EMPTY_JSON,
                )
            )
        }
        Memberships.insert {
            it[Memberships.id] = Memberships.entityId(membershipId)
            it.fields(
                Membership(
                    id = membershipId.toString(),
                    principalId = principalId.toString(),
                    appId = appId,
                    status = membershipStatus,
                    profile = profile,
                    data = EMPTY_JSON,
                )
            )
        }
        principalId.toString()
    }

    fun seedInvitedUser(
        appId: String,
        email: String,
        tenantId: String? = null,
        profile: JsonObject = JsonObject(emptyMap()),
    ): String = transaction {
        val identityId = UUID.randomUUID()
        val principalId = UUID.randomUUID()
        val membershipId = UUID.randomUUID()

        AuthIdentities.insert {
            it[AuthIdentities.id] = AuthIdentities.entityId(identityId)
            it.fields(
                AuthIdentity(
                    id = identityId.toString(),
                    status = IdentityStatus.ACTIVE,
                    primaryEmail = email.trim().lowercase(),
                    data = EMPTY_JSON,
                )
            )
        }
        AuthPrincipals.insert {
            it[AuthPrincipals.id] = AuthPrincipals.entityId(principalId)
            it.fields(
                AuthPrincipal(
                    id = principalId.toString(),
                    kind = PrincipalKind.USER,
                    identityId = identityId.toString(),
                    status = PrincipalStatus.ACTIVE,
                    data = EMPTY_JSON,
                )
            )
        }
        Memberships.insert {
            it[Memberships.id] = Memberships.entityId(membershipId)
            it.fields(
                Membership(
                    id = membershipId.toString(),
                    principalId = principalId.toString(),
                    appId = appId,
                    tenantId = tenantId,
                    status = MembershipStatus.ACTIVE,
                    profile = profile,
                    data = EMPTY_JSON,
                )
            )
        }
        principalId.toString()
    }

    fun seedTenant(appId: String, name: String = "tenant-${UUID.randomUUID()}"): String = transaction {
        val tenantId = UUID.randomUUID()
        Tenants.insert {
            it[Tenants.id] = Tenants.entityId(tenantId)
            it.fields(
                Tenant(
                    id = tenantId.toString(),
                    appId = appId,
                    name = name,
                    data = EMPTY_JSON,
                )
            )
        }
        tenantId.toString()
    }

    fun seedIdentity(email: String): String = transaction {
        val identityId = UUID.randomUUID()
        AuthIdentities.insert {
            it[AuthIdentities.id] = AuthIdentities.entityId(identityId)
            it.fields(
                AuthIdentity(
                    id = identityId.toString(),
                    status = IdentityStatus.ACTIVE,
                    primaryEmail = email.trim().lowercase(),
                    data = EMPTY_JSON,
                )
            )
        }
        identityId.toString()
    }

    fun seedUserPrincipalForIdentity(
        identityId: String,
        appId: String,
        tenantId: String? = null,
        membershipStatus: MembershipStatus = MembershipStatus.ACTIVE,
        profile: JsonObject = JsonObject(emptyMap()),
    ): String = transaction {
        val principalId = UUID.randomUUID()
        val membershipId = UUID.randomUUID()
        AuthPrincipals.insert {
            it[AuthPrincipals.id] = AuthPrincipals.entityId(principalId)
            it.fields(
                AuthPrincipal(
                    id = principalId.toString(),
                    kind = PrincipalKind.USER,
                    identityId = identityId,
                    status = PrincipalStatus.ACTIVE,
                    data = EMPTY_JSON,
                )
            )
        }
        Memberships.insert {
            it[Memberships.id] = Memberships.entityId(membershipId)
            it.fields(
                Membership(
                    id = membershipId.toString(),
                    principalId = principalId.toString(),
                    appId = appId,
                    tenantId = tenantId,
                    status = membershipStatus,
                    profile = profile,
                    data = EMPTY_JSON,
                )
            )
        }
        principalId.toString()
    }

    fun seedProviderAccount(
        identityId: String,
        subject: String,
        provider: UserProvider = UserProvider.GOOGLE,
        email: String = "$subject@example.test",
    ): String = transaction {
        val providerKind = provider.toAuthProviderKind()
        val providerAccountId = UUID.randomUUID()
        ProviderAccounts.insert {
            it[ProviderAccounts.id] = ProviderAccounts.entityId(providerAccountId)
            it.fields(
                AuthProviderAccount(
                    id = providerAccountId.toString(),
                    identityId = identityId,
                    provider = providerKind,
                    issuer = issuerFor(providerKind),
                    subject = subject,
                    email = email.trim().lowercase(),
                    emailVerified = true,
                    data = EMPTY_JSON,
                )
            )
        }
        providerAccountId.toString()
    }

    fun grantPermissions(
        principalId: String,
        appId: String,
        permissionNames: List<String>,
        roleName: String = "role-${UUID.randomUUID()}",
    ): List<String> = transaction {
        val roleId = UUID.randomUUID()
        Roles.insert {
            it[Roles.id] = Roles.entityId(roleId)
            it.fields(Role(id = roleId.toString(), name = roleName, appId = appId, data = EMPTY_JSON))
        }

        PrincipalRoles.insert {
            val id = UUID.randomUUID()
            it[PrincipalRoles.id] = PrincipalRoles.entityId(id)
            it.fields(
                PrincipalRole(
                    id = id.toString(),
                    principalId = principalId,
                    roleId = roleId.toString(),
                    data = EMPTY_JSON,
                )
            )
        }

        permissionNames.forEach { permissionName ->
            val permissionId = UUID.randomUUID()
            Permissions.insert {
                it[Permissions.id] = Permissions.entityId(permissionId)
                it.fields(Permission(id = permissionId.toString(), name = permissionName, appId = appId, data = EMPTY_JSON))
            }
            RolePermissions.insert {
                val id = UUID.randomUUID()
                it[RolePermissions.id] = RolePermissions.entityId(id)
                it.fields(
                    RolePermission(
                        id = id.toString(),
                        roleId = roleId.toString(),
                        permissionId = permissionId.toString(),
                        data = EMPTY_JSON,
                    )
                )
            }
        }

        permissionNames
    }

    fun providerAccountsByProviderAndSubject(
        provider: UserProvider,
        subject: String,
    ): List<AuthProviderAccount> = transaction {
        val providerKind = provider.toAuthProviderKind()
        ProviderAccounts.selectAll()
            .where {
                (ProviderAccounts.provider eq providerKind) and
                    (ProviderAccounts.issuer eq issuerFor(providerKind)) and
                    (ProviderAccounts.subject eq subject)
            }
            .map { ProviderAccounts.toDto(it) }
    }

    fun sessionById(sessionId: String): Session? = transaction {
        Sessions.selectAll()
            .where { Sessions.id eq UUID.fromString(sessionId) }
            .map { Sessions.toDto(it) }
            .firstOrNull()
    }

    fun principalById(principalId: String): AuthPrincipal? = transaction {
        AuthPrincipals.selectAll()
            .where { AuthPrincipals.id eq UUID.fromString(principalId) }
            .map { AuthPrincipals.toDto(it) }
            .firstOrNull()
    }

    fun membershipsForPrincipal(principalId: String): List<Membership> = transaction {
        Memberships.selectAll()
            .where { Memberships.principalId eq UUID.fromString(principalId) }
            .map { Memberships.toDto(it) }
    }

    fun refreshTokensForSession(sessionId: String): List<RefreshToken> = transaction {
        RefreshTokens.selectAll()
            .where { RefreshTokens.sessionId eq UUID.fromString(sessionId) }
            .map { RefreshTokens.toDto(it) }
    }

    fun auditEvents(): List<AuthAuditEvent> = transaction {
        AuthAuditEvents.selectAll()
            .map { AuthAuditEvents.toDto(it) }
    }

    /** Seed a service account. Returns its service principal id. */
    fun seedServiceAccount(
        clientId: String,
        appId: String,
        authMethod: String,
        secretHash: String? = null,
        publicJwks: String? = null,
        scopes: String = "",
        allowedAudiences: String = """["*"]""",
        status: PrincipalStatus = PrincipalStatus.ACTIVE,
    ): String = transaction {
        val principalId = UUID.randomUUID()
        val serviceAccountId = UUID.randomUUID()
        AuthPrincipals.insert {
            it[AuthPrincipals.id] = AuthPrincipals.entityId(principalId)
            it.fields(
                AuthPrincipal(
                    id = principalId.toString(),
                    kind = PrincipalKind.SERVICE,
                    identityId = null,
                    status = status,
                    data = EMPTY_JSON,
                )
            )
        }
        ServiceAccounts.insert {
            it[ServiceAccounts.id] = ServiceAccounts.entityId(serviceAccountId)
            it.fields(
                ServiceAccount(
                    id = serviceAccountId.toString(),
                    appId = appId,
                    principalId = principalId.toString(),
                    clientId = clientId,
                    name = clientId,
                    authMethod = authMethod,
                    secretHash = secretHash,
                    publicJwks = publicJwks,
                    scopes = scopes,
                    allowedAudiences = allowedAudiences,
                    status = status,
                    data = EMPTY_JSON,
                )
            )
        }
        principalId.toString()
    }

    private fun issuerFor(provider: AuthProviderKind): String =
        when (provider) {
            AuthProviderKind.GOOGLE -> "https://accounts.google.com"
            AuthProviderKind.APPLE -> "https://appleid.apple.com"
            AuthProviderKind.SUPABASE -> "supabase"
            AuthProviderKind.REAKTOR -> "https://api.reaktor.build/auth"
        }
}
