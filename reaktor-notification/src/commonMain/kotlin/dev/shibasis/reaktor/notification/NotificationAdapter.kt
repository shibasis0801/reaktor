package dev.shibasis.reaktor.notification

import dev.shibasis.reaktor.core.adapters.NotificationPermissionClient
import dev.shibasis.reaktor.core.adapters.PermissionAdapter
import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature

abstract class NotificationAdapter<Controller>(
    controller: Controller,
    final override val permissionAdapter: PermissionAdapter<*>,
) : Adapter<Controller>(controller), NotificationsClient

var Feature.Notifications by CreateSlot<NotificationsClient>()

interface NotificationsClient : NotificationPermissionClient {
    val devHarness: NotificationDevHarness?
        get() = null

    suspend fun getPlatformCapabilities(): NotificationPlatformCapabilities
    suspend fun registerCategories(categories: List<NotificationCategorySpec>)
    suspend fun getDeviceToken(): DevicePushToken?
    suspend fun registerRemoteEndpoint(userId: String? = null): RegisterEndpointResult
    suspend fun unregisterRemoteEndpoint()
    suspend fun updatePreferences(command: UpdateNotificationPreferences)
    suspend fun scheduleLocal(request: LocalNotificationRequest): LocalNotificationId
    suspend fun getState(): NotificationRuntimeState =
        NotificationRuntimeState(
            platform = getPlatformCapabilities().platform,
            permission = getPermissions(),
            capabilities = getPlatformCapabilities(),
        )
    suspend fun setForegroundPresentation(policy: ForegroundPresentationPolicy) = Unit
    suspend fun setBadge(count: Int?) = Unit
    suspend fun clearDelivered(ids: List<String> = emptyList()) = Unit
    suspend fun cancelScheduled(ids: List<String> = emptyList()) = Unit
    suspend fun openSettings(target: NotificationSettingsTarget = NotificationSettingsTarget.App) = Unit
    fun addReceivedListener(listener: suspend (NotificationEnvelope) -> Unit): ListenerHandle
    fun addResponseListener(listener: suspend (NotificationResponseEvent) -> Unit): ListenerHandle
}

interface ListenerHandle {
    fun remove()
}

class SimpleListenerHandle(
    private val onRemove: () -> Unit,
) : ListenerHandle {
    override fun remove() = onRemove()
}
