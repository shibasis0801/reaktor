/**
 * Atom: Card
 *
 * Container component for grouping related content.
 * Provides visual separation and hierarchy.
 */

import React from 'react';
import { useTokens } from '../theme/ReaktorThemeProvider';
import { ComponentVariant } from './Button';

// ============================================================================
// TYPES
// ============================================================================

export interface RCardProps extends React.HTMLAttributes<HTMLDivElement> {
  variant?: ComponentVariant;
  onClick?: () => void;
  containerColor?: string;
  contentColor?: string;
}

// ============================================================================
// MAIN COMPONENT
// ============================================================================

export function RCard({
  variant = 'filled',
  onClick,
  containerColor,
  contentColor,
  children,
  style,
  ...props
}: RCardProps) {
  const tokens = useTokens();
  const { colors, shapes, elevation, spacing } = tokens;

  const isClickable = !!onClick;

  // Variant-based styling
  const getContainerColor = (): string => {
    if (containerColor) return containerColor;
    switch (variant) {
      case 'filled':
        return colors.surfaceContainer;
      case 'elevated':
        return colors.surface;
      case 'outlined':
        return colors.surface;
      case 'tonal':
        return colors.secondaryContainer;
      default:
        return colors.surfaceContainer;
    }
  };

  const getContentColor = (): string => {
    if (contentColor) return contentColor;
    switch (variant) {
      case 'tonal':
        return colors.onSecondaryContainer;
      default:
        return colors.onSurface;
    }
  };

  const getShadow = (): string => {
    switch (variant) {
      case 'elevated':
        return elevation.sm;
      default:
        return 'none';
    }
  };

  const getBorder = (): string => {
    switch (variant) {
      case 'outlined':
        return `1px solid ${colors.outlineVariant}`;
      default:
        return 'none';
    }
  };

  const cardStyle: React.CSSProperties = {
    backgroundColor: getContainerColor(),
    color: getContentColor(),
    borderRadius: shapes.md,
    boxShadow: getShadow(),
    border: getBorder(),
    overflow: 'hidden',
    cursor: isClickable ? 'pointer' : 'default',
    transition: 'box-shadow 150ms ease-in-out, transform 150ms ease-in-out',
    ...style,
  };

  return (
    <div
      {...props}
      onClick={onClick}
      style={cardStyle}
      role={isClickable ? 'button' : undefined}
      tabIndex={isClickable ? 0 : undefined}
    >
      {children}
    </div>
  );
}

// ============================================================================
// CONVENIENCE VARIANTS
// ============================================================================

export function RElevatedCard(props: Omit<RCardProps, 'variant'>) {
  return <RCard {...props} variant="elevated" />;
}

export function ROutlinedCard(props: Omit<RCardProps, 'variant'>) {
  return <RCard {...props} variant="outlined" />;
}

export function RFilledCard(props: Omit<RCardProps, 'variant'>) {
  return <RCard {...props} variant="filled" />;
}

// ============================================================================
// CARD WITH PADDING
// ============================================================================

export interface RCardContentProps extends RCardProps {
  padding?: string;
}

export function RCardContent({
  padding,
  children,
  style,
  ...props
}: RCardContentProps) {
  const tokens = useTokens();
  const { spacing } = tokens;

  const actualPadding = padding || spacing.md;

  return (
    <RCard {...props} style={style}>
      <div style={{ padding: actualPadding }}>
        {children}
      </div>
    </RCard>
  );
}
