/**
 * Token Factory - utilities for creating design tokens
 *
 * Provides intelligent token derivation so you only need to specify
 * a few key colors and the rest are automatically generated.
 */

import {
  ColorSchemeTokens,
  DesignTokens,
  defaultTypography,
  defaultSpacing,
  defaultShapes,
  defaultElevation,
  defaultSizing,
  defaultBreakpoints,
  defaultMotion,
} from './DesignTokens';

/**
 * Parse a hex color to RGB components
 */
function hexToRgb(hex: string): { r: number; g: number; b: number } {
  const result = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex);
  if (!result) {
    return { r: 0, g: 0, b: 0 };
  }
  return {
    r: parseInt(result[1], 16) / 255,
    g: parseInt(result[2], 16) / 255,
    b: parseInt(result[3], 16) / 255,
  };
}

/**
 * Convert RGB to hex
 */
function rgbToHex(r: number, g: number, b: number): string {
  const toHex = (c: number) => {
    const hex = Math.round(c * 255).toString(16);
    return hex.length === 1 ? '0' + hex : hex;
  };
  return `#${toHex(r)}${toHex(g)}${toHex(b)}`;
}

/**
 * Calculate relative luminance
 */
function luminance(hex: string): number {
  const { r, g, b } = hexToRgb(hex);
  const a = [r, g, b].map((v) => {
    return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
  });
  return a[0] * 0.2126 + a[1] * 0.7152 + a[2] * 0.0722;
}

/**
 * Lighten a color by blending toward white
 */
export function lighten(hex: string, fraction: number): string {
  const { r, g, b } = hexToRgb(hex);
  return rgbToHex(
    r + (1 - r) * fraction,
    g + (1 - g) * fraction,
    b + (1 - b) * fraction
  );
}

/**
 * Darken a color by blending toward black
 */
export function darken(hex: string, fraction: number): string {
  const { r, g, b } = hexToRgb(hex);
  return rgbToHex(
    r * (1 - fraction),
    g * (1 - fraction),
    b * (1 - fraction)
  );
}

/**
 * Pick white or black content color based on background luminance
 */
export function autoContentColor(
  background: string,
  lightContent: string = '#FFFFFF',
  darkContent: string = '#1C1B1F'
): string {
  return luminance(background) < 0.5 ? lightContent : darkContent;
}

/**
 * Add alpha to a hex color
 */
export function withAlpha(hex: string, alpha: number): string {
  const { r, g, b } = hexToRgb(hex);
  return `rgba(${Math.round(r * 255)}, ${Math.round(g * 255)}, ${Math.round(b * 255)}, ${alpha})`;
}

/**
 * Create a container color from a source color
 */
function containerColor(source: string, lightMode: boolean): string {
  return lightMode ? lighten(source, 0.85) : darken(source, 0.3);
}

/**
 * Create a complete light mode color scheme from minimal input
 */
export function createLightColorScheme(
  primary: string,
  secondary: string,
  tertiary: string = secondary,
  background: string = '#FFFFFF',
  surface: string = '#FFFFFF',
  error: string = '#B00020',
  success: string = '#4CAF50',
  warning: string = '#FFC107',
  info: string = '#2196F3'
): ColorSchemeTokens {
  const darkContent = '#1C1B1F';

  return {
    // Surfaces
    background,
    surface,
    surfaceVariant: lighten(surface, 0.05),
    surfaceContainer: darken(surface, 0.04),
    surfaceContainerHigh: darken(surface, 0.08),
    surfaceContainerLow: darken(surface, 0.02),

    onBackground: autoContentColor(background, darkContent),
    onSurface: autoContentColor(surface, darkContent),
    onSurfaceVariant: withAlpha(darkContent, 0.7),

    // Primary
    primary,
    primaryContainer: containerColor(primary, true),
    onPrimary: autoContentColor(primary),
    onPrimaryContainer: darken(primary, 0.4),

    // Secondary
    secondary,
    secondaryContainer: containerColor(secondary, true),
    onSecondary: autoContentColor(secondary),
    onSecondaryContainer: darken(secondary, 0.4),

    // Tertiary
    tertiary,
    tertiaryContainer: containerColor(tertiary, true),
    onTertiary: autoContentColor(tertiary),
    onTertiaryContainer: darken(tertiary, 0.4),

    // Error
    error,
    errorContainer: containerColor(error, true),
    onError: autoContentColor(error),
    onErrorContainer: darken(error, 0.4),

    // Success
    success,
    onSuccess: autoContentColor(success),

    // Warning
    warning,
    onWarning: autoContentColor(warning),

    // Info
    info,
    onInfo: autoContentColor(info),

    // Utility
    outline: withAlpha(darkContent, 0.3),
    outlineVariant: withAlpha(darkContent, 0.15),
    scrim: withAlpha('#000000', 0.32),
    shadow: '#000000',

    // Inverse
    inverseSurface: '#313033',
    inverseOnSurface: '#F4EFF4',
    inversePrimary: lighten(primary, 0.4),
  };
}

/**
 * Create a complete dark mode color scheme from minimal input
 */
export function createDarkColorScheme(
  primary: string,
  secondary: string,
  tertiary: string = secondary,
  background: string = '#1C1B1F',
  surface: string = '#1C1B1F',
  error: string = '#CF6679',
  success: string = '#81C784',
  warning: string = '#FFD54F',
  info: string = '#64B5F6'
): ColorSchemeTokens {
  const lightContent = '#E6E1E5';

  return {
    // Surfaces
    background,
    surface,
    surfaceVariant: lighten(surface, 0.1),
    surfaceContainer: lighten(surface, 0.08),
    surfaceContainerHigh: lighten(surface, 0.12),
    surfaceContainerLow: lighten(surface, 0.04),

    onBackground: autoContentColor(background, lightContent),
    onSurface: autoContentColor(surface, lightContent),
    onSurfaceVariant: withAlpha(lightContent, 0.7),

    // Primary - lighter for dark mode
    primary: lighten(primary, 0.3),
    primaryContainer: darken(primary, 0.2),
    onPrimary: '#1C1B1F',
    onPrimaryContainer: lighten(primary, 0.6),

    // Secondary
    secondary: lighten(secondary, 0.3),
    secondaryContainer: darken(secondary, 0.2),
    onSecondary: '#1C1B1F',
    onSecondaryContainer: lighten(secondary, 0.6),

    // Tertiary
    tertiary: lighten(tertiary, 0.3),
    tertiaryContainer: darken(tertiary, 0.2),
    onTertiary: '#1C1B1F',
    onTertiaryContainer: lighten(tertiary, 0.6),

    // Error
    error,
    errorContainer: darken(error, 0.3),
    onError: '#1C1B1F',
    onErrorContainer: lighten(error, 0.4),

    // Success
    success,
    onSuccess: '#1C1B1F',

    // Warning
    warning,
    onWarning: '#1C1B1F',

    // Info
    info,
    onInfo: '#1C1B1F',

    // Utility
    outline: withAlpha(lightContent, 0.4),
    outlineVariant: withAlpha(lightContent, 0.2),
    scrim: withAlpha('#000000', 0.6),
    shadow: '#000000',

    // Inverse
    inverseSurface: '#E6E1E5',
    inverseOnSurface: '#313033',
    inversePrimary: primary,
  };
}

/**
 * Create complete design tokens from color scheme
 */
export function createDesignTokens(colors: ColorSchemeTokens): DesignTokens {
  return {
    colors,
    typography: defaultTypography,
    spacing: defaultSpacing,
    shapes: defaultShapes,
    elevation: defaultElevation,
    sizing: defaultSizing,
    breakpoints: defaultBreakpoints,
    motion: defaultMotion,
  };
}

/**
 * Create design tokens with custom colors (auto-detect dark mode)
 */
export function createTokens(
  primary: string,
  secondary: string,
  tertiary?: string,
  darkMode: boolean = false
): DesignTokens {
  const colors = darkMode
    ? createDarkColorScheme(primary, secondary, tertiary)
    : createLightColorScheme(primary, secondary, tertiary);

  return createDesignTokens(colors);
}
