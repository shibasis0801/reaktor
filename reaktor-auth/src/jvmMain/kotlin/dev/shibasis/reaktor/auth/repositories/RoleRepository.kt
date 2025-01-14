package dev.shibasis.reaktor.auth.repositories

import dev.shibasis.reaktor.auth.Roles
import dev.shibasis.reaktor.auth.utils.ExposedAdapter
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class RoleRepository(
    database: Database
) : ExposedAdapter(database) {
    fun getRole(id: Long) = sql {
        Roles.selectAll().where { Roles.id eq id }.singleOrNull()?.apply(Roles::toDto)
    }

    fun getRoles(appId: Long) = sql {
        Roles.selectAll().where { Roles.appId eq appId }.map(Roles::toDto)
    }

    fun createRole(name: String, appId: Long) = sql {
        Roles.insertAndGetId {
            it[Roles.name] = name
            it[Roles.appId] = appId
        }.value
    }

    fun updateRole(id: Long, name: String) = sql {
        Roles.update({ Roles.id eq id }) {
            it[Roles.name] = name
        }
    }

    fun deleteRole(id: Long) = sql {
        Roles.deleteWhere { Roles.id eq id }
    }
}