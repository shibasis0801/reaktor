package dev.shibasis.reaktor.ui.forms

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How a [FormTextField] looks: the app's tokens, passed in. */
@Immutable
data class FormFieldStyle(
    val label: TextStyle,
    val text: TextStyle,
    val error: TextStyle,
    val field: Color,
    val outline: Color,
    val focusedOutline: Color,
    val errorOutline: Color,
    val cursor: Color,
    val shape: Shape = RoundedCornerShape(12.dp),
    val minHeight: Dp = 48.dp,
    val padding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
)

/**
 * A text field for a [FormField], built to house-style IN6, ST3 and AC1:
 * - the label is always shown above the field, never a placeholder standing in for one;
 * - the error is shown under the field, in words, and is the field's error to a screen reader;
 * - the field is checked when focus leaves it, not on every keystroke, and an error clears as soon
 *   as the text is fixed;
 * - the hit area is at least [FormFieldStyle.minHeight].
 *
 * [leading] and [trailing] sit inside the outline (a currency symbol, a clear button).
 *
 * [onDone] is what the keyboard's action key does: save the dialog, add the tag. Giving it makes
 * that key read "Done" unless [keyboard] names another action; without it the key does what the
 * platform does (next field, or hide the keyboard).
 */
@Composable
fun FormTextField(
    field: FormField<*>,
    label: String,
    style: FormFieldStyle,
    modifier: Modifier = Modifier,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    inputTransformation: InputTransformation? = null,
    singleLine: Boolean = true,
    focusRequester: FocusRequester = remember { FocusRequester() },
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onDone: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    // The newest onDone, without rebuilding the handler when the caller passes a fresh lambda.
    val done = rememberUpdatedState(onDone)
    val action = remember {
        // An object, not a lambda, for the same reason as the decorator below.
        object : KeyboardActionHandler {
            override fun onKeyboardAction(performDefaultAction: () -> Unit) {
                done.value?.invoke() ?: performDefaultAction()
            }
        }
    }
    LaunchedEffect(field) {
        snapshotFlow { field.state.text.toString() }.collect { field.edited() }
    }
    val error = field.error
    val outline = when {
        error != null -> style.errorOutline
        focused -> style.focusedOutline
        else -> style.outline
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(label, style = style.label)
        BasicTextField(
            state = field.state,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    if (focused && !state.isFocused) field.left()
                    focused = state.isFocused
                }
                .semantics {
                    contentDescription = label
                    if (error != null) error(error)
                },
            textStyle = style.text,
            keyboardOptions = if (onDone != null && keyboard.imeAction == ImeAction.Unspecified) keyboard.copy(imeAction = ImeAction.Done) else keyboard,
            onKeyboardAction = if (onDone != null) action else null,
            inputTransformation = inputTransformation,
            lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.Default,
            cursorBrush = SolidColor(style.cursor),
            // An object, not a lambda: a composable fun interface written as a lambda fails to link
            // on iOS (IrLinkageError, see the reaktor-app skill).
            decorator = object : TextFieldDecorator {
                @Composable
                override fun Decoration(innerTextField: @Composable () -> Unit) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = style.minHeight)
                            .background(style.field, style.shape)
                            .border(if (focused || error != null) 2.dp else 1.dp, outline, style.shape)
                            .padding(style.padding),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            leading?.invoke()
                            Box(Modifier.weight(1f)) { innerTextField() }
                            trailing?.invoke()
                        }
                    }
                }
            },
        )
        if (error != null) BasicText(error, style = style.error)
    }
}
