/**
 * Atom: Button
 *
 * The primary interactive element for user actions.
 * Supports multiple variants, sizes, and states.
 */

import React from 'react';
import { useTokens } from '../theme/ReaktorThemeProvider';

// ============================================================================
// TYPES
// ============================================================================

export type ComponentSize = 'small' | 'medium' | 'large';
export type ComponentVariant = 'filled' | 'outlined' | 'text' | 'tonal' | 'elevated';
export type ComponentState = 'enabled' | 'disabled' | 'loading' | 'error' | 'success';
export type IconPosition = 'start' | 'end' | 'only';

export interface RButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  label?: string;
  icon?: React.ReactNode;
  iconPosition?: IconPosition;
  size?: ComponentSize;
  variant?: ComponentVariant;
  state?: ComponentState;
  fullWidth?: boolean;
}

// ============================================================================
// MAIN COMPONENT
// ============================================================================

export function RButton({
  label,
  icon,
  iconPosition = 'start',
  size = 'medium',
  variant = 'filled',
  state = 'enabled',
  fullWidth = false,
  className,
  style,
  disabled,
  children,
  ...props
}: RButtonProps) {
  const tokens = useTokens();
  const { colors, spacing, shapes, sizing } = tokens;

  const isDisabled = state === 'disabled' || disabled;
  const isLoading = state === 'loading';

  // Size-based styling
  const height = {
    small: sizing.buttonSm,
    medium: sizing.buttonMd,
    large: sizing.buttonLg,
  }[size];

  const padding = {
    small: spacing.sm,
    medium: spacing.md,
    large: spacing.lg,
  }[size];

  const iconSize = {
    small: sizing.iconXs,
    medium: sizing.iconSm,
    large: sizing.iconMd,
  }[size];

  // Variant-based colors
  const getColors = () => {
    if (isDisabled) {
      return {
        background: `rgba(0,0,0,0.12)`,
        color: `rgba(0,0,0,0.38)`,
        border: 'transparent',
      };
    }

    switch (variant) {
      case 'filled':
        return {
          background: colors.primary,
          color: colors.onPrimary,
          border: 'transparent',
        };
      case 'tonal':
        return {
          background: colors.secondaryContainer,
          color: colors.onSecondaryContainer,
          border: 'transparent',
        };
      case 'elevated':
        return {
          background: colors.surface,
          color: colors.primary,
          border: 'transparent',
        };
      case 'outlined':
        return {
          background: 'transparent',
          color: colors.primary,
          border: colors.outline,
        };
      case 'text':
        return {
          background: 'transparent',
          color: colors.primary,
          border: 'transparent',
        };
      default:
        return {
          background: colors.primary,
          color: colors.onPrimary,
          border: 'transparent',
        };
    }
  };

  const { background, color, border } = getColors();

  const buttonStyle: React.CSSProperties = {
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.xs,
    height,
    padding: `0 ${padding}`,
    backgroundColor: background,
    color,
    border: border === 'transparent' ? 'none' : `1px solid ${border}`,
    borderRadius: shapes.md,
    cursor: isDisabled || isLoading ? 'not-allowed' : 'pointer',
    opacity: isLoading ? 0.7 : 1,
    fontFamily: 'inherit',
    fontSize: 'inherit',
    fontWeight: 500,
    textTransform: 'none',
    transition: 'all 150ms ease-in-out',
    width: fullWidth ? '100%' : 'auto',
    boxShadow: variant === 'elevated' ? tokens.elevation.sm : 'none',
    ...style,
  };

  const renderContent = () => {
    if (isLoading) {
      return (
        <span
          style={{
            width: iconSize,
            height: iconSize,
            border: `2px solid ${color}`,
            borderTopColor: 'transparent',
            borderRadius: '50%',
            animation: 'spin 1s linear infinite',
          }}
        />
      );
    }

    const iconOnly = iconPosition === 'only' || (icon && !label && !children);

    if (iconOnly && icon) {
      return <span style={{ width: iconSize, height: iconSize }}>{icon}</span>;
    }

    const content = label || children;

    if (icon && iconPosition === 'start') {
      return (
        <>
          <span style={{ width: iconSize, height: iconSize }}>{icon}</span>
          {content && <span>{content}</span>}
        </>
      );
    }

    if (icon && iconPosition === 'end') {
      return (
        <>
          {content && <span>{content}</span>}
          <span style={{ width: iconSize, height: iconSize }}>{icon}</span>
        </>
      );
    }

    return content;
  };

  return (
    <button
      {...props}
      disabled={isDisabled || isLoading}
      style={buttonStyle}
      className={className}
    >
      {renderContent()}
    </button>
  );
}

// ============================================================================
// CONVENIENCE VARIANTS
// ============================================================================

export function RButtonPrimary(props: Omit<RButtonProps, 'variant'>) {
  return <RButton {...props} variant="filled" />;
}

export function RButtonSecondary(props: Omit<RButtonProps, 'variant'>) {
  return <RButton {...props} variant="tonal" />;
}

export function RButtonOutlined(props: Omit<RButtonProps, 'variant'>) {
  return <RButton {...props} variant="outlined" />;
}

export function RButtonText(props: Omit<RButtonProps, 'variant'>) {
  return <RButton {...props} variant="text" />;
}

// ============================================================================
// ICON BUTTON
// ============================================================================

export interface RIconButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  icon: React.ReactNode;
  size?: ComponentSize;
  variant?: ComponentVariant;
  'aria-label'?: string;
}

export function RIconButton({
  icon,
  size = 'medium',
  variant = 'text',
  style,
  ...props
}: RIconButtonProps) {
  const tokens = useTokens();
  const { colors, sizing, shapes } = tokens;

  const buttonSize = {
    small: sizing.buttonSm,
    medium: sizing.buttonMd,
    large: sizing.buttonLg,
  }[size];

  const iconSize = {
    small: sizing.iconSm,
    medium: sizing.iconMd,
    large: sizing.iconLg,
  }[size];

  const bgColor = variant === 'filled'
    ? colors.primary
    : variant === 'tonal'
      ? colors.secondaryContainer
      : 'transparent';

  const fgColor = variant === 'filled'
    ? colors.onPrimary
    : variant === 'tonal'
      ? colors.onSecondaryContainer
      : colors.primary;

  const buttonStyle: React.CSSProperties = {
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    width: buttonSize,
    height: buttonSize,
    padding: 0,
    backgroundColor: bgColor,
    color: fgColor,
    border: 'none',
    borderRadius: '50%',
    cursor: 'pointer',
    ...style,
  };

  return (
    <button {...props} style={buttonStyle}>
      <span style={{ width: iconSize, height: iconSize }}>{icon}</span>
    </button>
  );
}

// Add keyframes for loading spinner
if (typeof document !== 'undefined') {
  const styleId = 'reaktor-button-styles';
  if (!document.getElementById(styleId)) {
    const style = document.createElement('style');
    style.id = styleId;
    style.textContent = `
      @keyframes spin {
        from { transform: rotate(0deg); }
        to { transform: rotate(360deg); }
      }
    `;
    document.head.appendChild(style);
  }
}
