package dev.shibasis.reaktor.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import dev.shibasis.reaktor.core.adapters.PermissionAdapter
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * Reads `TYPE_STEP_COUNTER`, which reports steps since boot rather than per day. The daily
 * baseline is persisted so the count survives process death, and re-based on a new day or after a
 * reboot — see [StepMath].
 *
 * Many devices, including most emulators, have no step counter; those report
 * [StepAvailability.Unsupported] rather than a misleading zero.
 */
class AndroidStepCounter(
    context: Context,
    private val permissionAdapter: PermissionAdapter<*>? = null,
) : StepCounterAdapter<Context>(context.applicationContext) {
    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(SensorManager::class.java)
    private val store = appContext.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    private val sensor: Sensor?
        get() = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    override suspend fun availability(): StepAvailability = when {
        sensor == null -> StepAvailability.Unsupported
        !hasPermission() -> StepAvailability.PermissionRequired
        else -> StepAvailability.Available
    }

    override suspend fun requestPermission(): StepAvailability {
        if (sensor == null) return StepAvailability.Unsupported
        if (!needsRuntimePermission()) return StepAvailability.Available
        permissionAdapter?.request(Manifest.permission.ACTIVITY_RECOGNITION)
        return availability()
    }

    override suspend fun read(today: String): StepReading {
        val state = availability()
        if (state != StepAvailability.Available) return StepReading(state)

        val counter = awaitCounter() ?: return StepReading(StepAvailability.Available, stepsToday = readStored(today))

        val stored = loadBaseline()
        val baseline = StepMath.rebase(counter, stored, today)
        if (baseline != stored) saveBaseline(baseline)

        val steps = StepMath.stepsToday(counter, baseline, today)
        store.edit().putInt(KEY_LAST_STEPS, steps).putString(KEY_LAST_DATE, today).apply()
        return StepReading(StepAvailability.Available, steps)
    }

    /**
     * The step counter only emits on change, so a still device can stay silent. The timeout keeps
     * that from hanging the caller; the last stored total is used instead.
     */
    private suspend fun awaitCounter(): Long? {
        val target = sensor ?: return null
        val manager = sensorManager ?: return null
        return try {
            withTimeout(READ_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine { continuation ->
                    val listener = object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent) {
                            manager.unregisterListener(this)
                            if (continuation.isActive) continuation.resume(event.values.firstOrNull()?.toLong())
                        }

                        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                    }
                    manager.registerListener(listener, target, SensorManager.SENSOR_DELAY_UI)
                    continuation.invokeOnCancellation { manager.unregisterListener(listener) }
                }
            }
        } catch (_: TimeoutCancellationException) {
            null
        }
    }

    private fun readStored(today: String): Int =
        if (store.getString(KEY_LAST_DATE, null) == today) store.getInt(KEY_LAST_STEPS, 0) else 0

    private fun loadBaseline(): StepBaseline? {
        val date = store.getString(KEY_BASELINE_DATE, null) ?: return null
        return StepBaseline(date, store.getLong(KEY_BASELINE_COUNTER, 0L))
    }

    private fun saveBaseline(baseline: StepBaseline) {
        store.edit()
            .putString(KEY_BASELINE_DATE, baseline.date)
            .putLong(KEY_BASELINE_COUNTER, baseline.counterAtDayStart)
            .apply()
    }

    private fun needsRuntimePermission() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private fun hasPermission(): Boolean {
        if (!needsRuntimePermission()) return true
        return appContext.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val STORE_NAME = "reaktor_step_counter"
        const val KEY_BASELINE_DATE = "baseline_date"
        const val KEY_BASELINE_COUNTER = "baseline_counter"
        const val KEY_LAST_STEPS = "last_steps"
        const val KEY_LAST_DATE = "last_date"
        const val READ_TIMEOUT_MILLIS = 2_000L
    }
}
