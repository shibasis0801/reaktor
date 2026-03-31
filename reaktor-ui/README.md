# reaktor-ui

> **Stability: Early** - Functional for current scope but not a polished public design system.

`reaktor-ui` is the shared UI layer for Reaktor apps, providing Compose-first components and design tokens.

## Platforms

Android (Compose), iOS (Compose), JVM (Compose Desktop), JavaScript/Web (React wrappers)

## What it contains

### Design tokens

- `DesignTokens` / `TokenFactory` - Design token system
- `MaterialTokens` - Material3 token definitions
- `LightColors` / `DarkColors` - Color schemes
- `ReaktorThemeProvider` / `ReaktorDesignSystem` - Theme provider composables

### Compose components (atoms)

`Button`, `Divider`, `Icon`, `Input`, `Badge`, `Chip`, `Card`, `Progress`, `Avatar`, `Text`, `Toggle`, `Spacer`

### Compose components (molecules)

`EmptyState`, `SearchBar`, `ListItem`

### Theme

`Theme` is the customizable theme with composable factories:
- `ButtonPrimary`, `ButtonSecondary`, `ButtonIcon`, `ButtonFloatingAction`
- `TextView`, `CardView`, `InputText`, `Space`

### Web components

`WebButton`, `WebText`, `WebCard`, `WebComponent` - React-compatible wrappers

### Layout

`Responsive` - Adaptive layout helpers for different screen sizes

## Design goals

- Keep visual language consistent across products
- Avoid product code hardcoding sizes, spacing, and semantic colors
- Provide reusable UI components without forcing a single visual style

## Dependencies

- `reaktor-core`, `reaktor-io`
- Compose runtime, foundation, material3, material-icons-extended
- Coil 3.2.0 (image loading, network, SVG)
- Kotlin wrappers + React (web)
