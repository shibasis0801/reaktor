package dev.shibasis.reaktor.ui.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.shibasis.reaktor.ui.core.tokens.*

/**
 * ReaktorThemeProvider
 *
 * Main theme wrapper for the Reaktor UI system.
 * Provides design tokens to all child components.
 *
 * This is the entry point for theming - wrap your app content with this.
 */

@Composable
fun ReaktorTheme(
    tokens: DesignTokens,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Convert our tokens to Material3 ColorScheme for compatibility
    val colorScheme = tokens.colors.toMaterial3ColorScheme()

    CompositionLocalProvider(
        LocalDesignTokens provides tokens,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}

/**
 * Convenience wrapper that creates tokens from minimal color input
 */
@Composable
fun ReaktorTheme(
    primary: androidx.compose.ui.graphics.Color,
    secondary: androidx.compose.ui.graphics.Color,
    tertiary: androidx.compose.ui.graphics.Color = secondary,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) {
        TokenFactory.createDarkColorScheme(primary, secondary, tertiary)
    } else {
        TokenFactory.createLightColorScheme(primary, secondary, tertiary)
    }

    val tokens = DesignTokens(colors = colorScheme)

    ReaktorTheme(
        tokens = tokens,
        darkTheme = darkTheme,
        content = content,
    )
}

/**
 * Convert Reaktor ColorSchemeTokens to Material3 ColorScheme
 */
private fun ColorSchemeTokens.toMaterial3ColorScheme(): androidx.compose.material3.ColorScheme {
    return lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = scrim,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        inversePrimary = inversePrimary,
    )
}
