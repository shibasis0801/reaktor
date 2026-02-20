import { html, css } from 'react-strict-dom';
import { colors, spacing, radii, shadows } from '../tokens';

type CardVariant = 'elevated' | 'filled' | 'outlined';

interface CardProps {
  children: React.ReactNode;
  variant?: CardVariant;
  onClick?: () => void;
  padding?: keyof typeof spacing;
}

const styles = css.create({
  base: {
    borderRadius: radii.md,
    overflow: 'hidden',
  },
  elevated: {
    backgroundColor: colors.surface,
    boxShadow: shadows.sm,
  },
  filled: {
    backgroundColor: colors.surfaceContainer,
  },
  outlined: {
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderStyle: 'solid',
    borderColor: colors.outlineVariant,
  },
  clickable: {
    cursor: 'pointer',
    transition: 'transform 0.15s ease, box-shadow 0.15s ease',
  },
  // Padding variants
  paddingNone: { padding: spacing.none },
  paddingXs: { padding: spacing.xs },
  paddingSm: { padding: spacing.sm },
  paddingMd: { padding: spacing.md },
  paddingLg: { padding: spacing.lg },
  paddingXl: { padding: spacing.xl },
});

export function Card({
  children,
  variant = 'elevated',
  onClick,
  padding = 'md',
}: CardProps) {
  const paddingKey = `padding${padding.charAt(0).toUpperCase() + padding.slice(1)}` as keyof typeof styles;

  return (
    <html.div
      onClick={onClick}
      role={onClick ? 'button' : undefined}
      tabIndex={onClick ? 0 : undefined}
      style={[
        styles.base,
        styles[variant],
        styles[paddingKey],
        onClick && styles.clickable,
      ]}
    >
      {children}
    </html.div>
  );
}
