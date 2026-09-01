package dev.shibasis.reaktor.notification

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import dev.shibasis.reaktor.core.adapters.AndroidPermissionAdapter
import dev.shibasis.reaktor.core.adapters.NotificationPermissionOptions
import dev.shibasis.reaktor.core.adapters.NotificationPermissionStatus
import dev.shibasis.reaktor.core.framework.Dispatch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.absoluteValue

data class AndroidNotificationsConfig(
    val fcmProjectId: String? = null,
    val defaultSmallIconName: String? = null,
    val smallIconResId: Int? = null,
    val autoDisplayRemoteMessages: Boolean = true,
)

class AndroidNotificationsClient(
    context: Context,
    private val config: AndroidNotificationsConfig = AndroidNotificationsConfig(),
) : NotificationAdapter<Context>(context.applicationContext, AndroidPermissionAdapter(context)) {
    private val appContext = context.applicationContext

    // Process-wide rather than per-instance. What delivers a notification response on Android is
    // a BroadcastReceiver, which is scoped to the process and reaches whichever client happens to
    // be installed when it fires — while listeners are registered once, by application code that
    // has no reason to run again. An Activity that builds a client in onCreate therefore silently
    // orphans every listener the moment it is recreated: the action fires, the notification is
    // dismissed, and nothing else happens. Two clients in one process are always the same app, so
    // sharing the hub is both the fix and the honest model.
    private val events = AndroidNotificationsRuntime.events
    private val channelRegistry = AndroidNotificationChannelRegistry(appContext)
    private val renderer = AndroidNotificationRenderer(appContext, channelRegistry, config)
    private val scheduler = AndroidNotificationScheduler(appContext)

    override val devHarness: AndroidNotificationDevHarness = AndroidNotificationDevHarness(this)

    private var categories: List<NotificationCategorySpec> = emptyList()
    private var cachedToken: DevicePushToken? = null
    private var foregroundPresentation = ForegroundPresentationPolicy()
    private var badgeCount: Int? = null
    private val preferences = mutableMapOf<String, Boolean>()
    private val scheduledIds = mutableSetOf<String>()
    private val deliveredIds = mutableSetOf<String>()

    init {
        AndroidNotificationsRuntime.install(this)
    }

    override suspend fun getPermissions(options: NotificationPermissionOptions): NotificationPermissionStatus =
        permissionAdapter.getNotificationPermissionStatus(options.withCategoryIds())

    override suspend fun requestPermissions(options: NotificationPermissionOptions): NotificationPermissionStatus {
        return permissionAdapter.requestNotificationPermission(options.withCategoryIds())
    }

    private fun NotificationPermissionOptions.withCategoryIds(): NotificationPermissionOptions =
        copy(categoryIds = categoryIds.ifEmpty { categories.map { it.id } })

    override suspend fun getPlatformCapabilities(): NotificationPlatformCapabilities =
        NotificationPlatformCapabilities(
            platform = NotificationPlatform.Android,
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
                NotificationPresentationFeature.Conversation,
                NotificationPresentationFeature.RichMedia,
                NotificationPresentationFeature.Progress,
            ),
            actionFeatures = setOf(
                NotificationActionFeature.Tap,
                NotificationActionFeature.ActionButton,
                NotificationActionFeature.Dismiss,
                NotificationActionFeature.DirectReply,
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
            ) + if (scheduler.canScheduleExact()) {
                setOf(NotificationSchedulingFeature.ExactDelivery)
            } else {
                emptySet()
            },
            extensionFeatures = setOf(
                NotificationExtensionFeature.ForegroundService,
                NotificationExtensionFeature.FullScreenIntent,
                NotificationExtensionFeature.Bubble,
            ),
        )

    override suspend fun registerCategories(categories: List<NotificationCategorySpec>) {
        this.categories = categories
        channelRegistry.register(categories)
    }

    override suspend fun getDeviceToken(): DevicePushToken? {
        val token = suspendCancellableCoroutine<String?> { continuation ->
            FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    continuation.resume(if (task.isSuccessful) task.result else null)
                }
        }
        cachedToken = token?.let {
            DevicePushToken(
                provider = "fcm",
                value = it,
                projectId = config.fcmProjectId,
                deviceId = appContext.notificationDeviceId(),
            )
        }
        return cachedToken
    }

    fun recordNewToken(token: String) {
        cachedToken = DevicePushToken(
            provider = "fcm",
            value = token,
            projectId = config.fcmProjectId,
            deviceId = appContext.notificationDeviceId(),
        )
        devHarness.recordToken(cachedToken)
    }

    override suspend fun registerRemoteEndpoint(userId: String?): RegisterEndpointResult {
        val token = cachedToken ?: getDeviceToken()
        return RegisterEndpointResult(
            endpointId = token?.value?.let { "android-fcm-${it.hashCode().absoluteValue}" } ?: "android-fcm-unavailable",
            registered = token != null,
            detail = if (token == null) "No FCM token available" else "Local endpoint probe only",
        )
    }

    override suspend fun unregisterRemoteEndpoint() {
        cachedToken = null
    }

    override suspend fun updatePreferences(command: UpdateNotificationPreferences) {
        preferences[command.categoryId] = command.enabled
    }

    override suspend fun scheduleLocal(request: LocalNotificationRequest): LocalNotificationId {
        return if (request.shouldScheduleLater()) {
            scheduler.schedule(request)
            scheduledIds += request.id
            LocalNotificationId(request.id)
        } else {
            renderer.show(request)
            deliveredIds += request.id
            LocalNotificationId(request.id)
        }
    }

    override suspend fun getState(): NotificationRuntimeState =
        NotificationRuntimeState(
            platform = NotificationPlatform.Android,
            permission = getPermissions(),
            capabilities = getPlatformCapabilities(),
            categories = categories,
            pendingLocalIds = scheduledIds.toList(),
            deliveredIds = deliveredIds.toList(),
            badge = badgeCount,
            foregroundPresentation = foregroundPresentation,
            preferences = preferences.toMap(),
            detail = "activeNotifications=${activeNotificationIds().size}",
        )

    override suspend fun setForegroundPresentation(policy: ForegroundPresentationPolicy) {
        foregroundPresentation = policy
    }

    override suspend fun setBadge(count: Int?) {
        badgeCount = count?.coerceAtLeast(0)
    }

    override suspend fun clearDelivered(ids: List<String>) {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        if (ids.isEmpty()) {
            manager.cancelAll()
            deliveredIds.clear()
        } else {
            ids.forEach { id -> manager.cancel(id.notificationRequestCode()) }
            deliveredIds.removeAll(ids.toSet())
        }
    }

    override suspend fun cancelScheduled(ids: List<String>) {
        val targetIds = if (ids.isEmpty()) scheduledIds.toList() else ids
        targetIds.forEach(scheduler::cancel)
        scheduledIds.removeAll(targetIds.toSet())
    }

    override suspend fun openSettings(target: NotificationSettingsTarget) {
        val intent = when (target) {
            NotificationSettingsTarget.App -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
            NotificationSettingsTarget.ExactAlarms ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(Uri.fromParts("package", appContext.packageName, null))
                } else {
                    // Nothing to grant before Android 12 - exact alarms were simply allowed.
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                }

            is NotificationSettingsTarget.Category ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, target.categoryId)
                } else {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                }
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    override fun addReceivedListener(listener: suspend (NotificationEnvelope) -> Unit): ListenerHandle =
        events.addReceivedListener(listener)

    override fun addResponseListener(listener: suspend (NotificationResponseEvent) -> Unit): ListenerHandle =
        events.addResponseListener(listener)

    fun handleRemoteMessage(message: RemoteMessage) {
        val envelope = NotificationEnvelope.fromDataMap(message.data)
        Dispatch.Default.launch {
            events.emitReceived(envelope)
            devHarness.recordReceivedFromPlatform(envelope)
            if (config.autoDisplayRemoteMessages) {
                scheduleLocal(
                    LocalNotificationRequest(
                        id = envelope.id,
                        categoryId = envelope.categoryId,
                        content = envelope.content,
                        route = envelope.route,
                    ),
                )
            }
        }
    }

    fun handleLaunchIntent(intent: Intent?): Boolean {
        if (intent == null || !intent.hasExtra(EXTRA_NOTIFICATION_ID)) return false
        val event = NotificationResponseEvent(
            notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID).orEmpty(),
            categoryId = intent.getStringExtra(EXTRA_CATEGORY_ID) ?: DEFAULT_CHANNEL_ID,
            route = routeFromIntent(intent),
            actionId = "open",
            source = NotificationEventSource.Tap,
        )
        Dispatch.Default.launch {
            events.emitResponse(event)
            devHarness.recordResponseFromPlatform(event)
        }
        return true
    }

    internal suspend fun deliverScheduled(request: LocalNotificationRequest) {
        renderer.show(request)
        deliveredIds += request.id
        // Alarms are one-shot, so a repeating request has to arm its own next occurrence.
        if (request.trigger.isRepeating) {
            scheduler.rearm(request)
        } else {
            scheduledIds -= request.id
            scheduler.forget(request.id)
        }
    }

    internal suspend fun handleActionIntent(intent: Intent) {
        val event = responseEventFromIntent(intent)
        // A swipe already removed the notification, and a dismissing action just cancelled it,
        // so neither is still delivered by the time the response lands.
        if (event.dismissed || intent.dismissesNotification()) {
            deliveredIds -= event.notificationId
        }
        events.emitResponse(event)
        devHarness.recordResponseFromPlatform(event)
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
            actionId = "open",
            source = NotificationEventSource.Tap,
        )
        events.emitResponse(event)
        devHarness.recordResponseFromPlatform(event)
        return devHarness.refresh()
    }

    private fun activeNotificationIds(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            appContext.getSystemService(NotificationManager::class.java).activeNotifications.map { it.id.toString() }
        } else {
            deliveredIds.toList()
        }
}

private fun Context.notificationDeviceId(): String? =
    Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        ?.takeIf { it.isNotBlank() }
