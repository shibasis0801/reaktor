package dev.shibasis.reaktor.ui.atoms

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.ui.core.components.ComponentSize
import dev.shibasis.reaktor.ui.core.tokens.Tokens

/**
 * Atom: Progress
 *
 * Loading and progress indicators.
 * Supports circular and linear styles, determinate and indeterminate.
 */

// ============================================================================
// CIRCULAR PROGRESS
// ============================================================================

@Composable
fun RCircularProgress(
    modifier: Modifier = Modifier,
    progress: Float? = null, // null = indeterminate
    size: ComponentSize = ComponentSize.Medium,
    color: Color = Color.Unspecified,
    trackColor: Color = Color.Unspecified,
    strokeWidth: Dp? = null,
) {
    val colors = Tokens.colors
    val sizing = Tokens.sizing

    val progressColor = if (color == Color.Unspecified) colors.primary else color
    val progressTrackColor = if (trackColor == Color.Unspecified) {
        colors.surfaceContainerHigh
    } else trackColor

    val indicatorSize = when (size) {
        ComponentSize.Small -> sizing.iconSm
        ComponentSize.Medium -> sizing.iconLg
        ComponentSize.Large -> sizing.iconXl
    }

    val stroke = strokeWidth ?: when (size) {
        ComponentSize.Small -> 2.dp
        ComponentSize.Medium -> 3.dp
        ComponentSize.Large -> 4.dp
    }

    if (progress != null) {
        CircularProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = modifier.size(indicatorSize),
            color = progressColor,
            trackColor = progressTrackColor,
            strokeWidth = stroke,
            strokeCap = StrokeCap.Round,
        )
    } else {
        CircularProgressIndicator(
            modifier = modifier.size(indicatorSize),
            color = progressColor,
            trackColor = progressTrackColor,
            strokeWidth = stroke,
            strokeCap = StrokeCap.Round,
        )
    }
}

// ============================================================================
// LINEAR PROGRESS
// ============================================================================

@Composable
fun RLinearProgress(
    modifier: Modifier = Modifier,
    progress: Float? = null, // null = indeterminate
    color: Color = Color.Unspecified,
    trackColor: Color = Color.Unspecified,
) {
    val colors = Tokens.colors

    val progressColor = if (color == Color.Unspecified) colors.primary else color
    val progressTrackColor = if (trackColor == Color.Unspecified) {
        colors.surfaceContainerHigh
    } else trackColor

    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = modifier,
            color = progressColor,
            trackColor = progressTrackColor,
            strokeCap = StrokeCap.Round,
        )
    } else {
        LinearProgressIndicator(
            modifier = modifier,
            color = progressColor,
            trackColor = progressTrackColor,
            strokeCap = StrokeCap.Round,
        )
    }
}

// ============================================================================
// CONVENIENCE
// ============================================================================

@Composable
fun RLoadingSpinner(
    modifier: Modifier = Modifier,
    size: ComponentSize = ComponentSize.Medium,
) {
    RCircularProgress(
        modifier = modifier,
        progress = null,
        size = size,
    )
}
