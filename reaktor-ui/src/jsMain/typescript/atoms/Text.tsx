/**
 * Atom: Text
 *
 * Typography component for displaying text content.
 * Follows the type scale from design tokens.
 */

import React from 'react';
import { useTokens } from '../theme/ReaktorThemeProvider';
import { TextStyleTokens } from '../tokens/DesignTokens';

// ============================================================================
// TYPES
// ============================================================================

export type TextRole = 'display' | 'headline' | 'title' | 'body' | 'label' | 'caption';
export type TextSize = 'small' | 'medium' | 'large';
export type TextOverflow = 'clip' | 'ellipsis' | 'visible';

export interface RTextProps extends React.HTMLAttributes<HTMLElement> {
  text?: string;
  role?: TextRole;
  size?: TextSize;
  color?: string;
  maxLines?: number;
  overflow?: TextOverflow;
  as?: React.ElementType;
  textAlign?: React.CSSProperties['textAlign'];
}

// ============================================================================
// FONT WEIGHT MAPPING
// ============================================================================

const fontWeightMap: Record<string, number> = {
  thin: 100,
  extraLight: 200,
  light: 300,
  normal: 400,
  medium: 500,
  semiBold: 600,
  bold: 700,
  extraBold: 800,
  black: 900,
};

// ============================================================================
// MAIN COMPONENT
// ============================================================================

export function RText({
  text,
  role = 'body',
  size = 'medium',
  color,
  maxLines,
  overflow = 'ellipsis',
  as,
  textAlign,
  children,
  style,
  ...props
}: RTextProps) {
  const tokens = useTokens();
  const { colors, typography } = tokens;

  // Get style tokens based on role and size
  const getStyleTokens = (): TextStyleTokens => {
    switch (role) {
      case 'display':
        return size === 'small' ? typography.displaySmall
          : size === 'large' ? typography.displayLarge
          : typography.displayMedium;
      case 'headline':
        return size === 'small' ? typography.headlineSmall
          : size === 'large' ? typography.headlineLarge
          : typography.headlineMedium;
      case 'title':
        return size === 'small' ? typography.titleSmall
          : size === 'large' ? typography.titleLarge
          : typography.titleMedium;
      case 'body':
        return size === 'small' ? typography.bodySmall
          : size === 'large' ? typography.bodyLarge
          : typography.bodyMedium;
      case 'label':
        return size === 'small' ? typography.labelSmall
          : size === 'large' ? typography.labelLarge
          : typography.labelMedium;
      case 'caption':
        return typography.bodySmall;
      default:
        return typography.bodyMedium;
    }
  };

  // Get default color based on role
  const getDefaultColor = (): string => {
    switch (role) {
      case 'display':
      case 'headline':
        return colors.onBackground;
      case 'title':
      case 'body':
        return colors.onSurface;
      case 'label':
      case 'caption':
        return colors.onSurfaceVariant;
      default:
        return colors.onSurface;
    }
  };

  // Determine semantic HTML element
  const getElement = (): React.ElementType => {
    if (as) return as;
    switch (role) {
      case 'display':
        return size === 'large' ? 'h1' : size === 'medium' ? 'h2' : 'h3';
      case 'headline':
        return size === 'large' ? 'h2' : size === 'medium' ? 'h3' : 'h4';
      case 'title':
        return size === 'large' ? 'h4' : size === 'medium' ? 'h5' : 'h6';
      case 'body':
        return 'p';
      case 'label':
        return 'span';
      case 'caption':
        return 'span';
      default:
        return 'span';
    }
  };

  const styleTokens = getStyleTokens();
  const resolvedColor = color || getDefaultColor();
  const ElementComponent = getElement();

  const textStyle: React.CSSProperties = {
    color: resolvedColor,
    fontSize: styleTokens.fontSize,
    lineHeight: styleTokens.lineHeight,
    fontWeight: fontWeightMap[styleTokens.fontWeight] || 400,
    letterSpacing: styleTokens.letterSpacing,
    textAlign,
    margin: 0,
    ...(maxLines && maxLines > 0 ? {
      display: '-webkit-box',
      WebkitLineClamp: maxLines,
      WebkitBoxOrient: 'vertical' as const,
      overflow: 'hidden',
      textOverflow: overflow === 'ellipsis' ? 'ellipsis' : 'clip',
    } : {}),
    ...style,
  };

  return (
    <ElementComponent {...props} style={textStyle}>
      {text || children}
    </ElementComponent>
  );
}

// ============================================================================
// CONVENIENCE COMPONENTS
// ============================================================================

export function RDisplayText(props: Omit<RTextProps, 'role'>) {
  return <RText {...props} role="display" />;
}

export function RHeadline(props: Omit<RTextProps, 'role'>) {
  return <RText {...props} role="headline" />;
}

export function RTitle(props: Omit<RTextProps, 'role'>) {
  return <RText {...props} role="title" />;
}

export function RBody(props: Omit<RTextProps, 'role'>) {
  return <RText {...props} role="body" />;
}

export function RLabel(props: Omit<RTextProps, 'role'>) {
  return <RText {...props} role="label" />;
}

export function RCaption(props: Omit<RTextProps, 'role'>) {
  return <RText {...props} role="caption" size="small" />;
}
