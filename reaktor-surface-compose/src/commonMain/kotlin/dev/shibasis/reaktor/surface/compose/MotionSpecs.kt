package dev.shibasis.reaktor.surface.compose

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import dev.shibasis.reaktor.surface.Easing
import dev.shibasis.reaktor.surface.Motion
import dev.shibasis.reaktor.surface.SurfaceMotion

val Easing.curve: androidx.compose.animation.core.Easing
    get() = when (this) {
        Easing.Standard -> FastOutSlowInEasing
        Easing.Decelerate -> LinearOutSlowInEasing
        Easing.Accelerate -> FastOutLinearInEasing
        Easing.Linear -> LinearEasing
    }

fun <T> Motion.spec(reduced: Boolean = false): AnimationSpec<T> = when {
    reduced -> snap()
    this is Motion.Spring -> spring(dampingRatio = damping, stiffness = stiffness)
    this is Motion.Timed -> tween(millis, easing = easing.curve)
    else -> snap()
}

val surfaceMotion: SurfaceMotion
    @Composable @ReadOnlyComposable get() = LocalThemeSnapshot.current.motion

class MotionTrace(val millis: Long, val values: List<Float>)

fun Motion.trace(samples: Int = 64): MotionTrace {
    val animation = TargetBasedAnimation(spec<Float>(), Float.VectorConverter, 0f, 1f)
    val nanos = animation.durationNanos
    return MotionTrace(nanos / 1_000_000, List(samples + 1) { animation.getValueFromNanos(nanos * it / samples) })
}
