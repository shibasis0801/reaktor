package dev.shibasis.reaktor.tactile

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature

/**
 * What a buzz is for, rather than how long it lasts.
 *
 * Named by meaning because the two platforms disagree about the mechanism: iOS exposes a small set
 * of semantic feedback types played through the Taptic Engine and actively discourages arbitrary
 * durations, while Android takes a duration and an amplitude. A caller that asked for "80
 * milliseconds" would be writing Android and hoping iOS approximated it; a caller that asks for
 * [Success] gets the right thing on both.
 */
enum class HapticPattern {
    /** A single light tick. Confirms a tap landed — the lightest thing worth feeling. */
    Tap,

    /** Something finished, and the user may not be looking at the screen. */
    Success,

    /** Something needs attention but nothing is broken. */
    Warning,

    /** Something failed, or is about to be destructive. */
    Alert,
}

/**
 * Plays haptic feedback.
 *
 * Deliberately fire-and-forget and never throws: haptics are an enhancement, and an app that
 * crashed because a device had no vibrator, or because the user switched feedback off, would be
 * broken by its own decoration. Implementations no-op wherever the hardware or the user says no.
 */
abstract class HapticsAdapter<Controller>(
    controller: Controller,
) : Adapter<Controller>(controller) {
    /** True when this device can actually produce feedback. Callers rarely need to ask. */
    abstract val available: Boolean

    /** Plays [pattern], or does nothing where it cannot. */
    abstract fun play(pattern: HapticPattern)
}

var Feature.Haptics by CreateSlot<HapticsAdapter<*>>()
