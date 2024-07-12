package dev.shibasis.reaktor.auth


import kotlinx.datetime.Clock
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import java.util.UUID

// https://www.youtube.com/watch?v=b6VhN_HHDiQ&t=2827s

// Each app also should have a set of permissions based on their payment status
object Apps: IdTable<Int>("apps") {
    override val id = integer("id").autoIncrement().entityId()
    override val primaryKey = PrimaryKey(id)

    val createdAt = timestamp("created_at").default(Clock.System.now())
    val updatedAt = timestamp("updated_at").default(Clock.System.now())

    val name = varchar("name", 50)
    // this is for downstream to configure.
//    val data = json<String>("data", Json)
}

object Users : IdTable<UUID>("users") {
    override val id = uuid("id").entityId()
    override val primaryKey = PrimaryKey(id)

    val appId = integer("app_id").references(Apps.id)
    val email = varchar("email", 50)
    val name = varchar("name", 50)

    // appId = 0 implies ovd user, but this needs RLS and other checks
    val createdAt = timestamp("created_at").default(Clock.System.now())
    val updatedAt = timestamp("updated_at").default(Clock.System.now())
}

object Roles : IdTable<Int>("roles") {
    override val id = integer("id").autoIncrement().entityId()
    override val primaryKey = PrimaryKey(id)

    val name = varchar("name", 50)
    val appId = integer("app_id").references(Apps.id)
    // could be enum
    val permissions = array<String>("permissions")
}


object UserRoles : Table("user_roles") {
    val userId = uuid("user_id").references(Users.id)
    val roleId = integer("role_id").references(Roles.id)
    val appId = integer("app_id").references(Apps.id)
    override val primaryKey = PrimaryKey(arrayOf(userId, roleId, appId))
}