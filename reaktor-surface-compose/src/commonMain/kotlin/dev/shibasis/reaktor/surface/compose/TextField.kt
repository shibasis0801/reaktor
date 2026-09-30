package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.PressKernel
import dev.shibasis.reaktor.surface.PressProperties
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot

data class FieldProperties(val enabled: Boolean, val error: Boolean, val empty: Boolean)

class FieldSlots(
    val editor: @Composable () -> Unit,
    val placeholder: (@Composable () -> Unit)?,
    val leading: (@Composable () -> Unit)?,
    val trailing: (@Composable () -> Unit)?,
)

interface FieldAppearance : ComposeAppearance<FieldProperties, PressState, FieldSlots> {
    @Composable
    fun textStyle(properties: FieldProperties, theme: ThemeSnapshot): TextStyle

    @Composable
    fun cursor(properties: FieldProperties, theme: ThemeSnapshot): Brush
}

@Composable
fun TextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: Boolean = false,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.SingleLine,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
    appearance: FieldAppearance = LocalAppearances.current.field,
    placeholder: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val properties = FieldProperties(enabled, error, state.text.isEmpty())
    val press = rememberMachine(PressKernel, PressProperties(enabled)) {}
    val source = remember { MutableInteractionSource() }
    source.feed(press)
    val theme = LocalThemeSnapshot.current
    val feedback = rememberFeedback(press.state.pressed, press.state.focused)
    BasicTextField(
        state = state,
        modifier = modifier,
        enabled = enabled,
        textStyle = appearance.textStyle(properties, theme),
        keyboardOptions = keyboardOptions,
        onKeyboardAction = onKeyboardAction,
        lineLimits = lineLimits,
        interactionSource = source,
        cursorBrush = appearance.cursor(properties, theme),
        decorator = TextFieldDecorator { editor ->
            appearance.Content(properties, press.state, theme, feedback, FieldSlots(editor, placeholder, leading, trailing))
        },
    )
}

val BareField: FieldAppearance = object : FieldAppearance {
    @Composable
    override fun textStyle(properties: FieldProperties, theme: ThemeSnapshot) = TextStyle.Default

    @Composable
    override fun cursor(properties: FieldProperties, theme: ThemeSnapshot): Brush = SolidColor(Color.Black)

    @Composable
    override fun Content(properties: FieldProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: FieldSlots) {
        Row(
            Modifier
                .defaultMinSize(minHeight = 48.dp)
                .border(if (state.focused) 2.dp else 1.dp, if (properties.error) Color.Red else Color.Gray)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            slots.leading?.invoke()
            Box(Modifier.weight(1f)) {
                if (properties.empty) slots.placeholder?.invoke()
                slots.editor()
            }
            slots.trailing?.invoke()
        }
    }
}
