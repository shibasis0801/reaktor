package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.Activated
import dev.shibasis.reaktor.surface.Held
import dev.shibasis.reaktor.surface.PressInput
import dev.shibasis.reaktor.surface.PressKernel
import dev.shibasis.reaktor.surface.PressProperties
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot

class ListRowSlots(
    val leading: (@Composable () -> Unit)?,
    val headline: @Composable () -> Unit,
    val supporting: (@Composable () -> Unit)?,
    val trailing: (@Composable () -> Unit)?,
)

typealias ListRowAppearance = ComposeAppearance<PressProperties, PressState, ListRowSlots>

@Composable
fun ListRow(
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onHold: (() -> Unit)? = null,
    behavior: ButtonBehavior = PressKernel,
    appearance: ListRowAppearance = LocalAppearances.current.listRow,
    leading: (@Composable () -> Unit)? = null,
    supporting: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    headline: @Composable () -> Unit,
) {
    val properties = PressProperties(enabled, holdable = onHold != null)
    val machine = rememberMachine(behavior, properties) { pressed ->
        when (pressed) {
            Activated -> onActivate()
            Held -> onHold?.invoke()
        }
    }
    val source = remember { MutableInteractionSource() }
    source.feed(machine)
    Box(
        modifier.combinedClickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onLongClick = onHold?.let { { machine.send(PressInput.Hold(machine.nextSequence())) } },
        ) {
            machine.send(PressInput.Activate(machine.nextSequence()))
        },
        propagateMinConstraints = true,
    ) {
        val state = machine.state
        appearance.Content(properties, state, LocalThemeSnapshot.current, rememberFeedback(state.pressed, state.focusVisible), ListRowSlots(leading, headline, supporting, trailing))
    }
}

val BareListRow: ListRowAppearance = object : ListRowAppearance {
    @Composable
    override fun Content(properties: PressProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ListRowSlots) {
        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            slots.leading?.invoke()
            Column(Modifier.weight(1f)) {
                slots.headline()
                slots.supporting?.invoke()
            }
            slots.trailing?.invoke()
        }
    }
}
