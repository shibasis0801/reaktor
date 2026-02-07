package dev.shibasis.reaktor.ui.molecules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.shibasis.reaktor.ui.atoms.*
import dev.shibasis.reaktor.ui.core.components.ComponentSize
import dev.shibasis.reaktor.ui.core.components.TextSpec
import dev.shibasis.reaktor.ui.core.tokens.Tokens

/**
 * Molecule: ListItem
 *
 * A versatile list item component for displaying content in lists.
 * Combines avatar, text, and action elements.
 */

@Composable
fun RListItem(
    modifier: Modifier = Modifier,
    headlineText: String,
    supportingText: String? = null,
    overlineText: String? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val spacing = Tokens.spacing
    val sizing = Tokens.sizing

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = spacing.md, vertical = spacing.sm)
            .defaultMinSize(minHeight = sizing.touchTargetMin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading content (icon, avatar, etc.)
        if (leadingContent != null) {
            Box(
                modifier = Modifier.padding(end = spacing.md),
                contentAlignment = Alignment.Center,
            ) {
                leadingContent()
            }
        }

        // Text content
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            if (overlineText != null) {
                RLabel(
                    text = overlineText,
                    size = TextSpec.TextSize.Small,
                )
                RVerticalSpaceXxs()
            }

            RBody(
                text = headlineText,
                size = TextSpec.TextSize.Large,
                maxLines = 1,
            )

            if (supportingText != null) {
                RVerticalSpaceXxs()
                RBody(
                    text = supportingText,
                    size = TextSpec.TextSize.Small,
                    color = Tokens.colors.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }

        // Trailing content (icons, switches, badges, etc.)
        if (trailingContent != null) {
            Box(
                modifier = Modifier.padding(start = spacing.md),
                contentAlignment = Alignment.Center,
            ) {
                trailingContent()
            }
        }
    }
}

// ============================================================================
// CONVENIENCE VARIANTS
// ============================================================================

/**
 * List item with leading icon
 */
@Composable
fun RListItemIcon(
    headlineText: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    iconColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    RListItem(
        modifier = modifier,
        headlineText = headlineText,
        supportingText = supportingText,
        onClick = onClick,
        leadingContent = {
            RIcon(icon = icon, color = iconColor, size = ComponentSize.Medium)
        },
        trailingContent = trailingContent,
    )
}

/**
 * List item with leading avatar
 */
@Composable
fun RListItemAvatar(
    headlineText: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    imageUrl: String? = null,
    initials: String? = null,
    onClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    RListItem(
        modifier = modifier,
        headlineText = headlineText,
        supportingText = supportingText,
        onClick = onClick,
        leadingContent = {
            RAvatar(
                imageUrl = imageUrl,
                initials = initials,
                size = ComponentSize.Medium,
            )
        },
        trailingContent = trailingContent,
    )
}

/**
 * List item with switch
 */
@Composable
fun RListItemSwitch(
    headlineText: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    RListItem(
        modifier = modifier,
        headlineText = headlineText,
        supportingText = supportingText,
        onClick = if (enabled) { { onCheckedChange(!checked) } } else null,
        trailingContent = {
            RSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        },
    )
}

/**
 * List item with checkbox
 */
@Composable
fun RListItemCheckbox(
    headlineText: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    RListItem(
        modifier = modifier,
        headlineText = headlineText,
        supportingText = supportingText,
        onClick = if (enabled) { { onCheckedChange(!checked) } } else null,
        leadingContent = {
            RCheckbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        },
    )
}

/**
 * List item with radio button
 */
@Composable
fun RListItemRadio(
    headlineText: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    RListItem(
        modifier = modifier,
        headlineText = headlineText,
        supportingText = supportingText,
        onClick = if (enabled) onClick else null,
        leadingContent = {
            RRadioButton(
                selected = selected,
                onClick = onClick,
                enabled = enabled,
            )
        },
    )
}
