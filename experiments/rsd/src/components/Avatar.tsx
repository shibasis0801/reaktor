import { html, css } from 'react-strict-dom';
import { colors, typography } from '../tokens';

type AvatarSize = 'small' | 'medium' | 'large';

interface AvatarProps {
  src?: string;
  initials?: string;
  size?: AvatarSize;
  alt?: string;
}

const styles = css.create({
  base: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: '50%',
    overflow: 'hidden',
    backgroundColor: colors.primaryContainer,
    color: colors.onPrimaryContainer,
    fontWeight: '500',
  },
  small: {
    width: 32,
    height: 32,
    fontSize: typography.labelSmall.fontSize,
  },
  medium: {
    width: 40,
    height: 40,
    fontSize: typography.labelMedium.fontSize,
  },
  large: {
    width: 56,
    height: 56,
    fontSize: typography.titleMedium.fontSize,
  },
  image: {
    width: '100%',
    height: '100%',
    objectFit: 'cover',
  },
});

export function Avatar({ src, initials, size = 'medium', alt }: AvatarProps) {
  return (
    <html.div style={[styles.base, styles[size]]}>
      {src ? (
        <html.img src={src} alt={alt || ''} style={styles.image} />
      ) : (
        initials?.slice(0, 2).toUpperCase()
      )}
    </html.div>
  );
}
