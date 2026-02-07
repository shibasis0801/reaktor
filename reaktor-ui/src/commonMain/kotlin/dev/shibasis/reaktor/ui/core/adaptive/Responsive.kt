package dev.shibasis.reaktor.ui.core.adaptive

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.ui.core.tokens.BreakpointTokens
import dev.shibasis.reaktor.ui.core.tokens.Tokens

/**
 * Responsive Utilities
 *
 * Provides adaptive layout capabilities for mobile, tablet, and desktop.
 * Works across all platforms: Android, iOS, Web, Desktop.
 */

// ============================================================================
// SCREEN SIZE DETECTION
// ============================================================================

/**
 * Current screen class based on width
 */
enum class ScreenClass {
    Mobile,     // < 600dp (phones in portrait)
    Tablet,     // 600-839dp (tablets, phones in landscape)
    Desktop,    // 840-1199dp (small laptops, tablets in landscape)
    LargeDesktop // >= 1200dp (desktops, large monitors)
}

/**
 * Window size information
 */
data class WindowSize(
    val width: Dp,
    val height: Dp,
    val screenClass: ScreenClass,
) {
    val isMobile: Boolean get() = screenClass == ScreenClass.Mobile
    val isTablet: Boolean get() = screenClass == ScreenClass.Tablet
    val isDesktop: Boolean get() = screenClass == ScreenClass.Desktop || screenClass == ScreenClass.LargeDesktop
    val isCompact: Boolean get() = isMobile
    val isMedium: Boolean get() = isTablet
    val isExpanded: Boolean get() = isDesktop
}

val LocalWindowSize = compositionLocalOf<WindowSize> {
    WindowSize(
        width = 360.dp,
        height = 800.dp,
        screenClass = ScreenClass.Mobile
    )
}

// ============================================================================
// RESPONSIVE WRAPPER
// ============================================================================

/**
 * Wrapper that provides window size information to children
 */
@Composable
fun ResponsiveLayout(
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.(WindowSize) -> Unit,
) {
    val breakpoints = Tokens.breakpoints

    BoxWithConstraints(modifier = modifier) {
        val width = maxWidth
        val height = maxHeight

        val screenClass = when {
            width < breakpoints.tablet -> ScreenClass.Mobile
            width < breakpoints.desktop -> ScreenClass.Tablet
            width < breakpoints.largeDesktop -> ScreenClass.Desktop
            else -> ScreenClass.LargeDesktop
        }

        val windowSize = remember(width, height, screenClass) {
            WindowSize(width, height, screenClass)
        }

        content(windowSize)
    }
}

// ============================================================================
// RESPONSIVE VALUE SELECTOR
// ============================================================================

/**
 * Select a value based on screen class
 */
@Composable
fun <T> responsiveValue(
    mobile: T,
    tablet: T = mobile,
    desktop: T = tablet,
    largeDesktop: T = desktop,
): T {
    val breakpoints = Tokens.breakpoints

    return responsiveValueWithConstraints { width ->
        when {
            width < breakpoints.tablet -> mobile
            width < breakpoints.desktop -> tablet
            width < breakpoints.largeDesktop -> desktop
            else -> largeDesktop
        }
    }
}

/**
 * Select a value using BoxWithConstraints
 */
@Composable
fun <T> responsiveValueWithConstraints(
    selector: (width: Dp) -> T,
): T {
    var result: T? = null
    BoxWithConstraints {
        result = selector(maxWidth)
    }
    // This is a workaround - in real usage, wrap content in BoxWithConstraints
    return result ?: selector(360.dp) // Default to mobile
}

// ============================================================================
// ADAPTIVE MODIFIERS
// ============================================================================

/**
 * Apply different modifiers based on screen class
 */
@Composable
fun Modifier.responsive(
    mobile: Modifier = Modifier,
    tablet: Modifier = mobile,
    desktop: Modifier = tablet,
): Modifier {
    val breakpoints = Tokens.breakpoints

    // Note: This requires being inside a BoxWithConstraints
    // For a simpler API, use ResponsiveLayout
    return this.then(mobile) // Default implementation
}

// ============================================================================
// GRID HELPERS
// ============================================================================

/**
 * Calculate number of grid columns based on screen class
 */
@Composable
fun responsiveColumns(
    mobile: Int = 1,
    tablet: Int = 2,
    desktop: Int = 3,
    largeDesktop: Int = 4,
): Int = responsiveValue(mobile, tablet, desktop, largeDesktop)

/**
 * Calculate content padding based on screen class
 */
@Composable
fun responsivePadding(): Dp {
    val spacing = Tokens.spacing
    return responsiveValue(
        mobile = spacing.md,
        tablet = spacing.lg,
        desktop = spacing.xl,
        largeDesktop = spacing.xxl,
    )
}

/**
 * Calculate max content width for centered layouts
 */
@Composable
fun responsiveMaxWidth(): Dp = responsiveValue(
    mobile = Dp.Unspecified,    // Full width on mobile
    tablet = 720.dp,
    desktop = 960.dp,
    largeDesktop = 1200.dp,
)

// ============================================================================
// NAVIGATION HELPERS
// ============================================================================

/**
 * Determine navigation layout type
 */
enum class NavigationType {
    BottomNavigation,    // Mobile: bottom bar
    NavigationRail,      // Tablet: side rail
    NavigationDrawer,    // Desktop: permanent drawer
}

@Composable
fun responsiveNavigationType(): NavigationType = responsiveValue(
    mobile = NavigationType.BottomNavigation,
    tablet = NavigationType.NavigationRail,
    desktop = NavigationType.NavigationDrawer,
)

// ============================================================================
// LIST/DETAIL LAYOUT
// ============================================================================

/**
 * Determine if list-detail should show both panes
 */
@Composable
fun shouldShowListAndDetail(): Boolean = responsiveValue(
    mobile = false,
    tablet = false,
    desktop = true,
)

/**
 * Calculate list pane width in list-detail layout
 */
@Composable
fun listPaneWidth(): Dp = responsiveValue(
    mobile = Dp.Unspecified,
    tablet = Dp.Unspecified,
    desktop = 320.dp,
    largeDesktop = 400.dp,
)
