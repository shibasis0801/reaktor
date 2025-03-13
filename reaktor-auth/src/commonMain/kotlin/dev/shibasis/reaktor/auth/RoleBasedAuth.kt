package dev.shibasis.reaktor.auth

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Contextual
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
    val id: Long,
    val name: String,
    val rowData: RowData
)

@JsExport
@Serializable
data class Entity(
    val id: Long,
    val name: String,
    val appId: Long,
    val rowData: RowData
)

@JsExport
enum class UserStatus {
    ONBOARDING,
    ACTIVE,
    INACTIVE,
    BANNED;

    object Serializer: kotlinx.serialization.KSerializer<UserStatus> {
        override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("status", kotlinx.serialization.descriptors.PrimitiveKind.STRING)
        override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: UserStatus) {
            encoder.encodeString(value.name)
        }
        override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): UserStatus {
            return UserStatus.valueOf(decoder.decodeString())
        }
    }
}

@JsExport
enum class UserProvider {
    GOOGLE
}


@JsExport
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


@JsExport
@Serializable
data class Role(
    val id: Long,
    val name: String,
    val appId: Long,
    val rowData: RowData,
)

@JsExport
@Serializable
data class Permission(
    val id: Long,
    val name: String,
    val appId: Long,
    val rowData: RowData,
)

@JsExport
@Serializable
data class Session @OptIn(ExperimentalUuidApi::class) constructor(
    @Contextual val id: Uuid,
    val userId: Long,
    val appId: Long,
    val contextId: Long,
    val expiresAt: LocalDateTime,
    val rowData: RowData,
)

@JsExport
@Serializable
data class Context(
    val id: Long,
    val name: String,
    val appId: Long,
    val rowData: RowData
)

@JsExport
@Serializable
data class RolePermission(
    val id: Long,
    val roleId: Long,
    val permissionId: Long,
    val rowData: RowData
)

@JsExport
@Serializable
data class UserRole(
    val id: Long,
    val userId: Long,
    val roleId: Long,
    val contextId: Long,
    val rowData: RowData
)