package dev.shibasis.reaktor.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.shibasis.reaktor.core.framework.Dispatch
import dev.shibasis.reaktor.core.framework.json
import java.util.Calendar
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

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
