package dev.shibasis.reaktor.auth.entities

import dev.shibasis.reaktor.auth.Entities
import dev.shibasis.reaktor.auth.Entity
import dev.shibasis.reaktor.auth.ExposedAdapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

class EntityService(
    database: Database
): ExposedAdapter(database) {

    fun getEntity(id: Long) = sql {
        success(Entities.selectAll().where { Entities.id eq id }.singleOrNull())
    }

    fun getEntities(appId: Long) = sql {
        success(Entities.selectAll().where { Entities.appId eq appId }.map { Entities.toDto(it) })
    }

    fun createEntity(name: String, data: JsonElement, appId: Long) = sql {
        success(Entities.insert {
            it[Entities.name] = name
            it[Entities.data] = data
            it[Entities.appId] = appId
        })
    }

    fun updateEntity(id: Long, name: String, data: String) = sql {
        val entity = Entities.selectAll().where { Entities.id eq id }.singleOrNull()
        if (entity == null) {
            failure("Entity not found")
        } else {
            success(Entities.update({ Entities.id eq id }) {
                it[Entities.name] = name
                it[Entities.data] = Json.decodeFromString<JsonElement>(data)
            })
        }
    }

    fun deleteEntity(id: Long) = sql {
        val entity = Entities.selectAll().where { Entities.id eq id }.singleOrNull()
        if (entity == null) {
            failure("Entity not found")
        } else {
            success(Entities.deleteWhere { Entities.id eq id })
        }
    }
}