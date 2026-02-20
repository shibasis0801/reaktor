import { html, css } from 'react-strict-dom';
import { colors, spacing, radii, typography } from '../tokens';

type ChipVariant = 'assist' | 'filter' | 'input' | 'suggestion';

interface ChipProps {
  label: string;
  onClick?: () => void;
  selected?: boolean;
  variant?: ChipVariant;
  icon?: React.ReactNode;
}

const styles = css.create({
  base: {
    display: 'inline-flex',
    alignItems: 'center',
    gap: spacing.xs,
    height: 32,
    paddingInline: spacing.md,
    borderRadius: radii.sm,
    fontSize: typography.labelLarge.fontSize,
    fontWeight: typography.labelLarge.fontWeight,
    cursor: 'pointer',
    border: 'none',
    transition: 'all 0.15s ease',
  },
  assist: {
    backgroundColor: {
      default: 'transparent',
      ':hover': colors.surfaceVariant,
    },
    color: colors.onSurface,
    borderWidth: 1,
    borderStyle: 'solid',
    borderColor: colors.outline,
  },
  filter: {
    backgroundColor: {
      default: 'transparent',
      ':hover': colors.surfaceVariant,
    },
    color: colors.onSurfaceVariant,
    borderWidth: 1,
    borderStyle: 'solid',
    borderColor: colors.outline,
  },
  filterSelected: {
    backgroundColor: colors.secondaryContainer,
    color: colors.onSecondaryContainer,
    borderColor: colors.secondaryContainer,
  },
  input: {
    backgroundColor: {
      default: 'transparent',
      ':hover': colors.surfaceVariant,
    },
    color: colors.onSurfaceVariant,
    borderWidth: 1,
    borderStyle: 'solid',
    borderColor: colors.outline,
  },
  suggestion: {
    backgroundColor: {
      default: 'transparent',
      ':hover': colors.surfaceVariant,
    },
    color: colors.onSurfaceVariant,
    borderWidth: 1,
    borderStyle: 'solid',
    borderColor: colors.outline,
  },
  icon: {
    width: 18,
    height: 18,
  },
});

export function Chip({
  label,
  onClick,
  selected = false,
  variant = 'assist',
  icon,
}: ChipProps) {
  return (
    <html.button
      onClick={onClick}
      style={[
        styles.base,
        styles[variant],
        variant === 'filter' && selected && styles.filterSelected,
      ]}
    >
      {icon && <html.span style={styles.icon}>{icon}</html.span>}
      {label}
    </html.button>
  );
}
