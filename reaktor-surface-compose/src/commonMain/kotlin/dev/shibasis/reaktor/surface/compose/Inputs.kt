package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import dev.shibasis.reaktor.surface.PressInput
import kotlinx.coroutines.flow.Flow

@Composable
fun rememberInteractions(machine: Machine<*, *, PressInput, *>): MutableInteractionSource {
    val inputModes = rememberUpdatedState(LocalInputModeManager.current)
    return remember(machine) { MachineInteractions(machine) { inputModes.value.inputMode == InputMode.Keyboard } }
}

private class MachineInteractions(
    private val machine: Machine<*, *, PressInput, *>,
    private val keyboard: () -> Boolean,
) : MutableInteractionSource {
    private val delegate = MutableInteractionSource()
    private val contacts = mutableMapOf<PressInteraction.Press, Long>()
    private var next = 0L

    override val interactions: Flow<Interaction> get() = delegate.interactions

    override suspend fun emit(interaction: Interaction) {
        translate(interaction)
        delegate.emit(interaction)
    }

    override fun tryEmit(interaction: Interaction): Boolean {
        translate(interaction)
        return delegate.tryEmit(interaction)
    }

    private fun translate(interaction: Interaction) {
        when (interaction) {
            is PressInteraction.Press -> {
                val contact = ++next
                contacts[interaction] = contact
                machine.send(PressInput.Press(contact))
            }
            is PressInteraction.Release -> contacts.remove(interaction.press)?.let { machine.send(PressInput.Release(it)) }
            is PressInteraction.Cancel -> contacts.remove(interaction.press)?.let { machine.send(PressInput.Cancel(it)) }
            is FocusInteraction.Focus -> machine.send(PressInput.Focus(true, keyboard()))
            is FocusInteraction.Unfocus -> machine.send(PressInput.Focus(false, false))
            is HoverInteraction.Enter -> machine.send(PressInput.Hover(true))
            is HoverInteraction.Exit -> machine.send(PressInput.Hover(false))
        }
    }
}
