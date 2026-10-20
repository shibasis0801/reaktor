package dev.shibasis.reaktor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * Whether the person using this device has asked it for less movement, read live.
 *
 * Every platform has the setting and none of them applies it to an app's own animations: Android
 * scales its animator durations to zero and leaves a Compose `tween` alone, iOS and the browser
 * only report it. An app that does not ask moves anyway, for exactly the people who said motion
 * makes them ill (WCAG 2.3.3).
 *
 * - Android: *Remove animations* (the animator duration scale at zero).
 * - iOS: Settings › Accessibility › Motion › Reduce Motion.
 * - Browser: `prefers-reduced-motion: reduce`.
 * - Desktop: macOS's Reduce motion, GNOME's animations switch; false elsewhere, where there is no
 *   setting to read.
 *
 * Changes while the app is open are followed.
 */
@Composable
expect fun rememberReduceMotion(): Boolean

/**
 * The answer, provided once near the root so motion helpers can read it without each asking the
 * platform. Defaults to false: an app that never provides it keeps the behaviour it had.
 */
val LocalReduceMotion = compositionLocalOf { false }
