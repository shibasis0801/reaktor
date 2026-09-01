package dev.shibasis.reaktor.notification

import dev.shibasis.reaktor.core.adapters.NotificationPermissionStatus
import dev.shibasis.reaktor.core.adapters.PermissionPlatform
import kotlinx.serialization.Serializable

@Serializable
enum class NotificationPlatform {
    Android,
    Ios,
    Jvm,
    Js,
    Unknown,
}

fun NotificationPlatform.toPermissionPlatform(): PermissionPlatform =
    when (this) {
        NotificationPlatform.Android -> PermissionPlatform.Android
        NotificationPlatform.Ios -> PermissionPlatform.Ios
        NotificationPlatform.Jvm -> PermissionPlatform.Jvm
        NotificationPlatform.Js -> PermissionPlatform.Js
        NotificationPlatform.Unknown -> PermissionPlatform.Unknown
    }

fun PermissionPlatform.toNotificationPlatform(): NotificationPlatform =
    when (this) {
        PermissionPlatform.Android -> NotificationPlatform.Android
        PermissionPlatform.Ios -> NotificationPlatform.Ios
        PermissionPlatform.Jvm -> NotificationPlatform.Jvm
        PermissionPlatform.Js -> NotificationPlatform.Js
        PermissionPlatform.Unknown -> NotificationPlatform.Unknown
    }

@Serializable
data class DevicePushToken(
    val provider: String,
    val value: String,
    val projectId: String? = null,
    val deviceId: String? = null,
)

@Serializable
data class RegisterEndpointResult(
    val endpointId: String,
    val registered: Boolean,
    val detail: String = "",
)

@Serializable
data class UpdateNotificationPreferences(
    val categoryId: String,
    val enabled: Boolean,
)

@Serializable
data class NotificationRuntimeState(
    val platform: NotificationPlatform,
    val permission: NotificationPermissionStatus,
    val capabilities: NotificationPlatformCapabilities,
    val categories: List<NotificationCategorySpec> = emptyList(),
    val pendingLocalIds: List<String> = emptyList(),
    val deliveredIds: List<String> = emptyList(),
    val badge: Int? = null,
    val foregroundPresentation: ForegroundPresentationPolicy = ForegroundPresentationPolicy(),
    val preferences: Map<String, Boolean> = emptyMap(),
    val detail: String = "",
)

@Serializable
data class ForegroundPresentationPolicy(
    val alert: Boolean = true,
    val banner: Boolean = true,
    val list: Boolean = true,
    val sound: Boolean = true,
    val badge: Boolean = true,
)

@Serializable
sealed class NotificationSettingsTarget {
    @Serializable
    data object App : NotificationSettingsTarget()

    @Serializable
    data class Category(val categoryId: String) : NotificationSettingsTarget()

    /**
     * Where the user allows this app to schedule alarms that land on time.
     *
     * Android only, and the only route: the right behind [NotificationSchedulingFeature.
     * ExactDelivery] cannot be requested with a permission dialog, just a settings page the app
     * sends the user to. Elsewhere this opens the ordinary notification settings.
     */
    @Serializable
    data object ExactAlarms : NotificationSettingsTarget()
}

@Serializable
enum class NotificationAuthorizationMode {
    RuntimePermission,
    Standard,
    Provisional,
    Ephemeral,
    AppSettings,
    ChannelSettings,
}

@Serializable
enum class NotificationPresentationFeature {
    Alert,
    Banner,
    List,
    Sound,
    Badge,
    ForegroundPresentation,
    Grouping,
    Conversation,
    RichMedia,
    Progress,
}

@Serializable
enum class NotificationActionFeature {
    Tap,
    ActionButton,
    Dismiss,
    DirectReply,
    TextInput,
}

@Serializable
enum class NotificationSchedulingFeature {
    LocalImmediate,
    LocalDelayed,
    LocalCalendar,
    RemotePush,
    BackgroundSync,
    CancelScheduled,
    DeliveredInbox,

    /**
     * Scheduled notifications will actually arrive at the time they were asked for.
     *
     * Reported rather than assumed because on Android it is a right the user grants and can take
     * away at any moment, so it is a fact about right now, not about the build. Absent, a request
     * asking for [NotificationPrecision.Exact] still gets scheduled -- it simply lands inside a
     * batching window, which is currently about an hour wide.
     */
    ExactDelivery,
}

@Serializable
enum class NotificationExtensionFeature {
    ServiceExtension,
    ContentExtension,
    LiveActivity,
    ForegroundService,
    FullScreenIntent,
    Bubble,
}

@Serializable
data class NotificationPlatformCapabilities(
    val platform: NotificationPlatform,
    val authorizationModes: Set<NotificationAuthorizationMode> = emptySet(),
    val presentationFeatures: Set<NotificationPresentationFeature> = emptySet(),
    val actionFeatures: Set<NotificationActionFeature> = emptySet(),
    val schedulingFeatures: Set<NotificationSchedulingFeature> = emptySet(),
    val extensionFeatures: Set<NotificationExtensionFeature> = emptySet(),
)
