# Reaktor UI

A cross-platform UI component library designed for consistency between **Compose Multiplatform** (Android, iOS, Desktop) and **React** (Web) applications.

## Architecture

Reaktor UI follows **Atomic Design** principles with a **token-based** theming system:

```
reaktor-ui/
├── src/commonMain/kotlin/dev/shibasis/reaktor/ui/
│   ├── core/
│   │   ├── tokens/          # Design token definitions
│   │   │   ├── DesignTokens.kt      # All token types
│   │   │   └── TokenFactory.kt      # Token generation utilities
│   │   ├── theme/           # Theme provider
│   │   │   └── ReaktorThemeProvider.kt
│   │   ├── components/      # Platform-agnostic specs
│   │   │   └── ComponentSpec.kt
│   │   └── adaptive/        # Responsive utilities
│   │       └── Responsive.kt
│   ├── atoms/               # Smallest components
│   │   ├── Button.kt, Text.kt, Input.kt
│   │   ├── Card.kt, Icon.kt, Avatar.kt
│   │   ├── Badge.kt, Chip.kt, Divider.kt
│   │   ├── Progress.kt, Toggle.kt, Spacer.kt
│   ├── molecules/           # Composed from atoms
│   │   ├── ListItem.kt, SearchBar.kt, EmptyState.kt
│   └── material/            # Material Design implementation
│       └── MaterialTokens.kt
└── ts/src/                  # React/TypeScript implementation
    ├── tokens/, theme/, atoms/
```

## Key Features

### 1. Token-Based Theming (No Hardcoded Values)

```kotlin
// Kotlin/Compose
@Composable
fun MyComponent() {
    val colors = Tokens.colors
    val spacing = Tokens.spacing

    Box(
        modifier = Modifier
            .background(colors.primary)
            .padding(spacing.md)
    )
}
```

```tsx
// React/TypeScript
function MyComponent() {
  const { colors, spacing } = useTokens();

  return (
    <div style={{
      backgroundColor: colors.primary,
      padding: spacing.md
    }}>
      Content
    </div>
  );
}
```

### 2. Swappable Design Systems

The base token system can be implemented by any design language:

```kotlin
// Material Design (included)
ReaktorTheme(tokens = MaterialTokens.Default) { ... }

// Custom theme with just colors
ReaktorTheme(
    primary = Color(0xFF6750A4),
    secondary = Color(0xFF625B71),
) { ... }

// Future: Neomorphic, Glass, etc.
// ReaktorTheme(tokens = NeomorphicTokens.Default) { ... }
```

### 3. Responsive/Adaptive Design

```kotlin
@Composable
fun AdaptiveLayout() {
    ResponsiveLayout { windowSize ->
        when (windowSize.screenClass) {
            ScreenClass.Mobile -> MobileLayout()
            ScreenClass.Tablet -> TabletLayout()
            ScreenClass.Desktop -> DesktopLayout()
        }
    }
}

// Or use responsive values
val columns = responsiveColumns(mobile = 1, tablet = 2, desktop = 3)
val padding = responsivePadding()
val navType = responsiveNavigationType()
```

### 4. Cross-Platform Component API

Components have matching APIs in Kotlin and TypeScript:

```kotlin
// Kotlin
RButton(
    onClick = { },
    label = "Click Me",
    variant = ComponentVariant.Filled,
    size = ComponentSize.Medium,
)
```

```tsx
// TypeScript
<RButton
  onClick={() => {}}
  label="Click Me"
  variant="filled"
  size="medium"
/>
```

## Token Categories

| Category | Purpose | Examples |
|----------|---------|----------|
| **Colors** | Semantic color roles | primary, surface, error, onPrimary |
| **Typography** | Text styles | displayLarge, bodyMedium, labelSmall |
| **Spacing** | Consistent spacing | xs (4dp), md (16dp), xl (32dp) |
| **Shapes** | Corner radii | sm (8dp), md (12dp), full (pill) |
| **Elevation** | Shadows/depth | sm, md, lg |
| **Sizing** | Component dimensions | iconMd, buttonLg, avatarXl |
| **Breakpoints** | Responsive thresholds | mobile, tablet, desktop |
| **Motion** | Animation timing | durationFast (150ms), durationSlow (500ms) |

## Components

### Atoms (Smallest Building Blocks)

| Component | Description |
|-----------|-------------|
| `RButton` | Primary, Secondary, Outlined, Text, Icon variants |
| `RText` | Display, Headline, Title, Body, Label, Caption |
| `RInput` | Outlined, Filled, Underlined with validation |
| `RCard` | Elevated, Outlined, Filled containers |
| `RIcon` | Sized icon display |
| `RAvatar` | Image, Initials, or Icon avatar |
| `RBadge` | Count or dot badges |
| `RChip` | Assist, Filter, Input, Suggestion chips |
| `RDivider` | Horizontal/Vertical separators |
| `RProgress` | Circular/Linear, Determinate/Indeterminate |
| `RSwitch`, `RCheckbox`, `RRadioButton` | Toggle controls |
| `RSpace*` | Token-based spacers |

### Molecules (Composed Components)

| Component | Description |
|-----------|-------------|
| `RListItem` | Versatile list item with leading/trailing content |
| `RListItemIcon`, `RListItemAvatar` | Icon/Avatar list items |
| `RListItemSwitch`, `RListItemCheckbox` | Interactive list items |
| `RSearchBar` | Search input with clear button |
| `REmptyState` | Empty state with icon, title, action |
| `RLoadingState` | Loading spinner with message |
| `RErrorState` | Error display with retry |

## Usage

### Compose Multiplatform

```kotlin
// In your app's root composable
@Composable
fun App() {
    ReaktorTheme(tokens = MaterialTokens.Reaktor) {
        // Your app content
        Column(modifier = Modifier.padding(Tokens.spacing.md)) {
            RTitle(text = "Welcome")
            RVerticalSpaceMd()
            RButtonPrimary(
                onClick = { },
                label = "Get Started"
            )
        }
    }
}
```

### React/TypeScript

```tsx
import {
  ReaktorThemeColors,
  RTitle,
  RButtonPrimary,
  useSpacing
} from 'reaktor-ui';

function App() {
  return (
    <ReaktorThemeColors primary="#702632" secondary="#008080">
      <div style={{ padding: useSpacing().md }}>
        <RTitle text="Welcome" />
        <RButtonPrimary label="Get Started" onClick={() => {}} />
      </div>
    </ReaktorThemeColors>
  );
}
```

## Custom Themes

Create custom design systems by implementing `DesignTokens`:

```kotlin
val myTokens = DesignTokens(
    colors = TokenFactory.createLightColorScheme(
        primary = Color(0xFF1E88E5),
        secondary = Color(0xFF43A047),
    ),
    typography = TypographyTokens(
        // Override specific text styles
    ),
    spacing = SpacingTokens(
        md = 20.dp,  // Custom medium spacing
    ),
    // ... other tokens
)

ReaktorTheme(tokens = myTokens) { ... }
```

## Platform Targets

| Platform | Technology | Status |
|----------|------------|--------|
| Android | Compose | ✅ |
| iOS | Compose Multiplatform | ✅ |
| Desktop | Compose Desktop | ✅ |
| Web | React/TypeScript | ✅ |
| React Native | - | Out of scope |

## Dependencies

### Kotlin/Compose
- Compose Runtime, Foundation, Material3
- Compose MaterialIconsExtended
- Coil 3 (image loading)

### TypeScript/React
- React 19+
- No additional UI dependencies (pure CSS-in-JS)

## Future Work

- [ ] Neomorphic design implementation
- [ ] Glassmorphism design implementation
- [ ] Organisms (complex composed components)
- [ ] Templates (page layouts)
- [ ] Animation utilities
- [ ] Accessibility improvements
- [ ] Component documentation site
