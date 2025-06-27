package dev.shibasis.reaktor.auth

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.js.JsExport


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
    override var createdAt: Instant = Clock.System.now(),
    override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@JsExport
@Serializable
data class Entity(
    val id: String,
    val name: String,
    val appId: String,
    override var data: JsonElement,
    override var createdAt: Instant = Clock.System.now(),
    override var updatedAt: Instant = Clock.System.now()
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
@Serializable
data class User(
    val id: String,
    val name: String,
    val socialId: String,
    val appId: String,
    val provider: UserProvider,
    val status: UserStatus,
    override var data: JsonElement,
    override var createdAt: Instant = Clock.System.now(),
    override var updatedAt: Instant = Clock.System.now()
): AuditableDto


@JsExport
@Serializable
data class Role(
    val id: String,
    val name: String,
    val appId: String,
    override var data: JsonElement,
    override var createdAt: Instant = Clock.System.now(),
    override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@JsExport
@Serializable
data class Permission(
    val id: String,
    val name: String,
    val appId: String,
    override var data: JsonElement,
    override var createdAt: Instant = Clock.System.now(),
    override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@JsExport
@Serializable
data class Session(
    val id: String,
    val userId: String,
    val appId: String,
    val contextId: String,
    val expiresAt: Instant = Clock.System.now() ,
    override var data: JsonElement,
    override var createdAt: Instant = Clock.System.now(),
    override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@JsExport
@Serializable
data class Context(
    val id: String,
    val name: String,
    val appId: String,
    override var data: JsonElement,
    override var createdAt: Instant = Clock.System.now(),
    override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@JsExport
@Serializable
data class RolePermission(
    val id: String,
    val roleId: String,
    val permissionId: String,
    override var data: JsonElement,
    override var createdAt: Instant = Clock.System.now(),
    override var updatedAt: Instant = Clock.System.now()
): AuditableDto

@JsExport
@Serializable
data class UserRole(
    val id: String,
    val userId: String,
    val roleId: String,
    val contextId: String,
    override var data: JsonElement,
    override var createdAt: Instant = Clock.System.now(),
    override var updatedAt: Instant = Clock.System.now()
): AuditableDto
