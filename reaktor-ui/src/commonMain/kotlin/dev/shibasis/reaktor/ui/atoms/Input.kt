package dev.shibasis.reaktor.ui.atoms

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import dev.shibasis.reaktor.ui.core.components.ComponentSize
import dev.shibasis.reaktor.ui.core.components.ComponentState
import dev.shibasis.reaktor.ui.core.components.InputSpec
import dev.shibasis.reaktor.ui.core.tokens.Tokens

/**
 * Atom: Input
 *
 * Text input field for user data entry.
 * Supports various input types, validation states, and accessories.
 */

// ============================================================================
// MAIN INPUT API
// ============================================================================

@Composable
fun RInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    label: String? = null,
    helperText: String? = null,
    errorText: String? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    onTrailingIconClick: (() -> Unit)? = null,
    size: ComponentSize = ComponentSize.Medium,
    variant: InputSpec.InputVariant = InputSpec.InputVariant.Outlined,
    state: ComponentState = ComponentState.Enabled,
    inputType: InputSpec.InputType = InputSpec.InputType.Text,
    maxLines: Int = 1,
    maxLength: Int? = null,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
) {
    val colors = Tokens.colors
    val shapes = Tokens.shapes
    val sizing = Tokens.sizing

    val isError = state == ComponentState.Error || errorText != null
    val enabled = state != ComponentState.Disabled

    val height = when (size) {
        ComponentSize.Small -> sizing.inputSm
        ComponentSize.Medium -> sizing.inputMd
        ComponentSize.Large -> sizing.inputLg
    }

    val shape = RoundedCornerShape(shapes.sm)

    val keyboardType = when (inputType) {
        InputSpec.InputType.Text -> KeyboardType.Text
        InputSpec.InputType.Password -> KeyboardType.Password
        InputSpec.InputType.Email -> KeyboardType.Email
        InputSpec.InputType.Number -> KeyboardType.Number
        InputSpec.InputType.Phone -> KeyboardType.Phone
        InputSpec.InputType.Url -> KeyboardType.Uri
        InputSpec.InputType.Search -> KeyboardType.Text
        InputSpec.InputType.Multiline -> KeyboardType.Text
    }

    val visualTransformation = if (inputType == InputSpec.InputType.Password) {
        PasswordVisualTransformation()
    } else {
        VisualTransformation.None
    }

    val actualMaxLines = if (inputType == InputSpec.InputType.Multiline) {
        maxLines.coerceAtLeast(3)
    } else {
        1
    }

    val singleLine = actualMaxLines == 1

    val inputColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.primary,
        unfocusedBorderColor = colors.outline,
        errorBorderColor = colors.error,
        focusedLabelColor = colors.primary,
        unfocusedLabelColor = colors.onSurfaceVariant,
        errorLabelColor = colors.error,
        cursorColor = colors.primary,
        errorCursorColor = colors.error,
        focusedLeadingIconColor = colors.onSurfaceVariant,
        unfocusedLeadingIconColor = colors.onSurfaceVariant,
        focusedTrailingIconColor = colors.onSurfaceVariant,
        unfocusedTrailingIconColor = colors.onSurfaceVariant,
    )

    val textFieldModifier = modifier.then(
        if (singleLine) Modifier.height(height) else Modifier
    )

    val constrainedValue = if (maxLength != null) {
        value.take(maxLength)
    } else {
        value
    }

    when (variant) {
        InputSpec.InputVariant.Outlined -> {
            OutlinedTextField(
                value = constrainedValue,
                onValueChange = { newValue ->
                    val constrained = if (maxLength != null) newValue.take(maxLength) else newValue
                    onValueChange(constrained)
                },
                modifier = textFieldModifier,
                enabled = enabled,
                isError = isError,
                label = label?.let { { Text(it) } },
                placeholder = { Text(placeholder) },
                leadingIcon = leadingIcon?.let {
                    { Icon(it, contentDescription = null) }
                },
                trailingIcon = trailingIcon?.let {
                    {
                        if (onTrailingIconClick != null) {
                            IconButton(onClick = onTrailingIconClick) {
                                Icon(it, contentDescription = null)
                            }
                        } else {
                            Icon(it, contentDescription = null)
                        }
                    }
                },
                supportingText = {
                    val text = errorText ?: helperText
                    if (text != null) {
                        Text(
                            text = text,
                            color = if (isError) colors.error else colors.onSurfaceVariant
                        )
                    }
                },
                visualTransformation = visualTransformation,
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = imeAction,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { onImeAction?.invoke() },
                    onSearch = { onImeAction?.invoke() },
                    onGo = { onImeAction?.invoke() },
                    onSend = { onImeAction?.invoke() },
                ),
                singleLine = singleLine,
                maxLines = actualMaxLines,
                shape = shape,
                colors = inputColors,
            )
        }

        InputSpec.InputVariant.Filled -> {
            TextField(
                value = constrainedValue,
                onValueChange = { newValue ->
                    val constrained = if (maxLength != null) newValue.take(maxLength) else newValue
                    onValueChange(constrained)
                },
                modifier = textFieldModifier,
                enabled = enabled,
                isError = isError,
                label = label?.let { { Text(it) } },
                placeholder = { Text(placeholder) },
                leadingIcon = leadingIcon?.let {
                    { Icon(it, contentDescription = null) }
                },
                trailingIcon = trailingIcon?.let {
                    {
                        if (onTrailingIconClick != null) {
                            IconButton(onClick = onTrailingIconClick) {
                                Icon(it, contentDescription = null)
                            }
                        } else {
                            Icon(it, contentDescription = null)
                        }
                    }
                },
                supportingText = {
                    val text = errorText ?: helperText
                    if (text != null) {
                        Text(
                            text = text,
                            color = if (isError) colors.error else colors.onSurfaceVariant
                        )
                    }
                },
                visualTransformation = visualTransformation,
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = imeAction,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { onImeAction?.invoke() },
                    onSearch = { onImeAction?.invoke() },
                    onGo = { onImeAction?.invoke() },
                    onSend = { onImeAction?.invoke() },
                ),
                singleLine = singleLine,
                maxLines = actualMaxLines,
                shape = shape,
            )
        }

        InputSpec.InputVariant.Underlined -> {
            // Underlined uses TextField with transparent container
            TextField(
                value = constrainedValue,
                onValueChange = { newValue ->
                    val constrained = if (maxLength != null) newValue.take(maxLength) else newValue
                    onValueChange(constrained)
                },
                modifier = textFieldModifier,
                enabled = enabled,
                isError = isError,
                label = label?.let { { Text(it) } },
                placeholder = { Text(placeholder) },
                leadingIcon = leadingIcon?.let {
                    { Icon(it, contentDescription = null) }
                },
                trailingIcon = trailingIcon?.let {
                    {
                        if (onTrailingIconClick != null) {
                            IconButton(onClick = onTrailingIconClick) {
                                Icon(it, contentDescription = null)
                            }
                        } else {
                            Icon(it, contentDescription = null)
                        }
                    }
                },
                supportingText = {
                    val text = errorText ?: helperText
                    if (text != null) {
                        Text(
                            text = text,
                            color = if (isError) colors.error else colors.onSurfaceVariant
                        )
                    }
                },
                visualTransformation = visualTransformation,
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = imeAction,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { onImeAction?.invoke() },
                    onSearch = { onImeAction?.invoke() },
                    onGo = { onImeAction?.invoke() },
                    onSend = { onImeAction?.invoke() },
                ),
                singleLine = singleLine,
                maxLines = actualMaxLines,
            )
        }
    }
}

// ============================================================================
// CONVENIENCE FUNCTIONS
// ============================================================================

@Composable
fun RSearchInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search...",
    leadingIcon: ImageVector? = null,
    onClear: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
) {
    RInput(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        inputType = InputSpec.InputType.Search,
        imeAction = ImeAction.Search,
        onImeAction = onSearch,
    )
}

@Composable
fun RPasswordInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Password",
    placeholder: String = "",
    errorText: String? = null,
    showPassword: Boolean = false,
    onTogglePasswordVisibility: (() -> Unit)? = null,
) {
    RInput(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = label,
        placeholder = placeholder,
        errorText = errorText,
        inputType = if (showPassword) InputSpec.InputType.Text else InputSpec.InputType.Password,
    )
}

@Composable
fun REmailInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Email",
    placeholder: String = "email@example.com",
    errorText: String? = null,
) {
    RInput(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = label,
        placeholder = placeholder,
        errorText = errorText,
        inputType = InputSpec.InputType.Email,
        imeAction = ImeAction.Next,
    )
}

@Composable
fun RTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    helperText: String? = null,
    errorText: String? = null,
    maxLines: Int = 5,
    maxLength: Int? = null,
) {
    RInput(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = label,
        placeholder = placeholder,
        helperText = helperText,
        errorText = errorText,
        inputType = InputSpec.InputType.Multiline,
        maxLines = maxLines,
        maxLength = maxLength,
    )
}
