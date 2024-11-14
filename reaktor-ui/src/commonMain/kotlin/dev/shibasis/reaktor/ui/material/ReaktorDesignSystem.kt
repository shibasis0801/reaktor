package dev.shibasis.reaktor.ui.material

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.shibasis.reaktor.ui.DesignSystem

//// Primary Palette (Sky Blue)
//val md_theme_light_primary = Color(0xFF006494)          // Dark sky blue
//val md_theme_light_onPrimary = Color(0xFFFFFFFF)        // White
//val md_theme_light_primaryContainer = Color(0xFFD0EFFF) // Very light sky blue
//val md_theme_light_onPrimaryContainer = Color(0xFF001F29) // Almost black
//
//// Secondary Palette (Shifted Hue for Contrast)
//val md_theme_light_secondary = Color(0xFF004D6D)          // Dark violet-blue
//val md_theme_light_onSecondary = Color(0xFFFFFFFF)        // White
//val md_theme_light_secondaryContainer = Color(0xFFCCE7FF) // Very light violet-blue
//val md_theme_light_onSecondaryContainer = Color(0xFF001A24) // Almost black
//
//// Tertiary Palette (Further Hue Shift)
//val md_theme_light_tertiary = Color(0xFF5E35B1)          // Dark purple
//val md_theme_light_onTertiary = Color(0xFFFFFFFF)        // White
//val md_theme_light_tertiaryContainer = Color(0xFFD1C4E9) // Very light purple
//val md_theme_light_onTertiaryContainer = Color(0xFF311B92) // Almost black
//
//// Neutral Colors
//val md_theme_light_background = Color(0xFFFCFCFC)       // Almost white
//val md_theme_light_onBackground = Color(0xFF1A1A1A)     // Almost black
//val md_theme_light_surface = Color(0xFFFCFCFC)          // Almost white
//val md_theme_light_onSurface = Color(0xFF1A1A1A)        // Almost black
//
//// Error Colors (Material Defaults)
//val md_theme_light_error = Color(0xFFB3261E)
//val md_theme_light_onError = Color(0xFFFFFFFF)
//val md_theme_light_errorContainer = Color(0xFFF9DEDC)
//val md_theme_light_onErrorContainer = Color(0xFF410E0B)
//
//// Dark Theme Colors (Inverted Tones)
//val md_theme_dark_primary = Color(0xFF80D6FF)           // Light sky blue
//val md_theme_dark_onPrimary = Color(0xFF00344E)         // Darker blue
//val md_theme_dark_primaryContainer = Color(0xFF004B6B)  // Darker sky blue
//val md_theme_dark_onPrimaryContainer = Color(0xFFD0EFFF) // Very light sky blue
//
//val md_theme_dark_secondary = Color(0xFF66C2FF)         // Light violet-blue
//val md_theme_dark_onSecondary = Color(0xFF00344E)
//val md_theme_dark_secondaryContainer = Color(0xFF004B6B)
//val md_theme_dark_onSecondaryContainer = Color(0xFFCCE7FF)
//
//val md_theme_dark_tertiary = Color(0xFFB39DDB)          // Light purple
//val md_theme_dark_onTertiary = Color(0xFF311B92)
//val md_theme_dark_tertiaryContainer = Color(0xFF4527A0)
//val md_theme_dark_onTertiaryContainer = Color(0xFFD1C4E9)
//
//val md_theme_dark_background = Color(0xFF1A1A1A)        // Almost black
//val md_theme_dark_onBackground = Color(0xFFE6E6E6)      // Light gray
//val md_theme_dark_surface = Color(0xFF1A1A1A)           // Almost black
//val md_theme_dark_onSurface = Color(0xFFE6E6E6)         // Light gray
//
//// Error Colors (Material Defaults) for Dark Theme
//val md_theme_dark_error = Color(0xFFF2B8B5)
//val md_theme_dark_onError = Color(0xFF601410)
//val md_theme_dark_errorContainer = Color(0xFF8C1D18)
//val md_theme_dark_onErrorContainer = Color(0xFFF9DEDC)

/////////// 22222222222222222

//// Primary Palette (Soft Blue-Green)
//val md_theme_light_primary = Color(0xFF70C1B3)          // Soft blue-green
//val md_theme_light_onPrimary = Color(0xFFFFFFFF)        // White
//val md_theme_light_primaryContainer = Color(0xFFB2DFDB) // Lighter soft blue-green
//val md_theme_light_onPrimaryContainer = Color(0xFF00201E) // Dark teal
//
//// Secondary Palette (Accent - Coral)
//val md_theme_light_secondary = Color(0xFFFF7F50)          // Coral
//val md_theme_light_onSecondary = Color(0xFFFFFFFF)        // White
//val md_theme_light_secondaryContainer = Color(0xFFFFD9CC) // Light coral
//val md_theme_light_onSecondaryContainer = Color(0xFF331100) // Dark brown
//
//// Tertiary Palette (Optional Accent - Bright Yellow)
//val md_theme_light_tertiary = Color(0xFFFFD700)          // Bright yellow (Gold)
//val md_theme_light_onTertiary = Color(0xFF000000)        // Black
//val md_theme_light_tertiaryContainer = Color(0xFFFFFFE0) // Light yellow
//val md_theme_light_onTertiaryContainer = Color(0xFF333300) // Dark olive
//
//// Neutral Colors
//val md_theme_light_background = Color(0xFFFCFCFC)       // Almost white
//val md_theme_light_onBackground = Color(0xFF1A1A1A)     // Almost black
//val md_theme_light_surface = Color(0xFFFFFFFF)          // White
//val md_theme_light_onSurface = Color(0xFF1A1A1A)        // Almost black
//
//// Error Colors (Material Defaults)
//val md_theme_light_error = Color(0xFFB3261E)
//val md_theme_light_onError = Color(0xFFFFFFFF)
//val md_theme_light_errorContainer = Color(0xFFF9DEDC)
//val md_theme_light_onErrorContainer = Color(0xFF410E0B)
//
//// Dark Theme Colors
//val md_theme_dark_primary = Color(0xFF4E837B)           // Darker soft blue-green
//val md_theme_dark_onPrimary = Color(0xFFB2DFDB)         // Light soft blue-green
//val md_theme_dark_primaryContainer = Color(0xFF356E64)  // Dark teal
//val md_theme_dark_onPrimaryContainer = Color(0xFFDEF7F5) // Very light blue-green
//
//val md_theme_dark_secondary = Color(0xFFFF8F70)         // Lighter coral
//val md_theme_dark_onSecondary = Color(0xFF5A1E00)       // Dark brown
//val md_theme_dark_secondaryContainer = Color(0xFF7F3F25) // Dark coral
//val md_theme_dark_onSecondaryContainer = Color(0xFFFFD9CC) // Light coral
//
//val md_theme_dark_tertiary = Color(0xFFFFE033)          // Bright yellow
//val md_theme_dark_onTertiary = Color(0xFF333300)        // Dark olive
//val md_theme_dark_tertiaryContainer = Color(0xFF7F7F00) // Olive
//val md_theme_dark_onTertiaryContainer = Color(0xFFFFFFE0) // Light yellow
//
//val md_theme_dark_background = Color(0xFF121212)        // Dark gray
//val md_theme_dark_onBackground = Color(0xFFE6E6E6)      // Light gray
//val md_theme_dark_surface = Color(0xFF1E1E1E)           // Darker gray
//val md_theme_dark_onSurface = Color(0xFFE6E6E6)         // Light gray
//
//// Error Colors (Material Defaults) for Dark Theme
//val md_theme_dark_error = Color(0xFFF2B8B5)
//val md_theme_dark_onError = Color(0xFF601410)
//val md_theme_dark_errorContainer = Color(0xFF8C1D18)
//val md_theme_dark_onErrorContainer = Color(0xFFF9DEDC)
//



// Primary Palette (Teal)
val md_theme_light_primary = Color(0xFF008080)          // Teal
val md_theme_light_onPrimary = Color(0xFFFFFFFF)        // White
val md_theme_light_primaryContainer = Color(0xFF66CCCC) // Light teal
val md_theme_light_onPrimaryContainer = Color(0xFF002021) // Dark teal

// Secondary Palette (Accent - Orange)
val md_theme_light_secondary = Color(0xFFFFA726)          // Bright orange (Accent)
val md_theme_light_onSecondary = Color(0xFF000000)        // Black
val md_theme_light_secondaryContainer = Color(0xFFFFD3B0) // Light orange
val md_theme_light_onSecondaryContainer = Color(0xFF331500) // Dark brown

// Tertiary Palette (Optional Accent)
val md_theme_light_tertiary = Color(0xFF4CAF50)          // Green
val md_theme_light_onTertiary = Color(0xFFFFFFFF)        // White
val md_theme_light_tertiaryContainer = Color(0xFFC8E6C9) // Light green
val md_theme_light_onTertiaryContainer = Color(0xFF002411) // Dark green

// Neutral Colors
val md_theme_light_background = Color(0xFFE0F7FA)       // Light cyan (Specified background color)
val md_theme_light_onBackground = Color(0xFF000000)     // Black
val md_theme_light_surface = Color(0xFFFFFFFF)          // White
val md_theme_light_onSurface = Color(0xFF000000)        // Black

// Error Colors (Material Defaults)
val md_theme_light_error = Color(0xFFB00020)
val md_theme_light_onError = Color(0xFFFFFFFF)
val md_theme_light_errorContainer = Color(0xFFFFCDD2)
val md_theme_light_onErrorContainer = Color(0xFF370B0E)

// Dark Theme Colors
val md_theme_dark_primary = Color(0xFF66CCCC)           // Light teal
val md_theme_dark_onPrimary = Color(0xFF003333)         // Dark teal
val md_theme_dark_primaryContainer = Color(0xFF004D4D)  // Darker teal
val md_theme_dark_onPrimaryContainer = Color(0xFFB2FFFF) // Very light teal

val md_theme_dark_secondary = Color(0xFFFFB74D)         // Light orange
val md_theme_dark_onSecondary = Color(0xFF000000)       // Black
val md_theme_dark_secondaryContainer = Color(0xFFE65100) // Dark orange
val md_theme_dark_onSecondaryContainer = Color(0xFFFFD3B0) // Light orange

val md_theme_dark_tertiary = Color(0xFF81C784)          // Light green
val md_theme_dark_onTertiary = Color(0xFF003300)        // Dark green
val md_theme_dark_tertiaryContainer = Color(0xFF2E7D32) // Dark green
val md_theme_dark_onTertiaryContainer = Color(0xFFC8E6C9) // Light green

val md_theme_dark_background = Color(0xFF303030)        // Dark gray (Adjusted for better contrast)
val md_theme_dark_onBackground = Color(0xFFE0F7FA)      // Light cyan (Specified background color)
val md_theme_dark_surface = Color(0xFF424242)           // Darker gray
val md_theme_dark_onSurface = Color(0xFFE0F7FA)         // Light cyan

// Error Colors (Material Defaults) for Dark Theme
val md_theme_dark_error = Color(0xFFCF6679)
val md_theme_dark_onError = Color(0xFF1E1E1E)
val md_theme_dark_errorContainer = Color(0xFFB00020)
val md_theme_dark_onErrorContainer = Color(0xFFFFCDD2)

private val LightColorScheme = lightColorScheme(
    primary = md_theme_light_primary,
    onPrimary = md_theme_light_onPrimary,
    primaryContainer = md_theme_light_primaryContainer,
    onPrimaryContainer = md_theme_light_onPrimaryContainer,
    secondary = md_theme_light_secondary,
    onSecondary = md_theme_light_onSecondary,
    secondaryContainer = md_theme_light_secondaryContainer,
    onSecondaryContainer = md_theme_light_onSecondaryContainer,
    tertiary = md_theme_light_tertiary,
    onTertiary = md_theme_light_onTertiary,
    tertiaryContainer = md_theme_light_tertiaryContainer,
    onTertiaryContainer = md_theme_light_onTertiaryContainer,
    background = md_theme_light_background,
    onBackground = md_theme_light_onBackground,
    surface = md_theme_light_surface,
    onSurface = md_theme_light_onSurface,
    error = md_theme_light_error,
    onError = md_theme_light_onError,
    errorContainer = md_theme_light_errorContainer,
    onErrorContainer = md_theme_light_onErrorContainer,
)

private val DarkColorScheme = darkColorScheme(
    primary = md_theme_dark_primary,
    onPrimary = md_theme_dark_onPrimary,
    primaryContainer = md_theme_dark_primaryContainer,
    onPrimaryContainer = md_theme_dark_onPrimaryContainer,
    secondary = md_theme_dark_secondary,
    onSecondary = md_theme_dark_onSecondary,
    secondaryContainer = md_theme_dark_secondaryContainer,
    onSecondaryContainer = md_theme_dark_onSecondaryContainer,
    tertiary = md_theme_dark_tertiary,
    onTertiary = md_theme_dark_onTertiary,
    tertiaryContainer = md_theme_dark_tertiaryContainer,
    onTertiaryContainer = md_theme_dark_onTertiaryContainer,
    background = md_theme_dark_background,
    onBackground = md_theme_dark_onBackground,
    surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface,
    error = md_theme_dark_error,
    onError = md_theme_dark_onError,
    errorContainer = md_theme_dark_errorContainer,
    onErrorContainer = md_theme_dark_onErrorContainer,
)

object ReaktorDesignSystem: DesignSystem {
    @Composable override fun getTypography() = MaterialTheme.typography
    @Composable override fun getColorScheme() = LightColorScheme
}