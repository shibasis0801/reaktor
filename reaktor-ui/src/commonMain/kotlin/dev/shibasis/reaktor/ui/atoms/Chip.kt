package dev.shibasis.reaktor.ui.atoms

import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.shibasis.reaktor.ui.core.tokens.Tokens

/**
 * Atom: Chip
 *
 * Compact elements that represent inputs, attributes, or actions.
 * Supports multiple variants: Assist, Filter, Input, Suggestion.
 */

// ============================================================================
// ASSIST CHIP (Actions/Suggestions)
// ============================================================================

@Composable
fun RAssistChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = Tokens.colors
    val sizing = Tokens.sizing

    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(sizing.iconSm),
                )
            }
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = colors.surface,
            labelColor = colors.onSurface,
            leadingIconContentColor = colors.primary,
        ),
        border = AssistChipDefaults.assistChipBorder(
            enabled = enabled,
            borderColor = colors.outline,
        ),
    )
}

// ============================================================================
// FILTER CHIP (Toggle Selection)
// ============================================================================

@Composable
fun RFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = Tokens.colors
    val sizing = Tokens.sizing

    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(sizing.iconSm),
                )
            }
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.surface,
            labelColor = colors.onSurfaceVariant,
            iconColor = colors.onSurfaceVariant,
            selectedContainerColor = colors.secondaryContainer,
            selectedLabelColor = colors.onSecondaryContainer,
            selectedLeadingIconColor = colors.onSecondaryContainer,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = colors.outline,
            selectedBorderColor = colors.secondaryContainer,
        ),
    )
}

// ============================================================================
// INPUT CHIP (User Input, Deletable)
// ============================================================================

@Composable
fun RInputChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    onTrailingIconClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val colors = Tokens.colors
    val sizing = Tokens.sizing

    InputChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(sizing.iconSm),
                )
            }
        },
        trailingIcon = trailingIcon?.let {
            {
                IconButton(
                    onClick = { onTrailingIconClick?.invoke() },
                    modifier = Modifier.size(sizing.iconSm),
                ) {
                    Icon(
                        imageVector = it,
                        contentDescription = "Remove",
                        modifier = Modifier.size(sizing.iconXs),
                    )
                }
            }
        },
        colors = InputChipDefaults.inputChipColors(
            containerColor = colors.surface,
            labelColor = colors.onSurfaceVariant,
            leadingIconColor = colors.onSurfaceVariant,
            trailingIconColor = colors.onSurfaceVariant,
            selectedContainerColor = colors.secondaryContainer,
            selectedLabelColor = colors.onSecondaryContainer,
            selectedLeadingIconColor = colors.onSecondaryContainer,
            selectedTrailingIconColor = colors.onSecondaryContainer,
        ),
        border = InputChipDefaults.inputChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = colors.outline,
            selectedBorderColor = colors.secondaryContainer,
        ),
    )
}

// ============================================================================
// SUGGESTION CHIP (Quick Actions)
// ============================================================================

@Composable
fun RSuggestionChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = Tokens.colors
    val sizing = Tokens.sizing

    SuggestionChip(
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier,
        enabled = enabled,
        icon = icon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(sizing.iconSm),
                )
            }
        },
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = colors.surface,
            labelColor = colors.onSurfaceVariant,
            iconContentColor = colors.onSurfaceVariant,
        ),
        border = SuggestionChipDefaults.suggestionChipBorder(
            enabled = enabled,
            borderColor = colors.outline,
        ),
    )
}
