package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.Activated
import dev.shibasis.reaktor.surface.BehaviorKernel
import dev.shibasis.reaktor.surface.PressInput
import dev.shibasis.reaktor.surface.PressKernel
import dev.shibasis.reaktor.surface.PressProperties
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.Pressed
import dev.shibasis.reaktor.surface.ThemeSnapshot

typealias ButtonBehavior = BehaviorKernel<PressProperties, PressState, PressInput, Pressed>
typealias ButtonAppearance = ComposeAppearance<PressProperties, PressState, ButtonSlots>

class ButtonSlots(val content: @Composable () -> Unit)

@Composable
fun Button(
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    behavior: ButtonBehavior = PressKernel,
    appearance: ButtonAppearance = LocalAppearances.current.button,
    content: @Composable () -> Unit,
) {
    val properties = PressProperties(enabled, busy)
    val machine = rememberMachine(behavior, properties) { if (it == Activated) onActivate() }
    val source = remember { MutableInteractionSource() }
    source.feed(machine)
    Box(
        modifier
            .semantics { if (busy) stateDescription = "Busy" }
            .clickable(source, indication = null, enabled = enabled, role = Role.Button) {
                machine.send(PressInput.Activate(machine.nextSequence()))
            },
        propagateMinConstraints = true,
    ) {
        val state = machine.state
        appearance.Content(properties, state, LocalThemeSnapshot.current, rememberFeedback(state.pressed, state.focusVisible), ButtonSlots(content))
    }
}

val BareButton: ButtonAppearance = object : ButtonAppearance {
    @Composable
    override fun Content(properties: PressProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ButtonSlots) {
        Box(Modifier.defaultMinSize(48.dp, 48.dp), contentAlignment = Alignment.Center) { slots.content() }
    }
}
