package dev.shibasis.reaktor.ui.atoms

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.ui.core.components.ButtonSpec
import dev.shibasis.reaktor.ui.core.components.ComponentSize
import dev.shibasis.reaktor.ui.core.components.ComponentState
import dev.shibasis.reaktor.ui.core.components.ComponentVariant
import dev.shibasis.reaktor.ui.core.tokens.Tokens

/**
 * Atom: Button
 *
 * The primary interactive element for user actions.
 * Supports multiple variants, sizes, and states.
 */

// ============================================================================
// PRIMARY BUTTON API
// ============================================================================

@Composable
fun RButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    iconPosition: ButtonSpec.IconPosition = ButtonSpec.IconPosition.Start,
    size: ComponentSize = ComponentSize.Medium,
    variant: ComponentVariant = ComponentVariant.Filled,
    state: ComponentState = ComponentState.Enabled,
    fullWidth: Boolean = false,
) {
    val spec = ButtonSpec(label, icon, iconPosition, size, variant, state, fullWidth)
    RButtonImpl(onClick, modifier, spec)
}

// ============================================================================
// CONVENIENCE VARIANTS
// ============================================================================

@Composable
fun RButtonPrimary(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    size: ComponentSize = ComponentSize.Medium,
    enabled: Boolean = true,
) {
    RButton(
        onClick = onClick,
        modifier = modifier,
        label = label,
        icon = icon,
        size = size,
        variant = ComponentVariant.Filled,
        state = if (enabled) ComponentState.Enabled else ComponentState.Disabled,
    )
}

@Composable
fun RButtonSecondary(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    size: ComponentSize = ComponentSize.Medium,
    enabled: Boolean = true,
) {
    RButton(
        onClick = onClick,
        modifier = modifier,
        label = label,
        icon = icon,
        size = size,
        variant = ComponentVariant.Tonal,
        state = if (enabled) ComponentState.Enabled else ComponentState.Disabled,
    )
}

@Composable
fun RButtonOutlined(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    size: ComponentSize = ComponentSize.Medium,
    enabled: Boolean = true,
) {
    RButton(
        onClick = onClick,
        modifier = modifier,
        label = label,
        icon = icon,
        size = size,
        variant = ComponentVariant.Outlined,
        state = if (enabled) ComponentState.Enabled else ComponentState.Disabled,
    )
}

@Composable
fun RButtonText(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    size: ComponentSize = ComponentSize.Medium,
    enabled: Boolean = true,
) {
    RButton(
        onClick = onClick,
        modifier = modifier,
        label = label,
        icon = icon,
        size = size,
        variant = ComponentVariant.Text,
        state = if (enabled) ComponentState.Enabled else ComponentState.Disabled,
    )
}

@Composable
fun RIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: ComponentSize = ComponentSize.Medium,
    variant: ComponentVariant = ComponentVariant.Text,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val colors = Tokens.colors
    val sizing = Tokens.sizing

    val iconSize = when (size) {
        ComponentSize.Small -> sizing.iconSm
        ComponentSize.Medium -> sizing.iconMd
        ComponentSize.Large -> sizing.iconLg
    }

    val containerColor = when (variant) {
        ComponentVariant.Filled -> colors.primary
        ComponentVariant.Tonal -> colors.secondaryContainer
        else -> Color.Transparent
    }

    val contentColor = when (variant) {
        ComponentVariant.Filled -> colors.onPrimary
        ComponentVariant.Tonal -> colors.onSecondaryContainer
        else -> colors.primary
    }

    IconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
fun RFloatingActionButton(
    onClick: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    label: String? = null,
    size: ComponentSize = ComponentSize.Medium,
    containerColor: Color? = null,
) {
    val colors = Tokens.colors
    val sizing = Tokens.sizing
    val spacing = Tokens.spacing

    val fabContainerColor = containerColor ?: colors.primaryContainer
    val fabContentColor = colors.onPrimaryContainer

    val iconSize = when (size) {
        ComponentSize.Small -> sizing.iconSm
        ComponentSize.Medium -> sizing.iconMd
        ComponentSize.Large -> sizing.iconLg
    }

    if (label != null) {
        ExtendedFloatingActionButton(
            onClick = onClick,
            modifier = modifier,
            containerColor = fabContainerColor,
            contentColor = fabContentColor,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(iconSize)
            )
            Spacer(Modifier.width(spacing.sm))
            Text(label)
        }
    } else {
        FloatingActionButton(
            onClick = onClick,
            modifier = modifier,
            containerColor = fabContainerColor,
            contentColor = fabContentColor,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

// ============================================================================
// IMPLEMENTATION
// ============================================================================

@Composable
private fun RButtonImpl(
    onClick: () -> Unit,
    modifier: Modifier,
    spec: ButtonSpec,
) {
    val colors = Tokens.colors
    val spacing = Tokens.spacing
    val shapes = Tokens.shapes
    val sizing = Tokens.sizing

    val enabled = spec.state == ComponentState.Enabled
    val loading = spec.state == ComponentState.Loading

    // Size-based styling
    val height = when (spec.size) {
        ComponentSize.Small -> sizing.buttonSm
        ComponentSize.Medium -> sizing.buttonMd
        ComponentSize.Large -> sizing.buttonLg
    }

    val horizontalPadding = when (spec.size) {
        ComponentSize.Small -> spacing.sm
        ComponentSize.Medium -> spacing.md
        ComponentSize.Large -> spacing.lg
    }

    val iconSize = when (spec.size) {
        ComponentSize.Small -> sizing.iconXs
        ComponentSize.Medium -> sizing.iconSm
        ComponentSize.Large -> sizing.iconMd
    }

    val shape: Shape = RoundedCornerShape(shapes.md)

    // Variant-based colors
    val containerColor = when (spec.variant) {
        ComponentVariant.Filled -> colors.primary
        ComponentVariant.Tonal -> colors.secondaryContainer
        ComponentVariant.Elevated -> colors.surface
        ComponentVariant.Outlined, ComponentVariant.Text -> Color.Transparent
    }

    val contentColor = when (spec.variant) {
        ComponentVariant.Filled -> colors.onPrimary
        ComponentVariant.Tonal -> colors.onSecondaryContainer
        ComponentVariant.Elevated -> colors.primary
        ComponentVariant.Outlined, ComponentVariant.Text -> colors.primary
    }

    val border = if (spec.variant == ComponentVariant.Outlined) {
        BorderStroke(1.dp, colors.outline)
    } else null

    val buttonColors = ButtonDefaults.buttonColors(
        containerColor = containerColor,
        contentColor = contentColor,
        disabledContainerColor = colors.onSurface.copy(alpha = 0.12f),
        disabledContentColor = colors.onSurface.copy(alpha = 0.38f),
    )

    val buttonModifier = modifier
        .height(height)
        .then(if (spec.fullWidth) Modifier.fillMaxWidth() else Modifier)

    Button(
        onClick = onClick,
        modifier = buttonModifier,
        enabled = enabled && !loading,
        shape = shape,
        colors = buttonColors,
        border = border,
        contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 0.dp),
        elevation = if (spec.variant == ComponentVariant.Elevated) {
            ButtonDefaults.buttonElevation()
        } else {
            ButtonDefaults.buttonElevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp,
            )
        }
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(iconSize),
                strokeWidth = 2.dp,
                color = contentColor,
            )
        } else {
            ButtonContent(spec, iconSize, spacing.xs)
        }
    }
}

@Composable
private fun RowScope.ButtonContent(
    spec: ButtonSpec,
    iconSize: androidx.compose.ui.unit.Dp,
    iconSpacing: androidx.compose.ui.unit.Dp,
) {
    val hasIcon = spec.icon != null
    val hasLabel = spec.label != null
    val iconOnly = spec.iconPosition == ButtonSpec.IconPosition.Only || (hasIcon && !hasLabel)

    when {
        iconOnly && hasIcon -> {
            Icon(
                imageVector = spec.icon!!,
                contentDescription = spec.label,
                modifier = Modifier.size(iconSize)
            )
        }
        hasIcon && hasLabel && spec.iconPosition == ButtonSpec.IconPosition.Start -> {
            Icon(
                imageVector = spec.icon!!,
                contentDescription = null,
                modifier = Modifier.size(iconSize)
            )
            Spacer(Modifier.width(iconSpacing))
            Text(spec.label!!)
        }
        hasIcon && hasLabel && spec.iconPosition == ButtonSpec.IconPosition.End -> {
            Text(spec.label!!)
            Spacer(Modifier.width(iconSpacing))
            Icon(
                imageVector = spec.icon!!,
                contentDescription = null,
                modifier = Modifier.size(iconSize)
            )
        }
        hasLabel -> {
            Text(spec.label!!)
        }
    }
}
