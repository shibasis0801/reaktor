import { html, css } from 'react-strict-dom';
import { colors, spacing, radii } from '../tokens';

type ButtonVariant = 'filled' | 'outlined' | 'text' | 'tonal';
type ButtonSize = 'small' | 'medium' | 'large';

interface ButtonProps {
  label: string;
  onClick?: () => void;
  variant?: ButtonVariant;
  size?: ButtonSize;
  disabled?: boolean;
  icon?: React.ReactNode;
}

const styles = css.create({
  base: {
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.xs,
    borderRadius: radii.md,
    fontWeight: '500',
    cursor: 'pointer',
    border: 'none',
    transition: 'all 0.15s ease',
  },
  // Variants
  filled: {
    backgroundColor: {
      default: colors.primary,
      ':hover': colors.onPrimaryContainer,
    },
    color: colors.onPrimary,
  },
  outlined: {
    backgroundColor: {
      default: 'transparent',
      ':hover': colors.surfaceVariant,
    },
    color: colors.primary,
    borderWidth: 1,
    borderStyle: 'solid',
    borderColor: colors.outline,
  },
  text: {
    backgroundColor: {
      default: 'transparent',
      ':hover': colors.surfaceVariant,
    },
    color: colors.primary,
  },
  tonal: {
    backgroundColor: {
      default: colors.secondaryContainer,
      ':hover': colors.surfaceContainerHigh,
    },
    color: colors.onSecondaryContainer,
  },
  // Sizes
  small: {
    height: 32,
    paddingInline: spacing.sm,
    fontSize: 12,
  },
  medium: {
    height: 40,
    paddingInline: spacing.md,
    fontSize: 14,
  },
  large: {
    height: 48,
    paddingInline: spacing.lg,
    fontSize: 16,
  },
  // States
  disabled: {
    opacity: 0.38,
    cursor: 'not-allowed',
  },
});

export function Button({
  label,
  onClick,
  variant = 'filled',
  size = 'medium',
  disabled = false,
  icon,
}: ButtonProps) {
  return (
    <html.button
      onClick={disabled ? undefined : onClick}
      disabled={disabled}
      style={[
        styles.base,
        styles[variant],
        styles[size],
        disabled && styles.disabled,
      ]}
    >
      {icon}
      {label}
    </html.button>
  );
}
