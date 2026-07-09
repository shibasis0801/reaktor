package dev.shibasis.reaktor.notification

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.js.JsExport

@JsExport
@Serializable
data class NotificationEndpointInput(
    val platform: String = "",
    val provider: String = "",
    val token: String = "",
    val projectId: String? = null,
    val deviceId: String? = null,
    val appId: String? = null,
    val appVersion: String? = null,
    val locale: String? = null,
    val permissionState: String? = null,
    val capabilities: List<String> = emptyList(),
)

@JsExport
@Serializable
data class NotificationEndpointRecord(
    val id: String,
    val userId: String,
    val platform: String,
    val provider: String,
    val tokenTail: String,
    val projectId: String? = null,
    val deviceId: String? = null,
    val permissionState: String? = null,
    val enabled: Boolean = true,
    val updatedAt: String = "",
)

@JsExport
@Serializable
data class NotificationPreferenceRecord(
    val userId: String,
    val categoryId: String,
    val enabled: Boolean,
    val updatedAt: String = "",
)

@JsExport
@Serializable
data class NotificationDeliveryRecord(
    val id: String,
    val userId: String,
    val endpointId: String,
    val status: String,
    val title: String,
    val body: String,
    val dryRun: Boolean = true,
    val createdAt: String = "",
)

object NotificationDeliveryStatuses {
    const val Queued = "queued"
    const val DryRunAccepted = "dry_run_accepted"
    const val Sent = "sent"
    const val UnsupportedProvider = "unsupported_provider"
    const val MissingProjectId = "missing_project_id"
}

@Serializable
data class NotificationDispatchPayload(
    val deliveryId: String,
    val notificationId: String,
    val endpointId: String,
    val userId: String,
    val platform: String,
    val provider: String,
    val token: String,
    val projectId: String? = null,
    val title: String,
    val body: String,
    val categoryId: String,
    val dryRun: Boolean,
    val type: String = "reaktor.notification.delivery",
    val route: NotificationRoute = NotificationRoute.None,
    val data: Map<String, String> = emptyMap(),
)

@Serializable
data class NotificationDispatchResult(
    val deliveryId: String,
    val notificationId: String,
    val endpointId: String,
    val status: String,
    val dispatched: Boolean,
    val dryRun: Boolean,
)

@Serializable
data class NotificationDispatchState(
    val deliveryId: String,
    val notificationId: String,
    val endpointId: String,
    val status: String,
    val dispatched: Boolean,
    val dryRun: Boolean,
    val title: String,
    val categoryId: String,
    val updatedAt: String,
)

@Serializable
data class NotificationDispatchStateSnapshot(
    val state: NotificationDispatchState? = null,
)

fun NotificationDispatchPayload.envelope(): NotificationEnvelope =
    NotificationEnvelope(
        id = notificationId,
        type = type,
        categoryId = categoryId,
        content = NotificationContent(
            title = title,
            body = body,
        ),
        route = route,
        correlationId = deliveryId,
        data = data + ("delivery_id" to deliveryId),
    )

fun NotificationDispatchPayload.providerData(): Map<String, String> =
    envelope().toDataMap()

fun NotificationDispatchPayload.fcmRequestBody(): String =
    buildJsonObject {
        put(
            "message",
            buildJsonObject {
                val providerData = providerData()
                put("token", token)
                put(
                    "notification",
                    buildJsonObject {
                        put("title", title)
                        put("body", body)
                    },
                )
                put(
                    "data",
                    buildJsonObject {
                        providerData.forEach { (key, value) -> put(key, value) }
                    },
                )
                put(
                    "android",
                    buildJsonObject {
                        put("priority", "HIGH")
                        put(
                            "notification",
                            buildJsonObject {
                                put("channel_id", categoryId)
                                put("tag", notificationId)
                                put("notification_priority", "PRIORITY_HIGH")
                                put("default_sound", true)
                            },
                        )
                    },
                )
                put(
                    "apns",
                    buildJsonObject {
                        put(
                            "headers",
                            buildJsonObject {
                                put("apns-priority", "10")
                            },
                        )
                        put(
                            "payload",
                            buildJsonObject {
                                put(
                                    "aps",
                                    buildJsonObject {
                                        put(
                                            "alert",
                                            buildJsonObject {
                                                put("title", title)
                                                put("body", body)
                                            },
                                        )
                                        put("category", categoryId)
                                        put("thread-id", data["chatId"] ?: categoryId)
                                        put("sound", "default")
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }.toString()

fun NotificationDispatchPayload.dispatchResult(
    status: String,
    dispatched: Boolean,
    dryRun: Boolean,
): NotificationDispatchResult =
    NotificationDispatchResult(
        deliveryId = deliveryId,
        notificationId = notificationId,
        endpointId = endpointId,
        status = status,
        dispatched = dispatched,
        dryRun = dryRun,
    )

fun NotificationDispatchResult.toState(
    payload: NotificationDispatchPayload,
    updatedAt: String,
): NotificationDispatchState =
    NotificationDispatchState(
        deliveryId = deliveryId,
        notificationId = notificationId,
        endpointId = endpointId,
        status = status,
        dispatched = dispatched,
        dryRun = dryRun,
        title = payload.title,
        categoryId = payload.categoryId,
        updatedAt = updatedAt,
    )
