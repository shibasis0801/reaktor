package dev.shibasis.reaktor.sensors

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreMotion.CMAuthorizationStatusAuthorized
import platform.CoreMotion.CMAuthorizationStatusDenied
import platform.CoreMotion.CMAuthorizationStatusNotDetermined
import platform.CoreMotion.CMAuthorizationStatusRestricted
import platform.CoreMotion.CMPedometer
import platform.Foundation.NSCalendar
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone
import kotlin.coroutines.resume

/**
 * The iPhone's own pedometer, via CoreMotion.
 *
 * Unlike Android's monotonic since-boot counter, CoreMotion answers interval queries directly, so
 * none of [StepMath]'s baseline bookkeeping is needed here — asking for midnight-to-now returns
 * exactly that.
 *
 * This still only counts steps the phone itself was carried for. On Apple the better source is
 * usually HealthKit, which is where a paired Watch writes; this is the fallback for a phone with
 * no health permission, and the reason both exist is that neither alone is right.
 *
 * Requires `NSMotionUsageDescription` in the app's Info.plist. Without it, iOS terminates the app
 * on the first query rather than returning an error.
 */
@OptIn(ExperimentalForeignApi::class)
class DarwinStepCounter : StepCounterAdapter<CMPedometer>(CMPedometer()) {

    private val pedometer: CMPedometer? get() = ref.get()

    private val iso = NSDateFormatter().apply {
        dateFormat = "yyyy-MM-dd"
        locale = NSLocale("en_US_POSIX")
        timeZone = NSTimeZone.localTimeZone
    }

    override suspend fun availability(): StepAvailability = when {
        !CMPedometer.isStepCountingAvailable() -> StepAvailability.Unsupported
        else -> when (CMPedometer.authorizationStatus()) {
            CMAuthorizationStatusDenied, CMAuthorizationStatusRestricted ->
                StepAvailability.PermissionRequired
            CMAuthorizationStatusAuthorized, CMAuthorizationStatusNotDetermined ->
                StepAvailability.Available
            else -> StepAvailability.Unsupported
        }
    }

    /**
     * CoreMotion has no explicit request call — the permission sheet appears on the first query.
     * So this asks for today's steps and reports whatever the answer turned out to be.
     */
    override suspend fun requestPermission(): StepAvailability {
        if (!CMPedometer.isStepCountingAvailable()) return StepAvailability.Unsupported
        read(iso.stringFromDate(NSDate()))
        return availability()
    }

    override suspend fun read(today: String): StepReading {
        val state = availability()
        if (state != StepAvailability.Available) return StepReading(state)
        val counter = pedometer ?: return StepReading(StepAvailability.Unsupported)

        val now = NSDate()
        val startOfDay = NSCalendar.currentCalendar.startOfDayForDate(now)

        val steps = suspendCancellableCoroutine { continuation ->
            counter.queryPedometerDataFromDate(startOfDay, toDate = now) { data, _ ->
                if (continuation.isActive) continuation.resume(data?.numberOfSteps?.intValue)
            }
        }

        // A refusal arrives as a null result rather than a thrown error, so an unreadable query is
        // reported as needing permission instead of as a confident zero.
        return steps?.let { StepReading(StepAvailability.Available, it) }
            ?: StepReading(StepAvailability.PermissionRequired)
    }
}
