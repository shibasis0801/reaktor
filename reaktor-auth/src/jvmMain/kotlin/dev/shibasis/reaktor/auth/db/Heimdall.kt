package dev.shibasis.reaktor.auth.db

import dev.shibasis.reaktor.auth.UserProvider
import dev.shibasis.reaktor.auth.UserStatus
import dev.shibasis.reaktor.auth.framework.LongAuditable
import dev.shibasis.reaktor.auth.framework.UUIDAuditable
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime


object Apps: LongAuditable("app") {
    val name = varchar("name", 100)
}

object Users: LongAuditable("user") {
    val name = varchar("name", 100)
    val socialId = text("social_id")
    val appId = foreignKey(Apps,"app_id")
    val provider = enumerationByName<UserProvider>("provider", 20).default(UserProvider.GOOGLE)
    val status = enumerationByName<UserStatus>("status", 50).default(UserStatus.ONBOARDING)
}

object Contexts: LongAuditable("context") {
    val name = varchar("name", 100)
    val appId = foreignKey(Apps,"app_id")
}

object Roles: LongAuditable("role")  {
    val name = varchar("name", 50)
    val appId = foreignKey(Apps,"app_id")
}

object Permissions: LongAuditable("permission") {
    val name = varchar("name", 100)
    val appId = foreignKey(Apps,"app_id")
}

object RolePermissions: LongAuditable("role_permissions") {
    val roleId = foreignKey(Roles,"role_id")
    val permissionId = foreignKey(Permissions, "permission_id")
}

object UserRoles: LongAuditable("user_role") {
    val userId = foreignKey(Users, "user_id")
    val roleId = foreignKey(Roles,"role_id")
    val contextId = foreignKey(Contexts, "context_id")
}

object Sessions: UUIDAuditable("session") {
    val userId = foreignKey(Users, "user_id")
    val appId = foreignKey(Apps,"app_id")
    val contextId = foreignKey(Contexts, "context_id")
    val expiresAt = datetime("expires_at")
}
