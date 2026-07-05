package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.db.UUIDAuditable
import dev.shibasis.reaktor.auth.db.jsonb
import dev.shibasis.reaktor.auth.kernel.AuthProviderKind
import dev.shibasis.reaktor.auth.kernel.IdentityStatus
import dev.shibasis.reaktor.auth.kernel.MembershipStatus
import dev.shibasis.reaktor.auth.kernel.PlatformKind
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalStatus
import dev.shibasis.reaktor.auth.services.uuid
import dev.shibasis.reaktor.core.framework.db.timestampZ
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder

object Apps: UUIDAuditable<App>("app") {
    val name = varchar("name", 100)

    override fun toDto(result: ResultRow) = App(
        id = result[id].value.toString(),
        name = result[name],
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt],
    )

    override fun UpdateBuilder<*>.selfFields(dto: App) {
        this[name] = dto.name
    }
}

object AuthIdentities: UUIDAuditable<AuthIdentity>("identity") {
    val status = enumerationByName<IdentityStatus>("status", 50).default(IdentityStatus.ACTIVE)
    val primaryEmail = text("primary_email").nullable()

    override fun toDto(result: ResultRow) = AuthIdentity(
        id = result[id].value.toString(),
        status = result[status],
        primaryEmail = result[primaryEmail],
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt],
    )

    override fun UpdateBuilder<*>.selfFields(dto: AuthIdentity) {
        this[status] = dto.status
        this[primaryEmail] = dto.primaryEmail
    }
}

object ProviderAccounts: UUIDAuditable<AuthProviderAccount>("provider_account") {
    val identityId = foreignKey(AuthIdentities, "identity_id")
    val provider = enumerationByName<AuthProviderKind>("provider", 20)
    val issuer = text("issuer")
    val subject = text("subject")
    val email = text("email").nullable()
    val emailVerified = bool("email_verified").default(false)
    val providerTenant = text("provider_tenant").nullable()

    override fun toDto(result: ResultRow) = AuthProviderAccount(
        id = result[id].value.toString(),
        identityId = result[identityId].value.toString(),
        provider = result[provider],
        issuer = result[issuer],
        subject = result[subject],
        email = result[email],
        emailVerified = result[emailVerified],
        providerTenant = result[providerTenant],
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt],
    )

    override fun UpdateBuilder<*>.selfFields(dto: AuthProviderAccount) {
        this[identityId] = AuthIdentities.entityId(dto.identityId.uuid())
        this[provider] = dto.provider
        this[issuer] = dto.issuer
        this[subject] = dto.subject
        this[email] = dto.email
        this[emailVerified] = dto.emailVerified
        this[providerTenant] = dto.providerTenant
    }
}

object AuthPrincipals: UUIDAuditable<AuthPrincipal>("principal") {
    val kind = enumerationByName<PrincipalKind>("kind", 20)
    val identityId = foreignKey(AuthIdentities, "identity_id").nullable()
    val status = enumerationByName<PrincipalStatus>("status", 50).default(PrincipalStatus.ACTIVE)

    override fun toDto(result: ResultRow) = AuthPrincipal(
        id = result[id].value.toString(),
        kind = result[kind],
        identityId = result[identityId]?.value?.toString(),
        status = result[status],
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt],
    )

    override fun UpdateBuilder<*>.selfFields(dto: AuthPrincipal) {
        this[kind] = dto.kind
        this[identityId] = dto.identityId?.let { AuthIdentities.entityId(it.uuid()) }
        this[status] = dto.status
    }
}

object Contexts: UUIDAuditable<Context>("context") {
    val name = varchar("name", 100)
    val appId = foreignKey(Apps, "app_id")

    override fun toDto(result: ResultRow) = Context(
        id = result[id].value.toString(),
        name = result[name],
        appId = result[appId].value.toString(),
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt],
    )

    override fun UpdateBuilder<*>.selfFields(dto: Context) {
        this[name] = dto.name
        this[appId] = Apps.entityId(dto.appId.uuid())
    }
}

object Tenants: UUIDAuditable<Tenant>("tenant") {
    val appId = foreignKey(Apps, "app_id")
    val name = varchar("name", 100)
    val status = enumerationByName<MembershipStatus>("status", 50).default(MembershipStatus.ACTIVE)

    override fun toDto(result: ResultRow) = Tenant(
        id = result[id].value.toString(),
        appId = result[appId].value.toString(),
        name = result[name],
        status = result[status],
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt],
    )

    override fun UpdateBuilder<*>.selfFields(dto: Tenant) {
        this[appId] = Apps.entityId(dto.appId.uuid())
        this[name] = dto.name
        this[status] = dto.status
    }
}

object Memberships: UUIDAuditable<Membership>("membership") {
    val principalId = foreignKey(AuthPrincipals, "principal_id")
    val appId = foreignKey(Apps, "app_id")
    val tenantId = foreignKey(Tenants, "tenant_id").nullable()
    val contextId = foreignKey(Contexts, "context_id").nullable()
    val status = enumerationByName<MembershipStatus>("status", 50).default(MembershipStatus.ACTIVE)
    val profile = jsonb<JsonElement>("profile")

    override fun toDto(result: ResultRow) = Membership(
        id = result[id].value.toString(),
        principalId = result[principalId].value.toString(),
        appId = result[appId].value.toString(),
        tenantId = result[tenantId]?.value?.toString(),
        contextId = result[contextId]?.value?.toString(),
        status = result[status],
        profile = result[profile],
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt],
    )

    override fun UpdateBuilder<*>.selfFields(dto: Membership) {
        this[principalId] = AuthPrincipals.entityId(dto.principalId.uuid())
        this[appId] = Apps.entityId(dto.appId.uuid())
        this[tenantId] = dto.tenantId?.let { Tenants.entityId(it.uuid()) }
        this[contextId] = dto.contextId?.let { Contexts.entityId(it.uuid()) }
        this[status] = dto.status
        this[profile] = dto.profile
    }
}

object Roles: UUIDAuditable<Role>("role")  {
    val name = varchar("name", 50)
    val appId = foreignKey(Apps, "app_id")

    override fun toDto(result: ResultRow) = Role(
        id = result[id].value.toString(),
        name = result[name],
        appId = result[appId].value.toString(),
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt],
    )

    override fun UpdateBuilder<*>.selfFields(dto: Role) {
        this[name] = dto.name
        this[appId] = Apps.entityId(dto.appId.uuid())
    }
}

object Permissions: UUIDAuditable<Permission>("permission") {
    val name = varchar("name", 100)
    val appId = foreignKey(Apps, "app_id")

    override fun toDto(result: ResultRow) = Permission(
        id = result[id].value.toString(),
        name = result[name],
        appId = result[appId].value.toString(),
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt],
    )

    override fun UpdateBuilder<*>.selfFields(dto: Permission) {
        this[name] = dto.name
        this[appId] = Apps.entityId(dto.appId.uuid())
    }
}

object RolePermissions: UUIDAuditable<RolePermission>("role_permissions") {
    val roleId = foreignKey(Roles, "role_id")
    val permissionId = foreignKey(Permissions, "permission_id")

    override fun toDto(result: ResultRow) = RolePermission(
        id = result[id].value.toString(),
        roleId = result[roleId].value.toString(),
        permissionId = result[permissionId].value.toString(),
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt],
    )

    override fun UpdateBuilder<*>.selfFields(dto: RolePermission) {
        this[roleId] = Roles.entityId(dto.roleId.uuid())
        this[permissionId] = Permissions.entityId(dto.permissionId.uuid())
    }
}

object PrincipalRoles: UUIDAuditable<PrincipalRole>("principal_role") {
    val principalId = foreignKey(AuthPrincipals, "principal_id")
    val roleId = foreignKey(Roles, "role_id")
    val contextId = foreignKey(Contexts, "context_id").nullable()

    override fun toDto(result: ResultRow) = PrincipalRole(
        id = result[id].value.toString(),
        principalId = result[principalId].value.toString(),
        roleId = result[roleId].value.toString(),
        contextId = result[contextId]?.value?.toString(),
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt],
    )

    override fun UpdateBuilder<*>.selfFields(dto: PrincipalRole) {
        this[principalId] = AuthPrincipals.entityId(dto.principalId.uuid())
        this[roleId] = Roles.entityId(dto.roleId.uuid())
        this[contextId] = dto.contextId?.let { Contexts.entityId(it.uuid()) }
    }
}

object AuthProviderClients: UUIDAuditable<AuthProviderClient>("auth_provider_client") {
    val appId = foreignKey(Apps, "app_id")
    val provider = enumerationByName<AuthProviderKind>("provider", 20)
    val platform = enumerationByName<PlatformKind>("platform", 20)
    val clientId = text("client_id")
    val clientSecretRef = text("client_secret_ref").nullable()
    val issuer = text("issuer").nullable()
    val appleTeamId = text("apple_team_id").nullable()
    val bundleId = text("bundle_id").nullable()
    val redirectUris = jsonb<JsonElement>("redirect_uris")
    val enabled = bool("enabled").default(true)

    override fun toDto(result: ResultRow) = AuthProviderClient(
        id = result[id].value.toString(),
        appId = result[appId].value.toString(),
        provider = result[provider],
        platform = result[platform],
        clientId = result[clientId],
        clientSecretRef = result[clientSecretRef],
        issuer = result[issuer],
        appleTeamId = result[appleTeamId],
        bundleId = result[bundleId],
        redirectUris = result[redirectUris],
        enabled = result[enabled],
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt],
    )

    override fun UpdateBuilder<*>.selfFields(dto: AuthProviderClient) {
        this[appId] = Apps.entityId(dto.appId.uuid())
        this[provider] = dto.provider
        this[platform] = dto.platform
        this[clientId] = dto.clientId
        this[clientSecretRef] = dto.clientSecretRef
        this[issuer] = dto.issuer
        this[appleTeamId] = dto.appleTeamId
        this[bundleId] = dto.bundleId
        this[redirectUris] = dto.redirectUris
        this[enabled] = dto.enabled
    }
}

object Sessions: UUIDAuditable<Session>("session") {
    val principalId = foreignKey(AuthPrincipals, "principal_id")
    val appId = foreignKey(Apps, "app_id")
    val tenantId = foreignKey(Tenants, "tenant_id").nullable()
    val contextId = foreignKey(Contexts, "context_id").nullable()
    val expiresAt = timestampZ("expires_at")

    override fun toDto(result: ResultRow) = Session(
        id = result[id].value.toString(),
        principalId = result[principalId].value.toString(),
        appId = result[appId].value.toString(),
        tenantId = result[tenantId]?.value?.toString(),
        contextId = result[contextId]?.value?.toString(),
        expiresAt = result[expiresAt],
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt],
    )

    override fun UpdateBuilder<*>.selfFields(dto: Session) {
        this[principalId] = AuthPrincipals.entityId(dto.principalId.uuid())
        this[appId] = Apps.entityId(dto.appId.uuid())
        this[tenantId] = dto.tenantId?.let { Tenants.entityId(it.uuid()) }
        this[contextId] = dto.contextId?.let { Contexts.entityId(it.uuid()) }
        this[expiresAt] = dto.expiresAt
    }
}

object RefreshTokens: UUIDAuditable<RefreshToken>("refresh_token") {
    val sessionId = foreignKey(Sessions, "session_id")
    val tokenHash = varchar("token_hash", 255).uniqueIndex()
    val familyId = uuid("family_id")
    val principalId = foreignKey(AuthPrincipals, "principal_id")
    val previousTokenId = uuid("previous_token_id").nullable()
    val expiresAt = timestampZ("expires_at")
    val usedAt = timestampZ("used_at").nullable()
    val rotatedAt = timestampZ("rotated_at").nullable()
    val revokedAt = timestampZ("revoked_at").nullable()

    override fun toDto(result: ResultRow) = RefreshToken(
        id = result[id].value.toString(),
        sessionId = result[sessionId].value.toString(),
        tokenHash = result[tokenHash],
        familyId = result[familyId].toString(),
        principalId = result[principalId].value.toString(),
        previousTokenId = result[previousTokenId]?.toString(),
        expiresAt = result[expiresAt],
        usedAt = result[usedAt],
        rotatedAt = result[rotatedAt],
        revokedAt = result[revokedAt],
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt],
    )

    override fun UpdateBuilder<*>.selfFields(dto: RefreshToken) {
        this[sessionId] = Sessions.entityId(dto.sessionId.uuid())
        this[tokenHash] = dto.tokenHash
        this[familyId] = dto.familyId.uuid()
        this[principalId] = AuthPrincipals.entityId(dto.principalId.uuid())
        this[previousTokenId] = dto.previousTokenId?.uuid()
        this[expiresAt] = dto.expiresAt
        this[usedAt] = dto.usedAt
        this[rotatedAt] = dto.rotatedAt
        this[revokedAt] = dto.revokedAt
    }
}

object ServiceAccounts: UUIDAuditable<ServiceAccount>("service_account") {
    val appId = foreignKey(Apps, "app_id")
    val principalId = foreignKey(AuthPrincipals, "principal_id")
    val clientId = varchar("client_id", 255).uniqueIndex()
    val name = varchar("name", 100)
    val authMethod = varchar("auth_method", 30).default("PRIVATE_KEY_JWT")
    val publicJwks = text("public_jwks").nullable()
    val secretHash = varchar("secret_hash", 255).nullable()
    val scopes = text("scopes").default("")
    val allowedAudiences = text("allowed_audiences").default("[]")
    val status = enumerationByName<PrincipalStatus>("status", 50).default(PrincipalStatus.ACTIVE)

    override fun toDto(result: ResultRow) = ServiceAccount(
        id = result[id].value.toString(),
        appId = result[appId].value.toString(),
        principalId = result[principalId].value.toString(),
        clientId = result[clientId],
        name = result[name],
        authMethod = result[authMethod],
        publicJwks = result[publicJwks],
        secretHash = result[secretHash],
        scopes = result[scopes],
        allowedAudiences = result[allowedAudiences],
        status = result[status],
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt],
    )

    override fun UpdateBuilder<*>.selfFields(dto: ServiceAccount) {
        this[appId] = Apps.entityId(dto.appId.uuid())
        this[principalId] = AuthPrincipals.entityId(dto.principalId.uuid())
        this[clientId] = dto.clientId
        this[name] = dto.name
        this[authMethod] = dto.authMethod
        this[publicJwks] = dto.publicJwks
        this[secretHash] = dto.secretHash
        this[scopes] = dto.scopes
        this[allowedAudiences] = dto.allowedAudiences
        this[status] = dto.status
    }
}

object PersonalAccessTokens: UUIDAuditable<PersonalAccessToken>("personal_access_token") {
    val principalId = foreignKey(AuthPrincipals, "principal_id").nullable()
    val name = varchar("name", 100)
    val tokenHash = varchar("token_hash", 255).uniqueIndex()
    val scopes = text("scopes")
    val contextId = foreignKey(Contexts, "context_id").nullable()
    val expiresAt = timestampZ("expires_at").nullable()
    val lastUsedAt = timestampZ("last_used_at").nullable()
    val revokedAt = timestampZ("revoked_at").nullable()
    val appId = foreignKey(Apps, "app_id").nullable()
    val allowedAudiences = text("allowed_audiences").nullable()

    override fun toDto(result: ResultRow) = PersonalAccessToken(
        id = result[id].value.toString(),
        principalId = result[principalId]?.value?.toString(),
        name = result[name],
        tokenHash = result[tokenHash],
        scopes = result[scopes],
        contextId = result[contextId]?.value?.toString(),
        expiresAt = result[expiresAt],
        lastUsedAt = result[lastUsedAt],
        revokedAt = result[revokedAt],
        appId = result[appId]?.value?.toString(),
        allowedAudiences = result[allowedAudiences],
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt],
    )

    override fun UpdateBuilder<*>.selfFields(dto: PersonalAccessToken) {
        this[principalId] = dto.principalId?.let { AuthPrincipals.entityId(it.uuid()) }
        this[name] = dto.name
        this[tokenHash] = dto.tokenHash
        this[scopes] = dto.scopes
        this[contextId] = dto.contextId?.let { Contexts.entityId(it.uuid()) }
        this[expiresAt] = dto.expiresAt
        this[lastUsedAt] = dto.lastUsedAt
        this[revokedAt] = dto.revokedAt
        this[appId] = dto.appId?.let { Apps.entityId(it.uuid()) }
        this[allowedAudiences] = dto.allowedAudiences
    }
}

object SigningKeyRows: UUIDAuditable<SigningKeyRecord>("signing_key") {
    val kid = varchar("kid", 64).uniqueIndex()
    val alg = varchar("alg", 16).default("ES256")
    val publicJwk = jsonb<JsonElement>("public_jwk")
    val privateKeyRef = text("private_key_ref").nullable()
    val status = varchar("status", 16).default("current")
    val rotatedAt = timestampZ("rotated_at").nullable()
    val expiresAt = timestampZ("expires_at").nullable()

    override fun toDto(result: ResultRow) = SigningKeyRecord(
        id = result[id].value.toString(),
        kid = result[kid],
        alg = result[alg],
        publicJwk = result[publicJwk],
        privateKeyRef = result[privateKeyRef],
        status = result[status],
        rotatedAt = result[rotatedAt],
        expiresAt = result[expiresAt],
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt],
    )

    override fun UpdateBuilder<*>.selfFields(dto: SigningKeyRecord) {
        this[kid] = dto.kid
        this[alg] = dto.alg
        this[publicJwk] = dto.publicJwk
        this[privateKeyRef] = dto.privateKeyRef
        this[status] = dto.status
        this[rotatedAt] = dto.rotatedAt
        this[expiresAt] = dto.expiresAt
    }
}

object AuthAuditEvents: UUIDAuditable<AuthAuditEvent>("auth_audit_event") {
    val eventType = enumerationByName<AuthAuditEventType>("event_type", 40)
    val outcome = enumerationByName<AuthAuditOutcome>("outcome", 16)
    val actorPrincipalId = foreignKey(AuthPrincipals, "actor_principal_id").nullable()
    val subjectPrincipalId = foreignKey(AuthPrincipals, "subject_principal_id").nullable()
    val appId = foreignKey(Apps, "app_id").nullable()
    val tenantId = foreignKey(Tenants, "tenant_id").nullable()
    val contextId = foreignKey(Contexts, "context_id").nullable()
    val sessionId = foreignKey(Sessions, "session_id").nullable()
    val credentialType = varchar("credential_type", 40).nullable()
    val grantType = varchar("grant_type", 120).nullable()
    val tokenId = varchar("token_id", 120).nullable()
    val audience = text("audience").nullable()
    val scopes = jsonb<JsonElement>("scopes")
    val reason = text("reason").nullable()
    val requestId = varchar("request_id", 120).nullable()
    val ipAddress = varchar("ip_address", 120).nullable()
    val userAgent = text("user_agent").nullable()

    override fun toDto(result: ResultRow) = AuthAuditEvent(
        id = result[id].value.toString(),
        eventType = result[eventType],
        outcome = result[outcome],
        actorPrincipalId = result[actorPrincipalId]?.value?.toString(),
        subjectPrincipalId = result[subjectPrincipalId]?.value?.toString(),
        appId = result[appId]?.value?.toString(),
        tenantId = result[tenantId]?.value?.toString(),
        contextId = result[contextId]?.value?.toString(),
        sessionId = result[sessionId]?.value?.toString(),
        credentialType = result[credentialType],
        grantType = result[grantType],
        tokenId = result[tokenId],
        audience = result[audience],
        scopes = result[scopes],
        reason = result[reason],
        requestId = result[requestId],
        ipAddress = result[ipAddress],
        userAgent = result[userAgent],
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt],
    )

    override fun UpdateBuilder<*>.selfFields(dto: AuthAuditEvent) {
        this[eventType] = dto.eventType
        this[outcome] = dto.outcome
        this[actorPrincipalId] = dto.actorPrincipalId?.let { AuthPrincipals.entityId(it.uuid()) }
        this[subjectPrincipalId] = dto.subjectPrincipalId?.let { AuthPrincipals.entityId(it.uuid()) }
        this[appId] = dto.appId?.let { Apps.entityId(it.uuid()) }
        this[tenantId] = dto.tenantId?.let { Tenants.entityId(it.uuid()) }
        this[contextId] = dto.contextId?.let { Contexts.entityId(it.uuid()) }
        this[sessionId] = dto.sessionId?.let { Sessions.entityId(it.uuid()) }
        this[credentialType] = dto.credentialType
        this[grantType] = dto.grantType
        this[tokenId] = dto.tokenId
        this[audience] = dto.audience
        this[scopes] = dto.scopes
        this[reason] = dto.reason
        this[requestId] = dto.requestId
        this[ipAddress] = dto.ipAddress
        this[userAgent] = dto.userAgent
    }
}
