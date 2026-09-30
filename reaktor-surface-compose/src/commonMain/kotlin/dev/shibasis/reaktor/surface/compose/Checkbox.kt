package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.PressInput
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.ToggleKernel
import dev.shibasis.reaktor.surface.ToggleProperties

enum class CheckState { Checked, Unchecked, Mixed }

data class CheckProperties(val state: CheckState, val enabled: Boolean)

typealias CheckboxAppearance = ComposeAppearance<CheckProperties, PressState, SwitchSlots>

@Composable
fun Checkbox(
    state: CheckState,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    behavior: ToggleBehavior = ToggleKernel,
    appearance: CheckboxAppearance = LocalAppearances.current.checkbox,
    content: @Composable () -> Unit = {},
) {
    val machine = rememberMachine(behavior, ToggleProperties(state == CheckState.Checked, enabled)) { onCheckedChange(it.checked) }
    val source = remember { MutableInteractionSource() }
    source.feed(machine)
    Box(
        modifier.triStateToggleable(state.toggleable, source, indication = null, enabled = enabled, role = Role.Checkbox) {
            machine.send(PressInput.Activate(machine.nextSequence()))
        },
        propagateMinConstraints = true,
    ) {
        val pressState = machine.state
        appearance.Content(CheckProperties(state, enabled), pressState, LocalThemeSnapshot.current, rememberFeedback(pressState.pressed, pressState.focusVisible), SwitchSlots(content))
    }
}

private val CheckState.toggleable: ToggleableState
    get() = when (this) {
        CheckState.Checked -> ToggleableState.On
        CheckState.Unchecked -> ToggleableState.Off
        CheckState.Mixed -> ToggleableState.Indeterminate
    }

val BareCheckbox: CheckboxAppearance = object : CheckboxAppearance {
    @Composable
    override fun Content(properties: CheckProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: SwitchSlots) {
        Row(Modifier.defaultMinSize(minHeight = 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).border(1.dp, Color.Gray), contentAlignment = Alignment.Center) {
                when (properties.state) {
                    CheckState.Checked -> Box(Modifier.size(12.dp).background(Color.Black))
                    CheckState.Mixed -> Box(Modifier.size(width = 12.dp, height = 2.dp).background(Color.Black))
                    CheckState.Unchecked -> Unit
                }
            }
            slots.content()
        }
    }
}
