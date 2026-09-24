package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import dev.shibasis.reaktor.surface.PressInput

@Composable
fun MutableInteractionSource.feed(machine: Machine<*, *, PressInput, *>) {
    val inputModes = LocalInputModeManager.current
    LaunchedEffect(this, machine) {
        val contacts = mutableMapOf<PressInteraction.Press, Long>()
        var next = 0L
        interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    val contact = ++next
                    contacts[interaction] = contact
                    machine.send(PressInput.Press(contact))
                }
                is PressInteraction.Release -> contacts.remove(interaction.press)?.let { machine.send(PressInput.Release(it)) }
                is PressInteraction.Cancel -> contacts.remove(interaction.press)?.let { machine.send(PressInput.Cancel(it)) }
                is FocusInteraction.Focus -> machine.send(PressInput.Focus(true, inputModes.inputMode == InputMode.Keyboard))
                is FocusInteraction.Unfocus -> machine.send(PressInput.Focus(false, false))
                is HoverInteraction.Enter -> machine.send(PressInput.Hover(true))
                is HoverInteraction.Exit -> machine.send(PressInput.Hover(false))
            }
        }
    }
}
