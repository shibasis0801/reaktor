import { html, css } from 'react-strict-dom';
import { colors, typography } from '../tokens';

type TextRole = 'display' | 'headline' | 'title' | 'body' | 'label';
type TextSize = 'small' | 'medium' | 'large';

interface TextProps {
  children: React.ReactNode;
  role?: TextRole;
  size?: TextSize;
  color?: string;
}

const styles = css.create({
  base: {
    margin: 0,
  },
  // Display
  displayLarge: {
    fontSize: typography.displayLarge.fontSize,
    lineHeight: typography.displayLarge.lineHeight,
    fontWeight: typography.displayLarge.fontWeight,
  },
  displayMedium: {
    fontSize: typography.displayMedium.fontSize,
    lineHeight: typography.displayMedium.lineHeight,
    fontWeight: typography.displayMedium.fontWeight,
  },
  displaySmall: {
    fontSize: typography.displaySmall.fontSize,
    lineHeight: typography.displaySmall.lineHeight,
    fontWeight: typography.displaySmall.fontWeight,
  },
  // Headline
  headlineLarge: {
    fontSize: typography.headlineLarge.fontSize,
    lineHeight: typography.headlineLarge.lineHeight,
    fontWeight: typography.headlineLarge.fontWeight,
  },
  headlineMedium: {
    fontSize: typography.headlineMedium.fontSize,
    lineHeight: typography.headlineMedium.lineHeight,
    fontWeight: typography.headlineMedium.fontWeight,
  },
  headlineSmall: {
    fontSize: typography.headlineSmall.fontSize,
    lineHeight: typography.headlineSmall.lineHeight,
    fontWeight: typography.headlineSmall.fontWeight,
  },
  // Title
  titleLarge: {
    fontSize: typography.titleLarge.fontSize,
    lineHeight: typography.titleLarge.lineHeight,
    fontWeight: typography.titleLarge.fontWeight,
  },
  titleMedium: {
    fontSize: typography.titleMedium.fontSize,
    lineHeight: typography.titleMedium.lineHeight,
    fontWeight: typography.titleMedium.fontWeight,
  },
  titleSmall: {
    fontSize: typography.titleSmall.fontSize,
    lineHeight: typography.titleSmall.lineHeight,
    fontWeight: typography.titleSmall.fontWeight,
  },
  // Body
  bodyLarge: {
    fontSize: typography.bodyLarge.fontSize,
    lineHeight: typography.bodyLarge.lineHeight,
    fontWeight: typography.bodyLarge.fontWeight,
  },
  bodyMedium: {
    fontSize: typography.bodyMedium.fontSize,
    lineHeight: typography.bodyMedium.lineHeight,
    fontWeight: typography.bodyMedium.fontWeight,
  },
  bodySmall: {
    fontSize: typography.bodySmall.fontSize,
    lineHeight: typography.bodySmall.lineHeight,
    fontWeight: typography.bodySmall.fontWeight,
  },
  // Label
  labelLarge: {
    fontSize: typography.labelLarge.fontSize,
    lineHeight: typography.labelLarge.lineHeight,
    fontWeight: typography.labelLarge.fontWeight,
  },
  labelMedium: {
    fontSize: typography.labelMedium.fontSize,
    lineHeight: typography.labelMedium.lineHeight,
    fontWeight: typography.labelMedium.fontWeight,
  },
  labelSmall: {
    fontSize: typography.labelSmall.fontSize,
    lineHeight: typography.labelSmall.lineHeight,
    fontWeight: typography.labelSmall.fontWeight,
  },
  // Colors
  onBackground: { color: colors.onBackground },
  onSurface: { color: colors.onSurface },
  onSurfaceVariant: { color: colors.onSurfaceVariant },
});

function getStyleKey(role: TextRole, size: TextSize): keyof typeof styles {
  const sizeMap = {
    small: 'Small',
    medium: 'Medium',
    large: 'Large',
  };
  return `${role}${sizeMap[size]}` as keyof typeof styles;
}

function getColorStyle(role: TextRole): keyof typeof styles {
  if (role === 'display' || role === 'headline') return 'onBackground';
  if (role === 'label') return 'onSurfaceVariant';
  return 'onSurface';
}

export function Text({ children, role = 'body', size = 'medium' }: TextProps) {
  const styleKey = getStyleKey(role, size);
  const colorStyle = getColorStyle(role);

  // Choose semantic element based on role
  if (role === 'display') {
    const Element = size === 'large' ? html.h1 : size === 'medium' ? html.h2 : html.h3;
    return (
      <Element style={[styles.base, styles[styleKey], styles[colorStyle]]}>
        {children}
      </Element>
    );
  }

  if (role === 'headline') {
    const Element = size === 'large' ? html.h2 : size === 'medium' ? html.h3 : html.h4;
    return (
      <Element style={[styles.base, styles[styleKey], styles[colorStyle]]}>
        {children}
      </Element>
    );
  }

  if (role === 'title') {
    const Element = size === 'large' ? html.h4 : size === 'medium' ? html.h5 : html.h6;
    return (
      <Element style={[styles.base, styles[styleKey], styles[colorStyle]]}>
        {children}
      </Element>
    );
  }

  if (role === 'body') {
    return (
      <html.p style={[styles.base, styles[styleKey], styles[colorStyle]]}>
        {children}
      </html.p>
    );
  }

  return (
    <html.span style={[styles.base, styles[styleKey], styles[colorStyle]]}>
      {children}
    </html.span>
  );
}
