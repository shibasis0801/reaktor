package dev.shibasis.reaktor.notification.ongoing

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Something the user can act on without opening the app: the primary action on a live activity.
 *
 * [id] comes back through [OngoingActivities.responses] when it is tapped. Nothing here executes
 * on its own — the app decides what an action means.
 */
@Serializable
data class OngoingAction(
    val id: String,
    val label: String,
)

/**
 * A task in progress, small enough for a lock screen.
 *
 * The countdown is expressed as [endsAtMillis] — a wall-clock instant — and deliberately not as a
 * number of seconds remaining. A lock screen renders a live timer from an end time on its own,
 * with no process of ours awake to tick it. Sending "42 seconds left" instead would freeze the
 * moment the app is backgrounded, and be a lie the moment it is killed, which for a phone face
 * down on a bench is the ordinary case rather than the edge one.
 */
@Serializable
data class OngoingActivityState(
    /** What is running, e.g. the session or job name. */
    val title: String,
    /** The line under the title — what is happening right now. */
    val line: String = "",
    /** A third line for detail, shown where the platform has room for it. */
    val detail: String = "",
    /** When the current countdown ends, or null when nothing is counting. */
    val endsAtMillis: Long? = null,
    /** How long the current countdown runs in total, for platforms that draw progress. */
    val totalSeconds: Int = 0,
    val actions: List<OngoingAction> = emptyList(),
)

/**
 * A live, user-visible activity that outlives the screen it started on.
 *
 * An ongoing notification on Android and a Live Activity on Darwin — the same idea either side:
 * something the system keeps on the lock screen while the app is not in front, and takes down
 * when [end] is called.
 *
 * Best-effort in both directions. A platform that cannot show one, or a user who has refused
 * notifications, is not an error the caller has to handle: the app carries on and the activity
 * simply is not there.
 */
expect object OngoingActivities {

    /** True when this platform and this install can actually show one. */
    fun isAvailable(): Boolean

    /** Shows the activity, or replaces the one already running. */
    fun start(state: OngoingActivityState)

    /** Revises what is on screen. A no-op when nothing is running. */
    fun update(state: OngoingActivityState)

    /** Takes it down. Safe to call when nothing is running. */
    fun end()

    /**
     * [OngoingAction.id]s the user has tapped.
     *
     * Replayed, because the tap can land while the app is not running: the platform wakes the
     * process to deliver it, and whatever collects this may not be listening yet.
     */
    val responses: Flow<String>
}
