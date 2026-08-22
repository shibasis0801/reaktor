package dev.shibasis.reaktor.health

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature

/**
 * Whether the platform's health store can be read, and what stands in the way if not.
 */
enum class HealthAvailability {
    /** The store is present and the app holds at least the permissions it asked for. */
    Available,

    /** The store exists but has not granted what was asked for. */
    PermissionRequired,

    /**
     * The store is installed but too old to talk to, which Health Connect reports on devices
     * where it ships as an updatable app rather than as part of the system. Nothing on Apple
     * reports this — HealthKit is either there or the device is an iPad.
     */
    ProviderUpdateRequired,

    /** No health store on this device at all. */
    Unsupported,
}

/**
 * A single kind of access, asked for and granted one at a time.
 *
 * Both stores are per-type rather than all-or-nothing, and both let a user grant some and refuse
 * others, so a caller has to be able to ask what it actually got rather than assume.
 */
enum class HealthPermission {
    ReadSteps,
    ReadSessions,
    WriteSessions,
    ReadHeartRate,
    ReadEnergy,
}

/** Steps on one local day, keyed by ISO `yyyy-MM-dd`. */
data class DailySteps(val date: String, val steps: Int)

/** The shape of a recorded workout, kept narrow enough to mean the same thing on both stores. */
enum class SessionKind {
    StrengthTraining,
    Walking,
    Running,
    Cycling,
    Other,
}

/**
 * A workout as a health store understands it.
 *
 * Times are epoch millis rather than local dates because a session can cross midnight and both
 * stores record it against real instants.
 */
data class HealthSession(
    val id: String? = null,
    val startMillis: Long,
    val endMillis: Long,
    val title: String,
    val kind: SessionKind = SessionKind.StrengthTraining,
    val activeEnergyKcal: Double? = null,
)

/**
 * The device's health store — Health Connect on Android, HealthKit on Apple.
 *
 * Reading from the store rather than from a sensor is what makes a watch visible at all: a phone
 * pedometer only counts the steps the phone itself took, so a user who leaves their phone on a
 * desk and wears a watch is undercounted all day. Every wearable worth supporting writes into one
 * of these two stores, so one adapter reaches all of them without integrating any of them
 * directly. The exception is Apple Watch, which only ever writes to HealthKit on a paired iPhone;
 * that is Apple's boundary and no adapter can cross it.
 */
abstract class HealthAdapter<Controller>(
    controller: Controller,
) : Adapter<Controller>(controller) {
    /** Whether the store can be reached, before considering any particular permission. */
    abstract suspend fun availability(): HealthAvailability

    /** The subset of [permissions] already granted. */
    abstract suspend fun granted(permissions: Set<HealthPermission>): Set<HealthPermission>

    /**
     * Asks for [permissions] and reports what was actually granted, which may be fewer.
     *
     * Health Connect hands this to its own screen rather than a system dialog, so the caller can
     * be backgrounded while it happens.
     */
    abstract suspend fun request(permissions: Set<HealthPermission>): Set<HealthPermission>

    /**
     * Step totals per local day between [from] and [to], both ISO `yyyy-MM-dd` and inclusive.
     *
     * Days with nothing recorded are omitted rather than returned as zero, so a caller can tell
     * "walked nowhere" apart from "the watch had not synced yet" — see [StepMerge].
     */
    abstract suspend fun stepsByDay(from: String, to: String): List<DailySteps>

    /** Workouts recorded between [from] and [to], inclusive, by anything writing to the store. */
    abstract suspend fun sessions(from: String, to: String): List<HealthSession>

    /**
     * Records a workout, so what is logged here shows up in the ring, in Samsung Health, and in
     * whatever else reads the store. Returns false when the write was refused.
     */
    abstract suspend fun writeSession(session: HealthSession): Boolean
}

var Feature.Health by CreateSlot<HealthAdapter<*>>()

/**
 * Reconciling a health store with the phone's own pedometer.
 *
 * Both count the same walk, so adding them is the one thing that must never happen: a user with a
 * watch and a phone in their pocket would see roughly double.
 */
object StepMerge {
    /**
     * The store's totals, with the device's own count filling only the gaps.
     *
     * A day the store has never heard of falls back to the device. So does a day the store reports
     * as zero, because zero almost always means "nothing has synced yet" rather than "you did not
     * move" — a genuinely motionless day and an unsynced one look identical, and of the two,
     * showing the phone's count is the one that is sometimes right.
     */
    fun merge(store: List<DailySteps>, device: List<DailySteps>): List<DailySteps> {
        val byDate = LinkedHashMap<String, Int>()
        device.forEach { byDate[it.date] = it.steps }
        store.forEach { day -> if (day.steps > 0 || day.date !in byDate) byDate[day.date] = day.steps }
        return byDate.entries.sortedBy { it.key }.map { DailySteps(it.key, it.value) }
    }

    /** The count for one day out of a merged series, or null when neither source had it. */
    fun on(days: List<DailySteps>, date: String): Int? =
        days.firstOrNull { it.date == date }?.steps
}
