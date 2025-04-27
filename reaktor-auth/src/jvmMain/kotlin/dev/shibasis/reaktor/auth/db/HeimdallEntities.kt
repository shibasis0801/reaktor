package dev.shibasis.reaktor.auth.db

import dev.shibasis.reaktor.auth.*
import dev.shibasis.reaktor.auth.framework.*
import org.jetbrains.exposed.dao.id.EntityID
import java.util.UUID
import kotlin.uuid.toKotlinUuid

fun EntityID<UUID>.string() = value.toString()
fun String.toUUID() = UUID.fromString(this)

class UserEntity(id: EntityID<UUID>): AuditableEntity<UUID, User>(id, Users) {
    companion object: AuditableEntityCompanion<UUID, User, UserEntity>(Users)
    var name by Users.name
    var socialId by Users.socialId
    var appId by Users.appId
    var provider by Users.provider
    var status by Users.status

    override fun toDto() = User(
        id = id.string(),
        name = name,
        socialId = socialId,
        appId = appId.value.toString(),
        provider = provider,
        status = status,
        rowData = getRowData()
    )

    override fun fromDto(dto: User) {
        name = dto.name
        socialId = dto.socialId
        appId = EntityID(UUID.fromString(dto.appId), Apps)
        provider = dto.provider
        status = dto.status
    }
}

class AppEntity(id: EntityID<UUID>): AuditableEntity<UUID, App>(id, Apps) {
    companion object: AuditableEntityCompanion<UUID, App, AppEntity>(Apps)

    var name by Apps.name

    override fun toDto() = App(
        id = id.string(),
        name = name,
        rowData = getRowData()
    )

    override fun fromDto(dto: App) {
        name = dto.name
    }
}

class ContextEntity(id: EntityID<UUID>): AuditableEntity<UUID, Context>(id, Contexts) {
    companion object: AuditableEntityCompanion<UUID, Context, ContextEntity>(Contexts)

    var name by Contexts.name
    var appId by Contexts.appId

    override fun toDto() = Context(
        id = id.string(),
        name = name,
        appId = appId.string(),
        rowData = getRowData()
    )

    override fun fromDto(dto: Context) {
        name = dto.name
        appId = EntityID(UUID.fromString(dto.appId), Apps)
    }
}

class RoleEntity(id: EntityID<UUID>): AuditableEntity<UUID, Role>(id, Roles) {
    companion object: AuditableEntityCompanion<UUID, Role, RoleEntity>(Roles)
    var name by Roles.name
    var appId by Roles.appId

    override fun toDto() = Role(
        id = id.string(),
        name = name,
        appId = appId.string(),
        rowData = getRowData()
    )

    override fun fromDto(dto: Role) {
        name = dto.name
        appId = EntityID(UUID.fromString(dto.appId), Apps)
    }
}

class PermissionEntity(id: EntityID<UUID>) : AuditableEntity<UUID, Permission>(id, Permissions) {
    companion object: AuditableEntityCompanion<UUID, Permission, PermissionEntity>(Permissions)
    var name by Permissions.name
    var appId by Permissions.appId

    override fun toDto() = Permission(
        id = id.string(),
        name = name,
        appId = appId.string(),
        rowData = getRowData()
    )

    override fun fromDto(dto: Permission) {
        name = dto.name
        appId = EntityID(UUID.fromString(dto.appId), Apps)
    }
}

class RolePermissionEntity(id: EntityID<UUID>) : AuditableEntity<UUID, RolePermission>(id, RolePermissions) {
    companion object: AuditableEntityCompanion<UUID, RolePermission, RolePermissionEntity>(RolePermissions)
    var roleId by RolePermissions.roleId
    var permissionId by RolePermissions.permissionId

    override fun toDto() = RolePermission(
        id = id.string(),
        roleId = roleId.string(),
        permissionId = permissionId.string(),
        rowData = getRowData()
    )

    override fun fromDto(dto: RolePermission) {
        roleId = EntityID(UUID.fromString(dto.roleId), Roles)
        permissionId = EntityID(UUID.fromString(dto.permissionId), Permissions)
    }
}

class UserRoleEntity(id: EntityID<UUID>) : AuditableEntity<UUID, UserRole>(id, UserRoles) {
    companion object: AuditableEntityCompanion<UUID, UserRole, UserRoleEntity>(UserRoles)
    var userId by UserRoles.userId
    var roleId by UserRoles.roleId
    var contextId by UserRoles.contextId

    override fun toDto() = UserRole(
        id = id.string(),
        userId = userId.string(),
        roleId = roleId.string(),
        contextId = contextId.string(),
        rowData = getRowData()
    )
    override fun fromDto(dto: UserRole) {
        userId = EntityID(UUID.fromString(dto.userId), Users)
        roleId = EntityID(UUID.fromString(dto.roleId), Roles)
        contextId = EntityID(UUID.fromString(dto.contextId), Contexts)
    }
}

class SessionEntity(id: EntityID<UUID>): AuditableEntity<UUID, Session>(id, Sessions) {
    companion object: AuditableEntityCompanion<UUID, Session, SessionEntity>(Sessions)
    var userId by Sessions.userId
    var appId by Sessions.appId
    var contextId by Sessions.contextId
    var expiresAt by Sessions.expiresAt

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    override fun toDto() = Session(
        id = id.string(),
        userId = userId.string(),
        appId = appId.string(),
        contextId = contextId.string(),
        expiresAt = expiresAt,
        rowData = getRowData()
    )

    override fun fromDto(dto: Session) {
        userId = EntityID(UUID.fromString(dto.userId), Users)
        appId = EntityID(UUID.fromString(dto.appId), Apps)
        contextId = EntityID(UUID.fromString(dto.contextId), Contexts)
        expiresAt = dto.expiresAt
    }
}