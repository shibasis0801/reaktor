package dev.shibasis.reaktor.auth.repositories

import dev.shibasis.reaktor.auth.Apps
import dev.shibasis.reaktor.auth.Users
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

class AppRepository(
    database: Database
): ExposedAdapter(database) {
    fun getApp(id: Long) = sql {
        Apps.selectAll()
            .where { Apps.id eq id }
            .singleOrNull()
            ?.apply(Apps::toDto)
    }

    fun getApps() = sql {
        Apps.selectAll().map { Apps.toDto(it) }
    }

    fun createApp(name: String, data: JsonElement) = sql {
        Apps.insertAndGetId {
            it[Apps.name] = name
            it[Apps.data] = data
        }.value
    }

    fun updateApp(id: Long, name: String, data: String) = sql {
        Apps.update({ Apps.id eq id }) {
            it[Apps.name] = name
            it[Apps.data] = Json.decodeFromString<JsonElement>(data)
        }
    }

    fun deleteApp(id: Long) = sql {
        Apps.deleteWhere { Apps.id eq id }
    }
}