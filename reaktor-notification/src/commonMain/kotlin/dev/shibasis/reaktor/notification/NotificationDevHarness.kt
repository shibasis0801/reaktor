package dev.shibasis.reaktor.notification

import dev.shibasis.reaktor.core.adapters.NotificationPermissionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

@Serializable
data class NotificationDevState(
    val platform: NotificationPlatform,
    val permission: NotificationPermissionStatus,
    val capabilities: NotificationPlatformCapabilities,
    val categories: List<NotificationCategorySpec> = emptyList(),
    val token: DevicePushToken? = null,
    val lastReceived: NotificationEnvelope? = null,
    val lastResponse: NotificationResponseEvent? = null,
    val lastLocalNotificationId: String? = null,
    val workStatus: String = "idle",
    val platformSurfaceStatus: String = "idle",
) {
    val summary: String
        get() = listOfNotNull(
            "permission=${permission.state}",
            "categories=${categories.size}",
            token?.let { "token=${it.provider}:${it.value.takeLast(6)}" },
            lastReceived?.let { "received=${it.type}" },
            lastResponse?.let { "response=${it.actionId ?: "tap"}" },
            lastLocalNotificationId?.let { "local=$it" },
            "work=$workStatus",
            "surface=$platformSurfaceStatus",
        ).joinToString(" | ")
}

interface NotificationDevHarness {
    val state: StateFlow<NotificationDevState?>
    suspend fun refresh(): NotificationDevState
    suspend fun registerDefaultCategories(): NotificationDevState
    suspend fun requestPermission(): NotificationDevState
    suspend fun refreshToken(): NotificationDevState
    suspend fun sendLocal(): NotificationDevState
    suspend fun injectRemoteEnvelope(): NotificationDevState
    suspend fun simulateTap(): NotificationDevState
    suspend fun probePlatformSurface(): NotificationDevState
    fun markWorkStatus(status: String)
}

val DefaultNotificationCategories = listOf(
    NotificationCategorySpec(
        id = "messages",
        displayName = "Messages",
        actions = listOf(
            NotificationActionSpec(
                id = "open",
                title = "Open",
                route = NotificationRoute.None,
            ),
            NotificationActionSpec(
                id = "reply",
                title = "Reply",
                kind = NotificationActionKind.TextInput,
                route = NotificationRoute.None,
                textInput = NotificationTextInputOptions(
                    buttonTitle = "Send",
                    placeholder = "Reply",
                ),
            ),
        ),
        android = AndroidCategoryOptions(
            channelGroupId = "notifications",
            channelGroupName = "Notifications",
            description = "Messages, replies, and mentions.",
            importance = NotificationImportance.High,
            lights = NotificationLights(colorArgb = 0xFF66D9E8),
            vibrationPatternMillis = listOf(0, 80, 60, 120),
        ),
        ios = IosCategoryOptions(hiddenPreviewsBodyPlaceholder = "New message"),
    ),
    NotificationCategorySpec(
        id = "social",
        displayName = "Social",
        android = AndroidCategoryOptions(channelGroupId = "notifications", channelGroupName = "Notifications"),
    ),
    NotificationCategorySpec(
        id = "events",
        displayName = "Events",
        android = AndroidCategoryOptions(channelGroupId = "notifications", channelGroupName = "Notifications"),
    ),
    NotificationCategorySpec(
        id = "campaigns",
        displayName = "Campaigns",
        android = AndroidCategoryOptions(
            channelGroupId = "notifications",
            channelGroupName = "Notifications",
            importance = NotificationImportance.Low,
        ),
    ),
    NotificationCategorySpec(
        id = "system",
        displayName = "System",
        android = AndroidCategoryOptions(channelGroupId = "notifications", channelGroupName = "Notifications"),
    ),
)

open class BaseNotificationDevHarness(
    private val client: NotificationsClient,
    private val platform: NotificationPlatform,
) : NotificationDevHarness {
    private val mutableState = MutableStateFlow<NotificationDevState?>(null)
    override val state: StateFlow<NotificationDevState?> = mutableState

    private var categories: List<NotificationCategorySpec> = emptyList()
    private var lastReceived: NotificationEnvelope? = null
    private var lastResponse: NotificationResponseEvent? = null
    private var lastLocalNotificationId: String? = null
    private var token: DevicePushToken? = null
    private var workStatus: String = "idle"
    private var platformSurfaceStatus: String = "idle"

    override suspend fun refresh(): NotificationDevState {
        val snapshot = NotificationDevState(
            platform = platform,
            permission = client.getPermissions(),
            capabilities = client.getPlatformCapabilities(),
            categories = categories,
            token = token,
            lastReceived = lastReceived,
            lastResponse = lastResponse,
            lastLocalNotificationId = lastLocalNotificationId,
            workStatus = workStatus,
            platformSurfaceStatus = platformSurfaceStatus,
        )
        mutableState.value = snapshot
        return snapshot
    }

    override suspend fun registerDefaultCategories(): NotificationDevState {
        categories = DefaultNotificationCategories
        client.registerCategories(categories)
        return refresh()
    }

    override suspend fun requestPermission(): NotificationDevState {
        client.requestPermissions()
        return refresh()
    }

    override suspend fun refreshToken(): NotificationDevState {
        token = client.getDeviceToken()
        client.registerRemoteEndpoint()
        return refresh()
    }

    override suspend fun sendLocal(): NotificationDevState {
        val envelope = devEnvelope()
        lastLocalNotificationId = client.scheduleLocal(
            LocalNotificationRequest(
                id = envelope.id,
                categoryId = envelope.categoryId,
                content = envelope.content,
                route = envelope.route,
                delay = 1.seconds,
            ),
        ).value
        return refresh()
    }

    override suspend fun injectRemoteEnvelope(): NotificationDevState {
        lastReceived = devEnvelope(id = "dev-remote-${Clock.nowEpochMillis()}")
        return refresh()
    }

    override suspend fun simulateTap(): NotificationDevState {
        val envelope = lastReceived ?: devEnvelope()
        lastResponse = NotificationResponseEvent(
            notificationId = envelope.id,
            categoryId = envelope.categoryId,
            route = envelope.route,
            actionId = "open",
            source = NotificationEventSource.Tap,
        )
        return refresh()
    }

    override suspend fun probePlatformSurface(): NotificationDevState {
        categories = DefaultNotificationCategories
        client.registerCategories(categories)
        client.updatePreferences(UpdateNotificationPreferences(categoryId = "messages", enabled = true))
        client.setForegroundPresentation(ForegroundPresentationPolicy(alert = true, banner = true, list = true, sound = false, badge = true))
        client.setBadge(2)

        val immediate = devEnvelope(id = "dev-surface-${Clock.nowEpochMillis()}")
        lastLocalNotificationId = client.scheduleLocal(
            LocalNotificationRequest(
                id = immediate.id,
                categoryId = immediate.categoryId,
                content = immediate.content.copy(
                    title = "Reaktor notification surface",
                    body = "Actions, grouping, badge, cancel and clear paths are wired.",
                    summary = "SDK probe",
                    badge = 2,
                ),
                route = immediate.route,
                priority = NotificationPriority.High,
                android = AndroidNotificationOptions(
                    groupKey = "reaktor-dev",
                    actions = DefaultNotificationCategories.first().actions,
                    progress = NotificationProgress(max = 100, current = 42),
                ),
                ios = IosNotificationOptions(
                    threadId = "reaktor-dev",
                    interruptionLevel = NotificationInterruptionLevel.Active,
                    relevanceScore = 0.8,
                ),
            ),
        ).value

        val scheduledId = "dev-surface-pending-${Clock.nowEpochMillis()}"
        client.scheduleLocal(
            LocalNotificationRequest(
                id = scheduledId,
                categoryId = "system",
                content = NotificationContent("Reaktor pending probe", "This notification is scheduled then cancelled."),
                delay = 30.seconds,
                trigger = NotificationTrigger.TimeInterval(30.seconds),
            ),
        )
        client.cancelScheduled(listOf(scheduledId))
        client.clearDelivered(listOf(immediate.id))
        client.setBadge(null)
        platformSurfaceStatus = "probed"
        return refresh()
    }

    override fun markWorkStatus(status: String) {
        workStatus = status
        mutableState.update { current -> current?.copy(workStatus = status) }
    }

    protected fun recordReceived(envelope: NotificationEnvelope) {
        lastReceived = envelope
    }

    protected fun recordResponse(event: NotificationResponseEvent) {
        lastResponse = event
    }

    protected fun recordTokenForState(token: DevicePushToken?) {
        this.token = token
        mutableState.update { current -> current?.copy(token = token) }
    }

    private fun devEnvelope(id: String = "dev-local-${Clock.nowEpochMillis()}") = NotificationEnvelope(
        id = id,
        type = "reaktor.dev.notification",
        categoryId = "messages",
        content = NotificationContent(
            title = "Reaktor notification test",
            body = "Tap should dispatch reaktor.notification.open",
            threadId = "dev",
        ),
        route = NotificationRoute.GraphAction("reaktor.notification.open", "{}"),
        correlationId = "dev-${Clock.nowEpochMillis()}",
    )
}
