package dev.shibasis.reaktor.auth

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

val JsonEmpty = JsonObject(mapOf())

interface Auditable {
    var data: JsonElement
    var createdAt: Instant
    var updatedAt: Instant
}

@Table("app", schema = "heimdall")
data class AppEntity(
    @Id val id: UUID,
    val name: String,

    override var data: JsonElement = JsonEmpty,
    @Column("created_at") override var createdAt: Instant = Instant.now(),
    @Column("updated_at") override var updatedAt: Instant = Instant.now()
) : Auditable

@Table("\"user\"", schema = "heimdall")
data class UserEntity(
    @Id val id: UUID,
    val name: String,
    @Column("social_id") val socialId: String,
    @Column("app_id") val appId: UUID,
    val provider: UserProvider,
    val status: UserStatus,

    override var data: JsonElement = JsonEmpty,
    @Column("created_at") override var createdAt: Instant = Instant.now(),
    @Column("updated_at") override var updatedAt: Instant = Instant.now()
) : Auditable

@Table("context", schema = "heimdall")
data class ContextEntity(
    @Id val id: UUID,
    val name: String,
    @Column("app_id") val appId: UUID,

    override var data: JsonElement = JsonEmpty,
    @Column("created_at") override var createdAt: Instant = Instant.now(),
    @Column("updated_at") override var updatedAt: Instant = Instant.now()
) : Auditable

@Table("role", schema = "heimdall")
data class RoleEntity(
    @Id val id: UUID,
    val name: String,
    @Column("app_id") val appId: UUID,

    override var data: JsonElement = JsonEmpty,
    @Column("created_at") override var createdAt: Instant = Instant.now(),
    @Column("updated_at") override var updatedAt: Instant = Instant.now()
) : Auditable

@Table("permission", schema = "heimdall")
data class PermissionEntity(
    @Id val id: UUID,
    val name: String,
    @Column("app_id") val appId: UUID,

    override var data: JsonElement = JsonEmpty,
    @Column("created_at") override var createdAt: Instant = Instant.now(),
    @Column("updated_at") override var updatedAt: Instant = Instant.now()
) : Auditable

@Table("role_permissions", schema = "heimdall")
data class RolePermissionEntity(
    @Id val id: UUID,
    @Column("role_id") val roleId: UUID,
    @Column("permission_id") val permissionId: UUID,

    override var data: JsonElement = JsonEmpty,
    @Column("created_at") override var createdAt: Instant = Instant.now(),
    @Column("updated_at") override var updatedAt: Instant = Instant.now()
) : Auditable

@Table("user_role", schema = "heimdall")
data class UserRoleEntity(
    @Id val id: UUID,
    @Column("user_id") val userId: UUID,
    @Column("role_id") val roleId: UUID,
    @Column("context_id") val contextId: UUID,

    override var data: JsonElement = JsonEmpty,
    @Column("created_at") override var createdAt: Instant = Instant.now(),
    @Column("updated_at") override var updatedAt: Instant = Instant.now()
) : Auditable

@Table("session", schema = "heimdall")
data class SessionEntity(
    @Id val id: UUID,
    @Column("user_id") val userId: UUID,
    @Column("app_id") val appId: UUID,
    @Column("context_id") val contextId: UUID,
    @Column("expires_at") val expiresAt: Instant = Instant.now(),

    override var data: JsonElement = JsonEmpty,
    @Column("created_at") override var createdAt: Instant = Instant.now(),
    @Column("updated_at") override var updatedAt: Instant = Instant.now()
) : Auditable
