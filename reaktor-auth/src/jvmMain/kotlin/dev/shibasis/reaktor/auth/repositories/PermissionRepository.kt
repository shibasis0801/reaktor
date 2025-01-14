package dev.shibasis.reaktor.auth.repositories

import dev.shibasis.reaktor.auth.Permissions
import dev.shibasis.reaktor.auth.utils.ExposedAdapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class PermissionRepository(
    database: Database
) : ExposedAdapter(database) {
    fun getPermission(id: Long) = sql {
        Permissions.selectAll().where { Permissions.id eq id }.singleOrNull()?.apply(Permissions::toDto)
    }

    fun getPermissions(appId: Long) = sql {
        Permissions.selectAll().where { Permissions.appId eq appId }.map(Permissions::toDto)
    }

    fun createPermission(name: String, appId: Long) = sql {
        Permissions.insertAndGetId {
            it[Permissions.name] = name
            it[Permissions.appId] = appId
        }.value
    }

    fun updatePermission(id: Long, name: String) = sql {
        Permissions.update({ Permissions.id eq id }) {
            it[Permissions.name] = name
        }
    }

    fun deletePermission(id: Long) = sql {
        Permissions.deleteWhere { Permissions.id eq id }
    }
}