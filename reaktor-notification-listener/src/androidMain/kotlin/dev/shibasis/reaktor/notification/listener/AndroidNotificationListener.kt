package dev.shibasis.reaktor.notification.listener

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.os.PowerManager
import android.net.Uri
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import co.touchlab.kermit.Logger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.lang.ref.WeakReference

/**
 * The Android listener. Install it from `Application.onCreate`, before the system can bind the
 * service, and set the filter there too:
 *
 * ```kotlin
 * AndroidNotificationListener.attach(this)
 * AndroidNotificationListener.setFilter(allowed)
 * Feature.NotificationListener = AndroidNotificationListener
 * ```
 */
object AndroidNotificationListener : NotificationListener {
    private val log = Logger.withTag("NotificationListener")
    private val gate = NotificationGate()
    private val queue = Channel<PostedNotification>(Channel.UNLIMITED)
    private val _access = MutableStateFlow(ListenerAccess.NotGranted)
    private var context: Context? = null
    private var service = WeakReference<NotificationListenerService>(null)

    override val access: StateFlow<ListenerAccess> = _access.asStateFlow()
    override val posted: Flow<PostedNotification> = queue.receiveAsFlow()

    fun attach(context: Context) {
        this.context = context.applicationContext
        refresh()
    }

    override fun setFilter(allow: Set<String>) = gate.set(allow)

    override fun refresh() {
        val context = context ?: return
        val granted = isGranted(context)
        _access.value = when {
            !granted -> ListenerAccess.NotGranted
            service.get() != null -> ListenerAccess.Connected
            else -> ListenerAccess.Granted
        }
        // Granted but not bound: the system sometimes leaves a listener unbound after an update or
        // a crash. Asking costs nothing when it is already bound.
        if (granted && service.get() == null) {
            runCatching { NotificationListenerService.requestRebind(component(context)) }
                .onFailure { log.w(it) { "Could not ask for the listener to be bound again" } }
        }
    }

    override fun openAccessSettings() {
        val context = context ?: return
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override suspend fun active(): List<PostedNotification> {
        val bound = service.get() ?: return emptyList()
        val shade = runCatching { bound.activeNotifications }.getOrNull() ?: return emptyList()
        return shade.mapNotNull { sbn -> gate.admit(sbn.packageName) { sbn.read() } }
    }

    override fun batteryRestricted(): Boolean? {
        val context = context ?: return null
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        return !power.isIgnoringBatteryOptimizations(context.packageName)
    }

    override val maker: String? get() = Build.MANUFACTURER?.lowercase()

    override fun openKeepAliveSettings(): KeepAliveScreen? {
        val context = context ?: return null
        val tries = makerScreens(maker).map { component -> Intent().setComponent(component) to KeepAliveScreen.MakerAutostart } +
            listOf(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) to KeepAliveScreen.BatteryOptimisation,
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)) to KeepAliveScreen.AppDetails,
            )
        for ((intent, screen) in tries) {
            if (intent.resolveActivity(context.packageManager) == null) continue
            val opened = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            if (opened.isSuccess) return screen
            log.w(opened.exceptionOrNull()) { "Could not open ${intent.component ?: intent.action}" }
        }
        return null
    }

    /**
     * The makers' own autostart and background screens, the ones that stop listeners on their
     * phones whatever Android's own setting says. Names change between their OS versions, so each
     * maker has several, tried in order; a screen that isn't there is skipped.
     */
    private fun makerScreens(maker: String?): List<ComponentName> = when (maker) {
        "xiaomi", "redmi", "poco" -> listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        "oppo", "realme", "oneplus" -> listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        )
        "vivo", "iqoo" -> listOf(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        )
        "huawei", "honor" -> listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        )
        else -> emptyList()
    }

    internal fun connected(listener: NotificationListenerService) {
        service = WeakReference(listener)
        context = context ?: listener.applicationContext
        _access.value = ListenerAccess.Connected
    }

    internal fun disconnected() {
        service = WeakReference(null)
        _access.value = context?.let { if (isGranted(it)) ListenerAccess.Granted else ListenerAccess.NotGranted }
            ?: ListenerAccess.NotGranted
    }

    internal fun posted(sbn: StatusBarNotification) {
        // The package name is the only thing read before the gate decides.
        val notification = gate.admit(sbn.packageName) { sbn.read() } ?: return
        queue.trySend(notification)
    }

    private fun isGranted(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        val mine = component(context)
        return enabled.split(':').mapNotNull { ComponentName.unflattenFromString(it) }.any { it == mine }
    }

    private fun component(context: Context) = ComponentName(context, ReaktorNotificationListenerService::class.java)
}

private fun StatusBarNotification.read(): PostedNotification {
    val extras = notification.extras
    val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT)
    return PostedNotification(
        app = packageName,
        key = key,
        postedAtMs = postTime,
        title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        text = text?.toString(),
        subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
        lines = messages(extras).ifEmpty {
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty()
        },
        isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
        isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
    )
}

/** The texts of a MessagingStyle notification (an SMS app's, a chat's), oldest first. */
@Suppress("DEPRECATION")
private fun messages(extras: Bundle): List<String> {
    val messages: Array<Parcelable>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
    } else {
        extras.getParcelableArray(Notification.EXTRA_MESSAGES)
    }
    return messages.orEmpty().mapNotNull { (it as? Bundle)?.getCharSequence("text")?.toString() }
}

/** Declared in this module's manifest; everything it hears goes to [AndroidNotificationListener]. */
class ReaktorNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() = AndroidNotificationListener.connected(this)

    override fun onListenerDisconnected() = AndroidNotificationListener.disconnected()

    override fun onNotificationPosted(sbn: StatusBarNotification) = AndroidNotificationListener.posted(sbn)
}
