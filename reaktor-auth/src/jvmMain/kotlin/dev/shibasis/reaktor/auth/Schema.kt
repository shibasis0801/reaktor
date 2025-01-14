package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.UserRoles.defaultExpression
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toKotlinInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.Date
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toKotlinUuid


inline fun LocalDateTime.Companion.now() = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
inline fun <reified T : Any> Table.jsonb(name: String): Column<T> = jsonb(name, { Json.encodeToString(serializer<T>(), it) }, { Json.decodeFromString(serializer<T>(), it) })

interface Data<T> {
    fun toDto(result: ResultRow): T
}

object Apps: LongIdTable("app"), Data<App> {
    val name = varchar("name", 100)
    val data = jsonb<JsonElement>("data")
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)


    override fun toDto(result: ResultRow) = App(
        id = result[id].value,
        name = result[name],
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt]
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

    override fun toDto(result: ResultRow) = Entity(
        id = result[id].value,
        name = result[name],
        data = result[data],
        appId = result[appId].value,
        createdAt = result[createdAt],
        updatedAt = result[updatedAt]
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

    override fun toDto(result: ResultRow) = User(
        id = result[id].value,
        name = result[name],
        socialId = result[socialId],
        appId = result[appId].value,
        data = result[data],
        createdAt = result[createdAt],
        updatedAt = result[updatedAt]
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

    override fun toDto(result: ResultRow) = Role(
        id = result[id].value,
        name = result[name],
        appId = result[appId].value,
        createdAt = result[createdAt],
        updatedAt = result[updatedAt]
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

    override fun toDto(result: ResultRow) = Permission(
        id = result[id].value,
        name = result[name],
        appId = result[appId].value,
        createdAt = result[createdAt],
        updatedAt = result[updatedAt]
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

object Sessions: UUIDTable("session"), Data<Session> {
    val userId = reference("user_id", Users)
    val appId = reference("app_id", Apps)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val expiresAt = datetime("expires_at")

    @OptIn(ExperimentalUuidApi::class)
    override fun toDto(result: ResultRow) = Session(
        id = result[id].value.toKotlinUuid(),
        userId = result[userId].value,
        appId = result[appId].value,
        createdAt = result[createdAt],
        expiresAt = result[expiresAt]
    )
}

object RoleBasedAuth {
    fun initialize(
                   url: String,
                   user: String,
                   password: String
    ): Result<Database> {
        return try {
            Result.success(Database.connect(url, user = user, password = password).also {
                transaction {
                    SchemaUtils.create(
                        Apps,
                        Entities,
                        Users,
                        Roles,
                        Permissions,
                        UserRoles,
                        RolePermissions,
                        Sessions
                    )
                }
            })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

fun Date.toKotlinDateTime() = toInstant().toKotlinInstant().toLocalDateTime(TimeZone.currentSystemDefault())
