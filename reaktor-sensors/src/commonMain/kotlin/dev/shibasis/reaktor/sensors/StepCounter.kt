package dev.shibasis.reaktor.sensors

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature

/**
 * Where a step reading came from, so callers can tell "zero steps" apart from "no data".
 */
enum class StepAvailability {
    /** The device exposes a step counter and it is readable. */
    Available,

    /** Hardware is present but the user has not granted activity recognition. */
    PermissionRequired,

    /** No step counter on this device — common on emulators and older hardware. */
    Unsupported,
}

data class StepReading(
    val availability: StepAvailability,
    val stepsToday: Int = 0,
) {
    val hasData: Boolean get() = availability == StepAvailability.Available
}

/**
 * Reads the device's daily step count.
 *
 * Platform step counters are monotonic since boot rather than per-day, so implementations keep a
 * daily baseline and report the difference — see [StepMath], which holds that arithmetic in
 * testable, platform-free form.
 */
abstract class StepCounterAdapter<Controller>(
    controller: Controller,
) : Adapter<Controller>(controller) {
    /** Whether this device can report steps at all, and whether it needs permission first. */
    abstract suspend fun availability(): StepAvailability

    /** Asks for the activity-recognition permission. Returns the availability afterwards. */
    abstract suspend fun requestPermission(): StepAvailability

    /**
     * Steps taken on [today] (ISO `yyyy-MM-dd`). The caller supplies the date so the adapter does
     * not need a calendar of its own and callers stay in control of the timezone.
     */
    abstract suspend fun read(today: String): StepReading
}

var Feature.Steps by CreateSlot<StepCounterAdapter<*>>()

/** The per-day baseline a monotonic counter is measured against. */
data class StepBaseline(
    val date: String,
    val counterAtDayStart: Long,
)

/**
 * Converts a monotonic since-boot step counter into a per-day total.
 *
 * Three cases have to be handled: the first ever reading, the first reading of a new day, and a
 * reboot — which resets the hardware counter and would otherwise produce a negative total.
 */
object StepMath {
    /** The baseline that should be stored for [today] given the latest [counter]. */
    fun rebase(counter: Long, stored: StepBaseline?, today: String): StepBaseline = when {
        // First run, or the day rolled over: today starts from wherever the counter is now.
        stored == null || stored.date != today -> StepBaseline(today, counter)
        // Counter went backwards, so the device rebooted: everything it reports now is from today.
        counter < stored.counterAtDayStart -> StepBaseline(today, 0)
        else -> stored
    }

    /** Steps on [today], measured against the rebased baseline. Never negative. */
    fun stepsToday(counter: Long, stored: StepBaseline?, today: String): Int {
        val baseline = rebase(counter, stored, today)
        return (counter - baseline.counterAtDayStart).coerceAtLeast(0).toInt()
    }
}
