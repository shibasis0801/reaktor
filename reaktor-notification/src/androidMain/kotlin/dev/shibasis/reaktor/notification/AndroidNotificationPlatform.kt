package dev.shibasis.reaktor.notification

import android.Manifest
import android.app.AlarmManager
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
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.shibasis.reaktor.core.framework.Dispatch
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.core.framework.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.Calendar
import kotlin.math.absoluteValue

internal const val DEFAULT_CHANNEL_ID = "system"
private const val ACTION_NOTIFICATION_ALARM = "dev.shibasis.reaktor.notification.ALARM"
private const val ACTION_NOTIFICATION_RESPONSE = "dev.shibasis.reaktor.notification.RESPONSE"
private const val ACTION_NOTIFICATION_DISMISS = "dev.shibasis.reaktor.notification.DISMISS"
internal const val EXTRA_NOTIFICATION_ID = "reaktor_notification_id"
internal const val EXTRA_CATEGORY_ID = "reaktor_category_id"
private const val EXTRA_ROUTE_TYPE = "reaktor_route_type"
private const val EXTRA_ROUTE = "reaktor_route"
private const val EXTRA_ROUTE_PAYLOAD = "reaktor_route_payload"
private const val EXTRA_ACTION_ID = "reaktor_action_id"
internal const val EXTRA_DISMISSES_NOTIFICATION = "reaktor_dismisses_notification"
// Distinct from any notification's own request code, which is derived from its id.
private const val SHOW_ALARM_REQUEST_CODE = 0x5245414B
private const val ALARM_LOG_TAG = "ReaktorNotifications"
private const val EXTRA_REQUEST_JSON = "reaktor_request_json"
private const val EXTRA_ENVELOPE_JSON = "reaktor_envelope_json"
private const val ALARM_STORE_NAME = "reaktor_scheduled_alarms"

/**
 * How far past "now" a repeating calendar trigger is resolved from when re-arming. Comfortably
 * longer than the second-level granularity a calendar spec can match, so a trigger that just fired
 * cannot match itself again.
 */
private const val REARM_SETTLE_MILLIS = 60_000L

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
    private val context: Context,
    private val channelRegistry: AndroidNotificationChannelRegistry,
    private val config: AndroidNotificationsConfig,
) {
    fun show(request: LocalNotificationRequest): LocalNotificationId {
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

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun tapPendingIntent(envelope: NotificationEnvelope): PendingIntent {
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

    private fun responsePendingIntent(
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

    private fun resolveSmallIcon(request: LocalNotificationRequest): Int {
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

/** A pending alarm as persisted on disk, so it can survive process death and reboots. */
@Serializable
internal data class ScheduledAlarm(
    val request: LocalNotificationRequest,
    val targetAtMillis: Long,
)

class AndroidNotificationScheduler(
    private val context: Context,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val store = context.getSharedPreferences(ALARM_STORE_NAME, Context.MODE_PRIVATE)

    fun schedule(request: LocalNotificationRequest, fromMillis: Long = System.currentTimeMillis()) {
        scheduleAt(request, fromMillis + request.triggerDelayMillis(fromMillis))
    }

    /**
     * Re-arms a repeating request once it has fired. Calendar recurrences are resolved from a
     * moment just after now, so a trigger that only this instant elapsed advances to its next
     * occurrence instead of matching the current minute again and firing in a loop.
     */
    fun rearm(request: LocalNotificationRequest) {
        val settle = if (request.trigger is NotificationTrigger.Calendar) REARM_SETTLE_MILLIS else 0L
        schedule(request, System.currentTimeMillis() + settle)
    }

    fun cancel(id: String) {
        forget(id)
        alarmManager.cancel(alarmIntent(id))
    }

    /** Drops the persisted copy without touching the alarm — used once a one-shot has delivered. */
    fun forget(id: String) {
        store.edit().remove(id).apply()
    }

    /**
     * The OS clears alarms across a reboot, so every pending request is re-armed from disk.
     * Alarms still in the future keep their original firing time; repeating ones that elapsed
     * while the device was off roll to their next occurrence, and missed one-shots are dropped.
     */
    fun restoreAll() {
        val now = System.currentTimeMillis()
        store.all.keys.toList().forEach { id ->
            val alarm = read(id)
            if (alarm == null) {
                forget(id)
                return@forEach
            }
            when {
                alarm.targetAtMillis > now -> scheduleAt(alarm.request, alarm.targetAtMillis)
                alarm.request.trigger.isRepeating -> schedule(alarm.request)
                else -> forget(id)
            }
        }
    }

    /**
     * Arms the OS alarm, as close to the requested moment as the app is allowed to get.
     *
     * `AlarmManager.set` has been inexact since API 19 and currently batches to a window of about
     * an hour, which is fine for a digest and useless for a reminder somebody set a clock face to.
     * An exact request therefore tries `setExactAndAllowWhileIdle`, then falls back.
     *
     * Every exact path on Android needs `SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM` from API 31 --
     * `setAlarmClock` included, despite its history of being the permission-free way to do this.
     * Verified the hard way: it throws the same SecurityException as the rest. Which permission to
     * declare, and how to justify it to the store, is the *host app's* decision, so this module
     * declares neither and reads what it was given.
     *
     * The fallback matters more than the precision. A notification that arrives late is a poor
     * outcome; one that never arrives because it could not arrive *precisely* is a much worse one,
     * and that is what an unguarded exact call produces on any device where the right is missing.
     */
    private fun scheduleAt(request: LocalNotificationRequest, triggerAtMillis: Long) {
        store.edit()
            .putString(request.id, json.encodeToString(ScheduledAlarm(request, triggerAtMillis)))
            .apply()

        val intent = alarmIntent(request.id, request)
        if (request.precision == NotificationPrecision.Approximate) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent)
            return
        }

        // Both exact paths can be refused at runtime — the right can be revoked between the check
        // and the call, and OEM builds have their own rules about which of them an app may use. A
        // notification that arrives late is a poor outcome; one that never arrives because it
        // could not arrive *precisely* is a far worse one, so this degrades rather than gives up.
        val armed = runCatching {
            if (canScheduleExact()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent)
            } else {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAtMillis, showAlarmIntent()),
                    intent,
                )
            }
        }.onFailure {
            Log.w(ALARM_LOG_TAG, "Exact alarm refused for ${request.id}, falling back", it)
        }.isSuccess

        if (!armed) alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent)
    }

    /**
     * Whether the exact-alarm right is held. Always true below API 31, where it did not exist.
     *
     * Re-read on every arm rather than cached, because the user can revoke it in Settings at any
     * moment and a cached yes would silently downgrade every later alarm to a broken promise.
     */
    internal fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /**
     * What the lock screen opens when the alarm entry is tapped. The launcher activity, looked up
     * rather than named, since a framework cannot know the host app's entry point.
     */
    private fun showAlarmIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        return PendingIntent.getActivity(
            context,
            SHOW_ALARM_REQUEST_CODE,
            launch,
            pendingIntentFlags(immutable = true),
        )
    }

    private fun read(id: String): ScheduledAlarm? {
        val stored = store.getString(id, null) ?: return null
        return runCatching { json.decodeFromString<ScheduledAlarm>(stored) }.getOrNull()
    }

    // Extras are not part of PendingIntent equality, so the request is only attached when arming.
    private fun alarmIntent(id: String, request: LocalNotificationRequest? = null): PendingIntent {
        val intent = Intent(context, ReaktorNotificationAlarmReceiver::class.java)
            .setAction(ACTION_NOTIFICATION_ALARM)
        request?.let { intent.putExtra(EXTRA_REQUEST_JSON, json.encodeToString(it)) }
        return PendingIntent.getBroadcast(
            context,
            id.notificationRequestCode(),
            intent,
            pendingIntentFlags(immutable = true),
        )
    }
}

class ReaktorNotificationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestJson = intent.getStringExtra(EXTRA_REQUEST_JSON) ?: return
        val request = runCatching { json.decodeFromString<LocalNotificationRequest>(requestJson) }.getOrNull() ?: return
        Dispatch.Default.launch {
            AndroidNotificationsRuntime.ensure(context).deliverScheduled(request)
        }
    }
}

/** Restores pending alarms after a reboot or an app update, both of which clear them. */
class ReaktorNotificationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                runCatching { AndroidNotificationScheduler(context.applicationContext).restoreAll() }
            }
        }
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

open class ReaktorFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        AndroidNotificationsRuntime.ensure(this).handleRemoteMessage(message)
    }

    override fun onNewToken(token: String) {
        AndroidNotificationsRuntime.ensure(this).recordNewToken(token)
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

    fun ensure(context: Context): AndroidNotificationsClient {
        val existing = current()
        if (existing != null) return existing
        return AndroidNotificationsClient(context.applicationContext).also {
            Feature.Notifications = it
        }
    }
}

private fun NotificationImportance?.toAndroidImportance(): Int = when (this ?: NotificationImportance.Default) {
    NotificationImportance.Min -> NotificationManager.IMPORTANCE_MIN
    NotificationImportance.Low -> NotificationManager.IMPORTANCE_LOW
    NotificationImportance.Default -> NotificationManager.IMPORTANCE_DEFAULT
    NotificationImportance.High -> NotificationManager.IMPORTANCE_HIGH
}

private fun NotificationPriority.toAndroidPriority(): Int = when (this) {
    NotificationPriority.Min -> Notification.PRIORITY_MIN
    NotificationPriority.Low -> Notification.PRIORITY_LOW
    NotificationPriority.Default -> Notification.PRIORITY_DEFAULT
    NotificationPriority.High -> Notification.PRIORITY_HIGH
    NotificationPriority.Max -> Notification.PRIORITY_MAX
}

private fun NotificationVisibility?.toAndroidVisibility(): Int = when (this ?: NotificationVisibility.Private) {
    NotificationVisibility.Public -> Notification.VISIBILITY_PUBLIC
    NotificationVisibility.Private -> Notification.VISIBILITY_PRIVATE
    NotificationVisibility.Secret -> Notification.VISIBILITY_SECRET
}

private fun Notification.Builder.setSilentCompat(silent: Boolean): Notification.Builder {
    if (!silent) return this
    @Suppress("DEPRECATION")
    setSound(null)
    return this
}

private fun putRoute(intent: Intent, route: NotificationRoute) {
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

private fun putEnvelopeExtras(intent: Intent, envelope: NotificationEnvelope) {
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

private fun LocalNotificationRequest.toEnvelope(): NotificationEnvelope =
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

private fun LocalNotificationRequest.triggerDelayMillis(
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

private fun NotificationTrigger.Calendar.nextDelayMillis(nowMillis: Long = System.currentTimeMillis()): Long {
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

private fun pendingIntentFlags(immutable: Boolean): Int {
    val mutability = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        if (immutable) PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_MUTABLE
    } else {
        0
    }
    return PendingIntent.FLAG_UPDATE_CURRENT or mutability
}

internal fun String.notificationRequestCode(): Int = hashCode().absoluteValue
