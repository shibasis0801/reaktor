package dev.shibasis.reaktor.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

inline fun <reified T : Any> Table.jsonb(name: String): Column<T> = jsonb(name, { Json.encodeToString(serializer<T>(), it) }, { Json.decodeFromString(serializer<T>(), it) })

interface Data<T> {
    fun ResultRow.toDto(): T
}


object Apps: LongIdTable("app"), Data<App> {
    val name = varchar("name", 100)
    val data = jsonb<JsonElement>("data")
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)


    override fun ResultRow.toDto() = App(
        id = this[id].value,
        name = this[name],
        data = this[data],
        createdAt = this[createdAt],
        updatedAt = this[updatedAt]
    )
}

object Entities: LongIdTable("entity"), Data<Entity> {
    val name = varchar("name", 100)
    val data = jsonb<JsonElement>("data")
    val appId = reference("app_id", Apps)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
    init {
        uniqueIndex(name, appId)
    }

    override fun ResultRow.toDto() = Entity(
        id = this[id].value,
        name = this[name],
        data = this[data],
        appId = this[appId].value,
        createdAt = this[createdAt],
        updatedAt = this[updatedAt]
    )
}

object Users: LongIdTable("user"), Data<User> {
    val name = varchar("name", 100)
    val socialId = text("social_id")
    val appId = reference("app_id", Apps)
    val data = jsonb<JsonElement>("data")
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
    init {
        uniqueIndex(socialId, appId)
    }

    override fun ResultRow.toDto() = User(
        id = this[id].value,
        name = this[name],
        socialId = this[socialId],
        appId = this[appId].value,
        data = this[data],
        createdAt = this[createdAt],
        updatedAt = this[updatedAt]
    )
}

object Roles: LongIdTable("role"), Data<Role> {
    val name = varchar("name", 50)
    val appId = reference("app_id", Apps)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
    init {
        uniqueIndex(name, appId)
    }

    override fun ResultRow.toDto() = Role(
        id = this[id].value,
        name = this[name],
        appId = this[appId].value,
        createdAt = this[createdAt],
        updatedAt = this[updatedAt]
    )
}

object Permissions: LongIdTable("permission"), Data<Permission> {
    val name = varchar("name", 100)
    val appId = reference("app_id", Apps)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
    init {
        uniqueIndex(name, appId)
    }

    override fun ResultRow.toDto() = Permission(
        id = this[id].value,
        name = this[name],
        appId = this[appId].value,
        createdAt = this[createdAt],
        updatedAt = this[updatedAt]
    )
}

object RolePermissions: LongIdTable("role_permissions") {
    val roleId = reference("role_id", Roles)
    val permissionId = reference("permission_id", Permissions)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
    init {
        uniqueIndex(roleId, permissionId)
    }
}

object UserRoles: LongIdTable("user_role") {
    val userId = reference("user_id", Users)
    val roleId = reference("role_id", Roles)
    val entityId = reference("entity_id", Entities)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
    init {
        uniqueIndex(userId, roleId, entityId)
    }
}
