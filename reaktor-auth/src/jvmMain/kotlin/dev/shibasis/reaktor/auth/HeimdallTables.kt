package dev.shibasis.reaktor.auth

import kotlinx.serialization.json.Json
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

const val JSON_EMPTY_STRING = "{}"

// DO NOT USE IN R2DBC REPOSITORIES, WILL GET FUCKED BY SPRING AOT
val JSON_EMPTY_OBJECT = Json.parseToJsonElement(JSON_EMPTY_STRING)


interface Auditable {
    var data: String
    var createdAt: Instant
    var updatedAt: Instant
}

@Table("heimdall.app")
data class AppEntity(
    @Id val id: UUID,
    val name: String,

    override var data: String = JSON_EMPTY_STRING,
    @Column("created_at") override var createdAt: Instant = Instant.now(),
    @Column("updated_at") override var updatedAt: Instant = Instant.now()
): Auditable

@Table("heimdall.users")
data class UserEntity(
    @Id val id: UUID,
    val name: String,
    @Column("social_id") val socialId: String,
    @Column("app_id") val appId: UUID,
    val provider: UserProvider,
    val status: UserStatus,

    override var data: String = JSON_EMPTY_STRING,
    @Column("created_at") override var createdAt: Instant = Instant.now(),
    @Column("updated_at") override var updatedAt: Instant = Instant.now()
): Auditable

@Table("heimdall.context")
data class ContextEntity(
    @Id val id: UUID,
    val name: String,
    @Column("app_id") val appId: UUID,

    override var data: String = JSON_EMPTY_STRING,
    @Column("created_at") override var createdAt: Instant = Instant.now(),
    @Column("updated_at") override var updatedAt: Instant = Instant.now()
): Auditable

@Table("heimdall.role")
data class RoleEntity(
    @Id val id: UUID,
    val name: String,
    @Column("app_id") val appId: UUID,

    override var data: String = JSON_EMPTY_STRING,
    @Column("created_at") override var createdAt: Instant = Instant.now(),
    @Column("updated_at") override var updatedAt: Instant = Instant.now()
): Auditable

@Table("heimdall.permission")
data class PermissionEntity(
    @Id val id: UUID,
    val name: String,
    @Column("app_id") val appId: UUID,

    override var data: String = JSON_EMPTY_STRING,
    @Column("created_at") override var createdAt: Instant = Instant.now(),
    @Column("updated_at") override var updatedAt: Instant = Instant.now()
): Auditable

@Table("heimdall.role_permissions")
data class RolePermissionEntity(
    @Id val id: UUID,
    @Column("role_id") val roleId: UUID,
    @Column("permission_id") val permissionId: UUID,

    override var data: String = JSON_EMPTY_STRING,
    @Column("created_at") override var createdAt: Instant = Instant.now(),
    @Column("updated_at") override var updatedAt: Instant = Instant.now()
): Auditable

@Table("heimdall.user_role")
data class UserRoleEntity(
    @Id val id: UUID,
    @Column("user_id") val userId: UUID,
    @Column("role_id") val roleId: UUID,
    @Column("context_id") val contextId: UUID,

    override var data: String = JSON_EMPTY_STRING,
    @Column("created_at") override var createdAt: Instant = Instant.now(),
    @Column("updated_at") override var updatedAt: Instant = Instant.now()
): Auditable

@Table("heimdall.session")
data class SessionEntity(
    @Id val id: UUID,
    @Column("user_id") val userId: UUID,
    @Column("app_id") val appId: UUID,
    @Column("context_id") val contextId: UUID,
    @Column("expires_at") val expiresAt: Instant = Instant.now(),

    override var data: String = JSON_EMPTY_STRING,
    @Column("created_at") override var createdAt: Instant = Instant.now(),
    @Column("updated_at") override var updatedAt: Instant = Instant.now()
): Auditable
