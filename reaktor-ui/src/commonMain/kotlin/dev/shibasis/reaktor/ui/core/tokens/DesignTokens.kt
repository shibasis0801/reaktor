package dev.shibasis.reaktor.ui.core.tokens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reaktor UI Design Token System
 *
 * This is a platform-agnostic token system that can be implemented by any design system
 * (Material, Neomorphic, Glass, etc.). The base framework uses these tokens, not hardcoded values.
 *
 * Token hierarchy follows atomic design principles:
 * - Primitive tokens: Raw values (colors, sizes)
 * - Semantic tokens: Purpose-driven mappings (primary, surface, error)
 * - Component tokens: Component-specific values (button.padding, card.elevation)
 */

// ============================================================================
// PRIMITIVE COLOR TOKENS
// ============================================================================

/**
 * Raw color palette - no semantic meaning, just the palette available
 */
@Immutable
data class ColorPalette(
    val neutral0: Color = Color.White,
    val neutral10: Color = Color(0xFFF5F5F5),
    val neutral20: Color = Color(0xFFEEEEEE),
    val neutral30: Color = Color(0xFFE0E0E0),
    val neutral40: Color = Color(0xFFBDBDBD),
    val neutral50: Color = Color(0xFF9E9E9E),
    val neutral60: Color = Color(0xFF757575),
    val neutral70: Color = Color(0xFF616161),
    val neutral80: Color = Color(0xFF424242),
    val neutral90: Color = Color(0xFF212121),
    val neutral100: Color = Color.Black,

    val primary: Color = Color(0xFF6200EE),
    val primaryLight: Color = Color(0xFFBB86FC),
    val primaryDark: Color = Color(0xFF3700B3),

    val secondary: Color = Color(0xFF03DAC6),
    val secondaryLight: Color = Color(0xFF70EFDE),
    val secondaryDark: Color = Color(0xFF018786),

    val error: Color = Color(0xFFB00020),
    val errorLight: Color = Color(0xFFCF6679),
    val errorDark: Color = Color(0xFF8B0000),

    val success: Color = Color(0xFF4CAF50),
    val warning: Color = Color(0xFFFFC107),
    val info: Color = Color(0xFF2196F3),
)

// ============================================================================
// SEMANTIC COLOR TOKENS
// ============================================================================

/**
 * Semantic color scheme - colors with purpose, not just palette positions
 */
@Immutable
data class ColorSchemeTokens(
    // Surface colors
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerLow: Color,

    // Content colors (text/icons on surfaces)
    val onBackground: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,

    // Primary action colors
    val primary: Color,
    val primaryContainer: Color,
    val onPrimary: Color,
    val onPrimaryContainer: Color,

    // Secondary action colors
    val secondary: Color,
    val secondaryContainer: Color,
    val onSecondary: Color,
    val onSecondaryContainer: Color,

    // Tertiary accent colors
    val tertiary: Color,
    val tertiaryContainer: Color,
    val onTertiary: Color,
    val onTertiaryContainer: Color,

    // Feedback colors
    val error: Color,
    val errorContainer: Color,
    val onError: Color,
    val onErrorContainer: Color,

    val success: Color,
    val onSuccess: Color,

    val warning: Color,
    val onWarning: Color,

    val info: Color,
    val onInfo: Color,

    // Utility colors
    val outline: Color,
    val outlineVariant: Color,
    val scrim: Color,
    val shadow: Color,

    // Inverse colors (for special containers)
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val inversePrimary: Color,
)

// ============================================================================
// TYPOGRAPHY TOKENS
// ============================================================================

/**
 * Typography scale - size and weight combinations
 */
@Immutable
data class TypographyTokens(
    // Display - largest, for hero text
    val displayLarge: TextStyleTokens = TextStyleTokens(57.sp, 64.sp, FontWeight.Normal),
    val displayMedium: TextStyleTokens = TextStyleTokens(45.sp, 52.sp, FontWeight.Normal),
    val displaySmall: TextStyleTokens = TextStyleTokens(36.sp, 44.sp, FontWeight.Normal),

    // Headline - section headers
    val headlineLarge: TextStyleTokens = TextStyleTokens(32.sp, 40.sp, FontWeight.Normal),
    val headlineMedium: TextStyleTokens = TextStyleTokens(28.sp, 36.sp, FontWeight.Normal),
    val headlineSmall: TextStyleTokens = TextStyleTokens(24.sp, 32.sp, FontWeight.Normal),

    // Title - smaller headers, card titles
    val titleLarge: TextStyleTokens = TextStyleTokens(22.sp, 28.sp, FontWeight.Medium),
    val titleMedium: TextStyleTokens = TextStyleTokens(16.sp, 24.sp, FontWeight.Medium),
    val titleSmall: TextStyleTokens = TextStyleTokens(14.sp, 20.sp, FontWeight.Medium),

    // Body - main content text
    val bodyLarge: TextStyleTokens = TextStyleTokens(16.sp, 24.sp, FontWeight.Normal),
    val bodyMedium: TextStyleTokens = TextStyleTokens(14.sp, 20.sp, FontWeight.Normal),
    val bodySmall: TextStyleTokens = TextStyleTokens(12.sp, 16.sp, FontWeight.Normal),

    // Label - buttons, captions
    val labelLarge: TextStyleTokens = TextStyleTokens(14.sp, 20.sp, FontWeight.Medium),
    val labelMedium: TextStyleTokens = TextStyleTokens(12.sp, 16.sp, FontWeight.Medium),
    val labelSmall: TextStyleTokens = TextStyleTokens(11.sp, 16.sp, FontWeight.Medium),
)

@Immutable
data class TextStyleTokens(
    val fontSize: TextUnit,
    val lineHeight: TextUnit,
    val fontWeight: FontWeight,
    val letterSpacing: TextUnit = 0.sp,
)

enum class FontWeight {
    Thin,
    ExtraLight,
    Light,
    Normal,
    Medium,
    SemiBold,
    Bold,
    ExtraBold,
    Black
}

// ============================================================================
// SPACING TOKENS
// ============================================================================

/**
 * Spacing scale - consistent spacing across the app
 */
@Immutable
data class SpacingTokens(
    val none: Dp = 0.dp,
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val xxl: Dp = 48.dp,
    val xxxl: Dp = 64.dp,
)

// ============================================================================
// SHAPE TOKENS
// ============================================================================

/**
 * Shape/corner radius scale
 */
@Immutable
data class ShapeTokens(
    val none: Dp = 0.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val full: Dp = 9999.dp, // Fully rounded (pill shape)
)

// ============================================================================
// ELEVATION/SHADOW TOKENS
// ============================================================================

/**
 * Elevation levels for depth
 */
@Immutable
data class ElevationTokens(
    val none: Dp = 0.dp,
    val xs: Dp = 1.dp,
    val sm: Dp = 2.dp,
    val md: Dp = 4.dp,
    val lg: Dp = 8.dp,
    val xl: Dp = 16.dp,
)

// ============================================================================
// SIZING TOKENS
// ============================================================================

/**
 * Component size tokens for consistent sizing
 */
@Immutable
data class SizingTokens(
    // Touch target minimums
    val touchTargetMin: Dp = 48.dp,

    // Icon sizes
    val iconXs: Dp = 16.dp,
    val iconSm: Dp = 20.dp,
    val iconMd: Dp = 24.dp,
    val iconLg: Dp = 32.dp,
    val iconXl: Dp = 48.dp,

    // Button heights
    val buttonSm: Dp = 32.dp,
    val buttonMd: Dp = 40.dp,
    val buttonLg: Dp = 48.dp,

    // Input heights
    val inputSm: Dp = 32.dp,
    val inputMd: Dp = 40.dp,
    val inputLg: Dp = 56.dp,

    // Avatar sizes
    val avatarSm: Dp = 32.dp,
    val avatarMd: Dp = 40.dp,
    val avatarLg: Dp = 56.dp,
    val avatarXl: Dp = 80.dp,
)

// ============================================================================
// BREAKPOINT TOKENS (for responsive design)
// ============================================================================

/**
 * Screen breakpoints for adaptive layouts
 */
@Immutable
data class BreakpointTokens(
    val mobile: Dp = 0.dp,        // 0 - 599dp
    val tablet: Dp = 600.dp,      // 600 - 839dp
    val desktop: Dp = 840.dp,     // 840 - 1199dp
    val largeDesktop: Dp = 1200.dp, // 1200+dp
) {
    enum class ScreenClass {
        Mobile,
        Tablet,
        Desktop,
        LargeDesktop
    }

    fun classifyWidth(width: Dp): ScreenClass = when {
        width < tablet -> ScreenClass.Mobile
        width < desktop -> ScreenClass.Tablet
        width < largeDesktop -> ScreenClass.Desktop
        else -> ScreenClass.LargeDesktop
    }
}

// ============================================================================
// MOTION/ANIMATION TOKENS
// ============================================================================

/**
 * Animation duration tokens
 */
@Immutable
data class MotionTokens(
    val durationInstant: Int = 0,
    val durationFast: Int = 150,
    val durationNormal: Int = 300,
    val durationSlow: Int = 500,
    val durationSlowest: Int = 700,
)

// ============================================================================
// COMBINED DESIGN TOKENS
// ============================================================================

/**
 * Complete design token set - all tokens in one place
 */
@Immutable
data class DesignTokens(
    val colors: ColorSchemeTokens,
    val palette: ColorPalette = ColorPalette(),
    val typography: TypographyTokens = TypographyTokens(),
    val spacing: SpacingTokens = SpacingTokens(),
    val shapes: ShapeTokens = ShapeTokens(),
    val elevation: ElevationTokens = ElevationTokens(),
    val sizing: SizingTokens = SizingTokens(),
    val breakpoints: BreakpointTokens = BreakpointTokens(),
    val motion: MotionTokens = MotionTokens(),
)

// ============================================================================
// COMPOSITION LOCAL
// ============================================================================

val LocalDesignTokens = staticCompositionLocalOf<DesignTokens> {
    error("No DesignTokens provided. Wrap your content in a ReaktorTheme.")
}

/**
 * Access design tokens from composable scope
 */
object Tokens {
    val current: DesignTokens
        @Composable get() = LocalDesignTokens.current

    val colors: ColorSchemeTokens
        @Composable get() = current.colors

    val typography: TypographyTokens
        @Composable get() = current.typography

    val spacing: SpacingTokens
        @Composable get() = current.spacing

    val shapes: ShapeTokens
        @Composable get() = current.shapes

    val elevation: ElevationTokens
        @Composable get() = current.elevation

    val sizing: SizingTokens
        @Composable get() = current.sizing

    val breakpoints: BreakpointTokens
        @Composable get() = current.breakpoints

    val motion: MotionTokens
        @Composable get() = current.motion
}
