# Surface for Kotlin/JS React DOM

The React realization is authored in `src/jsMain/kotlin/.../surface/react` and
uses Kotlin React wrappers, React and React DOM. Compose remains the native/mobile
realization. This supersedes the RSD direction in the historical October 5
investigation. Neither RSD, React Native, React Native Web nor a custom reconciler
is part of this implementation.

Kotlin callers use the typed `SurfaceAction`, `SurfaceField`, `SurfaceCollection`,
`SurfaceDataTable`, `SurfacePaneHost` and other FCs. The exported objects support
existing JavaScript hosts; `ts/src/index.tsx` only binds those exports. Behavior,
state transitions, timers, accessibility and rendering belong to Kotlin.

| Capability | Shared contract / kernel | React DOM realization |
| --- | --- | --- |
| Actions, busy state, failure | Press | Native button activation, promise epoch and unmount ownership |
| Action groups | Roving | One tab stop, disabled-item skipping, Home/End, wrapping, typeahead and RTL |
| Fields and forms | Draft revisions | Native input/textarea/form, label/hint association |
| Checkbox, switch | Toggle | Native checked/disabled controls |
| Select, radio | OneOf | Native select/radio semantics |
| Toggle groups | ManyOf | Capacity refusal, controlled selection |
| Tabs and document tabs | TabSet | Roving focus, typeahead, automatic/manual selection, close requests |
| Lists and trees | Collection | Range/modifier selection, expansion, activation, menu requests, fixed-row windowing |
| Tables | Grid, TableLayout | Cell ranges, copy/activate, three-state sort, width/pin state, fixed-row windowing |
| Disclosure and menus | Disclosure, Menu | Focus return, nested/context menus, typeahead and Escape |
| Tooltip, splitter, toast | Tooltip, Splitter, Toast | Keyboard/pointer inputs, timer retirement, live announcements |
| Dialog, sheet, popover | Native modal/top-layer contracts | Focus containment, light dismissal, collision-aware placement and anchor tracking |
| Panes | PaneSpec, PanePreferences, pane plan | Constraint-based layout, resize handles, F6 cycling and focus restoration |
| Commands | CommandSet, command contexts | Focused island priority, availability, shadowing and hotkeys |
| Theme and appearances | ThemeSnapshot, ComponentRecipe | React environment context and per-component appearance slots |
| Presence | Exit-completion ownership | Keeps content mounted until the current exit completes |
| Frames, labels, state, marks | Structural roles and theme | Semantic DOM, SVG marks, reduced-motion visual transforms |
| Compose region | Scoped renderer lifetime | One isolated Compose viewport inside an owned React DOM region |

`SurfaceTheme` supplies theme, appearances, key convention, direction, reduced
motion and feedback. Appearance slots receive the already constructed DOM content
and theme; replacements must retain the event/ref/semantic ownership they replace.
There is no requirement that both renderers produce identical pixels.

Collection/table windowing is opt-in with explicit `height` and `rowHeight`.
Variable-height content uses ordinary DOM layout. A focus request outside the
window scrolls first and attaches focus when that row mounts. Callers retain
catalogue, selection, sorting, persistence and command authority.

Qualification belongs to the consuming browser in addition to common kernel
suites. Manna's `surface-controls.spec.js` and `surface-kotlin.spec.ts` exercise
real DOM keyboard/focus/selection/overlay and StrictMode retirement. Device,
screen-reader and cross-browser qualifications are distinct from Chromium checks.
Panes, command islands and appearance slots are compiled API coverage; their
complete browser interaction matrix is still pending. The current controls
journey additionally exercises a 10,000-row window, nested/context menus, grid
copy/selection, splitter reset, native overlays and superseded exit completions.

React hosts embedding Compose use `useComposeRegion` together with the Vite
dependency adapter in `reaktor-surface-compose/tools/compose-dependencies.mjs`.
For Compose 1.12.1 / Skiko 0.150.1 this defers renderer WASM until the region mounts,
binds the resolution listener to its existing window lifetime, and closes the
previous GPU surface/context on resize and disposal. The adapter checks linked
dependency shapes and refuses changed versions that require requalification.
Manna's renderer lifetime regression checks eight visits after garbage collection.
