package dev.shibasis.reaktor.ui.core.tokens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Token Factory - utilities for creating design tokens
 *
 * Provides intelligent token derivation so you only need to specify
 * a few key colors and the rest are automatically generated.
 */
object TokenFactory {

    /**
     * Lighten a color by blending toward white
     * @param fraction 0.0 = unchanged, 1.0 = fully white
     */
    fun lighten(color: Color, fraction: Float): Color {
        val r = color.red + (1f - color.red) * fraction
        val g = color.green + (1f - color.green) * fraction
        val b = color.blue + (1f - color.blue) * fraction
        return Color(r, g, b, color.alpha)
    }

    /**
     * Darken a color by blending toward black
     * @param fraction 0.0 = unchanged, 1.0 = fully black
     */
    fun darken(color: Color, fraction: Float): Color {
        val r = color.red * (1f - fraction)
        val g = color.green * (1f - fraction)
        val b = color.blue * (1f - fraction)
        return Color(r, g, b, color.alpha)
    }

    /**
     * Pick white or black content color based on background luminance
     */
    fun autoContentColor(
        background: Color,
        lightContent: Color = Color.White,
        darkContent: Color = Color(0xFF1C1B1F)
    ): Color {
        return if (background.luminance() < 0.5f) lightContent else darkContent
    }

    /**
     * Create a container color from a source color (lighter for light mode)
     */
    fun containerColor(source: Color, lightMode: Boolean): Color {
        return if (lightMode) lighten(source, 0.85f) else darken(source, 0.3f)
    }

    /**
     * Create a complete light mode color scheme from minimal input
     */
    fun createLightColorScheme(
        primary: Color,
        secondary: Color,
        tertiary: Color = secondary,
        background: Color = Color.White,
        surface: Color = Color.White,
        error: Color = Color(0xFFB00020),
        success: Color = Color(0xFF4CAF50),
        warning: Color = Color(0xFFFFC107),
        info: Color = Color(0xFF2196F3),
    ): ColorSchemeTokens {
        val darkContent = Color(0xFF1C1B1F)

        return ColorSchemeTokens(
            // Surfaces
            background = background,
            surface = surface,
            surfaceVariant = lighten(surface, 0.05f).copy(
                red = (surface.red + primary.red * 0.05f).coerceIn(0f, 1f),
                green = (surface.green + primary.green * 0.05f).coerceIn(0f, 1f),
                blue = (surface.blue + primary.blue * 0.05f).coerceIn(0f, 1f),
            ),
            surfaceContainer = darken(surface, 0.04f),
            surfaceContainerHigh = darken(surface, 0.08f),
            surfaceContainerLow = darken(surface, 0.02f),

            onBackground = autoContentColor(background, darkContent = darkContent),
            onSurface = autoContentColor(surface, darkContent = darkContent),
            onSurfaceVariant = darkContent.copy(alpha = 0.7f),

            // Primary
            primary = primary,
            primaryContainer = containerColor(primary, true),
            onPrimary = autoContentColor(primary),
            onPrimaryContainer = darken(primary, 0.4f),

            // Secondary
            secondary = secondary,
            secondaryContainer = containerColor(secondary, true),
            onSecondary = autoContentColor(secondary),
            onSecondaryContainer = darken(secondary, 0.4f),

            // Tertiary
            tertiary = tertiary,
            tertiaryContainer = containerColor(tertiary, true),
            onTertiary = autoContentColor(tertiary),
            onTertiaryContainer = darken(tertiary, 0.4f),

            // Error
            error = error,
            errorContainer = containerColor(error, true),
            onError = autoContentColor(error),
            onErrorContainer = darken(error, 0.4f),

            // Success
            success = success,
            onSuccess = autoContentColor(success),

            // Warning
            warning = warning,
            onWarning = autoContentColor(warning),

            // Info
            info = info,
            onInfo = autoContentColor(info),

            // Utility
            outline = darkContent.copy(alpha = 0.3f),
            outlineVariant = darkContent.copy(alpha = 0.15f),
            scrim = Color.Black.copy(alpha = 0.32f),
            shadow = Color.Black,

            // Inverse
            inverseSurface = Color(0xFF313033),
            inverseOnSurface = Color(0xFFF4EFF4),
            inversePrimary = lighten(primary, 0.4f),
        )
    }

    /**
     * Create a complete dark mode color scheme from minimal input
     */
    fun createDarkColorScheme(
        primary: Color,
        secondary: Color,
        tertiary: Color = secondary,
        background: Color = Color(0xFF1C1B1F),
        surface: Color = Color(0xFF1C1B1F),
        error: Color = Color(0xFFCF6679),
        success: Color = Color(0xFF81C784),
        warning: Color = Color(0xFFFFD54F),
        info: Color = Color(0xFF64B5F6),
    ): ColorSchemeTokens {
        val lightContent = Color(0xFFE6E1E5)

        return ColorSchemeTokens(
            // Surfaces
            background = background,
            surface = surface,
            surfaceVariant = lighten(surface, 0.1f),
            surfaceContainer = lighten(surface, 0.08f),
            surfaceContainerHigh = lighten(surface, 0.12f),
            surfaceContainerLow = lighten(surface, 0.04f),

            onBackground = autoContentColor(background, lightContent = lightContent),
            onSurface = autoContentColor(surface, lightContent = lightContent),
            onSurfaceVariant = lightContent.copy(alpha = 0.7f),

            // Primary - use lighter version for dark mode
            primary = lighten(primary, 0.3f),
            primaryContainer = darken(primary, 0.2f),
            onPrimary = Color(0xFF1C1B1F),
            onPrimaryContainer = lighten(primary, 0.6f),

            // Secondary
            secondary = lighten(secondary, 0.3f),
            secondaryContainer = darken(secondary, 0.2f),
            onSecondary = Color(0xFF1C1B1F),
            onSecondaryContainer = lighten(secondary, 0.6f),

            // Tertiary
            tertiary = lighten(tertiary, 0.3f),
            tertiaryContainer = darken(tertiary, 0.2f),
            onTertiary = Color(0xFF1C1B1F),
            onTertiaryContainer = lighten(tertiary, 0.6f),

            // Error
            error = error,
            errorContainer = darken(error, 0.3f),
            onError = Color(0xFF1C1B1F),
            onErrorContainer = lighten(error, 0.4f),

            // Success
            success = success,
            onSuccess = Color(0xFF1C1B1F),

            // Warning
            warning = warning,
            onWarning = Color(0xFF1C1B1F),

            // Info
            info = info,
            onInfo = Color(0xFF1C1B1F),

            // Utility
            outline = lightContent.copy(alpha = 0.4f),
            outlineVariant = lightContent.copy(alpha = 0.2f),
            scrim = Color.Black.copy(alpha = 0.6f),
            shadow = Color.Black,

            // Inverse
            inverseSurface = Color(0xFFE6E1E5),
            inverseOnSurface = Color(0xFF313033),
            inversePrimary = primary,
        )
    }
}
