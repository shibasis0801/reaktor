/**
 * ReaktorThemeProvider
 *
 * Main theme wrapper for the Reaktor UI system in React.
 * Provides design tokens to all child components via React Context.
 */

import React, { createContext, useContext, useMemo } from 'react';
import { DesignTokens } from '../tokens/DesignTokens';
import { createTokens } from '../tokens/TokenFactory';

// ============================================================================
// CONTEXT
// ============================================================================

const DesignTokensContext = createContext<DesignTokens | null>(null);

/**
 * Hook to access design tokens
 */
export function useTokens(): DesignTokens {
  const tokens = useContext(DesignTokensContext);
  if (!tokens) {
    throw new Error('useTokens must be used within a ReaktorTheme provider');
  }
  return tokens;
}

/**
 * Hook for color tokens
 */
export function useColors() {
  return useTokens().colors;
}

/**
 * Hook for typography tokens
 */
export function useTypography() {
  return useTokens().typography;
}

/**
 * Hook for spacing tokens
 */
export function useSpacing() {
  return useTokens().spacing;
}

/**
 * Hook for shape tokens
 */
export function useShapes() {
  return useTokens().shapes;
}

/**
 * Hook for sizing tokens
 */
export function useSizing() {
  return useTokens().sizing;
}

/**
 * Hook for elevation tokens
 */
export function useElevation() {
  return useTokens().elevation;
}

/**
 * Hook for breakpoint tokens
 */
export function useBreakpoints() {
  return useTokens().breakpoints;
}

/**
 * Hook for motion tokens
 */
export function useMotion() {
  return useTokens().motion;
}

// ============================================================================
// THEME PROVIDER COMPONENT
// ============================================================================

interface ReaktorThemeProps {
  tokens: DesignTokens;
  children: React.ReactNode;
}

/**
 * Main theme provider - pass complete DesignTokens
 */
export function ReaktorTheme({ tokens, children }: ReaktorThemeProps) {
  // Generate CSS custom properties from tokens
  const cssVariables = useMemo(() => generateCssVariables(tokens), [tokens]);

  return (
    <DesignTokensContext.Provider value={tokens}>
      <div style={cssVariables as React.CSSProperties}>
        {children}
      </div>
    </DesignTokensContext.Provider>
  );
}

interface ReaktorThemeColorsProps {
  primary: string;
  secondary: string;
  tertiary?: string;
  darkMode?: boolean;
  children: React.ReactNode;
}

/**
 * Convenience provider - generate tokens from colors
 */
export function ReaktorThemeColors({
  primary,
  secondary,
  tertiary,
  darkMode = false,
  children,
}: ReaktorThemeColorsProps) {
  const tokens = useMemo(
    () => createTokens(primary, secondary, tertiary, darkMode),
    [primary, secondary, tertiary, darkMode]
  );

  return <ReaktorTheme tokens={tokens}>{children}</ReaktorTheme>;
}

// ============================================================================
// CSS CUSTOM PROPERTIES GENERATION
// ============================================================================

function generateCssVariables(tokens: DesignTokens): Record<string, string> {
  const vars: Record<string, string> = {};

  // Colors
  Object.entries(tokens.colors).forEach(([key, value]) => {
    vars[`--r-color-${camelToKebab(key)}`] = value;
  });

  // Spacing
  Object.entries(tokens.spacing).forEach(([key, value]) => {
    vars[`--r-space-${key}`] = value;
  });

  // Shapes
  Object.entries(tokens.shapes).forEach(([key, value]) => {
    vars[`--r-radius-${key}`] = value;
  });

  // Elevation
  Object.entries(tokens.elevation).forEach(([key, value]) => {
    vars[`--r-shadow-${key}`] = value;
  });

  // Sizing
  Object.entries(tokens.sizing).forEach(([key, value]) => {
    vars[`--r-size-${camelToKebab(key)}`] = value;
  });

  // Typography (as CSS properties)
  Object.entries(tokens.typography).forEach(([key, style]) => {
    const prefix = `--r-type-${camelToKebab(key)}`;
    vars[`${prefix}-size`] = style.fontSize;
    vars[`${prefix}-line-height`] = style.lineHeight;
    vars[`${prefix}-weight`] = fontWeightToNumber(style.fontWeight);
    vars[`${prefix}-letter-spacing`] = style.letterSpacing;
  });

  // Motion
  vars['--r-duration-instant'] = `${tokens.motion.durationInstant}ms`;
  vars['--r-duration-fast'] = `${tokens.motion.durationFast}ms`;
  vars['--r-duration-normal'] = `${tokens.motion.durationNormal}ms`;
  vars['--r-duration-slow'] = `${tokens.motion.durationSlow}ms`;
  vars['--r-duration-slowest'] = `${tokens.motion.durationSlowest}ms`;

  return vars;
}

function camelToKebab(str: string): string {
  return str.replace(/([a-z])([A-Z])/g, '$1-$2').toLowerCase();
}

function fontWeightToNumber(weight: string): string {
  const weights: Record<string, string> = {
    thin: '100',
    extraLight: '200',
    light: '300',
    normal: '400',
    medium: '500',
    semiBold: '600',
    bold: '700',
    extraBold: '800',
    black: '900',
  };
  return weights[weight] || '400';
}

// ============================================================================
// DARK MODE HOOK
// ============================================================================

/**
 * Hook for detecting system dark mode preference
 */
export function useSystemDarkMode(): boolean {
  const [darkMode, setDarkMode] = React.useState(() => {
    if (typeof window === 'undefined') return false;
    return window.matchMedia('(prefers-color-scheme: dark)').matches;
  });

  React.useEffect(() => {
    if (typeof window === 'undefined') return;

    const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
    const handler = (e: MediaQueryListEvent) => setDarkMode(e.matches);

    mediaQuery.addEventListener('change', handler);
    return () => mediaQuery.removeEventListener('change', handler);
  }, []);

  return darkMode;
}
