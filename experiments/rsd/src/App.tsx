import { useState } from 'react';
import { html, css } from 'react-strict-dom';
import { colors, spacing, radii } from './tokens';
import {
  Button,
  Text,
  Card,
  Input,
  Chip,
  Divider,
  Avatar,
  Badge,
  Switch,
  LinearProgress,
  CircularProgress,
} from './components';

const styles = css.create({
  app: {
    minHeight: '100vh',
    backgroundColor: colors.background,
    padding: spacing.lg,
  },
  header: {
    marginBottom: spacing.xl,
  },
  subtitle: {
    marginTop: spacing.xs,
  },
  badgeRow: {
    display: 'flex',
    flexWrap: 'wrap',
    gap: spacing.sm,
    alignItems: 'center',
    marginTop: spacing.sm,
  },
  grid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))',
    gap: spacing.lg,
  },
  section: {
    display: 'flex',
    flexDirection: 'column',
    gap: spacing.md,
  },
  row: {
    display: 'flex',
    flexWrap: 'wrap',
    gap: spacing.sm,
    alignItems: 'center',
  },
  colorGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(4, 1fr)',
    gap: spacing.xs,
  },
  colorSwatchContainer: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    gap: spacing.xxs,
  },
  colorSwatchBase: {
    width: 40,
    height: 40,
    borderRadius: radii.sm,
    borderWidth: 1,
    borderStyle: 'solid',
    borderColor: colors.outlineVariant,
  },
  // Color swatches
  swatchPrimary: { backgroundColor: colors.primary },
  swatchSecondary: { backgroundColor: colors.secondary },
  swatchTertiary: { backgroundColor: colors.tertiary },
  swatchError: { backgroundColor: colors.error },
  swatchSuccess: { backgroundColor: colors.success },
  swatchWarning: { backgroundColor: colors.warning },
  swatchInfo: { backgroundColor: colors.info },
  swatchSurface: { backgroundColor: colors.surface },
  footer: {
    marginTop: spacing.xxl,
    textAlign: 'center',
  },
  footerContent: {
    marginTop: spacing.md,
  },
});

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <Card variant="outlined">
      <html.div style={styles.section}>
        <Text role="title" size="medium">{title}</Text>
        {children}
      </html.div>
    </Card>
  );
}

type SwatchColor = 'primary' | 'secondary' | 'tertiary' | 'error' | 'success' | 'warning' | 'info' | 'surface';

function ColorSwatch({ colorKey, name }: { colorKey: SwatchColor; name: string }) {
  const swatchStyleMap: Record<SwatchColor, typeof styles.swatchPrimary> = {
    primary: styles.swatchPrimary,
    secondary: styles.swatchSecondary,
    tertiary: styles.swatchTertiary,
    error: styles.swatchError,
    success: styles.swatchSuccess,
    warning: styles.swatchWarning,
    info: styles.swatchInfo,
    surface: styles.swatchSurface,
  };

  return (
    <html.div style={styles.colorSwatchContainer}>
      <html.div style={[styles.colorSwatchBase, swatchStyleMap[colorKey]]} />
      <Text role="label" size="small">{name}</Text>
    </html.div>
  );
}

export default function App() {
  const [inputValue, setInputValue] = useState('');
  const [switchValue, setSwitchValue] = useState(false);
  const [filter1, setFilter1] = useState(true);
  const [filter2, setFilter2] = useState(false);

  return (
    <html.main style={styles.app}>
      {/* Header */}
      <html.header style={styles.header}>
        <Text role="display" size="small">React Strict DOM</Text>
        <html.div style={styles.subtitle}>
          <Text role="body" size="large">
            Cross-platform UI components using semantic HTML
          </Text>
        </html.div>
        <html.div style={styles.badgeRow}>
          <Badge>RSD</Badge>
          <Badge>v0.0.55</Badge>
        </html.div>
      </html.header>

      {/* Component Grid */}
      <html.div style={styles.grid}>
        {/* Buttons */}
        <Section title="Buttons">
          <Text role="label">Variants</Text>
          <html.div style={styles.row}>
            <Button label="Filled" variant="filled" onClick={() => {}} />
            <Button label="Outlined" variant="outlined" onClick={() => {}} />
            <Button label="Text" variant="text" onClick={() => {}} />
            <Button label="Tonal" variant="tonal" onClick={() => {}} />
          </html.div>

          <Text role="label">Sizes</Text>
          <html.div style={styles.row}>
            <Button label="Small" size="small" onClick={() => {}} />
            <Button label="Medium" size="medium" onClick={() => {}} />
            <Button label="Large" size="large" onClick={() => {}} />
          </html.div>

          <Text role="label">States</Text>
          <html.div style={styles.row}>
            <Button label="Enabled" onClick={() => {}} />
            <Button label="Disabled" disabled onClick={() => {}} />
          </html.div>
        </Section>

        {/* Typography */}
        <Section title="Typography">
          <Text role="display" size="small">Display</Text>
          <Text role="headline" size="medium">Headline</Text>
          <Text role="title" size="medium">Title</Text>
          <Text role="body" size="medium">
            Body text for main content. This demonstrates the body style used for paragraphs and general content.
          </Text>
          <Text role="label" size="medium">Label</Text>
          <Text role="body" size="small">Caption / Body Small</Text>
        </Section>

        {/* Cards */}
        <Section title="Cards">
          <Text role="label">Elevated</Text>
          <Card variant="elevated">
            <Text role="title" size="small">Elevated Card</Text>
            <Text role="body" size="small">With shadow elevation</Text>
          </Card>

          <Text role="label">Outlined</Text>
          <Card variant="outlined">
            <Text role="title" size="small">Outlined Card</Text>
            <Text role="body" size="small">With border</Text>
          </Card>

          <Text role="label">Filled</Text>
          <Card variant="filled">
            <Text role="title" size="small">Filled Card</Text>
            <Text role="body" size="small">With container color</Text>
          </Card>
        </Section>

        {/* Inputs */}
        <Section title="Inputs">
          <Input
            value={inputValue}
            onChange={setInputValue}
            label="Outlined Input"
            placeholder="Type something..."
            variant="outlined"
          />
          <Input
            value=""
            onChange={() => {}}
            label="Filled Input"
            placeholder="Type something..."
            variant="filled"
          />
          <Input
            value=""
            onChange={() => {}}
            label="With Helper"
            helperText="This is helper text"
          />
          <Input
            value=""
            onChange={() => {}}
            label="With Error"
            error="This field has an error"
          />
        </Section>

        {/* Chips */}
        <Section title="Chips">
          <Text role="label">Assist Chips</Text>
          <html.div style={styles.row}>
            <Chip label="Help" variant="assist" onClick={() => {}} />
            <Chip label="Calendar" variant="assist" onClick={() => {}} />
          </html.div>

          <Text role="label">Filter Chips</Text>
          <html.div style={styles.row}>
            <Chip
              label="Active"
              variant="filter"
              selected={filter1}
              onClick={() => setFilter1(!filter1)}
            />
            <Chip
              label="Completed"
              variant="filter"
              selected={filter2}
              onClick={() => setFilter2(!filter2)}
            />
          </html.div>

          <Text role="label">Suggestion Chips</Text>
          <html.div style={styles.row}>
            <Chip label="React" variant="suggestion" onClick={() => {}} />
            <Chip label="Kotlin" variant="suggestion" onClick={() => {}} />
            <Chip label="TypeScript" variant="suggestion" onClick={() => {}} />
          </html.div>
        </Section>

        {/* Avatars & Badges */}
        <Section title="Avatars & Badges">
          <Text role="label">Sizes</Text>
          <html.div style={styles.row}>
            <Avatar initials="SM" size="small" />
            <Avatar initials="MD" size="medium" />
            <Avatar initials="LG" size="large" />
          </html.div>

          <Text role="label">With Badges</Text>
          <html.div style={styles.row}>
            <Badge count={5}>
              <Avatar initials="AB" />
            </Badge>
            <Badge count={99}>
              <Avatar initials="CD" />
            </Badge>
            <Badge count={100} maxCount={99}>
              <Avatar initials="EF" />
            </Badge>
            <Badge showDot>
              <Avatar initials="GH" />
            </Badge>
          </html.div>
        </Section>

        {/* Toggles */}
        <Section title="Toggles">
          <html.div style={styles.row}>
            <Text role="body">Switch:</Text>
            <Switch checked={switchValue} onChange={setSwitchValue} />
            <Text role="label">{switchValue ? 'ON' : 'OFF'}</Text>
          </html.div>
          <html.div style={styles.row}>
            <Text role="body">Disabled:</Text>
            <Switch checked={false} onChange={() => {}} disabled />
            <Switch checked={true} onChange={() => {}} disabled />
          </html.div>
        </Section>

        {/* Progress */}
        <Section title="Progress">
          <Text role="label">Linear (Determinate)</Text>
          <LinearProgress value={60} />

          <Text role="label">Linear (Indeterminate)</Text>
          <LinearProgress />

          <Text role="label">Circular</Text>
          <html.div style={styles.row}>
            <CircularProgress size="small" />
            <CircularProgress size="medium" />
            <CircularProgress size="large" />
          </html.div>
        </Section>

        {/* Colors */}
        <Section title="Color Palette">
          <html.div style={styles.colorGrid}>
            <ColorSwatch colorKey="primary" name="Primary" />
            <ColorSwatch colorKey="secondary" name="Secondary" />
            <ColorSwatch colorKey="tertiary" name="Tertiary" />
            <ColorSwatch colorKey="error" name="Error" />
            <ColorSwatch colorKey="success" name="Success" />
            <ColorSwatch colorKey="warning" name="Warning" />
            <ColorSwatch colorKey="info" name="Info" />
            <ColorSwatch colorKey="surface" name="Surface" />
          </html.div>
        </Section>
      </html.div>

      {/* Footer */}
      <html.footer style={styles.footer}>
        <Divider />
        <html.div style={styles.footerContent}>
          <Text role="body" size="small">
            React Strict DOM Demo - Cross-platform UI with semantic HTML
          </Text>
        </html.div>
      </html.footer>
    </html.main>
  );
}
