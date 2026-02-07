package dev.shibasis.reaktor.ui.atoms

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.ui.core.tokens.Tokens

/**
 * Atom: Divider
 *
 * Visual separator for content sections.
 * Supports horizontal and vertical orientations.
 */

@Composable
fun RDivider(
    modifier: Modifier = Modifier,
    horizontal: Boolean = true,
    thickness: Dp = 1.dp,
    color: Color = Color.Unspecified,
) {
    val colors = Tokens.colors
    val dividerColor = if (color == Color.Unspecified) colors.outlineVariant else color

    if (horizontal) {
        HorizontalDivider(
            modifier = modifier.fillMaxWidth(),
            thickness = thickness,
            color = dividerColor,
        )
    } else {
        VerticalDivider(
            modifier = modifier.fillMaxHeight(),
            thickness = thickness,
            color = dividerColor,
        )
    }
}

@Composable
fun RHorizontalDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 1.dp,
    color: Color = Color.Unspecified,
) {
    RDivider(
        modifier = modifier,
        horizontal = true,
        thickness = thickness,
        color = color,
    )
}

@Composable
fun RVerticalDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 1.dp,
    color: Color = Color.Unspecified,
) {
    RDivider(
        modifier = modifier,
        horizontal = false,
        thickness = thickness,
        color = color,
    )
}
