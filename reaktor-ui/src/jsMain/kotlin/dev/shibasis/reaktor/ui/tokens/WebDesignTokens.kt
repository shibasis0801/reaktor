package dev.shibasis.reaktor.ui.tokens

import kotlin.js.JsExport
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Reaktor UI Design Token System for Web (Kotlin/JS)
 *
 * This is the Kotlin implementation that gets exported to JavaScript.
 * TypeScript wrapper provides type-safe access and React integration.
 */

// ============================================================================
// COLOR SCHEME TOKENS
// ============================================================================

@JsExport
data class WebColorScheme(
    // Surface colors
    val background: String,
    val surface: String,
    val surfaceVariant: String,
    val surfaceContainer: String,
    val surfaceContainerHigh: String,
    val surfaceContainerLow: String,

    // Content colors
    val onBackground: String,
    val onSurface: String,
    val onSurfaceVariant: String,

    // Primary
    val primary: String,
    val primaryContainer: String,
    val onPrimary: String,
    val onPrimaryContainer: String,

    // Secondary
    val secondary: String,
    val secondaryContainer: String,
    val onSecondary: String,
    val onSecondaryContainer: String,

    // Tertiary
    val tertiary: String,
    val tertiaryContainer: String,
    val onTertiary: String,
    val onTertiaryContainer: String,

    // Feedback
    val error: String,
    val errorContainer: String,
    val onError: String,
    val onErrorContainer: String,

    val success: String,
    val onSuccess: String,

    val warning: String,
    val onWarning: String,

    val info: String,
    val onInfo: String,

    // Utility
    val outline: String,
    val outlineVariant: String,
    val scrim: String,
    val shadow: String,

    // Inverse
    val inverseSurface: String,
    val inverseOnSurface: String,
    val inversePrimary: String,
)

// ============================================================================
// TYPOGRAPHY TOKENS
// ============================================================================

@JsExport
data class WebTextStyle(
    val fontSize: String,
    val lineHeight: String,
    val fontWeight: String,
    val letterSpacing: String,
)

@JsExport
data class WebTypography(
    val displayLarge: WebTextStyle,
    val displayMedium: WebTextStyle,
    val displaySmall: WebTextStyle,

    val headlineLarge: WebTextStyle,
    val headlineMedium: WebTextStyle,
    val headlineSmall: WebTextStyle,

    val titleLarge: WebTextStyle,
    val titleMedium: WebTextStyle,
    val titleSmall: WebTextStyle,

    val bodyLarge: WebTextStyle,
    val bodyMedium: WebTextStyle,
    val bodySmall: WebTextStyle,

    val labelLarge: WebTextStyle,
    val labelMedium: WebTextStyle,
    val labelSmall: WebTextStyle,
)

// ============================================================================
// SPACING TOKENS
// ============================================================================

@JsExport
data class WebSpacing(
    val none: String = "0",
    val xxs: String = "2px",
    val xs: String = "4px",
    val sm: String = "8px",
    val md: String = "16px",
    val lg: String = "24px",
    val xl: String = "32px",
    val xxl: String = "48px",
    val xxxl: String = "64px",
)

// ============================================================================
// SHAPE TOKENS
// ============================================================================

@JsExport
data class WebShapes(
    val none: String = "0",
    val xs: String = "4px",
    val sm: String = "8px",
    val md: String = "12px",
    val lg: String = "16px",
    val xl: String = "24px",
    val full: String = "9999px",
)

// ============================================================================
// ELEVATION TOKENS
// ============================================================================

@JsExport
data class WebElevation(
    val none: String = "none",
    val xs: String = "0 1px 2px rgba(0,0,0,0.05)",
    val sm: String = "0 1px 3px rgba(0,0,0,0.1), 0 1px 2px rgba(0,0,0,0.06)",
    val md: String = "0 4px 6px rgba(0,0,0,0.1), 0 2px 4px rgba(0,0,0,0.06)",
    val lg: String = "0 10px 15px rgba(0,0,0,0.1), 0 4px 6px rgba(0,0,0,0.05)",
    val xl: String = "0 20px 25px rgba(0,0,0,0.1), 0 10px 10px rgba(0,0,0,0.04)",
)

// ============================================================================
// SIZING TOKENS
// ============================================================================

@JsExport
data class WebSizing(
    val touchTargetMin: String = "48px",
    val iconXs: String = "16px",
    val iconSm: String = "20px",
    val iconMd: String = "24px",
    val iconLg: String = "32px",
    val iconXl: String = "48px",
    val buttonSm: String = "32px",
    val buttonMd: String = "40px",
    val buttonLg: String = "48px",
    val inputSm: String = "32px",
    val inputMd: String = "40px",
    val inputLg: String = "56px",
    val avatarSm: String = "32px",
    val avatarMd: String = "40px",
    val avatarLg: String = "56px",
    val avatarXl: String = "80px",
)

// ============================================================================
// BREAKPOINT TOKENS
// ============================================================================

@JsExport
data class WebBreakpoints(
    val mobile: Int = 0,
    val tablet: Int = 600,
    val desktop: Int = 840,
    val largeDesktop: Int = 1200,
)

// ============================================================================
// MOTION TOKENS
// ============================================================================

@JsExport
data class WebMotion(
    val durationInstant: Int = 0,
    val durationFast: Int = 150,
    val durationNormal: Int = 300,
    val durationSlow: Int = 500,
    val durationSlowest: Int = 700,
)

// ============================================================================
// COMBINED DESIGN TOKENS
// ============================================================================

@JsExport
data class WebDesignTokens(
    val colors: WebColorScheme,
    val typography: WebTypography = defaultWebTypography(),
    val spacing: WebSpacing = WebSpacing(),
    val shapes: WebShapes = WebShapes(),
    val elevation: WebElevation = WebElevation(),
    val sizing: WebSizing = WebSizing(),
    val breakpoints: WebBreakpoints = WebBreakpoints(),
    val motion: WebMotion = WebMotion(),
)

// ============================================================================
// DEFAULT VALUES
// ============================================================================

@JsExport
fun defaultWebTypography(): WebTypography = WebTypography(
    displayLarge = WebTextStyle("57px", "64px", "400", "0"),
    displayMedium = WebTextStyle("45px", "52px", "400", "0"),
    displaySmall = WebTextStyle("36px", "44px", "400", "0"),

    headlineLarge = WebTextStyle("32px", "40px", "400", "0"),
    headlineMedium = WebTextStyle("28px", "36px", "400", "0"),
    headlineSmall = WebTextStyle("24px", "32px", "400", "0"),

    titleLarge = WebTextStyle("22px", "28px", "500", "0"),
    titleMedium = WebTextStyle("16px", "24px", "500", "0.15px"),
    titleSmall = WebTextStyle("14px", "20px", "500", "0.1px"),

    bodyLarge = WebTextStyle("16px", "24px", "400", "0.5px"),
    bodyMedium = WebTextStyle("14px", "20px", "400", "0.25px"),
    bodySmall = WebTextStyle("12px", "16px", "400", "0.4px"),

    labelLarge = WebTextStyle("14px", "20px", "500", "0.1px"),
    labelMedium = WebTextStyle("12px", "16px", "500", "0.5px"),
    labelSmall = WebTextStyle("11px", "16px", "500", "0.5px"),
)
