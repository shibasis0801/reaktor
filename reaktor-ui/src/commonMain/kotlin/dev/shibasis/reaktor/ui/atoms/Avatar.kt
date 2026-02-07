package dev.shibasis.reaktor.ui.atoms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.shibasis.reaktor.ui.core.components.ComponentSize
import dev.shibasis.reaktor.ui.core.tokens.Tokens

/**
 * Atom: Avatar
 *
 * User or entity representation with image, initials, or icon.
 * Supports multiple sizes and fallback states.
 */

@Composable
fun RAvatar(
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    initials: String? = null,
    icon: ImageVector? = null,
    size: ComponentSize = ComponentSize.Medium,
    backgroundColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    contentDescription: String? = null,
) {
    val colors = Tokens.colors
    val sizing = Tokens.sizing

    val avatarSize: Dp = when (size) {
        ComponentSize.Small -> sizing.avatarSm
        ComponentSize.Medium -> sizing.avatarMd
        ComponentSize.Large -> sizing.avatarLg
    }

    val fontSize = when (size) {
        ComponentSize.Small -> 12.sp
        ComponentSize.Medium -> 16.sp
        ComponentSize.Large -> 22.sp
    }

    val iconSize = when (size) {
        ComponentSize.Small -> sizing.iconSm
        ComponentSize.Medium -> sizing.iconMd
        ComponentSize.Large -> sizing.iconLg
    }

    val bgColor = if (backgroundColor == Color.Unspecified) {
        colors.primaryContainer
    } else backgroundColor

    val fgColor = if (contentColor == Color.Unspecified) {
        colors.onPrimaryContainer
    } else contentColor

    Box(
        modifier = modifier
            .size(avatarSize)
            .clip(CircleShape)
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        when {
            imageUrl != null -> {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(avatarSize),
                    contentScale = ContentScale.Crop,
                )
            }
            initials != null -> {
                Text(
                    text = initials.take(2).uppercase(),
                    color = fgColor,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Medium,
                )
            }
            icon != null -> {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(iconSize),
                    tint = fgColor,
                )
            }
        }
    }
}

// ============================================================================
// CONVENIENCE FUNCTIONS
// ============================================================================

@Composable
fun RAvatarImage(
    imageUrl: String,
    modifier: Modifier = Modifier,
    size: ComponentSize = ComponentSize.Medium,
    contentDescription: String? = null,
) {
    RAvatar(
        modifier = modifier,
        imageUrl = imageUrl,
        size = size,
        contentDescription = contentDescription,
    )
}

@Composable
fun RAvatarInitials(
    initials: String,
    modifier: Modifier = Modifier,
    size: ComponentSize = ComponentSize.Medium,
    backgroundColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
) {
    RAvatar(
        modifier = modifier,
        initials = initials,
        size = size,
        backgroundColor = backgroundColor,
        contentColor = contentColor,
    )
}

@Composable
fun RAvatarIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: ComponentSize = ComponentSize.Medium,
    backgroundColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    contentDescription: String? = null,
) {
    RAvatar(
        modifier = modifier,
        icon = icon,
        size = size,
        backgroundColor = backgroundColor,
        contentColor = contentColor,
        contentDescription = contentDescription,
    )
}

// ============================================================================
// EXTRA LARGE AVATAR (Profile Pages)
// ============================================================================

@Composable
fun RAvatarXl(
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    initials: String? = null,
    icon: ImageVector? = null,
    backgroundColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    contentDescription: String? = null,
) {
    val colors = Tokens.colors
    val sizing = Tokens.sizing

    val avatarSize = sizing.avatarXl
    val fontSize = 28.sp
    val iconSize = sizing.iconXl

    val bgColor = if (backgroundColor == Color.Unspecified) {
        colors.primaryContainer
    } else backgroundColor

    val fgColor = if (contentColor == Color.Unspecified) {
        colors.onPrimaryContainer
    } else contentColor

    Box(
        modifier = modifier
            .size(avatarSize)
            .clip(CircleShape)
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        when {
            imageUrl != null -> {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(avatarSize),
                    contentScale = ContentScale.Crop,
                )
            }
            initials != null -> {
                Text(
                    text = initials.take(2).uppercase(),
                    color = fgColor,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Medium,
                )
            }
            icon != null -> {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(iconSize),
                    tint = fgColor,
                )
            }
        }
    }
}
