package dev.shibasis.reaktor.notification

import dev.shibasis.reaktor.core.adapters.DarwinPermissionAdapter
import dev.shibasis.reaktor.core.adapters.NotificationPermissionOptions
import dev.shibasis.reaktor.core.adapters.NotificationPermissionStatus
import dev.shibasis.reaktor.core.framework.Dispatch
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationDismissActionIdentifier
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationCategoryOptionCustomDismissAction
import platform.UserNotifications.UNNotificationDefaultActionIdentifier
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNTextInputNotificationResponse
import platform.UserNotifications.UNUserNotificationCenter
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import kotlin.coroutines.resume
import kotlin.math.absoluteValue

class IosNotificationsClient : NotificationAdapter<Unit>(Unit, DarwinPermissionAdapter()) {
    private val center = UNUserNotificationCenter.currentNotificationCenter()
    private val events = NotificationEventHub()
    private var categories: List<NotificationCategorySpec> = emptyList()
    private var token: DevicePushToken? = null
    private var apnsTokenLength: ULong? = null
    private var foregroundPresentation = ForegroundPresentationPolicy()
    private var badgeCount: Int? = null
    private val preferences = mutableMapOf<String, Boolean>()
    private val scheduledIds = mutableSetOf<String>()
    private val deliveredIds = mutableSetOf<String>()

    override val devHarness: IosNotificationDevHarness = IosNotificationDevHarness(this)

    init {
        IosNotificationsRuntime.install(this)
    }

    override suspend fun requestPermissions(options: NotificationPermissionOptions): NotificationPermissionStatus =
        permissionAdapter.requestNotificationPermission(options).also { status ->
            if (status.appNotificationsEnabled) IosNotificationsRuntime.startRemoteTransport()
        }

    override suspend fun getPlatformCapabilities(): NotificationPlatformCapabilities =
        NotificationPlatformCapabilities(
            platform = NotificationPlatform.Ios,
            authorizationModes = setOf(
                NotificationAuthorizationMode.Standard,
                NotificationAuthorizationMode.Provisional,
                NotificationAuthorizationMode.AppSettings,
            ),
            presentationFeatures = setOf(
                NotificationPresentationFeature.Alert,
                NotificationPresentationFeature.Banner,
                NotificationPresentationFeature.List,
                NotificationPresentationFeature.Sound,
                NotificationPresentationFeature.Badge,
                NotificationPresentationFeature.ForegroundPresentation,
                NotificationPresentationFeature.Grouping,
                NotificationPresentationFeature.RichMedia,
            ),
            actionFeatures = setOf(
                NotificationActionFeature.Tap,
                NotificationActionFeature.ActionButton,
                NotificationActionFeature.Dismiss,
                NotificationActionFeature.TextInput,
            ),
            schedulingFeatures = setOf(
                NotificationSchedulingFeature.LocalImmediate,
                NotificationSchedulingFeature.LocalDelayed,
                NotificationSchedulingFeature.LocalCalendar,
                NotificationSchedulingFeature.RemotePush,
                NotificationSchedulingFeature.BackgroundSync,
                NotificationSchedulingFeature.CancelScheduled,
                NotificationSchedulingFeature.DeliveredInbox,
            ),
            extensionFeatures = setOf(
                NotificationExtensionFeature.ServiceExtension,
                NotificationExtensionFeature.ContentExtension,
                NotificationExtensionFeature.LiveActivity,
            ),
        )

    override suspend fun registerCategories(categories: List<NotificationCategorySpec>) {
        this.categories = categories
        val nativeCategories = categories.map { category ->
            UNNotificationCategory.categoryWithIdentifier(
                identifier = category.id,
                actions = category.actions.map(::toNativeAction),
                intentIdentifiers = emptyList<Any>(),
                options = if (category.ios?.allowDismissAction == false) 0u else UNNotificationCategoryOptionCustomDismissAction,
            )
        }.toSet()
        onMainQueue {
            center.setNotificationCategories(nativeCategories)
        }
    }

    override suspend fun getDeviceToken(): DevicePushToken? {
        if (token != null) return token
        if (getPermissions().appNotificationsEnabled) {
            IosNotificationsRuntime.startRemoteTransport()
            repeat(20) {
                token?.let { return it }
                delay(250)
            }
        }
        return token
    }

    fun recordApnsToken(token: NSData) {
        apnsTokenLength = token.length
    }

    fun recordFcmToken(value: String?) {
        token = value?.let { DevicePushToken(provider = "fcm", value = it) }
        devHarness.recordToken(token, apnsTokenLength)
    }

    override suspend fun registerRemoteEndpoint(userId: String?): RegisterEndpointResult =
        RegisterEndpointResult(
            endpointId = token?.value?.let { "ios-fcm-${it.hashCode().absoluteValue}" } ?: "ios-fcm-unavailable",
            registered = token != null,
            detail = if (token == null) "No FCM token available" else "Local endpoint probe only",
        )

    override suspend fun unregisterRemoteEndpoint() {
        token = null
    }

    override suspend fun updatePreferences(command: UpdateNotificationPreferences) {
        preferences[command.categoryId] = command.enabled
    }

    override suspend fun scheduleLocal(request: LocalNotificationRequest): LocalNotificationId =
        suspendCancellableCoroutine { continuation ->
            onMainQueue {
                val content = UNMutableNotificationContent().apply {
                    setTitle(request.content.title)
                    setBody(request.content.body)
                    setSubtitle(request.content.subtitle ?: "")
                    setCategoryIdentifier(request.categoryId)
                    setThreadIdentifier(request.ios?.threadId ?: request.content.threadId ?: request.categoryId)
                    request.ios?.targetContentId?.let { setTargetContentIdentifier(it) }
                    request.ios?.launchImageName?.let { setLaunchImageName(it) }
                    request.content.summary?.let { setSummaryArgument(it) }
                    setSound(request.content.sound.toIosSound())
                    request.ios?.relevanceScore?.let { setRelevanceScore(it) }
                    setUserInfo(notificationUserInfo(request))
                    val nativeAttachments = request.ios?.attachments.orEmpty().mapNotNull(::nativeAttachment)
                    if (nativeAttachments.isNotEmpty()) setAttachments(nativeAttachments)
                }
                val trigger = request.toIosTrigger()
                val nativeRequest = UNNotificationRequest.requestWithIdentifier(
                    identifier = request.id,
                    content = content,
                    trigger = trigger,
                )
                center.addNotificationRequest(nativeRequest) {
                    if (trigger == null) deliveredIds += request.id else scheduledIds += request.id
                    if (continuation.isActive) {
                        continuation.resume(LocalNotificationId(request.id))
                    }
                }
            }
        }

    override suspend fun getState(): NotificationRuntimeState =
        NotificationRuntimeState(
            platform = NotificationPlatform.Ios,
            permission = getPermissions(),
            capabilities = getPlatformCapabilities(),
            categories = categories,
            pendingLocalIds = scheduledIds.toList(),
            deliveredIds = deliveredIds.toList(),
            badge = badgeCount,
            foregroundPresentation = foregroundPresentation,
            preferences = preferences.toMap(),
            detail = "apnsTokenLength=${apnsTokenLength ?: 0u}",
        )

    override suspend fun setForegroundPresentation(policy: ForegroundPresentationPolicy) {
        foregroundPresentation = policy
    }

    override suspend fun setBadge(count: Int?) {
        badgeCount = count?.coerceAtLeast(0)
        onMainQueue {
            UIApplication.sharedApplication.applicationIconBadgeNumber = (badgeCount ?: 0).toLong()
        }
    }

    override suspend fun clearDelivered(ids: List<String>) {
        onMainQueue {
            if (ids.isEmpty()) {
                center.removeAllDeliveredNotifications()
                deliveredIds.clear()
            } else {
                center.removeDeliveredNotificationsWithIdentifiers(ids)
                deliveredIds.removeAll(ids.toSet())
            }
        }
    }

    override suspend fun cancelScheduled(ids: List<String>) {
        onMainQueue {
            if (ids.isEmpty()) {
                center.removeAllPendingNotificationRequests()
                scheduledIds.clear()
            } else {
                center.removePendingNotificationRequestsWithIdentifiers(ids)
                scheduledIds.removeAll(ids.toSet())
            }
        }
    }

    override suspend fun openSettings(target: NotificationSettingsTarget) {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        onMainQueue {
            UIApplication.sharedApplication.openURL(url)
        }
    }

    override fun addReceivedListener(listener: suspend (NotificationEnvelope) -> Unit): ListenerHandle =
        events.addReceivedListener(listener)

    override fun addResponseListener(listener: suspend (NotificationResponseEvent) -> Unit): ListenerHandle =
        events.addResponseListener(listener)

    fun willPresent(notification: UNNotification): UNNotificationPresentationOptions {
        val envelope = NotificationEnvelope.fromDataMap(notification.request.content.userInfo.toStringMap())
        Dispatch.Default.launch {
            events.emitReceived(envelope)
            devHarness.recordReceivedFromPlatform(envelope)
        }
        return foregroundPresentation.toIosPresentationOptions()
    }

    fun didReceive(response: UNNotificationResponse) {
        val envelope = NotificationEnvelope.fromDataMap(response.notification.request.content.userInfo.toStringMap())
        val event = NotificationResponseEvent(
            notificationId = envelope.id,
            categoryId = envelope.categoryId,
            actionId = response.actionIdentifier,
            route = envelope.route,
            directReplyText = (response as? UNTextInputNotificationResponse)?.userText,
            dismissed = response.actionIdentifier == UNNotificationDismissActionIdentifier,
            data = envelope.data,
            source = when (response.actionIdentifier) {
                UNNotificationDefaultActionIdentifier -> NotificationEventSource.Tap
                UNNotificationDismissActionIdentifier -> NotificationEventSource.Dismiss
                else -> NotificationEventSource.Action
            },
        )
        Dispatch.Default.launch {
            events.emitResponse(event)
            devHarness.recordResponseFromPlatform(event)
        }
    }

    internal suspend fun injectRemoteEnvelope(envelope: NotificationEnvelope): NotificationDevState {
        events.emitReceived(envelope)
        devHarness.recordReceivedFromPlatform(envelope)
        scheduleLocal(
            LocalNotificationRequest(
                id = envelope.id,
                categoryId = envelope.categoryId,
                content = envelope.content,
                route = envelope.route,
            ),
        )
        return devHarness.refresh()
    }

    internal suspend fun simulateTap(envelope: NotificationEnvelope): NotificationDevState {
        val event = NotificationResponseEvent(
            notificationId = envelope.id,
            categoryId = envelope.categoryId,
            route = envelope.route,
            actionId = UNNotificationDefaultActionIdentifier,
            source = NotificationEventSource.Tap,
        )
        events.emitResponse(event)
        devHarness.recordResponseFromPlatform(event)
        return devHarness.refresh()
    }
}
