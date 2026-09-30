package dev.shibasis.reaktor.surface.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
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
    private val pressState: State<Float>,
    private val focusState: State<Float>,
    val reducedMotion: Boolean,
) {
    val press: Float get() = pressState.value
    val focus: Float get() = focusState.value
}

@Composable
fun rememberFeedback(pressed: Boolean, focusVisible: Boolean): ComposeFeedback {
    val reducedMotion = LocalReducedMotion.current
    val spec = if (reducedMotion) snap() else spring<Float>(dampingRatio = 0.78f, stiffness = 520f)
    val press = animateFloatAsState(if (pressed) 1f else 0f, spec, label = "press")
    val focus = animateFloatAsState(if (focusVisible) 1f else 0f, spec, label = "focus")
    return remember(press, focus, reducedMotion) { ComposeFeedback(press, focus, reducedMotion) }
}
