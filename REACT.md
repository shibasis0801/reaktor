# React as a first-class host

A plan, written 2026-09-30 on `claude/brave-faraday-c1seji`. Nothing below is built unless it says
so. It exists because BestBuds went to the web as a Compose app first (`bestbuds/targets/appBrowser`,
app.bestbuds.ai) and the next step is a host that renders the same graph as real DOM.

## Why a second host

The Compose web build is the fast path, and it has a price that a phone on a mobile network pays on
every cold start:

| | Compose web (`ComposeViewport`) | React DOM |
| --- | --- | --- |
| Engine before first frame | `skiko.wasm`, 8.6 MB raw / 3.3 MB gzip | none |
| Text | drawn on a canvas; fonts fetched at runtime | the browser's own text, fonts and shaping |
| Find, translate, autofill, selection | not available | native |
| Accessibility | a mirrored semantics tree | the DOM itself |

A React host lets a web app drop Compose once every screen it shows has a React realization. Mobile
keeps Compose; the graph, interactors, repositories, ports, routes and deep links are shared.

## Rules this plan keeps

- **The graph stays the app model** (AGENTS.md §2). React is a second presentation host over
  `reaktor-graph-runtime`, not a second architecture. Only screens differ between hosts.
- **A headless host never acquires Compose** (§2). Every module a React app depends on must be
  Compose-free, checked by a boundary task like the runtime's `verifyRuntimeBoundary`.
- **Tokens have one source** (§4, §7). React reads CSS variables generated from the same values
  Compose uses. Hand-copied hex is the failure mode this repo already has (`WebDesignTokens`).
- **React is written in Kotlin**, with the kotlin-wrappers dependeasy already wires
  (`kotlinWrappers()`, `react()`, BOM 2025.10.4). TypeScript is limited to boot shims and handing
  npm components to Kotlin.
- **The workbench stays Compose.** `bestbuds/targets/reaktorWeb` removed its React host on purpose
  (`LEGACY_REACT_REMOVED.md`) and `tests/reaktorWeb/assert-source-boundary.mjs` enforces that.

## Where things stand

| Piece | Where | State |
| --- | --- | --- |
| Headless runtime | `reaktor-graph-runtime` | **Ready.** `Graph`, the node types, `RouteNode`/`RouteBinding`, `ContainerNode`, `StateInteractor`/`StartableInteractor`, `NavigationCapability`, `DeepLinks`, `Reaktor.web()`. Boundary-checked. |
| React graph host | `reaktor-graph` jsMain: `ReactNode`, `GraphContent`, `WebHost`, `WebNavigationBridge`, `Web*Container`, `WindowSizeClass` | **Prototype, never rendered in an app.** A node renders inline in its parent FC, so its hooks belong to the parent; entries are not keyed, so a tab switch can keep showing the old child; history follows only the root graph and reads Forward as Pop; state crosses by `useState` + `useEffect`. It sits in a module that pulls Compose and skiko through `api(":reaktor-ui")`. |
| `WebHost` | `reaktor-graph` jsMain | Worth keeping: CompanyOS drives it from TypeScript. |
| React atoms | `reaktor-ui` jsMain (`RButton`, `RText`, `RCard`) and `reaktor-ui/ts` | Unused. JS-only hardcoded tokens; `RText` stringifies element children; `usePromise` passes deps unspread. |
| xyflow bindings | `compose-flow` jsMain | Working; used by `reaktor-flow`'s `toReactFlowData`. |
| Workbench React renderer | `bestbuds/modules/engine` jsMain `render/react` (~19.7k lines) | Orphaned since `reaktorWeb` moved to Compose. Global-object bridges; `foundation/ReactInterop.kt`, the boot shims and the npm-injection pattern are the generic parts. |
| Surface | `reaktor-surface` (behaviour kernels) + `reaktor-surface-compose` (~20 controls) | No React realization. R50–R55 (React Strict DOM) deferred; `experiments/rsd` is standalone. |
| `reaktor-react` | React Native 0.68 bridge | Paused, out of the build, unrelated to React DOM. |
| JS tests | | None cover React code. The JS target is red (`BUILD_HEALTH.md`) because Compose reaches Node runs. |

Modules that drag Compose into a web consumer today, although what the web needs from them is not
UI:

- `reaktor-telemetry` → `reaktor-graph`, for `Graph` and `ContainerNode` only; both live in the
  runtime.
- `reaktor-auth` → `reaktor-ui`, `reaktor-graph`. Only three files use Compose (`ui/AppleIcon.kt`,
  `ui/GoogleIcon.kt`, `ui/LoginButtons.kt`); `AuthAdapter`, the `Auth` slot, `JwtDecoder` and the
  web Google/Apple providers do not. `reaktor-auth-core` holds only the kernel.
- `reaktor-media` → `reaktor-ui`. Eight of 33 files use Compose; the audio, gallery and file-picker
  adapters do not.
- `reaktor-graph` holds headless types: `View`, `ChildGraph`/`Controller` (its icon is a Compose
  `ImageVector`), `WindowSize`, `launchWhenWired`/`awaitConnected`.

## Target shape

```
reaktor-graph-runtime     headless graph                                   exists
reaktor-graph             Compose host                                     exists
reaktor-graph-react       React host: screens, GraphContent, containers   new
reaktor-surface           behaviour kernels                                exists
reaktor-surface-compose   Compose controls                                 exists
reaktor-surface-react     React controls over the same kernels             new
token values              Compose-free; Compose types and CSS generated    moved
web adapters              io, db, auth, media, telemetry, location, ...   host-agnostic
browser history           one indexed adapter, used by both hosts          new, runtime jsMain
```

## Decisions

1. **React DOM from Kotlin, not React Strict DOM.** RSD styles through StyleX, which is a Babel
   compile over component source. Kotlin emits JS after the point where that compile runs, so
   Kotlin-authored components cannot use it without a TypeScript layer in between. Surface R51
   becomes "Kotlin React DOM realization"; R53 (React Native) stays deferred.
2. **State enters React through `useSyncExternalStore`.** `StateFlow<T>.use()`: subscribe collects
   `drop(1)` on the main dispatcher and calls `onStoreChange`; `getSnapshot` returns `value`. A
   StateFlow's value is the same reference until it changes, which is exactly the snapshot
   contract, and it cannot tear under concurrent rendering. It replaces `toReactState`.
3. **Every node renders in its own component**, keyed by back-stack entry id, under React contexts
   for `Graph` and `BackStackEntry` (the `LocalGraph`/`LocalBackStackEntry` analogues). Hooks then
   belong to the screen that called them.
4. **Screens are provided by the host.** Common code declares routes, bindings and interactors;
   each host installs the screen nodes that consume those bindings, before `autoWire()`. The
   binding type is the contract between them. A JS build that can reach a Compose screen keeps
   Compose in its bundle, so this is what makes dropping Compose possible.
5. **Styling:** tokens as CSS custom properties generated from the token values; component styles
   through emotion (already in dependeasy's `react()` set); Surface kernel state mapped to state
   classes. Inline styles only for measured geometry.
6. **One browser-history adapter.** Indexed entries in `history.state` so Back and Forward are
   distinguishable, nested graph stacks reflected in the URL through the runtime's
   `routeFor`/`open(DeepLink)`, and a root guard that leaves the app when the graph is at its root.
   The Compose `BrowserBack` shipped for app.bestbuds.ai becomes a thin client of it, and
   `WebNavigationBridge` goes.
7. **`WebHost` keeps its API** and moves to the runtime's jsMain; only its `Content()` is React.

## Phases

### R0 — Headless foundations, no visible change

- `reaktor-telemetry` depends on `reaktor-graph-runtime` instead of `reaktor-graph`.
- Move `View`, `ChildGraph`/`Controller` (the icon becomes an icon id), `WindowSize` and
  `launchWhenWired` into the runtime.
- Auth split, which `BUILD_HEALTH.md` already names: `AuthAdapter`, the `Auth` slot, `JwtDecoder`
  and the providers move to the headless side; `reaktor-auth` keeps the Compose sign-in UI.
- Media split: the adapters and their web actuals leave the Compose image-loading code.
- Token values move to Compose-free types; `reaktor-ui` builds `Color`/`Dp` from them with no change
  for Compose callers; a Gradle task emits the CSS variables. `WebDesignTokens` is deleted. The same
  split applies to BestBuds' own tokens in `modules/design`.

**Done when** each headless module passes a boundary check, `jsNodeTest` is green for them (no
skiko in Node), and BestBuds' Android, iOS and app.bestbuds.ai builds are unchanged.

### R1 — `reaktor-graph-react`

- `StateFlow<T>.use()`; `ReactScreen`, the stateless `StatelessComposeNode` analogue; keyed
  `GraphContent`; the Graph and entry contexts; `SessionSlot`, tab and bottom-bar containers. The
  `ReactContainer`/`ReactContent` interfaces carry over.
- The shared browser history; `WebHost` moved, CompanyOS still working.
- Delete the prototypes: `ReactNode`'s demo code, the `useEffectRaw` wrappers, the empty
  `BottomNavigationView.kt`, `WebNavigationBridge`, the `reaktor-ui` React atoms and
  `reaktor-ui/ts`.

**Done when** a jsNodeTest suite with react-dom under jsdom covers push, pop, replace, Back and
Forward, a cold-start deep link, a tab switch, a session swap and a burst of StateFlow updates, and
a sample target renders a two-graph app under Playwright.

### R2 — Web adapters, complete and host-agnostic

Location (Geolocation API); notifications (Web Push with a service worker and VAPID, which needs a
server sender); camera (`getUserMedia`, with `<input capture>` as the fallback); voice
(`MediaRecorder`, transcoded server-side where the codec differs from mobile); video playback; an
error-reporting sink shaped like Crashlytics. Haptics only if `reaktor-tactile` is revived; AGENTS.md
keeps it a placeholder. Each adapter ships with a jsTest and the `Permissions-Policy` and CSP lines
it needs.

### R3 — `reaktor-surface-react`

React realizations of the Surface controls, driven by the same kernels so behaviour matches by
construction. DOM-native semantics: `button`, `dialog` with a focus trap, `listbox`, `switch`. One set
of conformance scenarios runs against both hosts, with axe checks under Playwright.

### R4 — Tooling

A dependeasy React application preset: production webpack settings, chunks kept under the 25 MiB
per-file Workers asset limit, `_headers`, and a bundle budget that fails the build. Browser tests
re-enabled against the preinstalled Chromium (dependeasy switches them off today). A template target
and its docs.

### R5 — BestBuds on React, web only

- `modules/app` splits into its headless graph and logic and per-host screen sets (decision 4), as
  a `composeMain`-style source set or as separate modules.
- React screens for the web; routes not yet ported render as Compose islands, a `ComposeViewport`
  inside a React component.
- Port order: sign-in and onboarding, the invite landing, chat list, chat, profile and settings,
  then the long tail.
- When no island remains, the web target drops Compose and skiko leaves the bundle.

**Done when** the `tests/appBrowser` Playwright suite passes against the React build and the
first-load transfer is under an agreed budget.

## Open questions for the owner

- Keep the workbench on Compose? Recommended: yes.
- Delete the orphaned engine React renderer once its generic helpers are lifted? Recommended: yes.
- Emotion for component styles, or a generated stylesheet? Emotion is the faster start; revisit if
  its runtime shows up in the budget.
- D9 in `bestbuds/targets/reaktorWeb/docusaurus/docs/private/bestbuds-surface-roadmap.md` ("Compose
  only … No web target for BestBuds") is superseded by app.bestbuds.ai and by this plan.
