package dev.shibasis.reaktor.notification

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration

@Serializable
data class NotificationCategorySpec(
    val id: String,
    val displayName: String,
    val actions: List<NotificationActionSpec> = emptyList(),
    val android: AndroidCategoryOptions? = null,
    val ios: IosCategoryOptions? = null,
)

@Serializable
data class AndroidCategoryOptions(
    val channelGroupId: String? = null,
    val channelGroupName: String? = null,
    val description: String? = null,
    val importance: NotificationImportance = NotificationImportance.Default,
    val showBadge: Boolean = true,
    val sound: NotificationSound = NotificationSound.Default,
    val lights: NotificationLights? = null,
    val vibrationPatternMillis: List<Long> = emptyList(),
    val bypassDnd: Boolean = false,
    val lockscreenVisibility: NotificationVisibility = NotificationVisibility.Private,
    val allowBubbles: Boolean = false,
)

@Serializable
data class IosCategoryOptions(
    val hiddenPreviewsBodyPlaceholder: String? = null,
    val allowDismissAction: Boolean = true,
    val customDismissAction: Boolean = true,
)

/**
 * A button on a notification.
 *
 * [dismissesNotification] clears the notification once the action is handled, which is what a
 * one-shot button ("Not today", "Mark as read") almost always wants. Turn it off for an action
 * that keeps the notification alive on purpose — a snooze that re-posts, or a reply that stays
 * put while it sends. Android needs this because auto-cancel only covers a tap on the body of a
 * notification, never its buttons; iOS clears a notification on any action regardless, so the
 * flag has no effect there.
 */
@Serializable
data class NotificationActionSpec(
    val id: String,
    val title: String,
    val kind: NotificationActionKind = NotificationActionKind.Open,
    val route: NotificationRoute = NotificationRoute.None,
    val options: Set<NotificationActionOption> = emptySet(),
    val textInput: NotificationTextInputOptions? = null,
    val dismissesNotification: Boolean = true,
)

@Serializable
enum class NotificationActionKind {
    Open,
    Button,
    TextInput,
}

@Serializable
enum class NotificationActionOption {
    AuthenticationRequired,
    Destructive,
    Foreground,
}

@Serializable
enum class NotificationImportance {
    Min,
    Low,
    Default,
    High,
}

@Serializable
enum class NotificationPriority {
    Min,
    Low,
    Default,
    High,
    Max,
}

@Serializable
enum class NotificationVisibility {
    Public,
    Private,
    Secret,
}

@Serializable
enum class NotificationInterruptionLevel {
    Passive,
    Active,
    TimeSensitive,
    Critical,
}

@Serializable
sealed class NotificationSound {
    @Serializable
    data object Default : NotificationSound()

    @Serializable
    data object Silent : NotificationSound()

    @Serializable
    data class Named(val name: String, val critical: Boolean = false, val volume: Double = 1.0) : NotificationSound()
}

@Serializable
data class NotificationLights(
    val colorArgb: Long,
    val onMillis: Int = 500,
    val offMillis: Int = 1500,
)

@Serializable
data class NotificationTextInputOptions(
    val buttonTitle: String = "Send",
    val placeholder: String = "",
    val resultKey: String = "reaktor_notification_reply",
)

@Serializable
sealed class NotificationRoute {
    @Serializable
    data class OpenPath(val path: String) : NotificationRoute()

    @Serializable
    data class GraphAction(
        @SerialName("action_type")
        val type: String,
        val payloadJson: String = "{}",
    ) : NotificationRoute()

    @Serializable
    data object None : NotificationRoute()
}

@Serializable
data class NotificationContent(
    val title: String,
    val body: String,
    val subtitle: String? = null,
    val summary: String? = null,
    val imageUrl: String? = null,
    val threadId: String? = null,
    val groupId: String? = null,
    val sender: String? = null,
    val badge: Int? = null,
    val sound: NotificationSound = NotificationSound.Default,
    val data: Map<String, String> = emptyMap(),
)

@Serializable
data class NotificationEnvelope(
    val id: String,
    val type: String,
    val categoryId: String,
    val content: NotificationContent,
    val route: NotificationRoute = NotificationRoute.None,
    val correlationId: String? = null,
    val data: Map<String, String> = emptyMap(),
) {
    fun toDataMap(): Map<String, String> = buildMap {
        put("reaktor_notification_id", id)
        put("reaktor_notification_type", type)
        put("reaktor_category_id", categoryId)
        put("reaktor_title", content.title)
        put("reaktor_body", content.body)
        content.subtitle?.let { put("reaktor_subtitle", it) }
        content.summary?.let { put("reaktor_summary", it) }
        content.threadId?.let { put("reaktor_thread_id", it) }
        content.groupId?.let { put("reaktor_group_id", it) }
        content.sender?.let { put("reaktor_sender", it) }
        content.badge?.let { put("reaktor_badge", it.toString()) }
        correlationId?.let { put("reaktor_correlation_id", it) }
        when (route) {
            is NotificationRoute.OpenPath -> {
                put("reaktor_route_type", "open_path")
                put("reaktor_route", route.path)
            }
            is NotificationRoute.GraphAction -> {
                put("reaktor_route_type", "graph_action")
                put("reaktor_route", route.type)
                put("reaktor_route_payload", route.payloadJson)
            }
            NotificationRoute.None -> put("reaktor_route_type", "none")
        }
        data.forEach { (key, value) -> put("data_$key", value) }
    }

    companion object {
        fun fromDataMap(data: Map<String, String>): NotificationEnvelope {
            val route = when (data["reaktor_route_type"]) {
                "open_path" -> NotificationRoute.OpenPath(data["reaktor_route"].orEmpty())
                "graph_action" -> NotificationRoute.GraphAction(
                    type = data["reaktor_route"].orEmpty(),
                    payloadJson = data["reaktor_route_payload"] ?: "{}",
                )
                else -> NotificationRoute.None
            }
            return NotificationEnvelope(
                id = data["reaktor_notification_id"] ?: "local-${Clock.nowEpochMillis()}",
                type = data["reaktor_notification_type"] ?: "dev.synthetic",
                categoryId = data["reaktor_category_id"] ?: "system",
                content = NotificationContent(
                    title = data["reaktor_title"] ?: data["title"] ?: "Notification",
                    body = data["reaktor_body"] ?: data["body"] ?: "Notification",
                    subtitle = data["reaktor_subtitle"],
                    summary = data["reaktor_summary"],
                    threadId = data["reaktor_thread_id"],
                    groupId = data["reaktor_group_id"],
                    sender = data["reaktor_sender"],
                    badge = data["reaktor_badge"]?.toIntOrNull(),
                ),
                route = route,
                correlationId = data["reaktor_correlation_id"],
                data = data.filterKeys { it.startsWith("data_") }
                    .mapKeys { it.key.removePrefix("data_") },
            )
        }
    }
}

@Serializable
data class LocalNotificationRequest(
    val id: String,
    val categoryId: String,
    val content: NotificationContent,
    val route: NotificationRoute = NotificationRoute.None,
    val delay: Duration = Duration.ZERO,
    val trigger: NotificationTrigger = NotificationTrigger.Immediate,
    val priority: NotificationPriority = NotificationPriority.Default,
    val foreground: Boolean = false,
    val android: AndroidNotificationOptions? = null,
    val ios: IosNotificationOptions? = null,
)

@Serializable
sealed class NotificationTrigger {
    @Serializable
    data object Immediate : NotificationTrigger()

    @Serializable
    data class TimeInterval(val delay: Duration, val repeats: Boolean = false) : NotificationTrigger()

    @Serializable
    data class Calendar(
        val year: Int? = null,
        val month: Int? = null,
        val day: Int? = null,
        val hour: Int? = null,
        val minute: Int? = null,
        val second: Int? = null,
        val repeats: Boolean = false,
    ) : NotificationTrigger()
}

/**
 * Whether this trigger should fire again after it delivers.
 *
 * This is stricter than the raw `repeats` flag, because two shapes can never recur safely:
 * a calendar trigger pinned to an absolute date has exactly one valid firing time, and a
 * zero-length interval would re-fire in a tight loop. Both report `false` here whatever their
 * flag says, so schedulers can re-arm on this property alone.
 */
val NotificationTrigger.isRepeating: Boolean
    get() = when (this) {
        NotificationTrigger.Immediate -> false
        is NotificationTrigger.TimeInterval -> repeats && delay > Duration.ZERO
        is NotificationTrigger.Calendar ->
            repeats && year == null && month == null && day == null
    }

@Serializable
data class AndroidNotificationOptions(
    val channelId: String? = null,
    val smallIconName: String? = null,
    val priority: NotificationPriority? = null,
    val visibility: NotificationVisibility? = null,
    val category: String? = null,
    val groupKey: String? = null,
    val groupSummary: Boolean = false,
    val onlyAlertOnce: Boolean = false,
    val ongoing: Boolean = false,
    val autoCancel: Boolean = true,
    val timeoutAfterMillis: Long? = null,
    val progress: NotificationProgress? = null,
    val colorArgb: Long? = null,
    val actions: List<NotificationActionSpec> = emptyList(),
)

@Serializable
data class IosNotificationOptions(
    val threadId: String? = null,
    val targetContentId: String? = null,
    val launchImageName: String? = null,
    val interruptionLevel: NotificationInterruptionLevel = NotificationInterruptionLevel.Active,
    val relevanceScore: Double? = null,
    val attachments: List<NotificationAttachment> = emptyList(),
)

@Serializable
data class NotificationAttachment(
    val id: String,
    val url: String,
    val typeHint: String? = null,
)

@Serializable
data class NotificationProgress(
    val max: Int,
    val current: Int,
    val indeterminate: Boolean = false,
)

@Serializable
data class LocalNotificationId(val value: String)

@Serializable
data class NotificationResponseEvent(
    val notificationId: String,
    val categoryId: String,
    val actionId: String? = null,
    val route: NotificationRoute = NotificationRoute.None,
    val directReplyText: String? = null,
    val dismissed: Boolean = false,
    val data: Map<String, String> = emptyMap(),
    val source: NotificationEventSource = NotificationEventSource.Unknown,
)

@Serializable
enum class NotificationEventSource {
    Foreground,
    RemotePush,
    Local,
    Tap,
    Action,
    Dismiss,
    Unknown,
}
