import { html, css } from 'react-strict-dom';
import { colors } from '../tokens';

interface SwitchProps {
  checked: boolean;
  onChange: (checked: boolean) => void;
  disabled?: boolean;
}

const styles = css.create({
  track: {
    width: 52,
    height: 32,
    borderRadius: 16,
    padding: 2,
    cursor: 'pointer',
    transition: 'background-color 0.2s ease',
    border: 'none',
  },
  trackOff: {
    backgroundColor: colors.surfaceContainerHigh,
    borderWidth: 2,
    borderStyle: 'solid',
    borderColor: colors.outline,
  },
  trackOn: {
    backgroundColor: colors.primary,
  },
  thumb: {
    width: 24,
    height: 24,
    borderRadius: 12,
    transition: 'transform 0.2s ease, width 0.2s ease',
  },
  thumbOff: {
    backgroundColor: colors.outline,
    transform: 'translateX(0)',
  },
  thumbOn: {
    backgroundColor: colors.onPrimary,
    transform: 'translateX(20px)',
  },
  disabled: {
    opacity: 0.38,
    cursor: 'not-allowed',
  },
});

export function Switch({ checked, onChange, disabled = false }: SwitchProps) {
  return (
    <html.button
      role="switch"
      aria-checked={checked}
      onClick={() => !disabled && onChange(!checked)}
      disabled={disabled}
      style={[
        styles.track,
        checked ? styles.trackOn : styles.trackOff,
        disabled && styles.disabled,
      ]}
    >
      <html.div
        style={[
          styles.thumb,
          checked ? styles.thumbOn : styles.thumbOff,
        ]}
      />
    </html.button>
  );
}
