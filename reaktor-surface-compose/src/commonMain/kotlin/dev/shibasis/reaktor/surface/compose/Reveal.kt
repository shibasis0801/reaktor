package dev.shibasis.reaktor.surface.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.surface.Motion

enum class Reveal { Fade, Rise, Expand, Pop }

fun <T> Motion.finite(reduced: Boolean = false): FiniteAnimationSpec<T> = when {
    reduced -> snap()
    this is Motion.Spring -> spring(dampingRatio = damping, stiffness = stiffness)
    this is Motion.Timed -> tween(millis, easing = easing.curve)
    else -> snap()
}

fun Reveal.enter(motion: Motion, reduced: Boolean = false): EnterTransition = when (this) {
    Reveal.Fade -> fadeIn(motion.finite(reduced))
    Reveal.Rise -> fadeIn(motion.finite(reduced)) + slideInVertically(motion.finite(reduced)) { it / 3 }
    Reveal.Expand -> fadeIn(motion.finite(reduced)) + expandVertically(motion.finite(reduced))
    Reveal.Pop -> fadeIn(motion.finite(reduced)) + scaleIn(motion.finite(reduced), initialScale = .85f)
}

fun Reveal.exit(motion: Motion, reduced: Boolean = false): ExitTransition = when (this) {
    Reveal.Fade -> fadeOut(motion.finite(reduced))
    Reveal.Rise -> fadeOut(motion.finite(reduced)) + slideOutVertically(motion.finite(reduced)) { it / 3 }
    Reveal.Expand -> fadeOut(motion.finite(reduced)) + shrinkVertically(motion.finite(reduced))
    Reveal.Pop -> fadeOut(motion.finite(reduced)) + scaleOut(motion.finite(reduced), targetScale = .85f)
}

@Composable
fun Revealed(visible: Boolean, reveal: Reveal = Reveal.Fade, motion: Motion = surfaceMotion.standard, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduced = LocalSurfaceEnvironment.current.reducedMotion
    AnimatedVisibility(visible, modifier, enter = reveal.enter(motion, reduced), exit = reveal.exit(motion, reduced)) { content() }
}
