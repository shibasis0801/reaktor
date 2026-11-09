package dev.shibasis.reaktor.notification.listener

import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A notification another app posted, copied out of the system only because that app is in the
 * filter. Nothing else about a notification ever reaches this type.
 */
data class PostedNotification(
    /** The posting app's package name. */
    val app: String,
    /** The system's key: the same for every update of one notification. */
    val key: String,
    val postedAtMs: Long,
    val title: String?,
    /** The expanded text when the app gave one, else the one-line text. */
    val text: String?,
    val subText: String?,
    /** Each message of a conversation notification, or each line of an inbox one, oldest first. */
    val lines: List<String> = emptyList(),
    /** A summary standing for a group of notifications that are also delivered one by one. */
    val isGroupSummary: Boolean = false,
    /** Ongoing: a download, a call, a persistent status. */
    val isOngoing: Boolean = false,
)

enum class ListenerAccess {
    /** This platform has no way to read other apps' notifications. */
    Unavailable,

    /** The user has not granted notification access, or took it back. */
    NotGranted,

    /** Granted, and the system has not bound the listener yet, or unbound it. */
    Granted,

    /** Bound: notifications in the filter are arriving. */
    Connected,
}

/**
 * Reads notifications other apps post, from the apps in [setFilter] and no others.
 *
 * Notification access is a special grant the user makes in Settings, not a runtime permission, and
 * the system binds the listener on its own schedule: it can start the app's process for it with no
 * screen, and it can unbind it. So [posted] is one stream for the life of the process, collected
 * by something that outlives screens (a session graph), and [active] catches up on whatever is
 * still in the shade after a gap.
 */
interface NotificationListener {
    val access: StateFlow<ListenerAccess>

    /**
     * Notifications from apps in the filter, as they are posted. One collector: each notification
     * is delivered once. Ones that arrive before anyone collects are kept until someone does.
     */
    val posted: Flow<PostedNotification>

    /**
     * The apps whose notifications may be read, by package name. Applied before anything is read:
     * a notification from any other app is dropped on arrival, its contents never touched.
     */
    fun setFilter(allow: Set<String>)

    /** Asks the system again whether access is granted, after the user may have changed it. */
    fun refresh()

    /** Opens the system screen where the user grants or removes access. */
    fun openAccessSettings()

    /** What is in the notification shade now, from apps in the filter. Empty unless connected. */
    suspend fun active(): List<PostedNotification>
}

var Feature.NotificationListener by CreateSlot<NotificationListener>()

/**
 * The filter, kept apart from any platform type so it can be tested everywhere: [admit] decides
 * on the package name alone, and only calls [read] (which copies the contents) when it is allowed.
 */
class NotificationGate {
    private var allow: Set<String> = emptySet()

    fun set(allow: Set<String>) {
        this.allow = allow.toSet()
    }

    fun allows(app: String): Boolean = app in allow

    inline fun admit(app: String, read: () -> PostedNotification): PostedNotification? =
        if (allows(app)) read() else null
}
