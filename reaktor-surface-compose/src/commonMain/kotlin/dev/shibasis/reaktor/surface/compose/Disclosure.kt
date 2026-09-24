package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import dev.shibasis.reaktor.surface.BehaviorKernel
import dev.shibasis.reaktor.surface.DisclosureEvent
import dev.shibasis.reaktor.surface.DisclosureInput
import dev.shibasis.reaktor.surface.DisclosureKernel
import dev.shibasis.reaktor.surface.DisclosureProperties
import dev.shibasis.reaktor.surface.DisclosureState
import dev.shibasis.reaktor.surface.PartKey
import dev.shibasis.reaktor.surface.PressKernel
import dev.shibasis.reaktor.surface.PressProperties

typealias DisclosureBehavior = BehaviorKernel<DisclosureProperties, DisclosureState, DisclosureInput, DisclosureEvent>
typealias PanelAppearance = ComposeAppearance<DisclosureProperties, DisclosureState, PanelSlots>

class PanelSlots(val content: @Composable () -> Unit)

@Stable
class ExpandedState(initial: Boolean) {
    var expanded by mutableStateOf(initial)
}

@Composable
fun rememberExpandedState(initial: Boolean = false): ExpandedState = remember { ExpandedState(initial) }

internal val CloseKey = PartKey("close")

@Stable
internal class Disclosure(
    val machine: Machine<DisclosureProperties, DisclosureState, DisclosureInput, DisclosureEvent>,
    val properties: DisclosureProperties,
    val choices: MutableMap<String, () -> Unit>,
    val closes: MutableSet<Any>,
)

@Composable
internal fun rememberDisclosure(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    enabled: Boolean,
    behavior: DisclosureBehavior,
): Disclosure {
    val choices = remember { linkedMapOf<String, () -> Unit>() }
    val closes = remember { mutableSetOf<Any>() }
    val properties = DisclosureProperties(expanded, enabled)
    val machine = rememberMachine(behavior, properties) { event ->
        when (event) {
            is DisclosureEvent.ExpandedChange -> onExpandedChange(event.expanded)
            is DisclosureEvent.Chosen -> choices[event.key]?.invoke()
        }
    }
    return Disclosure(machine, properties, choices, closes)
}

@Composable
internal fun Disclosure.Trigger(modifier: Modifier, appearance: ButtonAppearance, content: @Composable () -> Unit) =
    PressPart(modifier.part(machine, DisclosureKernel.Trigger), properties.enabled, appearance, content) {
        machine.send(DisclosureInput.Toggle(machine.nextSequence()))
    }

@Composable
internal fun Disclosure.Choice(
    key: String,
    onActivate: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    appearance: ButtonAppearance,
    content: @Composable () -> Unit,
) {
    val latest by rememberUpdatedState(onActivate)
    DisposableEffect(machine, key) {
        val choice = { latest() }
        choices[key] = choice
        onDispose { if (choices[key] === choice) choices.remove(key) }
    }
    PressPart(modifier.part(machine, PartKey(key)), enabled, appearance, content) {
        machine.send(DisclosureInput.Choose(key, machine.nextSequence()))
    }
}

@Composable
internal fun Disclosure.Close(modifier: Modifier, appearance: ButtonAppearance, content: @Composable () -> Unit) {
    DisposableEffect(machine) {
        val token = Any()
        closes.add(token)
        onDispose { closes.remove(token) }
    }
    PressPart(modifier.part(machine, CloseKey), true, appearance, content) {
        machine.send(DisclosureInput.Dismiss)
    }
}

@Composable
internal fun Disclosure.Panel(appearance: PanelAppearance, first: () -> PartKey?, content: @Composable () -> Unit) {
    appearance.Content(properties, machine.state, LocalThemeSnapshot.current, rememberFeedback(false, false), PanelSlots(content))
    DisposableEffect(machine) {
        machine.send(DisclosureInput.Mounted(first()))
        onDispose { machine.send(DisclosureInput.Unmounted) }
    }
}

@Composable
internal fun PressPart(
    modifier: Modifier,
    enabled: Boolean,
    appearance: ButtonAppearance,
    content: @Composable () -> Unit,
    onActivate: () -> Unit,
) {
    val properties = PressProperties(enabled)
    val press = rememberMachine(PressKernel, properties) {}
    val source = remember { MutableInteractionSource() }
    source.feed(press)
    Box(modifier.clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onActivate), propagateMinConstraints = true) {
        val state = press.state
        appearance.Content(properties, state, LocalThemeSnapshot.current, rememberFeedback(state.pressed, state.focusVisible), ButtonSlots(content))
    }
}
