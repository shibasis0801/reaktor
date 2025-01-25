package dev.shibasis.reaktor.auth.repositories

import dev.shibasis.reaktor.auth.UserRoles
import dev.shibasis.reaktor.auth.Roles
import dev.shibasis.reaktor.auth.utils.ExposedAdapter
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll

class UserRoleRepository(
    database: Database
) : ExposedAdapter(database) {
    fun getRolesByUserId(userId: Long) = sql {
        UserRoles.join(
            Roles,
            JoinType.INNER,
            UserRoles.roleId,
            Roles.id
        )
            .select { UserRoles.userId eq userId }
            .map { Roles.toDto(it) }
    }

    fun createUserRole(userId: Long, roleId: Long, entityId: Long) = sql {
        UserRoles.insertAndGetId {
            it[UserRoles.userId] = userId
            it[UserRoles.roleId] = roleId
            it[UserRoles.entityId] = entityId
        }.value
    }

    fun deleteUserRole(userId: Long, roleId: Long, entityId: Long) = sql {
        UserRoles.deleteWhere {
            (UserRoles.userId eq userId) and (UserRoles.roleId eq roleId) and (UserRoles.entityId eq entityId)
        }
    }

    fun deleteUserRolesByUserId(userId: Long) = sql {
        UserRoles.deleteWhere { UserRoles.userId eq userId }
    }

    fun deleteUserRolesByRoleId(roleId: Long) = sql {
        UserRoles.deleteWhere { UserRoles.roleId eq roleId }
    }

    fun deleteUserRolesByEntityId(entityId: Long) = sql {
        UserRoles.deleteWhere { UserRoles.entityId eq entityId }
    }
}
