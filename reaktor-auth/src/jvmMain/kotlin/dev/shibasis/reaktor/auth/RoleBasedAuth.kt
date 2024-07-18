package dev.shibasis.reaktor.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

inline fun <reified T : Any> Table.jsonb(name: String): Column<T> = jsonb(name, { Json.encodeToString(serializer<T>(), it) }, { Json.decodeFromString(serializer<T>(), it) })

object Apps : IntIdTable("app") {
    val name = varchar("name", 100)
    val data = jsonb<JsonElement>("data")
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
}

object Entities : IntIdTable("entity") {
    val name = varchar("name", 100)
    val data = jsonb<JsonElement>("data")
    val appId = reference("app_id", Apps)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
    init {
        uniqueIndex(name, appId)
    }
}

object Users : IntIdTable("user") {
    val name = varchar("name", 100)
    val socialId = text("social_id")
    val appId = reference("app_id", Apps)
    val data = jsonb<JsonElement>("data")
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
    init {
        uniqueIndex(socialId, appId)
    }
}

object Roles : IntIdTable("role") {
    val name = varchar("name", 50)
    val appId = reference("app_id", Apps)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
    init {
        uniqueIndex(name, appId)
    }
}

object Permissions : IntIdTable("permission") {
    val name = varchar("name", 100)
    val appId = reference("app_id", Apps)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
    init {
        uniqueIndex(name, appId)
    }
}

object RolePermissions : IntIdTable("role_permissions") {
    val roleId = reference("role_id", Roles)
    val permissionId = reference("permission_id", Permissions)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
    init {
        uniqueIndex(roleId, permissionId)
    }
}

object UserRoles : IntIdTable("user_role") {
    val userId = reference("user_id", Users)
    val roleId = reference("role_id", Roles)
    val entityId = reference("entity_id", Entities)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
    init {
        uniqueIndex(userId, roleId, entityId)
    }
}
