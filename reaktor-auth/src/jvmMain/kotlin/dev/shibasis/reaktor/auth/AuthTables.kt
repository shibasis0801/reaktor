package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.services.uuid
import dev.shibasis.reaktor.framework.UUIDAuditable
import dev.shibasis.reaktor.framework.db.timestampZ
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder

object Apps: UUIDAuditable<App>("app") {
    val name = varchar("name", 100)

    override fun toDto(result: ResultRow) = App(
        id = result[id].value.toString(),
        name = result[name],
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt]
    )

    override fun UpdateBuilder<*>.selfFields(dto: App) {
        this[name] = dto.name
    }
}

object Users: UUIDAuditable<User>("users") {
    val name = varchar("name", 100)
    val socialId = text("social_id").nullable()
    val appId = foreignKey(Apps,"app_id")
    val provider = enumerationByName<UserProvider>("provider", 20).nullable()
    val status = enumerationByName<UserStatus>("status", 50)
    val accountType = enumerationByName<AccountType>("account_type", 20).default(AccountType.USER)

    override fun toDto(result: ResultRow) = User(
        id = result[id].value.toString(),
        name = result[name],
        socialId = result[socialId],
        appId = result[appId].value.toString(),
        provider = result[provider],
        status = result[status],
        accountType = result[accountType],
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt]
    )

    override fun UpdateBuilder<*>.selfFields(dto: User) {
        this[name] = dto.name
        this[socialId] = dto.socialId
        this[appId] =  Apps.entityId(dto.appId.uuid())
        this[provider] = dto.provider
        this[status] = dto.status
        this[accountType] = dto.accountType
    }
}

object Contexts: UUIDAuditable<Context>("context") {
    val name = varchar("name", 100)
    val appId = foreignKey(Apps,"app_id")

    override fun toDto(result: ResultRow) = Context(
        id = result[id].value.toString(),
        name = result[name],
        appId = result[appId].value.toString(),
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt]
    )

    override fun UpdateBuilder<*>.selfFields(dto: Context) {
        this[name] = dto.name
        this[appId] = Apps.entityId(dto.appId.uuid())
    }
}

object Roles: UUIDAuditable<Role>("role")  {
    val name = varchar("name", 50)
    val appId = foreignKey(Apps,"app_id")

    override fun toDto(result: ResultRow) = Role(
        id = result[id].value.toString(),
        name = result[name],
        appId = result[appId].value.toString(),
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt]
    )

    override fun UpdateBuilder<*>.selfFields(dto: Role) {
        this[name] = dto.name
        this[appId] = Apps.entityId(dto.appId.uuid())
    }
}

object Permissions: UUIDAuditable<Permission>("permission") {
    val name = varchar("name", 100)
    val appId = foreignKey(Apps,"app_id")

    override fun toDto(result: ResultRow) = Permission(
        id = result[id].value.toString(),
        name = result[name],
        appId = result[appId].value.toString(),
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt]
    )

    override fun UpdateBuilder<*>.selfFields(dto: Permission) {
        this[name] = dto.name
        this[appId] = Apps.entityId(dto.appId.uuid())
    }
}

object RolePermissions: UUIDAuditable<RolePermission>("role_permissions") {
    val roleId = foreignKey(Roles,"role_id")
    val permissionId = foreignKey(Permissions, "permission_id")

    override fun toDto(result: ResultRow) = RolePermission(
        id = result[id].value.toString(),
        roleId = result[roleId].value.toString(),
        permissionId = result[permissionId].value.toString(),
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt]
    )

    override fun UpdateBuilder<*>.selfFields(dto: RolePermission) {
        this[roleId] = Roles.entityId(dto.roleId.uuid())
        this[permissionId] = Permissions.entityId(dto.permissionId.uuid())
    }
}

object UserRoles: UUIDAuditable<UserRole>("user_role") {
    val userId = foreignKey(Users, "user_id")
    val roleId = foreignKey(Roles,"role_id")
    val contextId = foreignKey(Contexts, "context_id")

    override fun toDto(result: ResultRow) = UserRole(
        id = result[id].value.toString(),
        userId = result[userId].value.toString(),
        roleId = result[roleId].value.toString(),
        contextId = result[contextId].value.toString(),
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt]
    )

    override fun UpdateBuilder<*>.selfFields(dto: UserRole) {
        this[userId] = Users.entityId(dto.userId.uuid())
        this[roleId] = Roles.entityId(dto.roleId.uuid())
        this[contextId] = Contexts.entityId(dto.contextId.uuid())
    }
}

object Sessions: UUIDAuditable<Session>("session") {
    val userId = foreignKey(Users, "user_id")
    val appId = foreignKey(Apps,"app_id")
    val contextId = foreignKey(Contexts, "context_id")
    val expiresAt = timestampZ("expires_at")

    override fun toDto(result: ResultRow) = Session(
        id = result[id].value.toString(),
        userId = result[userId].value.toString(),
        appId = result[appId].value.toString(),
        contextId = result[contextId].value.toString(),
        expiresAt = result[expiresAt],
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt]
    )

    override fun UpdateBuilder<*>.selfFields(dto: Session) {
        this[userId] = Users.entityId(dto.userId.uuid())
        this[appId] = Apps.entityId(dto.appId.uuid())
        this[contextId] = Contexts.entityId(dto.contextId.uuid())
        this[expiresAt] = dto.expiresAt
    }
}

object PersonalAccessTokens: UUIDAuditable<PersonalAccessToken>("personal_access_token") {
    val userId = foreignKey(Users, "user_id").nullable()
    val name = varchar("name", 100)
    val tokenHash = varchar("token_hash", 255).uniqueIndex()
    val scopes = text("scopes")
    val contextId = foreignKey(Contexts, "context_id").nullable()
    val expiresAt = timestampZ("expires_at").nullable()
    val lastUsedAt = timestampZ("last_used_at").nullable()
    val revokedAt = timestampZ("revoked_at").nullable()

    override fun toDto(result: ResultRow) = PersonalAccessToken(
        id = result[id].value.toString(),
        userId = result[userId]?.value?.toString(),
        name = result[name],
        tokenHash = result[tokenHash],
        scopes = result[scopes],
        contextId = result[contextId]?.value?.toString(),
        expiresAt = result[expiresAt],
        lastUsedAt = result[lastUsedAt],
        revokedAt = result[revokedAt],
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt]
    )

    override fun UpdateBuilder<*>.selfFields(dto: PersonalAccessToken) {
        this[userId] = dto.userId?.let { Users.entityId(it.uuid()) }
        this[name] = dto.name
        this[tokenHash] = dto.tokenHash
        this[scopes] = dto.scopes
        this[contextId] = dto.contextId?.let { Contexts.entityId(it.uuid()) }
        this[expiresAt] = dto.expiresAt
        this[lastUsedAt] = dto.lastUsedAt
        this[revokedAt] = dto.revokedAt
    }
}
