package dev.shibasis.reaktor.auth.repositories

import dev.shibasis.reaktor.auth.Entities
import dev.shibasis.reaktor.auth.utils.ExposedAdapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

class EntityRepository(
    database: Database
): ExposedAdapter(database) {
    fun getEntity(id: Long) = sql {
        Entities.selectAll().where { Entities.id eq id }.singleOrNull()
    }

    fun getEntities(appId: Long) = sql {
        Entities.selectAll().where { Entities.appId eq appId }.map(Entities::toDto)
    }

    fun createEntity(name: String, data: JsonElement, appId: Long) = sql {
        Entities.insertAndGetId {
            it[Entities.name] = name
            it[Entities.data] = data
            it[Entities.appId] = appId
        }.value
    }

    fun updateEntity(id: Long, name: String, data: String) = sql {
        Entities.update({ Entities.id eq id }) {
            it[Entities.name] = name
            it[Entities.data] = Json.decodeFromString<JsonElement>(data)
        }
    }

    fun deleteEntity(id: Long) = sql {
        Entities.deleteWhere { Entities.id eq id }
    }
}