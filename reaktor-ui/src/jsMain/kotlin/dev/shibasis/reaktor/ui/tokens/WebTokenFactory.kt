package dev.shibasis.reaktor.ui.tokens

import kotlin.js.JsExport
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Token Factory for Web (Kotlin/JS)
 *
 * Provides intelligent token derivation so you only need to specify
 * a few key colors and the rest are automatically generated.
 *
 * All functions are exported to JavaScript for TypeScript consumption.
 */

// ============================================================================
// COLOR UTILITIES
// ============================================================================

private data class RGB(val r: Float, val g: Float, val b: Float)

private fun hexToRgb(hex: String): RGB {
    val cleanHex = hex.removePrefix("#")
    if (cleanHex.length != 6) return RGB(0f, 0f, 0f)

    return try {
        RGB(
            r = cleanHex.substring(0, 2).toInt(16) / 255f,
            g = cleanHex.substring(2, 4).toInt(16) / 255f,
            b = cleanHex.substring(4, 6).toInt(16) / 255f,
        )
    } catch (e: Exception) {
        RGB(0f, 0f, 0f)
    }
}

private fun rgbToHex(r: Float, g: Float, b: Float): String {
    fun toHex(c: Float): String {
        val hex = (c * 255).roundToInt().coerceIn(0, 255).toString(16)
        return if (hex.length == 1) "0$hex" else hex
    }
    return "#${toHex(r)}${toHex(g)}${toHex(b)}"
}

private fun luminance(hex: String): Float {
    val (r, g, b) = hexToRgb(hex)
    val components = listOf(r, g, b).map { v ->
        if (v <= 0.03928f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }
    return components[0] * 0.2126f + components[1] * 0.7152f + components[2] * 0.0722f
}

/**
 * Lighten a color by blending toward white
 */
@JsExport
fun lighten(hex: String, fraction: Float): String {
    val (r, g, b) = hexToRgb(hex)
    return rgbToHex(
        r + (1f - r) * fraction,
        g + (1f - g) * fraction,
        b + (1f - b) * fraction
    )
}

/**
 * Darken a color by blending toward black
 */
@JsExport
fun darken(hex: String, fraction: Float): String {
    val (r, g, b) = hexToRgb(hex)
    return rgbToHex(
        r * (1f - fraction),
        g * (1f - fraction),
        b * (1f - fraction)
    )
}

/**
 * Pick white or black content color based on background luminance
 */
@JsExport
fun autoContentColor(
    background: String,
    lightContent: String = "#FFFFFF",
    darkContent: String = "#1C1B1F"
): String {
    return if (luminance(background) < 0.5f) lightContent else darkContent
}

/**
 * Add alpha to a hex color, returning rgba() string
 */
@JsExport
fun withAlpha(hex: String, alpha: Float): String {
    val (r, g, b) = hexToRgb(hex)
    return "rgba(${(r * 255).roundToInt()}, ${(g * 255).roundToInt()}, ${(b * 255).roundToInt()}, $alpha)"
}

private fun containerColor(source: String, lightMode: Boolean): String {
    return if (lightMode) lighten(source, 0.85f) else darken(source, 0.3f)
}

// ============================================================================
// COLOR SCHEME FACTORIES
// ============================================================================

/**
 * Create a complete light mode color scheme from minimal input
 */
@JsExport
fun createLightColorScheme(
    primary: String,
    secondary: String,
    tertiary: String = secondary,
    background: String = "#FFFFFF",
    surface: String = "#FFFFFF",
    error: String = "#B00020",
    success: String = "#4CAF50",
    warning: String = "#FFC107",
    info: String = "#2196F3"
): WebColorScheme {
    val darkContent = "#1C1B1F"

    return WebColorScheme(
        // Surfaces
        background = background,
        surface = surface,
        surfaceVariant = lighten(surface, 0.05f),
        surfaceContainer = darken(surface, 0.04f),
        surfaceContainerHigh = darken(surface, 0.08f),
        surfaceContainerLow = darken(surface, 0.02f),

        onBackground = autoContentColor(background, darkContent = darkContent),
        onSurface = autoContentColor(surface, darkContent = darkContent),
        onSurfaceVariant = withAlpha(darkContent, 0.7f),

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
        outline = withAlpha(darkContent, 0.3f),
        outlineVariant = withAlpha(darkContent, 0.15f),
        scrim = withAlpha("#000000", 0.32f),
        shadow = "#000000",

        // Inverse
        inverseSurface = "#313033",
        inverseOnSurface = "#F4EFF4",
        inversePrimary = lighten(primary, 0.4f),
    )
}

/**
 * Create a complete dark mode color scheme from minimal input
 */
@JsExport
fun createDarkColorScheme(
    primary: String,
    secondary: String,
    tertiary: String = secondary,
    background: String = "#1C1B1F",
    surface: String = "#1C1B1F",
    error: String = "#CF6679",
    success: String = "#81C784",
    warning: String = "#FFD54F",
    info: String = "#64B5F6"
): WebColorScheme {
    val lightContent = "#E6E1E5"

    return WebColorScheme(
        // Surfaces
        background = background,
        surface = surface,
        surfaceVariant = lighten(surface, 0.1f),
        surfaceContainer = lighten(surface, 0.08f),
        surfaceContainerHigh = lighten(surface, 0.12f),
        surfaceContainerLow = lighten(surface, 0.04f),

        onBackground = autoContentColor(background, lightContent = lightContent),
        onSurface = autoContentColor(surface, lightContent = lightContent),
        onSurfaceVariant = withAlpha(lightContent, 0.7f),

        // Primary - lighter for dark mode
        primary = lighten(primary, 0.3f),
        primaryContainer = darken(primary, 0.2f),
        onPrimary = "#1C1B1F",
        onPrimaryContainer = lighten(primary, 0.6f),

        // Secondary
        secondary = lighten(secondary, 0.3f),
        secondaryContainer = darken(secondary, 0.2f),
        onSecondary = "#1C1B1F",
        onSecondaryContainer = lighten(secondary, 0.6f),

        // Tertiary
        tertiary = lighten(tertiary, 0.3f),
        tertiaryContainer = darken(tertiary, 0.2f),
        onTertiary = "#1C1B1F",
        onTertiaryContainer = lighten(tertiary, 0.6f),

        // Error
        error = error,
        errorContainer = darken(error, 0.3f),
        onError = "#1C1B1F",
        onErrorContainer = lighten(error, 0.4f),

        // Success
        success = success,
        onSuccess = "#1C1B1F",

        // Warning
        warning = warning,
        onWarning = "#1C1B1F",

        // Info
        info = info,
        onInfo = "#1C1B1F",

        // Utility
        outline = withAlpha(lightContent, 0.4f),
        outlineVariant = withAlpha(lightContent, 0.2f),
        scrim = withAlpha("#000000", 0.6f),
        shadow = "#000000",

        // Inverse
        inverseSurface = "#E6E1E5",
        inverseOnSurface = "#313033",
        inversePrimary = primary,
    )
}

/**
 * Create complete design tokens from color scheme
 */
@JsExport
fun createWebDesignTokens(colors: WebColorScheme): WebDesignTokens {
    return WebDesignTokens(colors = colors)
}

/**
 * Create design tokens with custom colors
 */
@JsExport
fun createWebTokens(
    primary: String,
    secondary: String,
    tertiary: String? = null,
    darkMode: Boolean = false
): WebDesignTokens {
    val actualTertiary = tertiary ?: secondary
    val colors = if (darkMode) {
        createDarkColorScheme(primary, secondary, actualTertiary)
    } else {
        createLightColorScheme(primary, secondary, actualTertiary)
    }
    return createWebDesignTokens(colors)
}

// ============================================================================
// PREDEFINED THEMES
// ============================================================================

@JsExport
object WebMaterialTokens {
    val defaultLight: WebDesignTokens
        get() = createWebTokens("#6750A4", "#625B71", "#7D5260", false)

    val defaultDark: WebDesignTokens
        get() = createWebTokens("#6750A4", "#625B71", "#7D5260", true)

    val blueLight: WebDesignTokens
        get() = createWebTokens("#1976D2", "#455A64", "#00796B", false)

    val blueDark: WebDesignTokens
        get() = createWebTokens("#1976D2", "#455A64", "#00796B", true)

    val greenLight: WebDesignTokens
        get() = createWebTokens("#388E3C", "#5D4037", "#00796B", false)

    val greenDark: WebDesignTokens
        get() = createWebTokens("#388E3C", "#5D4037", "#00796B", true)

    val reaktorLight: WebDesignTokens
        get() = createLightColorScheme(
            primary = "#702632",
            secondary = "#008080",
            tertiary = "#008080",
            background = "#E0F7FA"
        ).let { createWebDesignTokens(it) }

    val reaktorDark: WebDesignTokens
        get() = createDarkColorScheme(
            primary = "#702632",
            secondary = "#008080",
            tertiary = "#008080"
        ).let { createWebDesignTokens(it) }
}
