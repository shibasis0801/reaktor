package dev.shibasis.reaktor.auth.apps

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
        success(Apps.selectAll().where { Apps.id eq id }.singleOrNull())
    }

    fun createApp(name: String, data: String) = sql {
        success(Apps.insert {
            it[Apps.name] = name
            it[Apps.data] = Json.decodeFromString<JsonElement>(data)
        })
    }

    fun updateApp(id: Long, name: String, data: String) = sql {
        val app = Apps.selectAll().where { Apps.id eq id }.singleOrNull()
        if (app == null) {
            failure("App not found")
        } else {
            success(Apps.update({ Apps.id eq id }) {
                it[Apps.name] = name
                it[Apps.data] = Json.decodeFromString<JsonElement>(data)
            })
        }
    }

    fun deleteApp(id: Long) = sql {
        val app = Apps.selectAll().where { Apps.id eq id }.singleOrNull()
        if (app == null) {
            failure("App not found")
        } else {
            success(Apps.deleteWhere { Apps.id eq id })
        }
    }
}
