package dev.shibasis.reaktor.core.adapters

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import kotlinx.serialization.Serializable


sealed class PermissionResult {
    data object Granted: PermissionResult()
    sealed class Denied(): PermissionResult() {
        data class Unknown(val error: Error): Denied()
        data object Once: Denied()
        data object Forever: Denied()
    }
}

object Permission {
    const val CAMERA = "CAMERA"
    const val LOCATION = "LOCATION"
    const val STORAGE = "STORAGE"
    const val GALLERY = "GALLERY"
    const val SPEECH_RECOGNITION = "SPEECH_RECOGINTION"
    const val NOTIFICATIONS = "NOTIFICATIONS"
}

@Serializable
enum class PermissionPlatform {
    Android,
    Ios,
    Jvm,
    Js,
    Unknown,
}

@Serializable
enum class NotificationPermissionState {
    Granted,
    Denied,
    NotDetermined,
    Provisional,
    Ephemeral,
    Unavailable,
}

@Serializable
data class NotificationPermissionStatus(
    val platform: PermissionPlatform,
    val state: NotificationPermissionState,
    val canRequest: Boolean,
    val appNotificationsEnabled: Boolean = state == NotificationPermissionState.Granted,
    val blockedCategories: List<String> = emptyList(),
    val detail: String = "",
)

@Serializable
data class NotificationPermissionOptions(
    val alert: Boolean = true,
    val badge: Boolean = true,
    val sound: Boolean = true,
    val provisional: Boolean = false,
    val criticalAlert: Boolean = false,
    val announcement: Boolean = false,
    val categoryIds: List<String> = emptyList(),
)

fun NotificationPermissionStatus.canDeliverNotifications(): Boolean =
    appNotificationsEnabled ||
        state == NotificationPermissionState.Granted ||
        state == NotificationPermissionState.Provisional ||
        state == NotificationPermissionState.Ephemeral

fun NotificationPermissionStatus.toPermissionResult(): PermissionResult =
    when {
        canDeliverNotifications() -> PermissionResult.Granted
        !canRequest -> PermissionResult.Denied.Forever
        else -> PermissionResult.Denied.Once
    }

fun PermissionResult.toNotificationPermissionStatus(
    platform: PermissionPlatform = PermissionPlatform.Unknown,
    detail: String = "",
): NotificationPermissionStatus =
    when (this) {
        PermissionResult.Granted ->
            NotificationPermissionStatus(
                platform = platform,
                state = NotificationPermissionState.Granted,
                canRequest = false,
                appNotificationsEnabled = true,
                detail = detail,
            )

        PermissionResult.Denied.Forever ->
            NotificationPermissionStatus(
                platform = platform,
                state = NotificationPermissionState.Denied,
                canRequest = false,
                appNotificationsEnabled = false,
                detail = detail,
            )

        PermissionResult.Denied.Once ->
            NotificationPermissionStatus(
                platform = platform,
                state = NotificationPermissionState.NotDetermined,
                canRequest = true,
                appNotificationsEnabled = false,
                detail = detail,
            )

        is PermissionResult.Denied.Unknown ->
            NotificationPermissionStatus(
                platform = platform,
                state = NotificationPermissionState.Unavailable,
                canRequest = false,
                appNotificationsEnabled = false,
                detail = error.message ?: detail,
            )
    }

interface NotificationPermissionClient {
    val permissionAdapter: PermissionAdapter<*>

    suspend fun getPermissions(options: NotificationPermissionOptions = NotificationPermissionOptions()): NotificationPermissionStatus =
        permissionAdapter.getNotificationPermissionStatus(options)

    suspend fun requestPermissions(options: NotificationPermissionOptions = NotificationPermissionOptions()): NotificationPermissionStatus =
        permissionAdapter.requestNotificationPermission(options)
}

abstract class PermissionAdapter<Controller>(controller: Controller): Adapter<Controller>(controller) {
    // requestAll is binary yes or no for all permissions
    abstract suspend fun request(vararg permissions: String): Boolean

    // You can also override this method for more granular permission handling
    open suspend fun requestOptional(vararg permissions: String): Map<String, PermissionResult> =
        permissions.associateWith { permission ->
            if (request(permission)) PermissionResult.Granted else PermissionResult.Denied.Once
        }

    open suspend fun getNotificationPermissionStatus(
        options: NotificationPermissionOptions = NotificationPermissionOptions(),
    ): NotificationPermissionStatus =
        PermissionResult.Denied.Unknown(Error("Notification permission status is not implemented"))
            .toNotificationPermissionStatus()

    open suspend fun requestNotificationPermission(
        options: NotificationPermissionOptions = NotificationPermissionOptions(),
    ): NotificationPermissionStatus =
        (requestOptional(Permission.NOTIFICATIONS)[Permission.NOTIFICATIONS]
            ?: PermissionResult.Denied.Unknown(Error("Notification permission request returned no result")))
            .toNotificationPermissionStatus()

    // Provide advice to add corresponding permissions to manifest/plist
    open fun logPermission(permission: String) {}
}

class InMemoryPermissionAdapter(
    initialPlatform: PermissionPlatform = PermissionPlatform.Unknown,
    initialNotificationState: NotificationPermissionState = NotificationPermissionState.NotDetermined,
) : PermissionAdapter<Unit>(Unit) {
    private var notificationStatus =
        NotificationPermissionStatus(
            platform = initialPlatform,
            state = initialNotificationState,
            canRequest = initialNotificationState == NotificationPermissionState.NotDetermined,
            appNotificationsEnabled = initialNotificationState.canDeliverNotifications(),
        )

    override suspend fun request(vararg permissions: String): Boolean =
        requestOptional(*permissions).values.all { it == PermissionResult.Granted }

    override suspend fun requestOptional(vararg permissions: String): Map<String, PermissionResult> {
        val requested = permissions.toList().ifEmpty { listOf(Permission.NOTIFICATIONS) }
        if (Permission.NOTIFICATIONS in requested) requestNotificationPermission()
        return requested.associateWith { permission ->
            when (permission) {
                Permission.NOTIFICATIONS -> notificationStatus.toPermissionResult()
                else -> PermissionResult.Denied.Unknown(Error("InMemoryPermissionAdapter does not handle $permission"))
            }
        }
    }

    override suspend fun getNotificationPermissionStatus(
        options: NotificationPermissionOptions,
    ): NotificationPermissionStatus =
        notificationStatus

    override suspend fun requestNotificationPermission(
        options: NotificationPermissionOptions,
    ): NotificationPermissionStatus {
        notificationStatus =
            notificationStatus.copy(
                state = NotificationPermissionState.Granted,
                canRequest = false,
                appNotificationsEnabled = true,
            )
        return notificationStatus
    }
}

private fun NotificationPermissionState.canDeliverNotifications(): Boolean =
    this == NotificationPermissionState.Granted ||
        this == NotificationPermissionState.Provisional ||
        this == NotificationPermissionState.Ephemeral

var Feature.Permission by CreateSlot<PermissionAdapter<*>>()
