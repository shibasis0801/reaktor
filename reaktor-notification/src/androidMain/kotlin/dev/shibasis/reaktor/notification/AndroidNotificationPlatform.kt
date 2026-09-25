package dev.shibasis.reaktor.notification

import android.Manifest
import android.app.Notification
import android.app.Notification.Action
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.shibasis.reaktor.core.framework.Dispatch
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.core.framework.json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.Calendar
import kotlin.math.absoluteValue

internal const val DEFAULT_CHANNEL_ID = "system"
internal const val ACTION_NOTIFICATION_ALARM = "dev.shibasis.reaktor.notification.ALARM"
internal const val ACTION_NOTIFICATION_RESPONSE = "dev.shibasis.reaktor.notification.RESPONSE"
internal const val ACTION_NOTIFICATION_DISMISS = "dev.shibasis.reaktor.notification.DISMISS"
internal const val EXTRA_NOTIFICATION_ID = "reaktor_notification_id"
internal const val EXTRA_CATEGORY_ID = "reaktor_category_id"
internal const val EXTRA_ROUTE_TYPE = "reaktor_route_type"
internal const val EXTRA_ROUTE = "reaktor_route"
internal const val EXTRA_ROUTE_PAYLOAD = "reaktor_route_payload"
internal const val EXTRA_ACTION_ID = "reaktor_action_id"
internal const val EXTRA_DISMISSES_NOTIFICATION = "reaktor_dismisses_notification"
// Distinct from any notification's own request code, which is derived from its id.
internal const val SHOW_ALARM_REQUEST_CODE = 0x5245414B
internal const val ALARM_LOG_TAG = "ReaktorNotifications"
internal const val EXTRA_REQUEST_JSON = "reaktor_request_json"
internal const val EXTRA_ENVELOPE_JSON = "reaktor_envelope_json"
internal const val ALARM_STORE_NAME = "reaktor_scheduled_alarms"

/**
 * How far past "now" a repeating calendar trigger is resolved from when re-arming. Comfortably
 * longer than the second-level granularity a calendar spec can match, so a trigger that just fired
 * cannot match itself again.
 */
internal const val REARM_SETTLE_MILLIS = 60_000L

class AndroidNotificationChannelRegistry(
    private val context: Context,
) {
    private var categories: List<NotificationCategorySpec> = emptyList()

    fun register(categories: List<NotificationCategorySpec>) {
        this.categories = categories
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        categories.mapNotNull { category ->
            val options = category.android ?: return@mapNotNull null
            val groupId = options.channelGroupId ?: return@mapNotNull null
            val groupName = options.channelGroupName ?: groupId
            groupId to groupName
        }.distinctBy { it.first }.forEach { (id, name) ->
            manager.createNotificationChannelGroup(NotificationChannelGroup(id, name))
        }
        categories.forEach { category ->
            val options = category.android
            val channel = NotificationChannel(
                category.id,
                category.displayName,
                options?.importance.toAndroidImportance(),
            ).apply {
                group = options?.channelGroupId
                description = options?.description
                setShowBadge(options?.showBadge ?: true)
                if (options?.bypassDnd == true) setBypassDnd(true)
                lockscreenVisibility = options?.lockscreenVisibility.toAndroidVisibility()
                options?.lights?.let { lights ->
                    enableLights(true)
                    lightColor = lights.colorArgb.toInt()
                }
                if (options?.vibrationPatternMillis?.isNotEmpty() == true) {
                    enableVibration(true)
                    vibrationPattern = options.vibrationPatternMillis.toLongArray()
                }
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun ensure(categoryId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (categories.none { it.id == categoryId }) {
            register(categories + NotificationCategorySpec(categoryId, categoryId.replaceFirstChar { it.uppercase() }))
        }
    }
}

class AndroidNotificationRenderer(
    internal val context: Context,
    internal val channelRegistry: AndroidNotificationChannelRegistry,
    internal val config: AndroidNotificationsConfig,
) {
    internal val avatars = NotificationAvatars(context)

    suspend fun show(request: LocalNotificationRequest): LocalNotificationId {
        val sender = request.content.sender
        val conversation = request.content.conversation
        if (sender != null && conversation != null) return showConversation(request, sender, conversation)
        val channelId = request.android?.channelId ?: request.categoryId
        channelRegistry.ensure(channelId)
        if (!canPost()) return LocalNotificationId(request.id)

        val envelope = request.toEnvelope()
        val pendingIntent = tapPendingIntent(envelope)
        val deleteIntent = responsePendingIntent(envelope, actionId = "dismiss", dismissed = true)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val icon = resolveSmallIcon(request)
        builder
            .setSmallIcon(icon)
            .setContentTitle(request.content.title)
            .setContentText(request.content.body)
            .setSubText(request.content.subtitle ?: request.content.summary)
            .setStyle(Notification.BigTextStyle().bigText(request.content.body))
            .setContentIntent(pendingIntent)
            .setDeleteIntent(deleteIntent)
            .setAutoCancel(request.android?.autoCancel ?: true)
            .setShowWhen(true)
            .setNumber(request.content.badge ?: 0)

        request.content.groupId?.let(builder::setGroup)
        request.android?.groupKey?.let(builder::setGroup)
        if (request.android?.groupSummary == true) builder.setGroupSummary(true)
        if (request.android?.onlyAlertOnce == true) builder.setOnlyAlertOnce(true)
        if (request.android?.ongoing == true) builder.setOngoing(true)
        request.android?.progress?.let { builder.setProgress(it.max, it.current, it.indeterminate) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(request.android?.category ?: Notification.CATEGORY_STATUS)
            builder.setPriority((request.android?.priority ?: request.priority).toAndroidPriority())
            builder.setVisibility((request.android?.visibility ?: NotificationVisibility.Private).toAndroidVisibility())
            request.android?.colorArgb?.let { builder.setColor(it.toInt()) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request.android?.timeoutAfterMillis?.let(builder::setTimeoutAfter)
        }
        when (request.content.sound) {
            NotificationSound.Default -> builder.setDefaults(Notification.DEFAULT_SOUND)
            NotificationSound.Silent -> builder.setSilentCompat(true)
            is NotificationSound.Named -> Unit
        }
        (request.android?.actions ?: emptyList()).forEach { action ->
            builder.addAction(nativeAction(envelope, action, icon))
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(request.id.notificationRequestCode(), builder.build())
        return LocalNotificationId(request.id)
    }

    internal fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal fun tapPendingIntent(envelope: NotificationEnvelope): PendingIntent {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent()
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        putEnvelopeExtras(launchIntent, envelope)
        return PendingIntent.getActivity(
            context,
            envelope.id.notificationRequestCode(),
            launchIntent,
            pendingIntentFlags(immutable = true),
        )
    }

    internal fun responsePendingIntent(
        envelope: NotificationEnvelope,
        actionId: String,
        dismissed: Boolean = false,
        mutable: Boolean = false,
        dismissesNotification: Boolean = false,
    ): PendingIntent {
        val intent = Intent(context, ReaktorNotificationActionReceiver::class.java)
            .setAction(if (dismissed) ACTION_NOTIFICATION_DISMISS else ACTION_NOTIFICATION_RESPONSE)
            .putExtra(EXTRA_ACTION_ID, actionId)
            .putExtra("reaktor_dismissed", dismissed)
            .putExtra(EXTRA_DISMISSES_NOTIFICATION, dismissesNotification)
        putEnvelopeExtras(intent, envelope)
        return PendingIntent.getBroadcast(
            context,
            "${envelope.id}:$actionId".notificationRequestCode(),
            intent,
            pendingIntentFlags(immutable = !mutable),
        )
    }

    private fun nativeAction(envelope: NotificationEnvelope, spec: NotificationActionSpec, icon: Int): Action {
        val pendingIntent = responsePendingIntent(
            envelope = envelope,
            actionId = spec.id,
            mutable = spec.kind == NotificationActionKind.TextInput,
            dismissesNotification = spec.dismissesNotification,
        )
        @Suppress("DEPRECATION")
        val builder = Action.Builder(icon, spec.title, pendingIntent)
        if (spec.kind == NotificationActionKind.TextInput) {
            val input = spec.textInput ?: NotificationTextInputOptions()
            builder.addRemoteInput(
                RemoteInput.Builder(input.resultKey)
                    .setLabel(input.placeholder)
                    .build(),
            )
        }
        return builder.build()
    }

    internal fun resolveSmallIcon(request: LocalNotificationRequest): Int {
        request.android?.smallIconName?.let { name ->
            val id = context.resources.getIdentifier(name, "drawable", context.packageName)
            if (id != 0) return id
        }
        config.defaultSmallIconName?.let { name ->
            val id = context.resources.getIdentifier(name, "drawable", context.packageName)
            if (id != 0) return id
        }
        return config.smallIconResId ?: android.R.drawable.ic_dialog_info
    }
}

class ReaktorNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Auto-cancel only covers a tap on the notification body, so an action button has to clear
        // its own notification. Done here rather than in the handler so the shade updates the
        // instant the button is pressed, whatever the response listener goes on to do.
        if (intent.dismissesNotification()) {
            context.cancelNotification(intent.getStringExtra(EXTRA_NOTIFICATION_ID))
        }
        Dispatch.Default.launch {
            AndroidNotificationsRuntime.ensure(context).handleActionIntent(intent)
        }
    }
}

class AndroidNotificationDevHarness(
    private val client: AndroidNotificationsClient,
) : BaseNotificationDevHarness(client, NotificationPlatform.Android) {
    private var token: DevicePushToken? = null
    private var lastEnvelope: NotificationEnvelope? = null

    fun recordToken(token: DevicePushToken?) {
        this.token = token
        recordTokenForState(token)
    }

    fun recordReceivedFromPlatform(envelope: NotificationEnvelope) {
        lastEnvelope = envelope
        recordReceived(envelope)
    }

    fun recordResponseFromPlatform(event: NotificationResponseEvent) {
        recordResponse(event)
    }

    override suspend fun injectRemoteEnvelope(): NotificationDevState {
        val envelope = NotificationEnvelope(
            id = "android-dev-remote-${Clock.nowEpochMillis()}",
            type = "reaktor.dev.notification",
            categoryId = "messages",
            content = NotificationContent(
                title = "Android notification test",
                body = "Synthetic FCM payload received",
                threadId = "dev",
            ),
            route = NotificationRoute.GraphAction("reaktor.notification.open", "{}"),
        )
        return inject(envelope)
    }

    override suspend fun inject(envelope: NotificationEnvelope): NotificationDevState {
        lastEnvelope = envelope
        return client.injectRemoteEnvelope(envelope)
    }

    override suspend fun simulateTap(): NotificationDevState {
        val envelope = lastEnvelope ?: NotificationEnvelope(
            id = "android-dev-tap-${Clock.nowEpochMillis()}",
            type = "reaktor.dev.notification",
            categoryId = "messages",
            content = NotificationContent("Android notification test", "Synthetic tap"),
            route = NotificationRoute.GraphAction("reaktor.notification.open", "{}"),
        )
        return client.simulateTap(envelope)
    }
}

object AndroidNotificationsRuntime {
    private var client: AndroidNotificationsClient? = null

    /**
     * The listeners for this process, shared by every client built inside it.
     *
     * See the note in AndroidNotificationsClient: responses arrive through a process-scoped
     * receiver, so holding listeners on one client instance loses them as soon as another is
     * constructed.
     */
    internal val events = NotificationEventHub()

    fun install(client: AndroidNotificationsClient) {
        this.client = client
    }

    fun current(): AndroidNotificationsClient? = client ?: Feature.Notifications as? AndroidNotificationsClient

    fun ensure(context: Context, config: AndroidNotificationsConfig = AndroidNotificationsConfig()): AndroidNotificationsClient {
        val existing = current()
        if (existing != null) return existing
        return AndroidNotificationsClient(context.applicationContext, config).also {
            Feature.Notifications = it
        }
    }
}

internal fun NotificationImportance?.toAndroidImportance(): Int = when (this ?: NotificationImportance.Default) {
    NotificationImportance.Min -> NotificationManager.IMPORTANCE_MIN
    NotificationImportance.Low -> NotificationManager.IMPORTANCE_LOW
    NotificationImportance.Default -> NotificationManager.IMPORTANCE_DEFAULT
    NotificationImportance.High -> NotificationManager.IMPORTANCE_HIGH
}

internal fun NotificationPriority.toAndroidPriority(): Int = when (this) {
    NotificationPriority.Min -> Notification.PRIORITY_MIN
    NotificationPriority.Low -> Notification.PRIORITY_LOW
    NotificationPriority.Default -> Notification.PRIORITY_DEFAULT
    NotificationPriority.High -> Notification.PRIORITY_HIGH
    NotificationPriority.Max -> Notification.PRIORITY_MAX
}

internal fun NotificationVisibility?.toAndroidVisibility(): Int = when (this ?: NotificationVisibility.Private) {
    NotificationVisibility.Public -> Notification.VISIBILITY_PUBLIC
    NotificationVisibility.Private -> Notification.VISIBILITY_PRIVATE
    NotificationVisibility.Secret -> Notification.VISIBILITY_SECRET
}

internal fun Notification.Builder.setSilentCompat(silent: Boolean): Notification.Builder {
    if (!silent) return this
    @Suppress("DEPRECATION")
    setSound(null)
    return this
}

internal fun putRoute(intent: Intent, route: NotificationRoute) {
    when (route) {
        is NotificationRoute.OpenPath -> {
            intent.putExtra(EXTRA_ROUTE_TYPE, "open_path")
            intent.putExtra(EXTRA_ROUTE, route.path)
        }
        is NotificationRoute.GraphAction -> {
            intent.putExtra(EXTRA_ROUTE_TYPE, "graph_action")
            intent.putExtra(EXTRA_ROUTE, route.type)
            intent.putExtra(EXTRA_ROUTE_PAYLOAD, route.payloadJson)
        }
        NotificationRoute.None -> {
            intent.putExtra(EXTRA_ROUTE_TYPE, "none")
        }
    }
}

internal fun routeFromIntent(intent: Intent): NotificationRoute = when (intent.getStringExtra(EXTRA_ROUTE_TYPE)) {
    "open_path" -> NotificationRoute.OpenPath(intent.getStringExtra(EXTRA_ROUTE).orEmpty())
    "graph_action" -> NotificationRoute.GraphAction(
        type = intent.getStringExtra(EXTRA_ROUTE).orEmpty(),
        payloadJson = intent.getStringExtra(EXTRA_ROUTE_PAYLOAD) ?: "{}",
    )
    else -> NotificationRoute.None
}

internal fun putEnvelopeExtras(intent: Intent, envelope: NotificationEnvelope) {
    intent.putExtra(EXTRA_NOTIFICATION_ID, envelope.id)
    intent.putExtra(EXTRA_CATEGORY_ID, envelope.categoryId)
    intent.putExtra(EXTRA_ENVELOPE_JSON, json.encodeToString(envelope))
    putRoute(intent, envelope.route)
}

/** Whether the action this intent carries should take its notification down with it. */
internal fun Intent.dismissesNotification(): Boolean =
    getBooleanExtra(EXTRA_DISMISSES_NOTIFICATION, false)

internal fun Context.cancelNotification(id: String?) {
    val notificationId = id?.takeIf { it.isNotEmpty() } ?: return
    getSystemService(NotificationManager::class.java).cancel(notificationId.notificationRequestCode())
}

internal fun responseEventFromIntent(intent: Intent): NotificationResponseEvent {
    val envelope = intent.getStringExtra(EXTRA_ENVELOPE_JSON)
        ?.let { runCatching { json.decodeFromString<NotificationEnvelope>(it) }.getOrNull() }
    val actionId = intent.getStringExtra(EXTRA_ACTION_ID)
    val dismissed = intent.getBooleanExtra("reaktor_dismissed", false)
    val replyText = RemoteInput.getResultsFromIntent(intent)
        ?.let { bundle ->
            bundle.keySet().firstOrNull()?.let { key -> bundle.getCharSequence(key)?.toString() }
        }
    return NotificationResponseEvent(
        notificationId = envelope?.id ?: intent.getStringExtra(EXTRA_NOTIFICATION_ID).orEmpty(),
        categoryId = envelope?.categoryId ?: intent.getStringExtra(EXTRA_CATEGORY_ID) ?: DEFAULT_CHANNEL_ID,
        actionId = actionId,
        route = envelope?.route ?: routeFromIntent(intent),
        directReplyText = replyText,
        dismissed = dismissed,
        data = envelope?.data ?: emptyMap(),
        source = if (dismissed) NotificationEventSource.Dismiss else NotificationEventSource.Action,
    )
}

internal fun LocalNotificationRequest.toEnvelope(): NotificationEnvelope =
    NotificationEnvelope(
        id = id,
        type = "local",
        categoryId = categoryId,
        content = content,
        route = route,
        data = content.data,
    )

internal fun LocalNotificationRequest.shouldScheduleLater(): Boolean =
    delay > kotlin.time.Duration.ZERO ||
        trigger !is NotificationTrigger.Immediate ||
        notBeforeMillis != null

internal fun LocalNotificationRequest.triggerDelayMillis(
    nowMillis: Long = System.currentTimeMillis(),
): Long {
    // Resolving the trigger from the floor instead of from now is what skips an occurrence: a
    // daily 18:30 asked for from tomorrow morning lands on tomorrow's 18:30, not tonight's.
    val from = earliestFrom(nowMillis)
    val held = from - nowMillis
    if (delay > kotlin.time.Duration.ZERO) return held + delay.inWholeMilliseconds.coerceAtLeast(1)
    return held + when (val trigger = trigger) {
        NotificationTrigger.Immediate -> 1
        is NotificationTrigger.TimeInterval -> trigger.delay.inWholeMilliseconds.coerceAtLeast(1)
        is NotificationTrigger.Calendar -> trigger.nextDelayMillis(from)
    }
}

internal fun NotificationTrigger.Calendar.nextDelayMillis(nowMillis: Long = System.currentTimeMillis()): Long {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.MILLISECOND, 0)
        year?.let { set(Calendar.YEAR, it) }
        month?.let { set(Calendar.MONTH, (it - 1).coerceIn(0, 11)) }
        day?.let { set(Calendar.DAY_OF_MONTH, it.coerceIn(1, getActualMaximum(Calendar.DAY_OF_MONTH))) }
        hour?.let { set(Calendar.HOUR_OF_DAY, it.coerceIn(0, 23)) }
        minute?.let { set(Calendar.MINUTE, it.coerceIn(0, 59)) }
        second?.let { set(Calendar.SECOND, it.coerceIn(0, 59)) }
    }
    if (calendar.timeInMillis <= nowMillis && year == null && month == null && day == null) {
        calendar.add(Calendar.DAY_OF_YEAR, 1)
    }
    return (calendar.timeInMillis - nowMillis).coerceAtLeast(1)
}

internal fun pendingIntentFlags(immutable: Boolean): Int {
    val mutability = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        if (immutable) PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_MUTABLE
    } else {
        0
    }
    return PendingIntent.FLAG_UPDATE_CURRENT or mutability
}

internal fun String.notificationRequestCode(): Int = hashCode().absoluteValue
