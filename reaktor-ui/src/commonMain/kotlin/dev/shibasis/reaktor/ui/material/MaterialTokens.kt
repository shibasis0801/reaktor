package dev.shibasis.reaktor.ui.material

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.shibasis.reaktor.ui.core.tokens.*

/**
 * Material Design 3 Token Implementation
 *
 * This provides a complete Material Design 3 theme using the Reaktor token system.
 * Other design implementations (Neomorphic, Glass, etc.) would create their own
 * implementations of DesignTokens.
 */
object MaterialTokens {

    // ========================================================================
    // PREDEFINED COLOR PALETTES
    // ========================================================================

    /** Default purple/teal Material palette */
    val DefaultLight = TokenFactory.createLightColorScheme(
        primary = Color(0xFF6750A4),      // M3 Primary Purple
        secondary = Color(0xFF625B71),    // M3 Secondary
        tertiary = Color(0xFF7D5260),     // M3 Tertiary
        error = Color(0xFFB3261E),
    )

    val DefaultDark = TokenFactory.createDarkColorScheme(
        primary = Color(0xFF6750A4),
        secondary = Color(0xFF625B71),
        tertiary = Color(0xFF7D5260),
        error = Color(0xFFB3261E),
    )

    /** Blue palette - professional/corporate */
    val BlueLight = TokenFactory.createLightColorScheme(
        primary = Color(0xFF1976D2),      // Material Blue 700
        secondary = Color(0xFF455A64),    // Blue Grey 700
        tertiary = Color(0xFF00796B),     // Teal 700
    )

    val BlueDark = TokenFactory.createDarkColorScheme(
        primary = Color(0xFF1976D2),
        secondary = Color(0xFF455A64),
        tertiary = Color(0xFF00796B),
    )

    /** Green palette - nature/eco */
    val GreenLight = TokenFactory.createLightColorScheme(
        primary = Color(0xFF388E3C),      // Green 700
        secondary = Color(0xFF5D4037),    // Brown 700
        tertiary = Color(0xFF00796B),     // Teal 700
    )

    val GreenDark = TokenFactory.createDarkColorScheme(
        primary = Color(0xFF388E3C),
        secondary = Color(0xFF5D4037),
        tertiary = Color(0xFF00796B),
    )

    /** Orange palette - energetic/warm */
    val OrangeLight = TokenFactory.createLightColorScheme(
        primary = Color(0xFFF57C00),      // Orange 700
        secondary = Color(0xFF5D4037),    // Brown 700
        tertiary = Color(0xFFE64A19),     // Deep Orange 700
    )

    val OrangeDark = TokenFactory.createDarkColorScheme(
        primary = Color(0xFFF57C00),
        secondary = Color(0xFF5D4037),
        tertiary = Color(0xFFE64A19),
    )

    /** Wine/Teal - the original Reaktor palette */
    val ReaktorLight = TokenFactory.createLightColorScheme(
        primary = Color(0xFF702632),      // Wine
        secondary = Color(0xFF008080),    // Teal
        tertiary = Color(0xFF008080),     // Teal
        background = Color(0xFFE0F7FA),   // Sky Blue
    )

    val ReaktorDark = TokenFactory.createDarkColorScheme(
        primary = Color(0xFF702632),
        secondary = Color(0xFF008080),
        tertiary = Color(0xFF008080),
    )

    // ========================================================================
    // COMPLETE TOKEN SETS
    // ========================================================================

    /** Default Material Design tokens */
    val Default: DesignTokens
        @Composable get() = DesignTokens(
            colors = if (isSystemInDarkTheme()) DefaultDark else DefaultLight,
        )

    /** Blue themed tokens */
    val Blue: DesignTokens
        @Composable get() = DesignTokens(
            colors = if (isSystemInDarkTheme()) BlueDark else BlueLight,
        )

    /** Green themed tokens */
    val Green: DesignTokens
        @Composable get() = DesignTokens(
            colors = if (isSystemInDarkTheme()) GreenDark else GreenLight,
        )

    /** Orange themed tokens */
    val Orange: DesignTokens
        @Composable get() = DesignTokens(
            colors = if (isSystemInDarkTheme()) OrangeDark else OrangeLight,
        )

    /** Original Reaktor themed tokens */
    val Reaktor: DesignTokens
        @Composable get() = DesignTokens(
            colors = if (isSystemInDarkTheme()) ReaktorDark else ReaktorLight,
        )

    // ========================================================================
    // CUSTOM TOKEN BUILDER
    // ========================================================================

    /**
     * Create a custom Material token set from primary and secondary colors
     */
    @Composable
    fun custom(
        primary: Color,
        secondary: Color,
        tertiary: Color = secondary,
        background: Color? = null,
        surface: Color? = null,
    ): DesignTokens {
        val isDark = isSystemInDarkTheme()
        val colors = if (isDark) {
            TokenFactory.createDarkColorScheme(
                primary = primary,
                secondary = secondary,
                tertiary = tertiary,
                background = background ?: Color(0xFF1C1B1F),
                surface = surface ?: Color(0xFF1C1B1F),
            )
        } else {
            TokenFactory.createLightColorScheme(
                primary = primary,
                secondary = secondary,
                tertiary = tertiary,
                background = background ?: Color.White,
                surface = surface ?: Color.White,
            )
        }
        return DesignTokens(colors = colors)
    }

    /**
     * Create explicit light mode tokens
     */
    fun light(
        primary: Color,
        secondary: Color,
        tertiary: Color = secondary,
        background: Color = Color.White,
        surface: Color = Color.White,
    ): DesignTokens {
        return DesignTokens(
            colors = TokenFactory.createLightColorScheme(
                primary = primary,
                secondary = secondary,
                tertiary = tertiary,
                background = background,
                surface = surface,
            )
        )
    }

    /**
     * Create explicit dark mode tokens
     */
    fun dark(
        primary: Color,
        secondary: Color,
        tertiary: Color = secondary,
        background: Color = Color(0xFF1C1B1F),
        surface: Color = Color(0xFF1C1B1F),
    ): DesignTokens {
        return DesignTokens(
            colors = TokenFactory.createDarkColorScheme(
                primary = primary,
                secondary = secondary,
                tertiary = tertiary,
                background = background,
                surface = surface,
            )
        )
    }
}
