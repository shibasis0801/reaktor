package dev.shibasis.reaktor.surface.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import dev.shibasis.reaktor.surface.ComponentRecipe
import dev.shibasis.reaktor.surface.ThemeSnapshot

interface ComposeAppearance<P : Any, S : Any, Slots : Any> {
    @Composable
    fun Content(properties: P, state: S, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: Slots)
}

fun <P : Any, S : Any, V : Any, Slots : Any> composeAppearance(
    recipe: ComponentRecipe<P, S, V>,
    render: @Composable (V, ComposeFeedback, Slots) -> Unit,
): ComposeAppearance<P, S, Slots> = object : ComposeAppearance<P, S, Slots> {
    @Composable
    override fun Content(properties: P, state: S, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: Slots) {
        render(recipe.resolve(properties, state, theme), feedback, slots)
    }
}

@Stable
class ComposeFeedback internal constructor(
    private val scope: CoroutineScope,
    pressed: Boolean,
    focusVisible: Boolean,
    reducedMotion: Boolean,
) {
    private val pressTrack = FeedbackTrack(scope, if (pressed) 1f else 0f)
    private val focusTrack = FeedbackTrack(scope, if (focusVisible) 1f else 0f)
    var reducedMotion: Boolean by mutableStateOf(reducedMotion)
        private set
    val press: Float get() = pressTrack.value
    val focus: Float get() = focusTrack.value

    internal fun update(pressed: Boolean, focusVisible: Boolean, reducedMotion: Boolean) {
        this.reducedMotion = reducedMotion
        pressTrack.moveTo(if (pressed) 1f else 0f, reducedMotion)
        focusTrack.moveTo(if (focusVisible) 1f else 0f, reducedMotion)
    }
}

private class FeedbackTrack(private val scope: CoroutineScope, initial: Float) {
    private var target = initial
    private val resting = mutableFloatStateOf(initial)
    private var moving by mutableStateOf<Animatable<Float, AnimationVector1D>?>(null)

    val value: Float get() = moving?.value ?: resting.floatValue

    fun moveTo(next: Float, reducedMotion: Boolean) {
        if (next == target) return
        target = next
        if (reducedMotion) {
            moving = null
            resting.floatValue = next
            return
        }
        val animatable = moving ?: Animatable(value).also { moving = it }
        scope.launch {
            animatable.animateTo(next, FeedbackSpring)
            if (moving === animatable && target == next) {
                resting.floatValue = next
                moving = null
            }
        }
    }
}

private val FeedbackSpring = spring<Float>(dampingRatio = 0.78f, stiffness = 520f)

@Composable
fun rememberFeedback(pressed: Boolean, focusVisible: Boolean): ComposeFeedback {
    val reducedMotion = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val feedback = remember(scope) { ComposeFeedback(scope, pressed, focusVisible, reducedMotion) }
    SideEffect { feedback.update(pressed, focusVisible, reducedMotion) }
    return feedback
}
