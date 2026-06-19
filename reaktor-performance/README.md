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
- `tools/size-report.mjs`: Node utility for producing report JSON from build
  artifacts and gzip-compressed sizes.
- `ReaktorLighthouse`: the standard Lighthouse surface in commonMain — category
  taxonomy, the durable `lighthouse.*` metric-name identities, the recommended
  "good" budget set (`ReaktorLighthouseBudgets`), and a pure `lighthouseReport(...)`
  builder onto `ReaktorPerformanceReport`.
- `tools/lighthouse-report.mjs`: the Node runner (`config -> run -> map LHR ->
  report -> budgets -> assert`). Imports the one mapper from `ts/src/index.ts`;
  `--self-test <lhr.json>` maps a fixture with no Chrome for CI.
- `ts/src/index.ts`: TypeScript report helpers for browser and Playwright hosts,
  and the `lighthouseReport(...)` / `budgetViolations(...)` mapper the runner uses.
- `cpp/reaktor_perf_timer.hpp`: header-only native scoped timer for FFI and
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
