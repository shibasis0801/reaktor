/**
 * Reaktor UI Design Token System for React/TypeScript
 *
 * This mirrors the Kotlin token system for cross-platform consistency.
 * All values should match the Compose Multiplatform implementation.
 */

// ============================================================================
// COLOR TYPES
// ============================================================================

export interface ColorSchemeTokens {
  // Surface colors
  background: string;
  surface: string;
  surfaceVariant: string;
  surfaceContainer: string;
  surfaceContainerHigh: string;
  surfaceContainerLow: string;

  // Content colors
  onBackground: string;
  onSurface: string;
  onSurfaceVariant: string;

  // Primary
  primary: string;
  primaryContainer: string;
  onPrimary: string;
  onPrimaryContainer: string;

  // Secondary
  secondary: string;
  secondaryContainer: string;
  onSecondary: string;
  onSecondaryContainer: string;

  // Tertiary
  tertiary: string;
  tertiaryContainer: string;
  onTertiary: string;
  onTertiaryContainer: string;

  // Feedback
  error: string;
  errorContainer: string;
  onError: string;
  onErrorContainer: string;

  success: string;
  onSuccess: string;

  warning: string;
  onWarning: string;

  info: string;
  onInfo: string;

  // Utility
  outline: string;
  outlineVariant: string;
  scrim: string;
  shadow: string;

  // Inverse
  inverseSurface: string;
  inverseOnSurface: string;
  inversePrimary: string;
}

// ============================================================================
// TYPOGRAPHY TYPES
// ============================================================================

export type FontWeight =
  | 'thin' | 'extraLight' | 'light' | 'normal'
  | 'medium' | 'semiBold' | 'bold' | 'extraBold' | 'black';

export interface TextStyleTokens {
  fontSize: string;
  lineHeight: string;
  fontWeight: FontWeight;
  letterSpacing: string;
}

export interface TypographyTokens {
  displayLarge: TextStyleTokens;
  displayMedium: TextStyleTokens;
  displaySmall: TextStyleTokens;

  headlineLarge: TextStyleTokens;
  headlineMedium: TextStyleTokens;
  headlineSmall: TextStyleTokens;

  titleLarge: TextStyleTokens;
  titleMedium: TextStyleTokens;
  titleSmall: TextStyleTokens;

  bodyLarge: TextStyleTokens;
  bodyMedium: TextStyleTokens;
  bodySmall: TextStyleTokens;

  labelLarge: TextStyleTokens;
  labelMedium: TextStyleTokens;
  labelSmall: TextStyleTokens;
}

// ============================================================================
// SPACING TYPES
// ============================================================================

export interface SpacingTokens {
  none: string;
  xxs: string;
  xs: string;
  sm: string;
  md: string;
  lg: string;
  xl: string;
  xxl: string;
  xxxl: string;
}

// ============================================================================
// SHAPE TYPES
// ============================================================================

export interface ShapeTokens {
  none: string;
  xs: string;
  sm: string;
  md: string;
  lg: string;
  xl: string;
  full: string;
}

// ============================================================================
// ELEVATION TYPES
// ============================================================================

export interface ElevationTokens {
  none: string;
  xs: string;
  sm: string;
  md: string;
  lg: string;
  xl: string;
}

// ============================================================================
// SIZING TYPES
// ============================================================================

export interface SizingTokens {
  touchTargetMin: string;
  iconXs: string;
  iconSm: string;
  iconMd: string;
  iconLg: string;
  iconXl: string;
  buttonSm: string;
  buttonMd: string;
  buttonLg: string;
  inputSm: string;
  inputMd: string;
  inputLg: string;
  avatarSm: string;
  avatarMd: string;
  avatarLg: string;
  avatarXl: string;
}

// ============================================================================
// BREAKPOINT TYPES
// ============================================================================

export interface BreakpointTokens {
  mobile: number;
  tablet: number;
  desktop: number;
  largeDesktop: number;
}

// ============================================================================
// MOTION TYPES
// ============================================================================

export interface MotionTokens {
  durationInstant: number;
  durationFast: number;
  durationNormal: number;
  durationSlow: number;
  durationSlowest: number;
}

// ============================================================================
// COMBINED DESIGN TOKENS
// ============================================================================

export interface DesignTokens {
  colors: ColorSchemeTokens;
  typography: TypographyTokens;
  spacing: SpacingTokens;
  shapes: ShapeTokens;
  elevation: ElevationTokens;
  sizing: SizingTokens;
  breakpoints: BreakpointTokens;
  motion: MotionTokens;
}

// ============================================================================
// DEFAULT VALUES
// ============================================================================

export const defaultTypography: TypographyTokens = {
  displayLarge: { fontSize: '57px', lineHeight: '64px', fontWeight: 'normal', letterSpacing: '0' },
  displayMedium: { fontSize: '45px', lineHeight: '52px', fontWeight: 'normal', letterSpacing: '0' },
  displaySmall: { fontSize: '36px', lineHeight: '44px', fontWeight: 'normal', letterSpacing: '0' },

  headlineLarge: { fontSize: '32px', lineHeight: '40px', fontWeight: 'normal', letterSpacing: '0' },
  headlineMedium: { fontSize: '28px', lineHeight: '36px', fontWeight: 'normal', letterSpacing: '0' },
  headlineSmall: { fontSize: '24px', lineHeight: '32px', fontWeight: 'normal', letterSpacing: '0' },

  titleLarge: { fontSize: '22px', lineHeight: '28px', fontWeight: 'medium', letterSpacing: '0' },
  titleMedium: { fontSize: '16px', lineHeight: '24px', fontWeight: 'medium', letterSpacing: '0.15px' },
  titleSmall: { fontSize: '14px', lineHeight: '20px', fontWeight: 'medium', letterSpacing: '0.1px' },

  bodyLarge: { fontSize: '16px', lineHeight: '24px', fontWeight: 'normal', letterSpacing: '0.5px' },
  bodyMedium: { fontSize: '14px', lineHeight: '20px', fontWeight: 'normal', letterSpacing: '0.25px' },
  bodySmall: { fontSize: '12px', lineHeight: '16px', fontWeight: 'normal', letterSpacing: '0.4px' },

  labelLarge: { fontSize: '14px', lineHeight: '20px', fontWeight: 'medium', letterSpacing: '0.1px' },
  labelMedium: { fontSize: '12px', lineHeight: '16px', fontWeight: 'medium', letterSpacing: '0.5px' },
  labelSmall: { fontSize: '11px', lineHeight: '16px', fontWeight: 'medium', letterSpacing: '0.5px' },
};

export const defaultSpacing: SpacingTokens = {
  none: '0',
  xxs: '2px',
  xs: '4px',
  sm: '8px',
  md: '16px',
  lg: '24px',
  xl: '32px',
  xxl: '48px',
  xxxl: '64px',
};

export const defaultShapes: ShapeTokens = {
  none: '0',
  xs: '4px',
  sm: '8px',
  md: '12px',
  lg: '16px',
  xl: '24px',
  full: '9999px',
};

export const defaultElevation: ElevationTokens = {
  none: 'none',
  xs: '0 1px 2px rgba(0,0,0,0.05)',
  sm: '0 1px 3px rgba(0,0,0,0.1), 0 1px 2px rgba(0,0,0,0.06)',
  md: '0 4px 6px rgba(0,0,0,0.1), 0 2px 4px rgba(0,0,0,0.06)',
  lg: '0 10px 15px rgba(0,0,0,0.1), 0 4px 6px rgba(0,0,0,0.05)',
  xl: '0 20px 25px rgba(0,0,0,0.1), 0 10px 10px rgba(0,0,0,0.04)',
};

export const defaultSizing: SizingTokens = {
  touchTargetMin: '48px',
  iconXs: '16px',
  iconSm: '20px',
  iconMd: '24px',
  iconLg: '32px',
  iconXl: '48px',
  buttonSm: '32px',
  buttonMd: '40px',
  buttonLg: '48px',
  inputSm: '32px',
  inputMd: '40px',
  inputLg: '56px',
  avatarSm: '32px',
  avatarMd: '40px',
  avatarLg: '56px',
  avatarXl: '80px',
};

export const defaultBreakpoints: BreakpointTokens = {
  mobile: 0,
  tablet: 600,
  desktop: 840,
  largeDesktop: 1200,
};

export const defaultMotion: MotionTokens = {
  durationInstant: 0,
  durationFast: 150,
  durationNormal: 300,
  durationSlow: 500,
  durationSlowest: 700,
};
