package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.BehaviorKernel
import dev.shibasis.reaktor.surface.CheckedChange
import dev.shibasis.reaktor.surface.PressInput
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.ToggleKernel
import dev.shibasis.reaktor.surface.ToggleProperties

typealias ToggleBehavior = BehaviorKernel<ToggleProperties, PressState, PressInput, CheckedChange>
typealias SwitchAppearance = ComposeAppearance<ToggleProperties, PressState, SwitchSlots>

class SwitchSlots(val content: @Composable () -> Unit)

@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    behavior: ToggleBehavior = ToggleKernel,
    appearance: SwitchAppearance = LocalAppearances.current.switch,
    content: @Composable () -> Unit = {},
) {
    val properties = ToggleProperties(checked, enabled)
    val machine = rememberMachine(behavior, properties) { onCheckedChange(it.checked) }
    val source = remember { MutableInteractionSource() }
    source.feed(machine)
    Box(
        modifier.toggleable(checked, source, indication = null, enabled = enabled, role = Role.Switch) {
            machine.send(PressInput.Activate(machine.nextSequence()))
        },
        propagateMinConstraints = true,
    ) {
        val state = machine.state
        appearance.Content(properties, state, LocalThemeSnapshot.current, rememberFeedback(state.pressed, state.focusVisible), SwitchSlots(content))
    }
}

@Stable
class ToggleState(initial: Boolean) {
    var checked by mutableStateOf(initial)
}

@Composable
fun rememberToggleState(initial: Boolean = false): ToggleState = remember { ToggleState(initial) }

@Composable
fun Switch(
    state: ToggleState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    behavior: ToggleBehavior = ToggleKernel,
    appearance: SwitchAppearance = LocalAppearances.current.switch,
    content: @Composable () -> Unit = {},
) = Switch(state.checked, { state.checked = it }, modifier, enabled, behavior, appearance, content)

val BareSwitch: SwitchAppearance = object : SwitchAppearance {
    @Composable
    override fun Content(properties: ToggleProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: SwitchSlots) {
        Row(
            Modifier.defaultMinSize(minHeight = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            slots.content()
            Box(
                Modifier.size(44.dp, 26.dp).clip(CircleShape).border(1.dp, Color.Gray, CircleShape).padding(3.dp),
                contentAlignment = if (properties.checked) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(if (properties.checked) Color.Black else Color.LightGray))
            }
        }
    }
}
