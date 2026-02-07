package dev.shibasis.reaktor.ui.molecules

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import dev.shibasis.reaktor.ui.atoms.*
import dev.shibasis.reaktor.ui.core.components.ComponentSize
import dev.shibasis.reaktor.ui.core.components.TextSpec
import dev.shibasis.reaktor.ui.core.tokens.Tokens

/**
 * Molecule: EmptyState
 *
 * Display when content is empty or unavailable.
 * Includes icon, title, description, and optional action.
 */

@Composable
fun REmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = Tokens.colors
    val spacing = Tokens.spacing

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            RIconXl(
                icon = icon,
                color = colors.onSurfaceVariant,
            )
            RVerticalSpaceLg()
        }

        RTitle(
            text = title,
            size = TextSpec.TextSize.Large,
        )

        if (description != null) {
            RVerticalSpaceSm()
            RBody(
                text = description,
                size = TextSpec.TextSize.Medium,
                color = colors.onSurfaceVariant,
            )
        }

        if (actionLabel != null && onAction != null) {
            RVerticalSpaceLg()
            RButtonPrimary(
                onClick = onAction,
                label = actionLabel,
            )
        }
    }
}

/**
 * Loading state display
 */
@Composable
fun RLoadingState(
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    val colors = Tokens.colors
    val spacing = Tokens.spacing

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RCircularProgress(size = ComponentSize.Large)

        if (message != null) {
            RVerticalSpaceMd()
            RBody(
                text = message,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * Error state display
 */
@Composable
fun RErrorState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    retryLabel: String = "Retry",
    onRetry: (() -> Unit)? = null,
) {
    val colors = Tokens.colors
    val spacing = Tokens.spacing

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            RIconXl(
                icon = icon,
                color = colors.error,
            )
            RVerticalSpaceLg()
        }

        RTitle(
            text = title,
            size = TextSpec.TextSize.Large,
            color = colors.error,
        )

        if (description != null) {
            RVerticalSpaceSm()
            RBody(
                text = description,
                color = colors.onSurfaceVariant,
            )
        }

        if (onRetry != null) {
            RVerticalSpaceLg()
            RButtonOutlined(
                onClick = onRetry,
                label = retryLabel,
            )
        }
    }
}
