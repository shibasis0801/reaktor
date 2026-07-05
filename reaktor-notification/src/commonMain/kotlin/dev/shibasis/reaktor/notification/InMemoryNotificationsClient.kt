package dev.shibasis.reaktor.notification

import dev.shibasis.reaktor.core.adapters.InMemoryPermissionAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration

class NotificationEventHub {
    private val receivedListeners = MutableStateFlow<List<suspend (NotificationEnvelope) -> Unit>>(emptyList())
    private val responseListeners = MutableStateFlow<List<suspend (NotificationResponseEvent) -> Unit>>(emptyList())

    fun addReceivedListener(listener: suspend (NotificationEnvelope) -> Unit): ListenerHandle {
        receivedListeners.update { it + listener }
        return SimpleListenerHandle { receivedListeners.update { listeners -> listeners - listener } }
    }

    fun addResponseListener(listener: suspend (NotificationResponseEvent) -> Unit): ListenerHandle {
        responseListeners.update { it + listener }
        return SimpleListenerHandle { responseListeners.update { listeners -> listeners - listener } }
    }

    suspend fun emitReceived(envelope: NotificationEnvelope) {
        receivedListeners.value.forEach { it(envelope) }
    }

    suspend fun emitResponse(event: NotificationResponseEvent) {
        responseListeners.value.forEach { it(event) }
    }
}

class InMemoryNotificationsClient(
    initialPlatform: NotificationPlatform = NotificationPlatform.Unknown,
    private val inMemoryPermissions: InMemoryPermissionAdapter =
        InMemoryPermissionAdapter(initialPlatform.toPermissionPlatform()),
) : NotificationAdapter<Unit>(Unit, inMemoryPermissions) {
    private val events = NotificationEventHub()
    private var categories: List<NotificationCategorySpec> = emptyList()
    private var token: DevicePushToken? = null
    private var badge: Int? = null
    private var foregroundPresentation = ForegroundPresentationPolicy()
    private val pendingIds = mutableListOf<String>()
    private val deliveredIds = mutableListOf<String>()
    private val preferences = mutableMapOf<String, Boolean>()
    var lastLocal: LocalNotificationRequest? = null
        private set

    override suspend fun getPlatformCapabilities(): NotificationPlatformCapabilities =
        NotificationPlatformCapabilities(
            platform = inMemoryPermissions.getNotificationPermissionStatus().platform.toNotificationPlatform(),
            authorizationModes = setOf(
                NotificationAuthorizationMode.RuntimePermission,
                NotificationAuthorizationMode.AppSettings,
                NotificationAuthorizationMode.ChannelSettings,
            ),
            presentationFeatures = setOf(
                NotificationPresentationFeature.Alert,
                NotificationPresentationFeature.Sound,
                NotificationPresentationFeature.Badge,
                NotificationPresentationFeature.ForegroundPresentation,
                NotificationPresentationFeature.Grouping,
                NotificationPresentationFeature.RichMedia,
                NotificationPresentationFeature.Progress,
            ),
            actionFeatures = setOf(
                NotificationActionFeature.Tap,
                NotificationActionFeature.ActionButton,
                NotificationActionFeature.DirectReply,
                NotificationActionFeature.Dismiss,
            ),
            schedulingFeatures = setOf(
                NotificationSchedulingFeature.LocalImmediate,
                NotificationSchedulingFeature.LocalDelayed,
                NotificationSchedulingFeature.LocalCalendar,
                NotificationSchedulingFeature.CancelScheduled,
                NotificationSchedulingFeature.DeliveredInbox,
            ),
        )

    override suspend fun registerCategories(categories: List<NotificationCategorySpec>) {
        this.categories = categories
    }

    override suspend fun getDeviceToken(): DevicePushToken? {
        if (token == null) token = DevicePushToken("memory", "memory-token")
        return token
    }

    override suspend fun registerRemoteEndpoint(userId: String?): RegisterEndpointResult =
        RegisterEndpointResult("memory-endpoint", registered = getDeviceToken() != null)

    override suspend fun unregisterRemoteEndpoint() {
        token = null
    }

    override suspend fun updatePreferences(command: UpdateNotificationPreferences) {
        preferences[command.categoryId] = command.enabled
    }

    override suspend fun scheduleLocal(request: LocalNotificationRequest): LocalNotificationId {
        lastLocal = request
        if (request.delay > Duration.ZERO || request.trigger !is NotificationTrigger.Immediate) {
            pendingIds += request.id
        } else {
            deliveredIds += request.id
        }
        return LocalNotificationId(request.id)
    }

    override suspend fun getState(): NotificationRuntimeState =
        NotificationRuntimeState(
            platform = inMemoryPermissions.getNotificationPermissionStatus().platform.toNotificationPlatform(),
            permission = getPermissions(),
            capabilities = getPlatformCapabilities(),
            categories = categories,
            pendingLocalIds = pendingIds.toList(),
            deliveredIds = deliveredIds.toList(),
            badge = badge,
            foregroundPresentation = foregroundPresentation,
            preferences = preferences.toMap(),
        )

    override suspend fun setForegroundPresentation(policy: ForegroundPresentationPolicy) {
        foregroundPresentation = policy
    }

    override suspend fun setBadge(count: Int?) {
        badge = count?.coerceAtLeast(0)
    }

    override suspend fun clearDelivered(ids: List<String>) {
        if (ids.isEmpty()) deliveredIds.clear() else deliveredIds.removeAll(ids.toSet())
    }

    override suspend fun cancelScheduled(ids: List<String>) {
        if (ids.isEmpty()) pendingIds.clear() else pendingIds.removeAll(ids.toSet())
    }

    override fun addReceivedListener(listener: suspend (NotificationEnvelope) -> Unit): ListenerHandle =
        events.addReceivedListener(listener)

    override fun addResponseListener(listener: suspend (NotificationResponseEvent) -> Unit): ListenerHandle =
        events.addResponseListener(listener)
}
