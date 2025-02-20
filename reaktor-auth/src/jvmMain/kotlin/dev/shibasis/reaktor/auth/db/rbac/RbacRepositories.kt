package dev.shibasis.reaktor.auth.db.rbac

import dev.shibasis.reaktor.auth.db.*
import dev.shibasis.reaktor.auth.framework.CrudRepository
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class RoleRepository(database: Database) : CrudRepository<Long, RoleEntity, RoleEntity.Companion>(database, RoleEntity) {
    fun findByName(name: String, appId: Long): Result<RoleEntity> = sql {
        entity.find { (Roles.name eq name) and (Roles.appId eq EntityID(appId, Apps)) }
            .firstOrNull() ?: throw NullPointerException("Role not found")
    }
}

@Component
class PermissionRepository(database: Database) : CrudRepository<Long, PermissionEntity, PermissionEntity.Companion>(database, PermissionEntity) {
    fun findByName(name: String, appId: Long): Result<PermissionEntity> = sql {
        entity.find { (Permissions.name eq name) and (Permissions.appId eq EntityID(appId, Apps)) }
            .firstOrNull() ?: throw NullPointerException("Permission not found")
    }
}

@Component
class RolePermissionRepository(database: Database) : CrudRepository<Long, RolePermissionEntity, RolePermissionEntity.Companion>(database, RolePermissionEntity) {
    fun findByRoleId(roleId: Long): Result<List<RolePermissionEntity>> = sql {
        entity.find { RolePermissions.roleId eq EntityID(roleId, Roles) }
            .toList()
    }
}

@Component
class UserRoleRepository(database: Database) : CrudRepository<Long, UserRoleEntity, UserRoleEntity.Companion>(database, UserRoleEntity) {
    // Returns roles for a given user within a specific context
    fun findByUserIdAndContext(userId: Long, contextId: Long): Result<List<UserRoleEntity>> = sql {
        entity.find {
            (UserRoles.userId eq EntityID(userId, Users)) and
                    (UserRoles.contextId eq EntityID(contextId, Contexts))
        }
            .toList()
    }
}

@Component
class SessionRepository(database: Database) : CrudRepository<UUID, SessionEntity, SessionEntity.Companion>(database, SessionEntity) {
    // Returns sessions for a given user within a specific context
    fun findByUserIdAndContext(userId: Long, contextId: Long): Result<List<SessionEntity>> = sql {
        entity.find {
            (Sessions.userId eq EntityID(userId, Users)) and
                    (Sessions.contextId eq EntityID(contextId, Contexts))
        }
            .toList()
    }
}
