package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.surface.Activated
import dev.shibasis.reaktor.surface.Axis
import dev.shibasis.reaktor.surface.PressInput
import dev.shibasis.reaktor.surface.PressKernel
import dev.shibasis.reaktor.surface.PressProperties

@Composable
fun ActionGroup(
    modifier: Modifier = Modifier,
    axis: Axis = Axis.Horizontal,
    content: @Composable ActionGroupScope.() -> Unit,
) {
    val roving = rememberRoving(axis) {}
    Box(modifier.roving(roving), propagateMinConstraints = true) { ActionGroupScope(roving).content() }
}

@Stable
class ActionGroupScope internal constructor(private val roving: Roving) {
    @Composable
    fun Item(
        key: String,
        onActivate: () -> Unit,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        typeahead: String? = null,
        appearance: ButtonAppearance = LocalAppearances.current.button,
        content: @Composable () -> Unit,
    ) {
        val properties = PressProperties(enabled)
        val machine = rememberMachine(PressKernel, properties) { if (it == Activated) onActivate() }
        val source = rememberInteractions(machine)
        Box(
            modifier
                .rovingItem(roving, key, enabled, typeahead)
                .press(source, enabled, null) {
                    roving.point(key, focus = true)
                    machine.send(PressInput.Activate(machine.nextSequence()))
                },
            propagateMinConstraints = true,
        ) {
            val state = machine.state
            appearance.Content(properties, state, LocalThemeSnapshot.current, rememberFeedback(state.pressed, state.focusVisible), ButtonSlots(content))
        }
    }
}
