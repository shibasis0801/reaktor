package dev.shibasis.reaktor.auth.db

import dev.shibasis.reaktor.auth.*
import dev.shibasis.reaktor.auth.framework.*
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import java.util.UUID
import kotlin.uuid.toKotlinUuid


class UserEntity(id: EntityID<Long>): LongAuditableEntity<User>(id, Users) {
    companion object: LongEntityClass<UserEntity>(Users)
    var name by Users.name
    var socialId by Users.socialId
    var appId by Users.appId
    var provider by Users.provider
    var status by Users.status

    override fun toDto() = User(
        id = id.value,
        name = name,
        socialId = socialId,
        appId = appId.value,
        provider = provider,
        status = status,
        rowData = getRowData()
    )

    override fun fromDto(dto: User) {
        name = dto.name
        socialId = dto.socialId
        appId = EntityID(dto.appId, Apps)
        provider = dto.provider
        status = dto.status
    }
}

class AppEntity(id: EntityID<Long>): LongAuditableEntity<App>(id, Apps) {
    companion object: LongEntityClass<AppEntity>(Apps)

    var name by Apps.name

    override fun toDto() = App(
        id = id.value,
        name = name,
        rowData = getRowData()
    )

    override fun fromDto(dto: App) {
        name = dto.name
    }
}

class ContextEntity(id: EntityID<Long>): LongAuditableEntity<Context>(id, Contexts) {
    companion object: LongEntityClass<ContextEntity>(Contexts)

    var name by Contexts.name
    var appId by Contexts.appId

    override fun toDto() = Context(
        id = id.value,
        name = name,
        appId = appId.value,
        rowData = getRowData()
    )

    override fun fromDto(dto: Context) {
        name = dto.name
        appId = EntityID(dto.appId, Apps)
    }
}

class RoleEntity(id: EntityID<Long>) : LongAuditableEntity<Role>(id, Roles) {
    companion object: LongEntityClass<RoleEntity>(Roles)
    var name by Roles.name
    var appId by Roles.appId

    override fun toDto() = Role(
        id = id.value,
        name = name,
        appId = appId.value,
        rowData = getRowData()
    )

    override fun fromDto(dto: Role) {
        name = dto.name
        appId = EntityID(dto.appId, Apps)
    }
}

class PermissionEntity(id: EntityID<Long>) : LongAuditableEntity<Permission>(id, Permissions) {
    companion object: LongEntityClass<PermissionEntity>(Permissions)
    var name by Permissions.name
    var appId by Permissions.appId

    override fun toDto() = Permission(
        id = id.value,
        name = name,
        appId = appId.value,
        rowData = getRowData()
    )

    override fun fromDto(dto: Permission) {
        name = dto.name
        appId = EntityID(dto.appId, Apps)
    }
}

class RolePermissionEntity(id: EntityID<Long>) : LongAuditableEntity<RolePermission>(id, RolePermissions) {
    companion object: LongEntityClass<RolePermissionEntity>(RolePermissions)
    var roleId by RolePermissions.roleId
    var permissionId by RolePermissions.permissionId

    override fun toDto() = RolePermission(
        id = id.value,
        roleId = roleId.value,
        permissionId = permissionId.value,
        rowData = getRowData()
    )

    override fun fromDto(dto: RolePermission) {
        roleId = EntityID(dto.roleId, Roles)
        permissionId = EntityID(dto.permissionId, Permissions)
    }
}

class UserRoleEntity(id: EntityID<Long>) : LongAuditableEntity<UserRole>(id, UserRoles) {
    companion object: LongEntityClass<UserRoleEntity>(UserRoles)
    var userId by UserRoles.userId
    var roleId by UserRoles.roleId
    var contextId by UserRoles.contextId

    override fun toDto() = UserRole(
        id = id.value,
        userId = userId.value,
        roleId = roleId.value,
        contextId = contextId.value,
        rowData = getRowData()
    )

    override fun fromDto(dto: UserRole) {
        userId = EntityID(dto.userId, Users)
        roleId = EntityID(dto.roleId, Roles)
        contextId = EntityID(dto.contextId, Contexts)
    }
}

class SessionEntity(id: EntityID<UUID>): UUIDAuditableEntity<Session>(id, Sessions) {
    companion object: UUIDEntityClass<SessionEntity>(Sessions)
    var userId by Sessions.userId
    var appId by Sessions.appId
    var contextId by Sessions.contextId
    var expiresAt by Sessions.expiresAt

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    override fun toDto() = Session(
        id = id.value.toKotlinUuid(),
        userId = userId.value,
        appId = appId.value,
        contextId = contextId.value,
        expiresAt = expiresAt,
        rowData = getRowData()
    )

    override fun fromDto(dto: Session) {
        userId = EntityID(dto.userId, Users)
        appId = EntityID(dto.appId, Apps)
        contextId = EntityID(dto.contextId, Contexts)
        expiresAt = dto.expiresAt
    }
}