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
- `ts/src/index.ts`: TypeScript report helpers for browser, Playwright, and
  Lighthouse adapters.
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

## Verification

```sh
./gradlew :reaktor-performance:compileKotlinJs :reaktor-performance:compileKotlinJvm :reaktor-performance:allTests --no-daemon --console=plain --no-build-cache
```
