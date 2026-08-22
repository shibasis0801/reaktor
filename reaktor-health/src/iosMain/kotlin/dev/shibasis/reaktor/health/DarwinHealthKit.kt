package dev.shibasis.reaktor.health

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.NSDateComponents
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970
import platform.HealthKit.HKAuthorizationStatusSharingAuthorized
import platform.HealthKit.HKHealthStore
import platform.HealthKit.HKObjectQueryNoLimit
import platform.HealthKit.HKObjectType
import platform.HealthKit.HKQuantityType
import platform.HealthKit.HKQuery
import platform.HealthKit.HKSampleQuery
import platform.HealthKit.HKQuantityTypeIdentifierActiveEnergyBurned
import platform.HealthKit.HKQuantityTypeIdentifierHeartRate
import platform.HealthKit.HKQuantityTypeIdentifierStepCount
import platform.HealthKit.HKSampleType
import platform.HealthKit.HKStatisticsCollectionQuery
import platform.HealthKit.HKStatisticsOptionCumulativeSum
import platform.HealthKit.HKUnit
import platform.HealthKit.HKWorkout
import platform.HealthKit.HKWorkoutActivityType
import platform.HealthKit.HKWorkoutActivityTypeCycling
import platform.HealthKit.HKWorkoutActivityTypeOther
import platform.HealthKit.HKWorkoutActivityTypeRunning
import platform.HealthKit.HKWorkoutActivityTypeTraditionalStrengthTraining
import platform.HealthKit.HKWorkoutActivityTypeWalking
import platform.HealthKit.countUnit
import platform.HealthKit.predicateForSamplesWithStartDate
import kotlin.coroutines.resume

/**
 * HealthKit — the Apple side of the same idea, and the only way to see an Apple Watch.
 *
 * Two things differ from Health Connect in ways callers can feel:
 *
 * HealthKit deliberately never reveals whether a *read* was granted, because refusing to share
 * step data and having no step data are meant to be indistinguishable to an app. So [granted]
 * can only honestly answer for writes; for reads it reports what was asked for and lets an empty
 * result speak for itself.
 *
 * There is also no Apple Watch on Android to fall back to. A watch paired to an iPhone writes
 * here and nowhere else, which is why this implementation exists rather than being folded into
 * one cross-platform store.
 */
@OptIn(ExperimentalForeignApi::class)
class DarwinHealthKit : HealthAdapter<HKHealthStore>(HKHealthStore()) {

    private val store: HKHealthStore? get() = ref.get()

    private val isoDate = NSDateFormatter().apply {
        dateFormat = "yyyy-MM-dd"
        timeZone = NSTimeZone.localTimeZone
        locale = NSLocale("en_US_POSIX")
    }

    override suspend fun availability(): HealthAvailability = when {
        !HKHealthStore.isHealthDataAvailable() -> HealthAvailability.Unsupported
        else -> HealthAvailability.Available
    }

    /**
     * Only the write permission can be answered. A read that was refused looks exactly like a read
     * with nothing in it, by Apple's design, so claiming to know would be inventing an answer.
     */
    override suspend fun granted(permissions: Set<HealthPermission>): Set<HealthPermission> {
        val health = store ?: return emptySet()
        return permissions.filterTo(mutableSetOf()) { permission ->
            when (permission) {
                HealthPermission.WriteSessions -> {
                    val type = HKObjectType.workoutType()
                    health.authorizationStatusForType(type) == HKAuthorizationStatusSharingAuthorized
                }
                else -> true
            }
        }
    }

    override suspend fun request(permissions: Set<HealthPermission>): Set<HealthPermission> {
        val health = store ?: return emptySet()
        val read = permissions.mapNotNull { it.readType() }.toSet()
        val write = if (HealthPermission.WriteSessions in permissions) {
            setOf(HKObjectType.workoutType() as HKSampleType)
        } else {
            emptySet()
        }

        val ok = suspendCancellableCoroutine { continuation ->
            health.requestAuthorizationToShareTypes(write, read) { success, _ ->
                if (continuation.isActive) continuation.resume(success)
            }
        }
        return if (ok) granted(permissions) else emptySet()
    }

    override suspend fun stepsByDay(from: String, to: String): List<DailySteps> {
        val health = store ?: return emptyList()
        val type = HKQuantityType.quantityTypeForIdentifier(HKQuantityTypeIdentifierStepCount)
            ?: return emptyList()
        val start = isoDate.dateFromString(from) ?: return emptyList()
        val end = isoDate.dateFromString(to) ?: return emptyList()

        val calendar = NSCalendar.currentCalendar
        // Anchoring on midnight is what makes each bucket a local day rather than a rolling 24
        // hours measured from whenever the query happened to run.
        val anchor = calendar.startOfDayForDate(start)
        val daily = NSDateComponents().apply { day = 1 }
        val endOfLastDay = calendar.dateByAddingUnit(
            unit = NSCalendarUnitDay,
            value = 1,
            toDate = calendar.startOfDayForDate(end),
            options = 0u,
        ) ?: return emptyList()

        val query = HKStatisticsCollectionQuery(
            quantityType = type,
            quantitySamplePredicate = HKQuery.predicateForSamplesWithStartDate(
                startDate = anchor,
                endDate = endOfLastDay,
                options = 0u,
            ),
            options = HKStatisticsOptionCumulativeSum,
            anchorDate = anchor,
            intervalComponents = daily,
        )

        return suspendCancellableCoroutine { continuation ->
            query.initialResultsHandler = { _, collection, _ ->
                val days = mutableListOf<DailySteps>()
                collection?.enumerateStatisticsFromDate(anchor, endOfLastDay) { statistics, _ ->
                    val sum = statistics?.sumQuantity()
                    // Absent rather than zero, matching Health Connect: a day nothing was written
                    // for is a gap the caller may fill from the phone's own counter.
                    if (sum != null) {
                        val steps = sum.doubleValueForUnit(HKUnit.countUnit()).toInt()
                        days.add(DailySteps(isoDate.stringFromDate(statistics.startDate), steps))
                    }
                }
                if (continuation.isActive) continuation.resume(days.toList())
            }
            health.executeQuery(query)
        }
    }

    override suspend fun sessions(from: String, to: String): List<HealthSession> {
        val health = store ?: return emptyList()
        val start = isoDate.dateFromString(from) ?: return emptyList()
        val end = isoDate.dateFromString(to) ?: return emptyList()
        val calendar = NSCalendar.currentCalendar
        val endOfLastDay = calendar.dateByAddingUnit(
            unit = NSCalendarUnitDay,
            value = 1,
            toDate = calendar.startOfDayForDate(end),
            options = 0u,
        ) ?: return emptyList()

        val predicate = HKQuery.predicateForSamplesWithStartDate(
            startDate = calendar.startOfDayForDate(start),
            endDate = endOfLastDay,
            options = 0u,
        )

        return suspendCancellableCoroutine { continuation ->
            val query = HKSampleQuery(
                sampleType = HKObjectType.workoutType(),
                predicate = predicate,
                limit = HKObjectQueryNoLimit,
                sortDescriptors = null,
            ) { _, samples, _ ->
                val sessions = samples.orEmpty().filterIsInstance<HKWorkout>().map { workout ->
                    HealthSession(
                        id = workout.UUID.UUIDString,
                        startMillis = (workout.startDate.timeIntervalSince1970 * 1000).toLong(),
                        endMillis = (workout.endDate.timeIntervalSince1970 * 1000).toLong(),
                        title = "",
                        kind = workout.workoutActivityType.asSessionKind(),
                    )
                }
                if (continuation.isActive) continuation.resume(sessions)
            }
            health.executeQuery(query)
        }
    }

    override suspend fun writeSession(session: HealthSession): Boolean {
        val health = store ?: return false
        if (session.endMillis <= session.startMillis) return false
        val start = NSDate.dateWithTimeIntervalSince1970(session.startMillis / 1000.0)
        val end = NSDate.dateWithTimeIntervalSince1970(session.endMillis / 1000.0)

        @Suppress("DEPRECATION")
        val workout = HKWorkout.workoutWithActivityType(
            workoutActivityType = session.kind.asActivityType(),
            startDate = start,
            endDate = end,
        )

        return suspendCancellableCoroutine { continuation ->
            health.saveObject(workout) { success, _ ->
                if (continuation.isActive) continuation.resume(success)
            }
        }
    }

    private fun HealthPermission.readType(): HKObjectType? = when (this) {
        HealthPermission.ReadSteps ->
            HKQuantityType.quantityTypeForIdentifier(HKQuantityTypeIdentifierStepCount)
        HealthPermission.ReadHeartRate ->
            HKQuantityType.quantityTypeForIdentifier(HKQuantityTypeIdentifierHeartRate)
        HealthPermission.ReadEnergy ->
            HKQuantityType.quantityTypeForIdentifier(HKQuantityTypeIdentifierActiveEnergyBurned)
        HealthPermission.ReadSessions -> HKObjectType.workoutType()
        HealthPermission.WriteSessions -> null
    }

    private fun SessionKind.asActivityType(): HKWorkoutActivityType = when (this) {
        SessionKind.StrengthTraining -> HKWorkoutActivityTypeTraditionalStrengthTraining
        SessionKind.Walking -> HKWorkoutActivityTypeWalking
        SessionKind.Running -> HKWorkoutActivityTypeRunning
        SessionKind.Cycling -> HKWorkoutActivityTypeCycling
        SessionKind.Other -> HKWorkoutActivityTypeOther
    }

    private fun HKWorkoutActivityType.asSessionKind(): SessionKind = when (this) {
        HKWorkoutActivityTypeTraditionalStrengthTraining -> SessionKind.StrengthTraining
        HKWorkoutActivityTypeWalking -> SessionKind.Walking
        HKWorkoutActivityTypeRunning -> SessionKind.Running
        HKWorkoutActivityTypeCycling -> SessionKind.Cycling
        else -> SessionKind.Other
    }
}
