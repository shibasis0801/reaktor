package dev.shibasis.reaktor.auth

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.js.JsExport


@JsExport
@Serializable
data class RowData(
    val data: JsonElement,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

@JsExport
@Serializable
data class App(
    val id: String,
    val name: String,
    val rowData: RowData
)

@JsExport
@Serializable
data class Entity(
    val id: String,
    val name: String,
    val appId: String,
    val rowData: RowData
)

@JsExport
enum class UserStatus {
    ONBOARDING,
    ACTIVE,
    INACTIVE,
    BANNED
}

@JsExport
enum class UserProvider {
    GOOGLE
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
    val rowData: RowData,
)


@JsExport
@Serializable
data class Role(
    val id: String,
    val name: String,
    val appId: String,
    val rowData: RowData,
)

@JsExport
@Serializable
data class Permission(
    val id: String,
    val name: String,
    val appId: String,
    val rowData: RowData,
)

@JsExport
@Serializable
data class Session(
    val id: String,
    val userId: String,
    val appId: String,
    val contextId: String,
    val expiresAt: LocalDateTime,
    val rowData: RowData,
)

@JsExport
@Serializable
data class Context(
    val id: String,
    val name: String,
    val appId: String,
    val rowData: RowData
)

@JsExport
@Serializable
data class RolePermission(
    val id: String,
    val roleId: String,
    val permissionId: String,
    val rowData: RowData
)

@JsExport
@Serializable
data class UserRole(
    val id: String,
    val userId: String,
    val roleId: String,
    val contextId: String,
    val rowData: RowData
)