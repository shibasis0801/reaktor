/**
 * Design Tokens for React Strict DOM Demo
 *
 * These mirror the Reaktor UI token system structure
 */

export const colors = {
  // Primary
  primary: '#6750A4',
  primaryContainer: '#EADDFF',
  onPrimary: '#FFFFFF',
  onPrimaryContainer: '#21005D',

  // Secondary
  secondary: '#625B71',
  secondaryContainer: '#E8DEF8',
  onSecondary: '#FFFFFF',
  onSecondaryContainer: '#1D192B',

  // Tertiary
  tertiary: '#7D5260',
  tertiaryContainer: '#FFD8E4',
  onTertiary: '#FFFFFF',
  onTertiaryContainer: '#31111D',

  // Surface
  background: '#FFFBFE',
  surface: '#FFFBFE',
  surfaceVariant: '#E7E0EC',
  surfaceContainer: '#F3EDF7',
  surfaceContainerHigh: '#ECE6F0',

  // On colors
  onBackground: '#1C1B1F',
  onSurface: '#1C1B1F',
  onSurfaceVariant: '#49454F',

  // Error
  error: '#B3261E',
  errorContainer: '#F9DEDC',
  onError: '#FFFFFF',
  onErrorContainer: '#410E0B',

  // Success/Warning/Info
  success: '#4CAF50',
  warning: '#FFC107',
  info: '#2196F3',

  // Utility
  outline: '#79747E',
  outlineVariant: '#CAC4D0',
};

export const spacing = {
  none: 0,
  xxs: 2,
  xs: 4,
  sm: 8,
  md: 16,
  lg: 24,
  xl: 32,
  xxl: 48,
};

export const radii = {
  none: 0,
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 24,
  full: 9999,
};

export const typography = {
  displayLarge: { fontSize: 57, lineHeight: 64, fontWeight: '400' as const },
  displayMedium: { fontSize: 45, lineHeight: 52, fontWeight: '400' as const },
  displaySmall: { fontSize: 36, lineHeight: 44, fontWeight: '400' as const },

  headlineLarge: { fontSize: 32, lineHeight: 40, fontWeight: '400' as const },
  headlineMedium: { fontSize: 28, lineHeight: 36, fontWeight: '400' as const },
  headlineSmall: { fontSize: 24, lineHeight: 32, fontWeight: '400' as const },

  titleLarge: { fontSize: 22, lineHeight: 28, fontWeight: '500' as const },
  titleMedium: { fontSize: 16, lineHeight: 24, fontWeight: '500' as const },
  titleSmall: { fontSize: 14, lineHeight: 20, fontWeight: '500' as const },

  bodyLarge: { fontSize: 16, lineHeight: 24, fontWeight: '400' as const },
  bodyMedium: { fontSize: 14, lineHeight: 20, fontWeight: '400' as const },
  bodySmall: { fontSize: 12, lineHeight: 16, fontWeight: '400' as const },

  labelLarge: { fontSize: 14, lineHeight: 20, fontWeight: '500' as const },
  labelMedium: { fontSize: 12, lineHeight: 16, fontWeight: '500' as const },
  labelSmall: { fontSize: 11, lineHeight: 16, fontWeight: '500' as const },
};

export const shadows = {
  none: 'none',
  sm: '0 1px 3px rgba(0,0,0,0.12), 0 1px 2px rgba(0,0,0,0.24)',
  md: '0 3px 6px rgba(0,0,0,0.15), 0 2px 4px rgba(0,0,0,0.12)',
  lg: '0 10px 20px rgba(0,0,0,0.15), 0 3px 6px rgba(0,0,0,0.10)',
};
