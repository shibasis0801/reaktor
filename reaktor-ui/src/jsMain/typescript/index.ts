/**
 * Reaktor UI for React
 *
 * A cross-platform UI component library designed for consistency
 * between Compose Multiplatform and React applications.
 *
 * The core token system and components are written in Kotlin/JS
 * and exported here. This TypeScript layer provides:
 * - Type-safe wrappers
 * - React Context integration
 * - Convenience hooks
 *
 * @example
 * ```tsx
 * import { ReaktorThemeColors, RButton, RText, RCard } from 'reaktor-ui';
 *
 * function App() {
 *   return (
 *     <ReaktorThemeColors primary="#6750A4" secondary="#625B71">
 *       <RCard variant="elevated">
 *         <RText role="title">Hello Reaktor</RText>
 *         <RButton label="Click Me" onClick={() => {}} />
 *       </RCard>
 *     </ReaktorThemeColors>
 *   );
 * }
 * ```
 */

// ============================================================================
// RE-EXPORT FROM KOTLIN/JS
// When building, the Kotlin/JS compiled output will be available at:
// reaktor-reaktor-ui (from the gradle module name)
// ============================================================================

// For now, we export the TypeScript implementations
// Once Kotlin/JS is built, these can be replaced with imports from the compiled output

// Theme System
export * from './theme';

// Design Tokens
export * from './tokens';

// Atomic Components (TypeScript implementations)
export * from './atoms';

// ============================================================================
// KOTLIN/JS BRIDGE TYPES
// These types match the @JsExport classes from Kotlin
// ============================================================================

/**
 * Bridge to Kotlin/JS WebDesignTokens
 * Import from compiled Kotlin output when available
 */
export interface KotlinWebDesignTokens {
  colors: KotlinWebColorScheme;
  typography: KotlinWebTypography;
  spacing: KotlinWebSpacing;
  shapes: KotlinWebShapes;
  elevation: KotlinWebElevation;
  sizing: KotlinWebSizing;
  breakpoints: KotlinWebBreakpoints;
  motion: KotlinWebMotion;
}

export interface KotlinWebColorScheme {
  background: string;
  surface: string;
  surfaceVariant: string;
  surfaceContainer: string;
  surfaceContainerHigh: string;
  surfaceContainerLow: string;
  onBackground: string;
  onSurface: string;
  onSurfaceVariant: string;
  primary: string;
  primaryContainer: string;
  onPrimary: string;
  onPrimaryContainer: string;
  secondary: string;
  secondaryContainer: string;
  onSecondary: string;
  onSecondaryContainer: string;
  tertiary: string;
  tertiaryContainer: string;
  onTertiary: string;
  onTertiaryContainer: string;
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
  outline: string;
  outlineVariant: string;
  scrim: string;
  shadow: string;
  inverseSurface: string;
  inverseOnSurface: string;
  inversePrimary: string;
}

export interface KotlinWebTypography {
  displayLarge: KotlinWebTextStyle;
  displayMedium: KotlinWebTextStyle;
  displaySmall: KotlinWebTextStyle;
  headlineLarge: KotlinWebTextStyle;
  headlineMedium: KotlinWebTextStyle;
  headlineSmall: KotlinWebTextStyle;
  titleLarge: KotlinWebTextStyle;
  titleMedium: KotlinWebTextStyle;
  titleSmall: KotlinWebTextStyle;
  bodyLarge: KotlinWebTextStyle;
  bodyMedium: KotlinWebTextStyle;
  bodySmall: KotlinWebTextStyle;
  labelLarge: KotlinWebTextStyle;
  labelMedium: KotlinWebTextStyle;
  labelSmall: KotlinWebTextStyle;
}

export interface KotlinWebTextStyle {
  fontSize: string;
  lineHeight: string;
  fontWeight: string;
  letterSpacing: string;
}

export interface KotlinWebSpacing {
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

export interface KotlinWebShapes {
  none: string;
  xs: string;
  sm: string;
  md: string;
  lg: string;
  xl: string;
  full: string;
}

export interface KotlinWebElevation {
  none: string;
  xs: string;
  sm: string;
  md: string;
  lg: string;
  xl: string;
}

export interface KotlinWebSizing {
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

export interface KotlinWebBreakpoints {
  mobile: number;
  tablet: number;
  desktop: number;
  largeDesktop: number;
}

export interface KotlinWebMotion {
  durationInstant: number;
  durationFast: number;
  durationNormal: number;
  durationSlow: number;
  durationSlowest: number;
}

// ============================================================================
// KOTLIN/JS FACTORY FUNCTIONS
// These will be imported from compiled Kotlin when available
// ============================================================================

/**
 * Factory functions exported from Kotlin/JS
 * Usage: import { createWebTokens } from 'reaktor-reaktor-ui'
 */
export type KotlinTokenFactory = {
  createLightColorScheme: (
    primary: string,
    secondary: string,
    tertiary?: string,
    background?: string,
    surface?: string,
    error?: string,
    success?: string,
    warning?: string,
    info?: string
  ) => KotlinWebColorScheme;

  createDarkColorScheme: (
    primary: string,
    secondary: string,
    tertiary?: string,
    background?: string,
    surface?: string,
    error?: string,
    success?: string,
    warning?: string,
    info?: string
  ) => KotlinWebColorScheme;

  createWebTokens: (
    primary: string,
    secondary: string,
    tertiary?: string,
    darkMode?: boolean
  ) => KotlinWebDesignTokens;

  lighten: (hex: string, fraction: number) => string;
  darken: (hex: string, fraction: number) => string;
  autoContentColor: (background: string, lightContent?: string, darkContent?: string) => string;
  withAlpha: (hex: string, alpha: number) => string;
};

/**
 * Material token presets from Kotlin/JS
 */
export type KotlinMaterialTokens = {
  defaultLight: KotlinWebDesignTokens;
  defaultDark: KotlinWebDesignTokens;
  blueLight: KotlinWebDesignTokens;
  blueDark: KotlinWebDesignTokens;
  greenLight: KotlinWebDesignTokens;
  greenDark: KotlinWebDesignTokens;
  reaktorLight: KotlinWebDesignTokens;
  reaktorDark: KotlinWebDesignTokens;
};

// Re-export commonly used types for convenience
export type {
  DesignTokens,
  ColorSchemeTokens,
  TypographyTokens,
  SpacingTokens,
  ShapeTokens,
  SizingTokens,
  BreakpointTokens,
} from './tokens/DesignTokens';

export type {
  ComponentSize,
  ComponentVariant,
  ComponentState,
  RButtonProps,
} from './atoms/Button';

export type {
  TextRole,
  TextSize,
  RTextProps,
} from './atoms/Text';

export type {
  RCardProps,
} from './atoms/Card';

export type {
  InputVariant,
  InputType,
  RInputProps,
} from './atoms/Input';
