package dev.shibasis.reaktor.auth.apps

import dev.shibasis.reaktor.auth.App
import dev.shibasis.reaktor.auth.Apps
import dev.shibasis.reaktor.auth.ExposedAdapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

class AppService(
    database: Database
): ExposedAdapter(database) {
    fun getApps() = sql {
        success(Apps.selectAll().map { Apps.toDto(it) })
    }

    fun getApp(id: Long) = sql {
        val app = Apps.selectAll().where { Apps.id eq id }.map { Apps.toDto(it) }
        if (app.isEmpty()) {
            failure("App not found")
        } else {
            success(app.single())
        }
    }

    fun createApp(name: String, data: JsonElement) = sql {
        try {
            val id = Apps.insertAndGetId {
                it[Apps.name] = name
                it[Apps.data] = data
            }
            success(id.value)
        } catch (e: Exception) {
            failure(e.message ?: "Error creating app")
        }
    }

    fun updateApp(id: Long, name: String, data: String) = sql {
        val app = Apps.selectAll().where { Apps.id eq id }.singleOrNull()
        if (app == null) {
            failure("App not found")
        } else {
            Apps.update({ Apps.id eq id }) {
                it[Apps.name] = name
                it[Apps.data] = Json.decodeFromString<JsonElement>(data)
            }
            success(id)
        }
    }

    fun deleteApp(id: Long) = sql {
        val deletedRows = Apps.deleteWhere { Apps.id eq id }
        if (deletedRows == 0) {
            failure("App not found")
        } else {
            success(id)
        }
    }
}
