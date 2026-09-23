package dev.shibasis.reaktor.notification.ongoing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import dev.shibasis.reaktor.core.utils.logger
import dev.shibasis.reaktor.core.utils.warn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * An ongoing notification, and deliberately not a foreground service.
 *
 * A service would keep the process alive, which is exactly what this does not need: the countdown
 * is drawn by the system from an end time, and a posted notification outlives the process that
 * posted it. Reaching for a service would buy nothing and cost a foreground-service type, its
 * permission, and a Play declaration to justify it.
 */
actual object OngoingActivities {

    private const val CHANNEL_ID = "reaktor_ongoing"
    /** One activity at a time, so one fixed id — starting another replaces it. */
    private const val NOTIFICATION_ID = 0x0E60
    internal const val ACTION_TAPPED = "dev.shibasis.reaktor.notification.ONGOING_ACTION"
    internal const val EXTRA_ACTION_ID = "actionId"
    internal const val EXTRA_ACTION_TEXT = "actionText"

    private val log = "OngoingActivities".logger()

    private var appContext: Context? = null
    private var contentIntent: PendingIntent? = null
    private var smallIcon: Int = android.R.drawable.ic_media_play

    private val _responses = MutableSharedFlow<OngoingResponse>(replay = 4, extraBufferCapacity = 16)
    actual val responses: Flow<OngoingResponse> = _responses.asSharedFlow()

    /**
     * Installs the pieces only the app can supply: a context, the icon the status bar shows, and
     * where a tap on the body should land.
     */
    fun attach(context: Context, icon: Int, openApp: PendingIntent?) {
        appContext = context.applicationContext
        smallIcon = icon
        contentIntent = openApp
    }

    internal fun emit(response: OngoingResponse) {
        _responses.tryEmit(response)
    }

    actual fun isAvailable(): Boolean {
        val context = appContext ?: return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    actual fun start(state: OngoingActivityState) = post(state)

    actual fun update(state: OngoingActivityState) = post(state)

    actual fun end() {
        val context = appContext ?: return
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun post(state: OngoingActivityState) {
        val context = appContext ?: return
        if (!isAvailable()) return
        ensureChannel(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(state.title)
            .setContentText(state.line)
            .setOngoing(true)
            // Silent: an activity that revises itself every set must never make a sound doing it.
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setShowWhen(false)
            .apply {
                // Sub-text rather than BigTextStyle: a big-text body replaces the content line in
                // the collapsed form, which on a lock screen is the only form most people see —
                // and the content line is where the thing being done actually is.
                if (state.detail.isNotBlank()) setSubText(state.detail)
                contentIntent?.let { setContentIntent(it) }
                // The system draws the countdown from the end time, so nothing of ours has to be
                // awake to keep it honest.
                state.endsAtMillis?.let { endsAt ->
                    setWhen(endsAt)
                    setShowWhen(true)
                    setUsesChronometer(true)
                    setChronometerCountDown(true)
                }
                state.actions.forEachIndexed { index, action ->
                    addAction(buildAction(context, action, index))
                }
            }

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        }.onFailure { log.warn { "Could not show the ongoing activity: ${it.message}" } }
    }

    private fun buildAction(
        context: Context,
        action: OngoingAction,
        index: Int,
    ): NotificationCompat.Action {
        val builder = NotificationCompat.Action.Builder(0, action.label, actionIntent(context, action, index))
        action.input?.let { input ->
            builder.addRemoteInput(
                RemoteInput.Builder(EXTRA_ACTION_TEXT).setLabel(input.hint).build(),
            )
            // The system collects the text and fires the same broadcast, so nothing here has to
            // stay awake waiting for it.
            builder.setAllowGeneratedReplies(false)
        }
        return builder.build()
    }

    private fun actionIntent(context: Context, action: OngoingAction, index: Int): PendingIntent {
        val intent = Intent(ACTION_TAPPED)
            .setPackage(context.packageName)
            .putExtra(EXTRA_ACTION_ID, action.id)
        // An action collecting text needs a mutable intent — that is how the system writes the
        // typed result into it. Nothing sensitive rides here: the extras are an action id this
        // app chose and the words the user just typed, and the intent is package-scoped so no
        // other app can receive or rewrite it.
        val mutability = if (action.input != null) {
            PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(
            context,
            index,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutability,
        )
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "In progress", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "Shows what is running while the app is not open."
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
        )
    }
}

/** Turns a tap on an action into something [OngoingActivities.responses] can carry. */
class OngoingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(OngoingActivities.EXTRA_ACTION_ID) ?: return
        // Present only when the action collected text and the system agreed to collect it — a
        // lock screen that demanded an unlock first delivers the tap with nothing typed.
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(OngoingActivities.EXTRA_ACTION_TEXT)
            ?.toString()
            .orEmpty()
        OngoingActivities.emit(OngoingResponse(id, text))
    }
}
