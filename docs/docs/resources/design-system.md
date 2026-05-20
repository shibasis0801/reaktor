# Reaktor Design System v1.0

This document defines the visual language and technical implementation of the Reaktor "Industrial Technical Manual" aesthetic. All future documentation and UI components should adhere to these specifications.

## 1. Core Visual Tokens

Reaktor uses a specific set of CSS variables defined in `src/css/custom.css`. These variables automatically adjust based on the user's theme (Light vs. Dark).

| Token | Light Mode (Paper) | Dark Mode (Negative) | Purpose |
| --- | --- | --- | --- |
| `--blueprint-ink` | `#1E3A8A` (Deep Blue) | `#E2E8F0` (Off-white) | Headers, borders, primary text. |
| `--blueprint-paper` | `#F4F4F0` (Cream) | `#0F172A` (Slate) | Main canvas background. |
| `--blueprint-paper-white`| `#FFFFFF` | `#1E293B` | Component backgrounds (diagrams, code). |
| `--industrial-orange` | `#F97316` | `#FB923C` | High-impact accents, callouts, focus. |
| `--blueprint-border` | `#CBD5E1` | `#334155` | Structural grid lines and table borders. |

## 2. Typography

The design system uses a multi-layered typographic approach to balance theme and readability.

- **Authoritative Mono (`VT323`)**: Used for main branding and high-level headers (H1).
- **Technical Mono (`Space Mono`)**: Used for sub-headers (H2-H4), metadata, and labels.
- **Readable Serif (`Lora`)**: Used for all body copy to ensure long-form reading comfort.
- **Code Mono (`JetBrains Mono`)**: Used exclusively for code blocks and technical readouts.

## 3. The Structural Grid

The interface is built on a rigid grid system that mimics a printed specification sheet.

- **Gap Logic**: Instead of traditional margins, sections are separated by `1px` gaps showing the underlying border color.
- **Radial/Linear Grid**: A subtle background pattern (`25px x 25px`) is applied to the canvas to evoke drafting paper.
- **Sharp Corners**: A global radius of `0` is enforced. No rounded corners allowed.

## 4. Component Standards

### Technical Diagrams (`Blueprint`)
Use the `<Blueprint />` React component for all architectural diagrams.
- **Badge**: Title must be in `Space Mono` uppercase.
- **Background**: Always use `--blueprint-paper-white`.
- **Shadow**: Solid offset shadow using `--blueprint-highlight`.

### Data Tables (`SpecTable`)
- Header must be `--blueprint-ink` with white/paper text.
- Row hover states should use `--blueprint-highlight`.

## 5. Mobile Optimization Guidelines

- **Typography**: Use `clamp()` for responsive font sizes (e.g., `font-size: clamp(2.5rem, 10vw, 5.5rem)`).
- **Stacking**: Use `grid-column: span 12` on mobile viewports (`max-width: 996px`) to collapse multi-column layouts.
- **Banners**: Vertical side banners should rotate back to horizontal or be hidden on screens narrower than `768px`.
