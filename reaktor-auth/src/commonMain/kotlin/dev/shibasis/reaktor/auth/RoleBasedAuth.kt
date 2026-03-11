package dev.shibasis.reaktor.auth

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.js.JsExport
import kotlin.time.Clock
import kotlin.time.Instant


@JsExport
interface AuditableDto {
    var data: JsonElement
    var createdAt: Instant
    var updatedAt: Instant
}

@JsExport
@Serializable
data class App(
    val id: String,
    val name: String,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@JsExport
@Serializable
data class Entity(
    val id: String,
    val name: String,
    val appId: String,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@JsExport
enum class UserStatus {
    ONBOARDING,
    ACTIVE,
    INACTIVE,
    BANNED
}

@JsExport
enum class UserProvider {
    GOOGLE,
    APPLE
}

@JsExport
enum class AccountType {
    USER,
    SERVICE_ACCOUNT
}

@JsExport
@Serializable
data class User(
    val id: String,
    val name: String,
    val socialId: String?,
    val appId: String,
    val provider: UserProvider?,
    val status: UserStatus,
    val accountType: AccountType = AccountType.USER,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto


@JsExport
@Serializable
data class Role(
    val id: String,
    val name: String,
    val appId: String,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@JsExport
@Serializable
data class Permission(
    val id: String,
    val name: String,
    val appId: String,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@JsExport
@Serializable
data class Session(
    val id: String,
    val userId: String,
    val appId: String,
    val contextId: String,
    @Contextual val expiresAt: Instant = Clock.System.now() ,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@JsExport
@Serializable
data class Context(
    val id: String,
    val name: String,
    val appId: String,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@JsExport
@Serializable
data class RolePermission(
    val id: String,
    val roleId: String,
    val permissionId: String,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@JsExport
@Serializable
data class UserRole(
    val id: String,
    val userId: String,
    val roleId: String,
    val contextId: String,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@JsExport
@Serializable
data class PersonalAccessToken(
    val id: String,
    val userId: String?,
    val name: String,
    val tokenHash: String,
    val scopes: String,
    val contextId: String?,
    @Contextual val expiresAt: Instant? = null,
    @Contextual val lastUsedAt: Instant? = null,
    @Contextual val revokedAt: Instant? = null,
    override var data: JsonElement,
    @Contextual override var createdAt: Instant = Clock.System.now(),
    @Contextual override var updatedAt: Instant = Clock.System.now()
): AuditableDto
