import { html, css } from 'react-strict-dom';
import { colors, spacing, radii, typography } from '../tokens';

type InputVariant = 'outlined' | 'filled';

interface InputProps {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  label?: string;
  helperText?: string;
  error?: string;
  variant?: InputVariant;
  disabled?: boolean;
}

const styles = css.create({
  container: {
    display: 'flex',
    flexDirection: 'column',
    gap: spacing.xs,
  },
  label: {
    fontSize: typography.labelMedium.fontSize,
    fontWeight: typography.labelMedium.fontWeight,
    color: colors.onSurfaceVariant,
  },
  labelFocused: {
    color: colors.primary,
  },
  labelError: {
    color: colors.error,
  },
  inputBase: {
    height: 56,
    paddingInline: spacing.md,
    fontSize: typography.bodyLarge.fontSize,
    borderRadius: radii.xs,
    outline: 'none',
    transition: 'border-color 0.15s ease',
  },
  outlined: {
    backgroundColor: 'transparent',
    borderWidth: 1,
    borderStyle: 'solid',
    borderColor: {
      default: colors.outline,
      ':focus': colors.primary,
    },
  },
  filled: {
    backgroundColor: colors.surfaceContainerHigh,
    borderWidth: 0,
    borderBottomWidth: 1,
    borderStyle: 'solid',
    borderColor: {
      default: colors.onSurfaceVariant,
      ':focus': colors.primary,
    },
    borderTopLeftRadius: radii.xs,
    borderTopRightRadius: radii.xs,
    borderBottomLeftRadius: 0,
    borderBottomRightRadius: 0,
  },
  inputError: {
    borderColor: colors.error,
  },
  inputDisabled: {
    opacity: 0.38,
    cursor: 'not-allowed',
  },
  helperText: {
    fontSize: typography.bodySmall.fontSize,
    color: colors.onSurfaceVariant,
    paddingInline: spacing.md,
  },
  errorText: {
    fontSize: typography.bodySmall.fontSize,
    color: colors.error,
    paddingInline: spacing.md,
  },
});

export function Input({
  value,
  onChange,
  placeholder,
  label,
  helperText,
  error,
  variant = 'outlined',
  disabled = false,
}: InputProps) {
  const hasError = !!error;

  return (
    <html.div style={styles.container}>
      {label && (
        <html.label
          style={[
            styles.label,
            hasError && styles.labelError,
          ]}
        >
          {label}
        </html.label>
      )}
      <html.input
        value={value}
        onChange={(e: React.ChangeEvent<HTMLInputElement>) => onChange(e.target.value)}
        placeholder={placeholder}
        disabled={disabled}
        style={[
          styles.inputBase,
          styles[variant],
          hasError && styles.inputError,
          disabled && styles.inputDisabled,
        ]}
      />
      {(error || helperText) && (
        <html.span style={hasError ? styles.errorText : styles.helperText}>
          {error || helperText}
        </html.span>
      )}
    </html.div>
  );
}
