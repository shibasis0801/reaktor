package dev.shibasis.reaktor.tactile

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Plays [HapticPattern]s through the system vibrator.
 *
 * Patterns are expressed as waveforms rather than single pulses because a bare buzz of one length
 * is hard to tell from another when the phone is on a bench two feet away — the gap in a
 * double-tap survives that, where fifty extra milliseconds does not.
 *
 * Amplitude control arrived in API 26 and the manager indirection in API 31, so both are guarded.
 * The `VIBRATE` permission is normal rather than dangerous, so it is granted at install and never
 * needs asking for.
 */
class AndroidHaptics(context: Context) : HapticsAdapter<Context>(context.applicationContext) {
    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }.getOrNull()

    override val available: Boolean
        get() = vibrator?.hasVibrator() == true

    override fun play(pattern: HapticPattern) {
        val device = vibrator?.takeIf { it.hasVibrator() } ?: return
        // Never throws: a device that refuses to vibrate is not a reason for anything to fail.
        runCatching {
            val (timings, amplitudes) = when (pattern) {
                HapticPattern.Tap -> longArrayOf(0, 18) to intArrayOf(0, 120)
                HapticPattern.Success -> longArrayOf(0, 45, 90, 90) to intArrayOf(0, 180, 0, 255)
                HapticPattern.Warning -> longArrayOf(0, 30, 70, 30) to intArrayOf(0, 160, 0, 160)
                HapticPattern.Alert -> longArrayOf(0, 70, 60, 70, 60, 70) to
                    intArrayOf(0, 255, 0, 255, 0, 255)
            }
            device.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        }
    }
}
