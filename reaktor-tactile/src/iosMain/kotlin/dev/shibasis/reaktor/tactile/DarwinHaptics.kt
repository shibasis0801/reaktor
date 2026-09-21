package dev.shibasis.reaktor.tactile

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSTimer
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType

/**
 * Plays [HapticPattern]s through the Taptic Engine.
 *
 * Uses UIKit's semantic generators rather than driving Core Haptics directly. Apple tunes these per
 * device and per accessibility setting, so a "success" feels like every other success on the phone
 * — which is the point of feedback the user is not looking at.
 *
 * [available] is true wherever UIKit is: the generators are safe no-ops on hardware without a
 * Taptic Engine, so claiming otherwise would just add a branch that never helps.
 *
 * [HapticPattern.Alarm] is the exception to the semantic rule, because UIKit has no three-second
 * feedback type to borrow — it is played as a train of heavy impacts on a timer. Core Haptics
 * could express it as one continuous event, at the cost of an engine to start, keep alive and
 * restart after every interruption, for a pattern the app plays once per rest period.
 */
@OptIn(ExperimentalForeignApi::class)
class DarwinHaptics : HapticsAdapter<Unit>(Unit) {
    private val notification = UINotificationFeedbackGenerator()
    private val impact = UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
    private val heavy = UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy)

    override val available: Boolean get() = true

    override fun play(pattern: HapticPattern) {
        runCatching {
            when (pattern) {
                // prepare() warms the engine so the tap is not late; without it the first buzz
                // after a quiet period lags behind the thing it is meant to confirm.
                HapticPattern.Tap -> {
                    impact.prepare()
                    impact.impactOccurred()
                }
                HapticPattern.Success -> {
                    notification.prepare()
                    notification.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
                }
                HapticPattern.Warning -> {
                    notification.prepare()
                    notification.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeWarning)
                }
                HapticPattern.Alert -> {
                    notification.prepare()
                    notification.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeError)
                }
                HapticPattern.Alarm -> playAlarm()
            }
        }
    }

    /**
     * Heavy impacts every [ALARM_INTERVAL_SECONDS] until [ALARM_DURATION_SECONDS] have passed.
     *
     * The timer holds the only reference to itself that matters, and invalidates from inside its
     * own block once the count is spent, so nothing outside has to remember to stop it.
     */
    private fun playAlarm() {
        heavy.prepare()
        var remaining = ALARM_PULSES
        heavy.impactOccurred()
        remaining -= 1
        if (remaining <= 0) return
        NSTimer.scheduledTimerWithTimeInterval(ALARM_INTERVAL_SECONDS, repeats = true) { timer ->
            heavy.impactOccurred()
            remaining -= 1
            if (remaining <= 0) timer?.invalidate()
        }
    }

    private companion object {
        /** How long the alarm lasts, in seconds. Long enough to cross a gym floor. */
        const val ALARM_DURATION_SECONDS = 3.0
        const val ALARM_INTERVAL_SECONDS = 0.2
        const val ALARM_PULSES = (ALARM_DURATION_SECONDS / ALARM_INTERVAL_SECONDS).toInt()
    }
}
