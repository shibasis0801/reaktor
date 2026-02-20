import { html, css } from 'react-strict-dom';
import { colors, typography } from '../tokens';

interface BadgeProps {
  count?: number;
  showDot?: boolean;
  maxCount?: number;
  children?: React.ReactNode;
}

const styles = css.create({
  container: {
    position: 'relative',
    display: 'inline-flex',
  },
  badge: {
    position: 'absolute',
    top: -4,
    right: -4,
    minWidth: 18,
    height: 18,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 9,
    backgroundColor: colors.error,
    color: colors.onError,
    fontSize: typography.labelSmall.fontSize,
    fontWeight: '500',
    paddingInline: 4,
  },
  dot: {
    position: 'absolute',
    top: 0,
    right: 0,
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: colors.error,
  },
  standalone: {
    minWidth: 18,
    height: 18,
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 9,
    backgroundColor: colors.primary,
    color: colors.onPrimary,
    fontSize: typography.labelSmall.fontSize,
    fontWeight: '500',
    paddingInline: 6,
  },
});

export function Badge({ count, showDot = false, maxCount = 99, children }: BadgeProps) {
  if (!children) {
    // Standalone badge
    return (
      <html.span style={styles.standalone}>
        {count !== undefined ? (count > maxCount ? `${maxCount}+` : count) : ''}
      </html.span>
    );
  }

  return (
    <html.div style={styles.container}>
      {children}
      {showDot && <html.span style={styles.dot} />}
      {count !== undefined && count > 0 && (
        <html.span style={styles.badge}>
          {count > maxCount ? `${maxCount}+` : count}
        </html.span>
      )}
    </html.div>
  );
}
