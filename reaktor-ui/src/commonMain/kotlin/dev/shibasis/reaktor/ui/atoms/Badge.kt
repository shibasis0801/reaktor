package dev.shibasis.reaktor.ui.atoms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.ui.core.components.BadgeSpec
import dev.shibasis.reaktor.ui.core.tokens.Tokens

/**
 * Atom: Badge
 *
 * Small status indicators, often displayed on icons or avatars.
 * Can show counts or simple dots.
 */

// ============================================================================
// BADGE WITH ANCHOR
// ============================================================================

@Composable
fun RBadge(
    modifier: Modifier = Modifier,
    count: Int? = null,
    showDot: Boolean = false,
    maxCount: Int = 99,
    color: BadgeSpec.BadgeColor = BadgeSpec.BadgeColor.Error,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = Tokens.colors

    val badgeColor = when (color) {
        BadgeSpec.BadgeColor.Primary -> colors.primary
        BadgeSpec.BadgeColor.Secondary -> colors.secondary
        BadgeSpec.BadgeColor.Error -> colors.error
        BadgeSpec.BadgeColor.Success -> colors.success
        BadgeSpec.BadgeColor.Warning -> colors.warning
        BadgeSpec.BadgeColor.Info -> colors.info
    }

    val badgeContentColor = when (color) {
        BadgeSpec.BadgeColor.Primary -> colors.onPrimary
        BadgeSpec.BadgeColor.Secondary -> colors.onSecondary
        BadgeSpec.BadgeColor.Error -> colors.onError
        BadgeSpec.BadgeColor.Success -> colors.onSuccess
        BadgeSpec.BadgeColor.Warning -> colors.onWarning
        BadgeSpec.BadgeColor.Info -> colors.onInfo
    }

    BadgedBox(
        modifier = modifier,
        badge = {
            when {
                count != null && count > 0 -> {
                    Badge(
                        containerColor = badgeColor,
                        contentColor = badgeContentColor,
                    ) {
                        val displayCount = if (count > maxCount) "$maxCount+" else count.toString()
                        Text(displayCount)
                    }
                }
                showDot -> {
                    Badge(
                        containerColor = badgeColor,
                    )
                }
            }
        },
        content = content,
    )
}

// ============================================================================
// STANDALONE BADGE (Without Anchor)
// ============================================================================

@Composable
fun RBadgeStandalone(
    text: String,
    modifier: Modifier = Modifier,
    color: BadgeSpec.BadgeColor = BadgeSpec.BadgeColor.Primary,
) {
    val colors = Tokens.colors
    val spacing = Tokens.spacing

    val badgeColor = when (color) {
        BadgeSpec.BadgeColor.Primary -> colors.primary
        BadgeSpec.BadgeColor.Secondary -> colors.secondary
        BadgeSpec.BadgeColor.Error -> colors.error
        BadgeSpec.BadgeColor.Success -> colors.success
        BadgeSpec.BadgeColor.Warning -> colors.warning
        BadgeSpec.BadgeColor.Info -> colors.info
    }

    val badgeContentColor = when (color) {
        BadgeSpec.BadgeColor.Primary -> colors.onPrimary
        BadgeSpec.BadgeColor.Secondary -> colors.onSecondary
        BadgeSpec.BadgeColor.Error -> colors.onError
        BadgeSpec.BadgeColor.Success -> colors.onSuccess
        BadgeSpec.BadgeColor.Warning -> colors.onWarning
        BadgeSpec.BadgeColor.Info -> colors.onInfo
    }

    Badge(
        modifier = modifier,
        containerColor = badgeColor,
        contentColor = badgeContentColor,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = spacing.xs),
        )
    }
}

// ============================================================================
// STATUS DOT
// ============================================================================

@Composable
fun RStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 8.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
fun ROnlineIndicator(
    online: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = Tokens.colors
    RStatusDot(
        color = if (online) colors.success else colors.outline,
        modifier = modifier,
    )
}
