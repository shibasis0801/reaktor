package dev.shibasis.reaktor.auth.repositories


import dev.shibasis.reaktor.auth.Permissions
import dev.shibasis.reaktor.auth.RolePermissions
import dev.shibasis.reaktor.auth.utils.ExposedAdapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class RolePermissionRepository(
    database: Database
) : ExposedAdapter(database) {

    fun getRolePermissions(roleId: Long) = sql {
        RolePermissions.selectAll().where { RolePermissions.roleId eq roleId }
            .map {
                val permissionId = it[RolePermissions.permissionId].value
                Permissions.toDto(Permissions.select { Permissions.id eq permissionId }.single())
            }
    }
    fun getPermissionsByRoleId(roleId: Long) = sql {
        RolePermissions.join(
            Permissions,
            JoinType.INNER,
            onColumn = RolePermissions.permissionId,
            otherColumn = Permissions.id
        )
            .select { RolePermissions.roleId eq roleId }
            .map { Permissions.toDto(it) }
    }

    fun createRolePermission(roleId: Long, permissionId: Long) = sql {
        RolePermissions.insertAndGetId {
            it[RolePermissions.roleId] = roleId
            it[RolePermissions.permissionId] = permissionId
        }.value
    }

    fun deleteRolePermission(roleId: Long, permissionId: Long) = sql {
        RolePermissions.deleteWhere {
            (RolePermissions.roleId eq roleId) and (RolePermissions.permissionId eq permissionId)
        }
    }

    fun deleteRolePermissionsByRoleId(roleId: Long) = sql {
        RolePermissions.deleteWhere { RolePermissions.roleId eq roleId }
    }
}