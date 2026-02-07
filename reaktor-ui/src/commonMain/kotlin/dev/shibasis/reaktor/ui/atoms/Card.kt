package dev.shibasis.reaktor.ui.atoms

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.ui.core.components.ComponentVariant
import dev.shibasis.reaktor.ui.core.tokens.Tokens

/**
 * Atom: Card
 *
 * Container component for grouping related content.
 * Provides visual separation and hierarchy.
 */

// ============================================================================
// MAIN CARD API
// ============================================================================

@Composable
fun RCard(
    modifier: Modifier = Modifier,
    variant: ComponentVariant = ComponentVariant.Filled,
    onClick: (() -> Unit)? = null,
    shape: Shape? = null,
    containerColor: Color? = null,
    contentColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Tokens.colors
    val shapes = Tokens.shapes
    val elevation = Tokens.elevation

    val cardShape = shape ?: RoundedCornerShape(shapes.md)

    val resolvedContainerColor = containerColor ?: when (variant) {
        ComponentVariant.Filled -> colors.surfaceContainer
        ComponentVariant.Elevated -> colors.surface
        ComponentVariant.Outlined -> colors.surface
        ComponentVariant.Tonal -> colors.secondaryContainer
        ComponentVariant.Text -> Color.Transparent
    }

    val resolvedContentColor = contentColor ?: when (variant) {
        ComponentVariant.Tonal -> colors.onSecondaryContainer
        else -> colors.onSurface
    }

    when (variant) {
        ComponentVariant.Elevated -> {
            if (onClick != null) {
                ElevatedCard(
                    onClick = onClick,
                    modifier = modifier,
                    shape = cardShape,
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = resolvedContainerColor,
                        contentColor = resolvedContentColor,
                    ),
                    elevation = CardDefaults.elevatedCardElevation(
                        defaultElevation = elevation.sm,
                    ),
                    content = content,
                )
            } else {
                ElevatedCard(
                    modifier = modifier,
                    shape = cardShape,
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = resolvedContainerColor,
                        contentColor = resolvedContentColor,
                    ),
                    elevation = CardDefaults.elevatedCardElevation(
                        defaultElevation = elevation.sm,
                    ),
                    content = content,
                )
            }
        }

        ComponentVariant.Outlined -> {
            if (onClick != null) {
                OutlinedCard(
                    onClick = onClick,
                    modifier = modifier,
                    shape = cardShape,
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = resolvedContainerColor,
                        contentColor = resolvedContentColor,
                    ),
                    border = BorderStroke(1.dp, colors.outlineVariant),
                    content = content,
                )
            } else {
                OutlinedCard(
                    modifier = modifier,
                    shape = cardShape,
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = resolvedContainerColor,
                        contentColor = resolvedContentColor,
                    ),
                    border = BorderStroke(1.dp, colors.outlineVariant),
                    content = content,
                )
            }
        }

        else -> {
            if (onClick != null) {
                Card(
                    onClick = onClick,
                    modifier = modifier,
                    shape = cardShape,
                    colors = CardDefaults.cardColors(
                        containerColor = resolvedContainerColor,
                        contentColor = resolvedContentColor,
                    ),
                    content = content,
                )
            } else {
                Card(
                    modifier = modifier,
                    shape = cardShape,
                    colors = CardDefaults.cardColors(
                        containerColor = resolvedContainerColor,
                        contentColor = resolvedContentColor,
                    ),
                    content = content,
                )
            }
        }
    }
}

// ============================================================================
// CONVENIENCE VARIANTS
// ============================================================================

@Composable
fun RElevatedCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    RCard(
        modifier = modifier,
        variant = ComponentVariant.Elevated,
        onClick = onClick,
        content = content,
    )
}

@Composable
fun ROutlinedCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    RCard(
        modifier = modifier,
        variant = ComponentVariant.Outlined,
        onClick = onClick,
        content = content,
    )
}

@Composable
fun RFilledCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    RCard(
        modifier = modifier,
        variant = ComponentVariant.Filled,
        onClick = onClick,
        content = content,
    )
}

// ============================================================================
// CARD WITH PADDING (Common Pattern)
// ============================================================================

@Composable
fun RCardContent(
    modifier: Modifier = Modifier,
    variant: ComponentVariant = ComponentVariant.Elevated,
    onClick: (() -> Unit)? = null,
    padding: Dp? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = Tokens.spacing
    val actualPadding = padding ?: spacing.md

    RCard(
        modifier = modifier,
        variant = variant,
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(actualPadding)) {
            content()
        }
    }
}
