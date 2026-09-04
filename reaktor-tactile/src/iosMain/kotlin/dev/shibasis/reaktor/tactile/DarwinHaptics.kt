package dev.shibasis.reaktor.tactile

import kotlinx.cinterop.ExperimentalForeignApi
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
 */
@OptIn(ExperimentalForeignApi::class)
class DarwinHaptics : HapticsAdapter<Unit>(Unit) {
    private val notification = UINotificationFeedbackGenerator()
    private val impact = UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)

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
            }
        }
    }
}
