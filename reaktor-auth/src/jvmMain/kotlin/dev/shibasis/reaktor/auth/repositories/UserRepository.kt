package dev.shibasis.reaktor.auth.repositories

import dev.shibasis.reaktor.auth.utils.ExposedAdapter
import dev.shibasis.reaktor.auth.Users
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class UserRepository(
    database: Database
) : ExposedAdapter(database) {
    fun getUser(id: Long) = sql {
        Users.selectAll().where { Users.id eq id }.singleOrNull()?.apply(Users::toDto)
    }

    fun getUser(appId: Long, socialId: String) = sql {
        Users.selectAll().where { (Users.socialId eq socialId) and (Users.appId eq appId) }
            .singleOrNull()?.apply(Users::toDto)
    }

    fun getUsers(appId: Long) = sql {
        Users.selectAll().where { Users.appId eq appId }.map(Users::toDto)
    }

    fun createUser(name: String, socialId: String, appId: Long, data: JsonElement) = sql {
        Users.insertAndGetId {
            it[Users.name] = name
            it[Users.socialId] = socialId
            it[Users.appId] = appId
            it[Users.data] = data
        }.value
    }

    fun updateUser(id: Long, name: String, data: String) = sql {
        Users.update({ Users.id eq id }) {
            it[Users.name] = name
            it[Users.data] = Json.decodeFromString<JsonElement>(data)
        }
    }

    fun deleteUser(id: Long) = sql {
        Users.deleteWhere { Users.id eq id }
    }
}