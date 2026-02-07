package dev.shibasis.reaktor.ui.atoms

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.shibasis.reaktor.ui.core.components.ComponentSize
import dev.shibasis.reaktor.ui.core.tokens.Tokens

/**
 * Atom: Icon
 *
 * Vector icon display component.
 * Supports standard sizes and automatic tinting.
 */

@Composable
fun RIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: ComponentSize = ComponentSize.Medium,
    color: Color = Color.Unspecified,
    contentDescription: String? = null,
) {
    val sizing = Tokens.sizing

    val iconSize = when (size) {
        ComponentSize.Small -> sizing.iconSm
        ComponentSize.Medium -> sizing.iconMd
        ComponentSize.Large -> sizing.iconLg
    }

    val tint = if (color == Color.Unspecified) {
        LocalContentColor.current
    } else {
        color
    }

    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier.size(iconSize),
        tint = tint,
    )
}

/**
 * Extra small icon for compact UIs
 */
@Composable
fun RIconXs(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    contentDescription: String? = null,
) {
    val sizing = Tokens.sizing
    val tint = if (color == Color.Unspecified) LocalContentColor.current else color

    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier.size(sizing.iconXs),
        tint = tint,
    )
}

/**
 * Extra large icon for hero sections
 */
@Composable
fun RIconXl(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    contentDescription: String? = null,
) {
    val sizing = Tokens.sizing
    val tint = if (color == Color.Unspecified) LocalContentColor.current else color

    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier.size(sizing.iconXl),
        tint = tint,
    )
}
