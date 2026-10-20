# reaktor-performance

Shared performance instrumentation and harness contracts for Reaktor hosts.

This module owns the cross-target data model for Reaktor performance reports,
small in-process collectors for Kotlin targets, browser web-vitals capture for
React/JS hosts, and thin mixed-language hooks for CI/tooling. Target-specific
suites can keep their runner code next to the app they validate, but report
models, app vitals, web vitals, artifact sizes, server vitals, flamegraph
frames, and reusable collection logic should live here.

## Surfaces

- `ReaktorPerformanceCollector`: Kotlin collector for marks, benchmark samples,
  app-vital snapshots, server vitals, build artifacts, build timings, profile
  captures, tool runs, and flamegraph frames.
- `ReaktorPerformanceHarness`: a small runner envelope for Maestro,
  Playwright, Keploy, k6, Lighthouse, Gradle, and custom harnesses.
- `ReaktorPerformanceReport`: serializable report shape used by desktop and web
  harnesses.
- `budgetViolations()`: common budget assertion helper for report samples.
- `ReaktorPerformanceReports`: JVM report writer for harness output files.
- `installReaktorWebVitals()`: lightweight browser collector in
  `src/jsMain/resources/reaktor-web-vitals.js` for React/Vite hosts that should
  not load the Kotlin runtime on the critical path.
- `ReaktorWebVitals.install()`: Kotlin/JS export for hosts that already have the
  Kotlin module graph loaded.
- Dependeasy `ArtifactSizeReport`: the Gradle task for artifact bytes, gzip,
  Brotli and declared budget metrics. It consumes build outputs and writes JSON.
- `ReaktorLighthouse`: the standard Lighthouse surface in commonMain — category
  taxonomy, the durable `lighthouse.*` metric-name identities, the recommended
  "good" budget set (`ReaktorLighthouseBudgets`), and a pure `lighthouseReport(...)`
  builder onto `ReaktorPerformanceReport`.
- `tools/lighthouse-report.mjs`: the Node runner (`config -> run -> map LHR ->
  report -> budgets -> assert`). Imports the one mapper from `ts/src/index.ts`;
  `--self-test <lhr.json>` maps a fixture with no Chrome for CI.
- `ts/src/index.ts`: TypeScript report helpers for browser and Playwright hosts,
  and the `lighthouseReport(...)` / `budgetViolations(...)` mapper the runner uses.
- `src/commonMain/cpp/include/reaktor/performance/timer.hpp`: header-only native scoped timer for FFI and
  FlexBuffer hot-path probes.

## Report domains

- Build artifacts: APK, IPA, JAR, Worker bundle, web bundle, Hermes bundle,
  WASM, native binaries, container images, and schema sizes.
- Build timings: Gradle, KSP, Kotlin/JS, CMake, Xcode, Wrangler, and CI phases.
- Runtime vitals: web vitals, app vitals, server startup/readiness/latency, and
  generic scoped metrics.
- Profiling: flamegraph frames and profile capture references from
  async-profiler, JFR, simpleperf, Perfetto, Instruments, CDP, or custom tools.
- Tool runs: normalized run envelopes for external harnesses so CI can compare
  results and enforce budgets consistently.

## Lighthouse

Lighthouse is the one standard web-perf driver. The contract — category taxonomy,
`lighthouse.*` metric names, and the recommended budgets — lives once in
`ReaktorLighthouse.kt` (commonMain) and is mirrored for the Node runner in
`ts/src/index.ts`. The runner produces a normal `ReaktorPerformanceReport`, so
Lighthouse results flow through the same budgets, report writer, and (later) OTel
pipeline as every other surface.

Recommended budgets (`ReaktorLighthouseBudgets.recommended()`), at the web.dev
"good" thresholds:

| Metric | Name | Budget | Direction |
| --- | --- | --- | --- |
| Largest Contentful Paint | `lighthouse.lcp` | ≤ 2500 ms | Max |
| Total Blocking Time | `lighthouse.tbt` | ≤ 200 ms | Max |
| Cumulative Layout Shift | `lighthouse.cls` | ≤ 0.1 | Max |
| Speed Index | `lighthouse.speed-index` | ≤ 3400 ms | Max |
| First Contentful Paint | `lighthouse.fcp` | ≤ 1800 ms | Max |
| Performance / A11y / Best-Practices / SEO score | `lighthouse.performance` … | ≥ 90 | Min |

`Min`-direction budgets (the higher-is-better category scores) are why
`ReaktorPerformanceBudget` carries a `direction`; everything else defaults to `Max`.

Run it:

```sh
# CI-safe unit: map a saved LHR fixture, no Chrome required.
node tools/lighthouse-report.mjs --self-test tools/fixtures/lighthouse-sample.json

# Live audit (needs Chrome; chrome-launcher honors CHROME_PATH).
cd tools && npm install
node lighthouse-report.mjs --target my-app --url https://example.com/ --preset desktop --assert \
  --out ../../build/reports/performance/my-app-lighthouse.json
```

`--assert` exits non-zero on any `Error`-severity violation, mirroring
`ReaktorPerformanceReport.requireWithinBudgets()`. In BestBuds the reference wiring is
`npm run perf:reaktorWeb:lighthouse`.

## Verification

```sh
./gradlew :reaktor-performance:compileKotlinJs :reaktor-performance:compileKotlinJvm :reaktor-performance:allTests --no-daemon --console=plain --no-build-cache
```

## Web sessions: React, DevTools and the Chrome DevTools Protocol

The TypeScript side measures a web app the way a person uses it: real headed Chrome, a
production build, real gestures. Each piece is a plain module you can call from any
Playwright or CDP script.

| Module | What it gives you |
| --- | --- |
| `ts/src/react.ts` | `installReactProbe()` hooks `__REACT_DEVTOOLS_GLOBAL_HOOK__` (wrapping React DevTools when it is there) and counts commits, components rendered, mounts, wasted renders (a parent re-rendered a non-memo child whose props did not change) and why each component rendered (props, state, hooks, context, parent). `recordProfilerRender` feeds a `<Profiler>`; `connectReactDevtools` attaches the standalone React DevTools. |
| `ts/src/mutations.ts` | `installMutationProbe({ root, camera })` counts DOM mutations under `root`, split into structural, attribute and text changes, with the busiest targets and attributes. Writes to the `camera` elements are counted apart, so a gesture can be held to zero mutations. |
| `ts/src/cdp.ts` | Tracing with the DevTools timeline categories, `Performance.getMetrics` deltas, screencast capture, paint flashing, the compositor layer tree, and the frame event categories. |
| `ts/src/trace.ts` | Reads a trace: main-thread breakdown, frames presented, dropped and missing content, long tasks, style and layout counts, raster and GPU time, interactions, and JavaScript by function. |
| `ts/src/flicker.ts` | Runs in a page: measures screencast frames for coverage dips (a frame that loses content and gets it back), whole-frame or per cell, and draws a strip of the frames it flagged. |
| `ts/src/session.ts` | Turns a set of runs into one `ReaktorSessionReport` (medians with p25/p75 and min/max per step and metric, React component table, layer summaries) and writes a before/after markdown table. |
| `ts/src/bundle.ts` | Splits a build into what the first page load fetches and what loads later, by package, using the source maps. |

Build size reports come from the typed `ArtifactSizeReport` task. BestBuds runs
its declared `bestbudsWebBundleBudget` check against that report. The `ts/src/bundle.ts`
source-map and import analysis functions remain available to profiling tools.

Command-line tools:

```
node tools/static-server.mjs --dist dist --port 4300 --immutable   # serve a build with brotli and immutable assets
node tools/trace-report.mjs --trace run.json --target app --maps dist/assets --out trace.json
node tools/session-report.mjs --runs runs/after --target app --label after \
  --compare before.json --rows rows.json --out after.json
```

`tools/route-host.mjs` serves a build over HTTP and accepts Playwright-style `route` and
`routeWebSocket` handlers, so a fake backend runs without request interception and the
HTTP cache and V8 code cache behave as they do in production.

### React DevTools

The standalone React DevTools (`react-devtools-core`) connect over a WebSocket on port 8097.
Keep them out of production bundles: load the backend only in a profiling or development
build, and only when the page asks for it.

1. Start the DevTools window: `npx react-devtools`.
2. Run a build that carries the backend. In Manna that is `npx vite build --mode profile`
   (React's profiling build, names kept, output in `dist-profile`) or
   `VITE_REACT_DEVTOOLS=1 npm run dev`.
3. Open the app with `?devtools` in the URL. `?devtools&devtoolsPort=8098&devtoolsHost=host`
   picks another address.

Without `?devtools` the backend stays idle, and the probe from `installReactProbe` still
counts commits and renders, so a test can read the same numbers headless.

### Chrome DevTools

Every trace the harness keeps is a DevTools trace file: open it in the Performance panel
(Load profile). To watch a run live, start Chrome with a debugging port; in Manna's perf
harness that is `PERF_DEBUG_PORT=9222`, which launches Chrome with
`--remote-debugging-port=9222`. Then open `chrome://inspect` in another Chrome, or point any
CDP client at `http://localhost:9222/json`.

The harness runs the same steps in the GPU-less browser that CI uses when you set
`PERF_HEADLESS=1 PERF_EXECUTABLE=<path to chrome-headless-shell>`. Raster there is in
software, so it shows raster costs that a GPU hides.

## Compose web (Skiko)

`tools/compose-web.mjs` instruments the linked Skiko renderer through
`instrumentComposeWeb(source)`, used by Surface's existing dependency adapter.
Install `installComposeWebProbe` as a browser init script before Compose mounts,
reset `globalThis.__reaktorCompose` before each measured action, then pass its
`snapshot()` to `composeWebSummary(snapshot, refreshMs)`.

It reports actual Skiko render/flush CPU duration, canvas input queue and
input-to-render-completion latency (with browser event delivery and listener-to-render queuing reported separately), backing/CSS canvas sizes and pixel density,
failed frames, lost contexts, pending inputs and dropped samples. Samples are
bounded. `composeWebMetrics(summary, prefix, scope)` exports the standard
`ReaktorPerformanceMetric` shape for reports and budgets.

The renderer sample also partitions CPU time into `prepareMs` (context setup and
clear), `sceneMs` (the Compose render delegate, including measurement/drawing),
and `submitMs` (Skia flush/submission). These are CPU boundaries, not GPU timers.
`recomposerTasks` separately measures the synchronous Compose task flush, which
can occur outside a render callback and can overlap scene work. Do not add its
inclusive duration to the frame duration. Keep `snapshot().frames`, `.inputs`
and `.phases` alongside the summary when attributing a slow interaction. The
injection discovers generated method names and fails if the pinned source
boundaries change.

Set
`textureUploads: true` only for GPU-upload investigations; it wraps WebGL uploads
and adds CPU overhead. Upload totals remain exact while shape samples are bounded
at 128 (the 32 busiest retained shapes are reported). `untrackedUploads` makes
shape sampling loss explicit. Uninstalled probes collect nothing. CPU completion is **not** GPU or
screen presentation; retain CDP frame-presentation and input traces alongside
these metrics. Input timings describe the next successful render, not a causal
response or INP: hover, touch slop, and zoom-limit input may not invalidate the
canvas at all. Read the gesture and CDP interaction trace before treating a long
wait as an application stall. An idle Compose canvas need not draw at the RAF cadence.

Run the behavioral checks with `node --test tools/compose-web.test.mjs`.
Set `COMPOSE_LINKED_MODULE` to the actual linked Kotlin module to qualify its
Skiko instrumentation boundary too. Boundary drift fails visibly.

`tools/worker-web.mjs` provides an independent, opt-in request/response probe for
named workers (default `blueprint-elk`). Install it before the worker is created.
`globalThis.__reaktorWorkers.snapshot()` reports bounded jobs with message-post
CPU time, response elapsed time and the worker's reported compute time. Response
elapsed time includes queuing, worker boot and message delivery; it is not main
thread CPU. Requests are correlated by worker identity and numeric request id;
payloads are never retained. Failed, canceled, pending and dropped work is explicit.
The probe also counts created/terminated workers across resets. Run
`node --test tools/worker-web.test.mjs` for its behavioral checks.
