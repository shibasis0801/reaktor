package dev.shibasis.reaktor.tactile

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import java.lang.ref.WeakReference

/**
 * Plays [HapticPattern]s through the window's haptic feedback, or the system vibrator.
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
    private val activity = (context as? Activity)?.let(::WeakReference)

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
        val view = activity?.get()?.window?.decorView
        if (view != null && pattern != HapticPattern.Alarm) {
            runCatching { view.performHapticFeedback(feedbackOf(pattern)) }
            return
        }
        val device = vibrator?.takeIf { it.hasVibrator() } ?: return
        // Never throws: a device that refuses to vibrate is not a reason for anything to fail.
        runCatching {
            val (timings, amplitudes) = when (pattern) {
                HapticPattern.Tap, HapticPattern.Selection -> longArrayOf(0, 18) to intArrayOf(0, 120)
                HapticPattern.Threshold -> longArrayOf(0, 30) to intArrayOf(0, 200)
                HapticPattern.Success -> longArrayOf(0, 45, 90, 90) to intArrayOf(0, 180, 0, 255)
                HapticPattern.Warning -> longArrayOf(0, 30, 70, 30) to intArrayOf(0, 160, 0, 160)
                HapticPattern.Alert -> longArrayOf(0, 70, 60, 70, 60, 70) to
                    intArrayOf(0, 255, 0, 255, 0, 255)
                // Three seconds, as five pulses rather than one long buzz. A continuous three
                // seconds is unpleasant to hold and easy to sleep through once it stops being
                // new; a train that keeps restarting stays a signal for its whole length.
                HapticPattern.Alarm -> longArrayOf(
                    0, 400, 200, 400, 200, 400, 200, 400, 200, 400, 200,
                ) to intArrayOf(0, 255, 0, 255, 0, 255, 0, 255, 0, 255, 0)
            }
            device.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        }
    }

    private fun feedbackOf(pattern: HapticPattern): Int {
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val confirmable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        return when (pattern) {
            HapticPattern.Tap -> HapticFeedbackConstants.VIRTUAL_KEY
            HapticPattern.Selection -> if (modern) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
            HapticPattern.Threshold -> if (modern) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.LONG_PRESS
            HapticPattern.Success -> if (confirmable) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
            HapticPattern.Warning, HapticPattern.Alert, HapticPattern.Alarm -> if (confirmable) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        }
    }
}
