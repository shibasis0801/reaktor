import { html, css } from 'react-strict-dom';
import { colors } from '../tokens';

interface DividerProps {
  vertical?: boolean;
}

const styles = css.create({
  horizontal: {
    width: '100%',
    height: 1,
    backgroundColor: colors.outlineVariant,
    border: 'none',
  },
  vertical: {
    width: 1,
    height: '100%',
    backgroundColor: colors.outlineVariant,
  },
});

export function Divider({ vertical = false }: DividerProps) {
  if (vertical) {
    return <html.div style={styles.vertical} />;
  }
  return <html.hr style={styles.horizontal} />;
}
