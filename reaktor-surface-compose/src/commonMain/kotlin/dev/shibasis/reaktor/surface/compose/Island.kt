package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.Availability
import dev.shibasis.reaktor.surface.CommandId
import dev.shibasis.reaktor.surface.CommandSet
import dev.shibasis.reaktor.surface.KeyName
import dev.shibasis.reaktor.surface.ThemeSnapshot

data class IslandState(val focusVisible: Boolean)

class IslandSlots(val content: @Composable () -> Unit)

typealias IslandAppearance = ComposeAppearance<Unit, IslandState, IslandSlots>

@Composable
fun Island(
    commands: CommandSet,
    onInvoke: (CommandId) -> Unit,
    modifier: Modifier = Modifier,
    focusable: Boolean = false,
    appearance: IslandAppearance = LocalAppearances.current[Appearance.Island],
    content: @Composable () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val hatch = remember { EscapeHatch() }
    var focused by remember { mutableStateOf(false) }
    val invoke by rememberUpdatedState(onInvoke)
    val actions = commands.commands
        .filter { it.availability == Availability.Available }
        .map { command -> CustomAccessibilityAction(command.label) { invoke(command.id); true } }
    Box(
        modifier
            .semantics { customActions = actions }
            .commands(commands, onInvoke)
            .onPreviewKeyEvent { hatch.onKey(it, focusManager) }
            .then(if (focusable) Modifier.onFocusChanged { focused = it.isFocused }.focusable() else Modifier.focusGroup()),
        propagateMinConstraints = true,
    ) {
        val visible = focusable && focused && keyboard
        appearance.Content(Unit, IslandState(visible), LocalThemeSnapshot.current, rememberFeedback(pressed = false, focusVisible = visible), IslandSlots(content))
    }
}

private class EscapeHatch {
    private var armed = false

    fun onKey(event: KeyEvent, focus: FocusManager): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val stroke = event.stroke() ?: return false
        val plain = !stroke.meta && !stroke.control && !stroke.alt
        if (armed && plain && stroke.key == KeyName.Tab) {
            armed = false
            focus.moveFocus(if (stroke.shift) FocusDirection.Previous else FocusDirection.Next)
            return true
        }
        armed = plain && !stroke.shift && stroke.key == KeyName.Escape
        return false
    }
}

val BareIsland: IslandAppearance = object : IslandAppearance {
    @Composable
    override fun Content(properties: Unit, state: IslandState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: IslandSlots) {
        Box(
            Modifier.drawWithContent {
                drawContent()
                if (state.focusVisible) drawRect(Color.Gray, style = Stroke(1.dp.toPx()))
            },
            propagateMinConstraints = true,
        ) { slots.content() }
    }
}
