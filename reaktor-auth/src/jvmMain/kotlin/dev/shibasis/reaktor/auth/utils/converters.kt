package dev.shibasis.reaktor.auth.utils

import dev.shibasis.reaktor.auth.*
import dev.shibasis.reaktor.core.framework.json
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import kotlinx.serialization.json.Json
import java.util.UUID

fun java.time.Instant.toKotlinInstant(): Instant =
    Instant.fromEpochSeconds(epochSecond, nano)

fun String.uuid(): UUID = UUID.fromString(this)

fun Auditable.read(rowData: RowData) {
    data = json.encodeToString(rowData.data)
    createdAt = rowData.createdAt.toJavaInstant()
    updatedAt = rowData.updatedAt.toJavaInstant()
}

fun Auditable.rowData(): RowData = RowData(
    json.parseToJsonElement(data),
    createdAt.toKotlinInstant(),
    updatedAt.toKotlinInstant()
)

fun App.toEntity(): AppEntity = AppEntity(
    id = id.uuid(),
    name = name
).apply { read(rowData) }

fun AppEntity.toDto(): App = App(
    id = id.toString(),
    name = name,
    rowData = rowData()
)

fun User.toEntity(): UserEntity = UserEntity(
    id = id.uuid(),
    name = name,
    socialId = socialId,
    appId = appId.uuid(),
    provider = provider,
    status = status
).apply { read(rowData) }

fun UserEntity.toDto(): User = User(
    id = id.toString(),
    name = name,
    socialId = socialId,
    appId = appId.toString(),
    provider = provider,
    status = status,
    rowData = rowData()
)

fun Context.toEntity(): ContextEntity = ContextEntity(
    id = id.uuid(),
    name = name,
    appId = appId.uuid()
).apply { read(rowData) }

fun ContextEntity.toDto(): Context = Context(
    id = id.toString(),
    name = name,
    appId = appId.toString(),
    rowData = rowData()
)

fun Role.toEntity(): RoleEntity = RoleEntity(
    id = id.uuid(),
    name = name,
    appId = appId.uuid()
).apply { read(rowData) }

fun RoleEntity.toDto(): Role = Role(
    id = id.toString(),
    name = name,
    appId = appId.toString(),
    rowData = rowData()
)

fun Permission.toEntity(): PermissionEntity = PermissionEntity(
    id = id.uuid(),
    name = name,
    appId = appId.uuid()
).apply { read(rowData) }

fun PermissionEntity.toDto(): Permission = Permission(
    id = id.toString(),
    name = name,
    appId = appId.toString(),
    rowData = rowData()
)

fun Session.toEntity(): SessionEntity = SessionEntity(
    id = id.uuid(),
    userId = userId.uuid(),
    appId = appId.uuid(),
    contextId = contextId.uuid(),
    expiresAt = expiresAt.toJavaInstant()
).apply { read(rowData) }

fun SessionEntity.toDto(): Session = Session(
    id = id.toString(),
    userId = userId.toString(),
    appId = appId.toString(),
    contextId = contextId.toString(),
    expiresAt = expiresAt.toKotlinInstant(),
    rowData = rowData()
)

fun RolePermission.toEntity(): RolePermissionEntity = RolePermissionEntity(
    id = id.uuid(),
    roleId = roleId.uuid(),
    permissionId = permissionId.uuid()
).apply { read(rowData) }

fun RolePermissionEntity.toDto(): RolePermission = RolePermission(
    id = id.toString(),
    roleId = roleId.toString(),
    permissionId = permissionId.toString(),
    rowData = rowData()
)

fun UserRole.toEntity(): UserRoleEntity = UserRoleEntity(
    id = id.uuid(),
    userId = userId.uuid(),
    roleId = roleId.uuid(),
    contextId = contextId.uuid()
).apply { read(rowData) }

fun UserRoleEntity.toDto(): UserRole = UserRole(
    id = id.toString(),
    userId = userId.toString(),
    roleId = roleId.toString(),
    contextId = contextId.toString(),
    rowData = rowData()
)
