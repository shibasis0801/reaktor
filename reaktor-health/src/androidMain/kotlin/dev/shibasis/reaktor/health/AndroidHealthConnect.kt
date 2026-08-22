package dev.shibasis.reaktor.health

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dev.shibasis.reaktor.core.extensions.getResultFromActivity
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import androidx.health.connect.client.permission.HealthPermission as HcPermission

/**
 * Health Connect — the store every Android wearable writes into.
 *
 * Since Android 14 it is part of the platform rather than an installable app, which is why
 * [availability] can answer [HealthAvailability.ProviderUpdateRequired] on older devices and
 * never on newer ones.
 *
 * Data appearing here is not this adapter's doing: it shows up because the user's watch app —
 * Samsung Health, Fitbit, Garmin Connect — has been told to write to Health Connect. An empty
 * read is far more often that switch being off than a person who did not move.
 */
class AndroidHealthConnect(
    context: Context,
) : HealthAdapter<Context>(context) {
    private val appContext = context.applicationContext

    private val client: HealthConnectClient?
        get() = runCatching {
            if (HealthConnectClient.getSdkStatus(appContext) == HealthConnectClient.SDK_AVAILABLE) {
                HealthConnectClient.getOrCreate(appContext)
            } else {
                null
            }
        }.getOrNull()

    override suspend fun availability(): HealthAvailability =
        when (HealthConnectClient.getSdkStatus(appContext)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.Available
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthAvailability.ProviderUpdateRequired
            else -> HealthAvailability.Unsupported
        }

    override suspend fun granted(permissions: Set<HealthPermission>): Set<HealthPermission> {
        val controller = client?.permissionController ?: return emptySet()
        val held = runCatching { controller.getGrantedPermissions() }.getOrElse { return emptySet() }
        return permissions.filter { it.asHealthConnect() in held }.toSet()
    }

    /**
     * Health Connect owns this screen, so the request goes through its own contract rather than
     * the system permission dialog. Without an activity to launch from there is nowhere to show
     * it, and the honest answer is whatever was already granted.
     */
    override suspend fun request(permissions: Set<HealthPermission>): Set<HealthPermission> {
        val activity = ref.get() as? ComponentActivity ?: return granted(permissions)
        val wanted = permissions.map { it.asHealthConnect() }.toSet()
        val result = runCatching {
            activity.getResultFromActivity(
                PermissionController.createRequestPermissionResultContract(),
                wanted,
            )
        }.getOrElse { return granted(permissions) }
        return permissions.filter { it.asHealthConnect() in result }.toSet()
    }

    override suspend fun stepsByDay(from: String, to: String): List<DailySteps> {
        val connect = client ?: return emptyList()
        val start = runCatching { LocalDate.parse(from) }.getOrNull() ?: return emptyList()
        val end = runCatching { LocalDate.parse(to) }.getOrNull() ?: return emptyList()
        if (end.isBefore(start)) return emptyList()

        val rows = runCatching {
            connect.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    // The range runs to the start of the day after [to], since the filter's end is
                    // exclusive and dropping the final day is the kind of bug nobody notices.
                    timeRangeFilter = TimeRangeFilter.between(
                        start.atStartOfDay(),
                        end.plusDays(1).atStartOfDay(),
                    ),
                    timeRangeSlicer = Period.ofDays(1),
                ),
            )
        }.getOrElse { return emptyList() }

        // A day with no total is left out rather than reported as zero: the caller decides what
        // an absent day means, and StepMerge can only fill a gap it can see.
        return rows.mapNotNull { row ->
            val steps = row.result[StepsRecord.COUNT_TOTAL] ?: return@mapNotNull null
            DailySteps(row.startTime.toLocalDate().toString(), steps.toInt())
        }
    }

    override suspend fun sessions(from: String, to: String): List<HealthSession> {
        val connect = client ?: return emptyList()
        val zone = ZoneId.systemDefault()
        val start = runCatching { LocalDate.parse(from).atStartOfDay(zone).toInstant() }.getOrNull()
            ?: return emptyList()
        val end = runCatching { LocalDate.parse(to).plusDays(1).atStartOfDay(zone).toInstant() }.getOrNull()
            ?: return emptyList()

        val response = runCatching {
            connect.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            )
        }.getOrElse { return emptyList() }

        return response.records.map { record ->
            HealthSession(
                id = record.metadata.id,
                startMillis = record.startTime.toEpochMilli(),
                endMillis = record.endTime.toEpochMilli(),
                title = record.title.orEmpty(),
                kind = record.exerciseType.asSessionKind(),
            )
        }
    }

    override suspend fun writeSession(session: HealthSession): Boolean {
        val connect = client ?: return false
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(session.startMillis)
        val end = Instant.ofEpochMilli(session.endMillis)
        if (!end.isAfter(start)) return false

        return runCatching {
            connect.insertRecords(
                listOf(
                    ExerciseSessionRecord(
                        startTime = start,
                        startZoneOffset = zone.rules.getOffset(start),
                        endTime = end,
                        endZoneOffset = zone.rules.getOffset(end),
                        exerciseType = session.kind.asExerciseType(),
                        title = session.title,
                        // Pinned to connect-client 1.1.0-alpha07, where this is a constructor
                        // argument; later versions replace it with Metadata.manualEntry().
                        metadata = Metadata(recordingMethod = Metadata.RECORDING_METHOD_MANUAL_ENTRY),
                    ),
                ),
            )
            true
        }.getOrElse { false }
    }

    private fun HealthPermission.asHealthConnect(): String = when (this) {
        HealthPermission.ReadSteps -> HcPermission.getReadPermission(StepsRecord::class)
        HealthPermission.ReadSessions -> HcPermission.getReadPermission(ExerciseSessionRecord::class)
        HealthPermission.WriteSessions -> HcPermission.getWritePermission(ExerciseSessionRecord::class)
        HealthPermission.ReadHeartRate -> HcPermission.getReadPermission(HeartRateRecord::class)
        HealthPermission.ReadEnergy -> HcPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
    }

    private fun SessionKind.asExerciseType(): Int = when (this) {
        SessionKind.StrengthTraining -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
        SessionKind.Walking -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
        SessionKind.Running -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
        SessionKind.Cycling -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
        SessionKind.Other -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
    }

    private fun Int.asSessionKind(): SessionKind = when (this) {
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> SessionKind.StrengthTraining
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> SessionKind.Walking
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> SessionKind.Running
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> SessionKind.Cycling
        else -> SessionKind.Other
    }
}
