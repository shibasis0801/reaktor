import { html, css } from 'react-strict-dom';
import { colors, radii } from '../tokens';

interface LinearProgressProps {
  value?: number; // 0-100, undefined for indeterminate
}

interface CircularProgressProps {
  size?: 'small' | 'medium' | 'large';
}

const linearStyles = css.create({
  track: {
    width: '100%',
    height: 4,
    backgroundColor: colors.surfaceContainerHigh,
    borderRadius: radii.full,
    overflow: 'hidden',
    position: 'relative',
  },
  indicator: {
    height: '100%',
    backgroundColor: colors.primary,
    borderRadius: radii.full,
    transition: 'width 0.3s ease',
  },
  indeterminate: {
    width: '30%',
    position: 'absolute',
    animationName: 'slideIndeterminate',
    animationDuration: '1.5s',
    animationIterationCount: 'infinite',
    animationTimingFunction: 'ease-in-out',
  },
  // Width percentages for determinate progress
  w0: { width: '0%' },
  w10: { width: '10%' },
  w20: { width: '20%' },
  w30: { width: '30%' },
  w40: { width: '40%' },
  w50: { width: '50%' },
  w60: { width: '60%' },
  w70: { width: '70%' },
  w80: { width: '80%' },
  w90: { width: '90%' },
  w100: { width: '100%' },
});

const circularStyles = css.create({
  container: {
    display: 'inline-block',
  },
  small: {
    width: 20,
    height: 20,
  },
  medium: {
    width: 32,
    height: 32,
  },
  large: {
    width: 48,
    height: 48,
  },
});

function getWidthStyle(value: number) {
  const clamped = Math.min(100, Math.max(0, value));
  const rounded = Math.round(clamped / 10) * 10;
  const key = `w${rounded}` as keyof typeof linearStyles;
  return linearStyles[key];
}

export function LinearProgress({ value }: LinearProgressProps) {
  const isIndeterminate = value === undefined;

  return (
    <html.div style={linearStyles.track}>
      <html.div
        style={[
          linearStyles.indicator,
          isIndeterminate ? linearStyles.indeterminate : getWidthStyle(value),
        ]}
      />
    </html.div>
  );
}

export function CircularProgress({ size = 'medium' }: CircularProgressProps) {
  return (
    <html.div style={[circularStyles.container, circularStyles[size]]}>
      <svg viewBox="0 0 50 50" width="100%" height="100%">
        <circle
          cx="25"
          cy="25"
          r="20"
          fill="none"
          stroke={colors.primary}
          strokeWidth="4"
          strokeLinecap="round"
          strokeDasharray="126"
          strokeDashoffset="80"
        >
          <animateTransform
            attributeName="transform"
            type="rotate"
            from="0 25 25"
            to="360 25 25"
            dur="2s"
            repeatCount="indefinite"
          />
        </circle>
      </svg>
    </html.div>
  );
}
