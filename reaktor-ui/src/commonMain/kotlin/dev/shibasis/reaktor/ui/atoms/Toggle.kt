package dev.shibasis.reaktor.ui.atoms

import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.shibasis.reaktor.ui.core.tokens.Tokens

/**
 * Atom: Toggle Controls
 *
 * Selection controls for binary or multiple choice states.
 * Includes Switch, Checkbox, and Radio buttons.
 */

// ============================================================================
// SWITCH
// ============================================================================

@Composable
fun RSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    thumbIcon: ImageVector? = null,
) {
    val colors = Tokens.colors
    val sizing = Tokens.sizing

    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        thumbContent = thumbIcon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(sizing.iconXs),
                )
            }
        },
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.onPrimary,
            checkedTrackColor = colors.primary,
            checkedBorderColor = colors.primary,
            checkedIconColor = colors.primary,
            uncheckedThumbColor = colors.outline,
            uncheckedTrackColor = colors.surfaceContainerHigh,
            uncheckedBorderColor = colors.outline,
            uncheckedIconColor = colors.surfaceContainerHigh,
            disabledCheckedThumbColor = colors.surface.copy(alpha = 0.38f),
            disabledCheckedTrackColor = colors.onSurface.copy(alpha = 0.12f),
            disabledUncheckedThumbColor = colors.onSurface.copy(alpha = 0.38f),
            disabledUncheckedTrackColor = colors.surfaceContainerHigh.copy(alpha = 0.12f),
        ),
    )
}

// ============================================================================
// CHECKBOX
// ============================================================================

@Composable
fun RCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = Tokens.colors

    Checkbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = CheckboxDefaults.colors(
            checkedColor = colors.primary,
            uncheckedColor = colors.onSurfaceVariant,
            checkmarkColor = colors.onPrimary,
            disabledCheckedColor = colors.onSurface.copy(alpha = 0.38f),
            disabledUncheckedColor = colors.onSurface.copy(alpha = 0.38f),
            disabledIndeterminateColor = colors.onSurface.copy(alpha = 0.38f),
        ),
    )
}

@Composable
fun RTriStateCheckbox(
    state: ToggleableState,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = Tokens.colors

    TriStateCheckbox(
        state = state,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = CheckboxDefaults.colors(
            checkedColor = colors.primary,
            uncheckedColor = colors.onSurfaceVariant,
            checkmarkColor = colors.onPrimary,
            disabledCheckedColor = colors.onSurface.copy(alpha = 0.38f),
            disabledUncheckedColor = colors.onSurface.copy(alpha = 0.38f),
            disabledIndeterminateColor = colors.onSurface.copy(alpha = 0.38f),
        ),
    )
}

// ============================================================================
// RADIO BUTTON
// ============================================================================

@Composable
fun RRadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = Tokens.colors

    RadioButton(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = RadioButtonDefaults.colors(
            selectedColor = colors.primary,
            unselectedColor = colors.onSurfaceVariant,
            disabledSelectedColor = colors.onSurface.copy(alpha = 0.38f),
            disabledUnselectedColor = colors.onSurface.copy(alpha = 0.38f),
        ),
    )
}
