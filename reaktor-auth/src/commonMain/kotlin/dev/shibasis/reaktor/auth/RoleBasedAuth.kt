package dev.shibasis.reaktor.auth

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class RowData(
    val data: JsonElement,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)


@Serializable
data class App(
    val id: Long,
    val name: String,
    val rowData: RowData
)

@Serializable
data class Entity(
    val id: Long,
    val name: String,
    val appId: Long,
    val rowData: RowData
)


enum class UserStatus {
    ONBOARDING,
    ACTIVE,
    INACTIVE,
    BANNED
}

enum class UserProvider {
    GOOGLE
}


@Serializable
data class User(
    val id: Long,
    val name: String,
    val socialId: String,
    val appId: Long,
    val provider: UserProvider,
    val status: UserStatus,
    val rowData: RowData,
)


@Serializable
data class Role(
    val id: Long,
    val name: String,
    val appId: Long,
    val rowData: RowData,
)

@Serializable
data class Permission(
    val id: Long,
    val name: String,
    val appId: Long,
    val rowData: RowData,
)

@Serializable
data class Session @OptIn(ExperimentalUuidApi::class) constructor(
    @Contextual val id: Uuid,
    val userId: Long,
    val appId: Long,
    val contextId: Long,
    val expiresAt: LocalDateTime,
    val rowData: RowData,
)


@Serializable
data class Context(
    val id: Long,
    val name: String,
    val appId: Long,
    val rowData: RowData
)

@Serializable
data class RolePermission(
    val id: Long,
    val roleId: Long,
    val permissionId: Long,
    val rowData: RowData
)

@Serializable
data class UserRole(
    val id: Long,
    val userId: Long,
    val roleId: Long,
    val contextId: Long,
    val rowData: RowData
)