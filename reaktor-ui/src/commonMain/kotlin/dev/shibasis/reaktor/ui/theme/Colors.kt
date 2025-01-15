package dev.shibasis.reaktor.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.shibasis.reaktor.ui.material.ReaktorColor

/**
 * Naively lighten a color by blending it toward white by [fraction].
 * fraction = 0.0f -> unchanged color
 * fraction = 1.0f -> fully white
 */
private fun lighten(color: Color, fraction: Float): Color {
    val r = color.red + (1f - color.red) * fraction
    val g = color.green + (1f - color.green) * fraction
    val b = color.blue + (1f - color.blue) * fraction
    return Color(r, g, b, color.alpha)
}

/**
 * Simple heuristic to pick white or black text based on the color’s luminance.
 */
private fun autoContentColor(background: Color): Color {
    return if (background.luminance() < 0.5f) Color.White else ReaktorColor.Black
}

fun minimalLightColorScheme(
    primary: Color,
    secondary: Color,
    tertiary: Color,
    surface: Color,
    background: Color,
    error: Color
): ColorScheme {
    // Derive containers (lighten them a bit)
    val primaryContainer   = lighten(primary, 0.2f)
    val secondaryContainer = lighten(secondary, 0.2f)
    val tertiaryContainer  = lighten(tertiary, 0.2f)
    val errorContainer     = lighten(error, 0.2f)

    // “On” colors: auto pick white or black
    val onPrimary          = autoContentColor(primary)
    val onPrimaryContainer = autoContentColor(primaryContainer)
    val onSecondary        = autoContentColor(secondary)
    val onSecondaryContainer = autoContentColor(secondaryContainer)
    val onTertiary         = autoContentColor(tertiary)
    val onTertiaryContainer= autoContentColor(tertiaryContainer)
    val onError            = autoContentColor(error)
    val onErrorContainer   = autoContentColor(errorContainer)

    // “On” versions for background/surface
    val onBackground       = autoContentColor(background)
    val onSurface          = autoContentColor(surface)

    // Derive some additional colors from existing
    val surfaceVariant     = lighten(surface, 0.1f)
    val onSurfaceVariant   = autoContentColor(surfaceVariant)
    val outline            = onSurface.copy(alpha = 0.50f)
    val outlineVariant     = onSurface.copy(alpha = 0.20f)
    val scrim              = ReaktorColor.Black

    // Inverse: flip background/surface vs. “on” color
    val inverseSurface     = onBackground
    val inverseOnSurface   = background
    // Make “inversePrimary” a tinted version of primary
    val inversePrimary     = lighten(primary, 0.4f)

    return lightColorScheme(
        primary               = primary,
        onPrimary             = onPrimary,
        primaryContainer      = primaryContainer,
        onPrimaryContainer    = onPrimaryContainer,

        secondary             = secondary,
        onSecondary           = onSecondary,
        secondaryContainer    = secondaryContainer,
        onSecondaryContainer  = onSecondaryContainer,

        tertiary              = tertiary,
        onTertiary            = onTertiary,
        tertiaryContainer     = tertiaryContainer,
        onTertiaryContainer   = onTertiaryContainer,

        error                 = error,
        onError               = onError,
        errorContainer        = errorContainer,
        onErrorContainer      = onErrorContainer,

        background            = background,
        onBackground          = onBackground,

        surface               = surface,
        onSurface             = onSurface,
        surfaceVariant        = surfaceVariant,
        onSurfaceVariant      = onSurfaceVariant,

        outline               = outline,
        outlineVariant        = outlineVariant,
        scrim                 = scrim,

        inverseSurface        = inverseSurface,
        inverseOnSurface      = inverseOnSurface,
        inversePrimary        = inversePrimary,
    )
}


fun minimalDarkColorScheme(
    primary: Color,
    secondary: Color,
    tertiary: Color,
    surface: Color,
    background: Color,
    error: Color
): ColorScheme {
    // In dark mode, we might lighten containers a bit to make them more visible
    val primaryContainer   = lighten(primary, 0.1f)
    val secondaryContainer = lighten(secondary, 0.1f)
    val tertiaryContainer  = lighten(tertiary, 0.1f)
    val errorContainer     = lighten(error, 0.1f)

    // Automatic “on” color
    val onPrimary          = autoContentColor(primary)
    val onPrimaryContainer = autoContentColor(primaryContainer)
    val onSecondary        = autoContentColor(secondary)
    val onSecondaryContainer = autoContentColor(secondaryContainer)
    val onTertiary         = autoContentColor(tertiary)
    val onTertiaryContainer= autoContentColor(tertiaryContainer)
    val onError            = autoContentColor(error)
    val onErrorContainer   = autoContentColor(errorContainer)

    val onBackground       = autoContentColor(background)
    val onSurface          = autoContentColor(surface)

    // Extra derivations
    val surfaceVariant     = lighten(surface, 0.15f)
    val onSurfaceVariant   = autoContentColor(surfaceVariant)
    val outline            = onSurface.copy(alpha = 0.60f)
    val outlineVariant     = onSurfaceVariant.copy(alpha = 0.30f)
    val scrim              = ReaktorColor.Black

    // Inverse approach
    val inverseSurface     = onBackground
    val inverseOnSurface   = background
    // Dark mode “inversePrimary” can lighten the primary even more:
    val inversePrimary     = lighten(primary, 0.4f)

    return darkColorScheme(
        primary               = primary,
        onPrimary             = onPrimary,
        primaryContainer      = primaryContainer,
        onPrimaryContainer    = onPrimaryContainer,

        secondary             = secondary,
        onSecondary           = onSecondary,
        secondaryContainer    = secondaryContainer,
        onSecondaryContainer  = onSecondaryContainer,

        tertiary              = tertiary,
        onTertiary            = onTertiary,
        tertiaryContainer     = tertiaryContainer,
        onTertiaryContainer   = onTertiaryContainer,

        error                 = error,
        onError               = onError,
        errorContainer        = errorContainer,
        onErrorContainer      = onErrorContainer,

        background            = background,
        onBackground          = onBackground,

        surface               = surface,
        onSurface             = onSurface,
        surfaceVariant        = surfaceVariant,
        onSurfaceVariant      = onSurfaceVariant,

        outline               = outline,
        outlineVariant        = outlineVariant,
        scrim                 = scrim,

        inverseSurface        = inverseSurface,
        inverseOnSurface      = inverseOnSurface,
        inversePrimary        = inversePrimary,
    )
}