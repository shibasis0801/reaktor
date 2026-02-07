package dev.shibasis.reaktor.ui.atoms

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import dev.shibasis.reaktor.ui.core.components.TextSpec
import dev.shibasis.reaktor.ui.core.tokens.FontWeight as TokenFontWeight
import dev.shibasis.reaktor.ui.core.tokens.Tokens

/**
 * Atom: Text
 *
 * Typography component for displaying text content.
 * Follows the type scale from design tokens.
 */

// ============================================================================
// MAIN TEXT API
// ============================================================================

@Composable
fun RText(
    text: String,
    modifier: Modifier = Modifier,
    role: TextSpec.TextRole = TextSpec.TextRole.Body,
    size: TextSpec.TextSize = TextSpec.TextSize.Medium,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextSpec.TextOverflow = TextSpec.TextOverflow.Ellipsis,
    textAlign: TextAlign? = null,
) {
    val typography = Tokens.typography
    val colors = Tokens.colors

    val styleTokens = when (role) {
        TextSpec.TextRole.Display -> when (size) {
            TextSpec.TextSize.Small -> typography.displaySmall
            TextSpec.TextSize.Medium -> typography.displayMedium
            TextSpec.TextSize.Large -> typography.displayLarge
        }
        TextSpec.TextRole.Headline -> when (size) {
            TextSpec.TextSize.Small -> typography.headlineSmall
            TextSpec.TextSize.Medium -> typography.headlineMedium
            TextSpec.TextSize.Large -> typography.headlineLarge
        }
        TextSpec.TextRole.Title -> when (size) {
            TextSpec.TextSize.Small -> typography.titleSmall
            TextSpec.TextSize.Medium -> typography.titleMedium
            TextSpec.TextSize.Large -> typography.titleLarge
        }
        TextSpec.TextRole.Body -> when (size) {
            TextSpec.TextSize.Small -> typography.bodySmall
            TextSpec.TextSize.Medium -> typography.bodyMedium
            TextSpec.TextSize.Large -> typography.bodyLarge
        }
        TextSpec.TextRole.Label -> when (size) {
            TextSpec.TextSize.Small -> typography.labelSmall
            TextSpec.TextSize.Medium -> typography.labelMedium
            TextSpec.TextSize.Large -> typography.labelLarge
        }
        TextSpec.TextRole.Caption -> typography.bodySmall
    }

    val textStyle = TextStyle(
        fontSize = styleTokens.fontSize,
        lineHeight = styleTokens.lineHeight,
        fontWeight = styleTokens.fontWeight.toCompose(),
        letterSpacing = styleTokens.letterSpacing,
    )

    val textOverflow = when (overflow) {
        TextSpec.TextOverflow.Clip -> TextOverflow.Clip
        TextSpec.TextOverflow.Ellipsis -> TextOverflow.Ellipsis
        TextSpec.TextOverflow.Visible -> TextOverflow.Visible
    }

    val resolvedColor = if (color == Color.Unspecified) {
        when (role) {
            TextSpec.TextRole.Display, TextSpec.TextRole.Headline -> colors.onBackground
            TextSpec.TextRole.Title -> colors.onSurface
            TextSpec.TextRole.Body -> colors.onSurface
            TextSpec.TextRole.Label -> colors.onSurfaceVariant
            TextSpec.TextRole.Caption -> colors.onSurfaceVariant
        }
    } else color

    Text(
        text = text,
        modifier = modifier,
        style = textStyle,
        color = resolvedColor,
        maxLines = maxLines,
        overflow = textOverflow,
        textAlign = textAlign,
    )
}

// ============================================================================
// CONVENIENCE FUNCTIONS
// ============================================================================

@Composable
fun RDisplayText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextSpec.TextSize = TextSpec.TextSize.Large,
    color: Color = Color.Unspecified,
) {
    RText(text, modifier, TextSpec.TextRole.Display, size, color)
}

@Composable
fun RHeadline(
    text: String,
    modifier: Modifier = Modifier,
    size: TextSpec.TextSize = TextSpec.TextSize.Medium,
    color: Color = Color.Unspecified,
) {
    RText(text, modifier, TextSpec.TextRole.Headline, size, color)
}

@Composable
fun RTitle(
    text: String,
    modifier: Modifier = Modifier,
    size: TextSpec.TextSize = TextSpec.TextSize.Medium,
    color: Color = Color.Unspecified,
) {
    RText(text, modifier, TextSpec.TextRole.Title, size, color)
}

@Composable
fun RBody(
    text: String,
    modifier: Modifier = Modifier,
    size: TextSpec.TextSize = TextSpec.TextSize.Medium,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    RText(text, modifier, TextSpec.TextRole.Body, size, color, maxLines)
}

@Composable
fun RLabel(
    text: String,
    modifier: Modifier = Modifier,
    size: TextSpec.TextSize = TextSpec.TextSize.Medium,
    color: Color = Color.Unspecified,
) {
    RText(text, modifier, TextSpec.TextRole.Label, size, color)
}

@Composable
fun RCaption(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    RText(text, modifier, TextSpec.TextRole.Caption, TextSpec.TextSize.Small, color)
}

// ============================================================================
// HELPER EXTENSIONS
// ============================================================================

private fun TokenFontWeight.toCompose(): FontWeight = when (this) {
    TokenFontWeight.Thin -> FontWeight.Thin
    TokenFontWeight.ExtraLight -> FontWeight.ExtraLight
    TokenFontWeight.Light -> FontWeight.Light
    TokenFontWeight.Normal -> FontWeight.Normal
    TokenFontWeight.Medium -> FontWeight.Medium
    TokenFontWeight.SemiBold -> FontWeight.SemiBold
    TokenFontWeight.Bold -> FontWeight.Bold
    TokenFontWeight.ExtraBold -> FontWeight.ExtraBold
    TokenFontWeight.Black -> FontWeight.Black
}
