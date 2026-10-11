# Build modernization qualification — 11 October 2026

Scope: Dependeasy, Reaktor, BestBuds, and Manna. The latest unfiltered BestBuds-led
composite succeeds: all 55 module build lifecycles, 446 main Hangar flows and 14
separate qualification flows pass. Selected XML reports contain 4,857 test cases,
zero failures/errors and 30 existing skips. Report reuse is distinguished below.
Unsigned iOS Debug/Release app builds and native Build execution have earlier,
separate qualifications. Credentialed releases, AGP's modern KMP/native boundary,
pinned mobile crypto final links and the complete Manna Kotlin migration remain
unfinished. Passing builds do not establish signing or physical-device behavior.

## BestBuds-led full scan and fixes

The final command from BestBuds is `./gradlew buildAll buildGraph --continue --scan
--profile --init-script ../.remote-dev/scan-profile-init.gradle
-Dcodex.scan.termsApproved=yes -Pbestbuds.devTools=false`. The normal wrapper chooses
this Mac based on available CPU/memory. The run succeeds in 44m02s of wall time;
Gradle reports 43m56s, 6,579 actionable tasks, 408 executed, two from cache and
6,169 up-to-date. All three declared buildAll entrypoints and 55 module lifecycles
complete. [Final scan](https://gradle.com/s/qkiiz6hozpnis).

The 957 selected XML reports contain 4,857 cases, zero failures/errors and 30
existing skips. Fresh execution covers 1,497 cases in 297 reports; 3,360 cases in
660 reports are reused from up-to-date tasks. All 475 engine tests and the complete
446-flow Hangar run are fresh. All 14 separate surface qualification flows pass.
Manna's 760 API and 796 browser/edge Vitest tests are outside the XML total; both
tasks are up-to-date here after their preserved fresh passing qualifications. All
three pnpm locks and monitored source/bootstrap inputs remain unchanged.

This follows two failed completed scans and two interrupted attempts. Corrections
retain the flow coverage, deadlines, performance limits and behavioral assertions:
compact/deduplicated graph export and its declared consumer input; row-aware
timeline selection and empty rows for negative clock origins; correct warm-cache
producer outcomes; whole-minute review fixtures with deterministic midnight cases;
and alternating, paired table measurements. Existing Agent/background-connection
owners also preserve dismissed errors and pending approvals. The final Build and
62-step timeline flows pass in the unfiltered suite.

Manna's optimized simulator-framework link exhausted the old 8 GiB Gradle heap.
The roots now own a 12 GiB Gradle maximum and retain an 8 GiB Kotlin compiler
maximum. The routing wrapper no longer overrides their checked-in budgets; other
projects retain their existing defaults. Actual JVM flags confirm the new budget,
the failed link passes in a focused 4m20s run and the complete composite then passes.
Native optimizations, three workers and load routing remain enabled.

Same-input graph comparison preserves all 12,420 unique task/dependency definitions
while reducing 12,240,426 bytes to 4,204,915 bytes, a 65.65% reduction. Each measured
FlexBuffer Android ABI retains seven compile/link commands instead of fourteen
Ninja commands after removing six unnecessary scans and one collation command.
These are size/command-count improvements, not controlled build-time speedups.

The local profile records 1.971s configuring projects and 2,541.41s in Hangar tests;
the main flow case takes 2,104.397s. Parallel task/transform durations are accumulated
work rather than a wall-time critical path. The warm final run cannot establish a
speedup against earlier runs with different source/cache states. The full-run table
comparison passes unchanged relative/absolute policies: paired wheel ratio 0.854,
PageDown ratio 0.851 and wheel median p95 3.2ms. Raw rounds and the existing
reference-dependent absolute key policy are retained in the evidence.

[HLD and domain report](/Users/ovd/dev/.remote-dev/composite-scan-20261011/REPORT.md)
and [machine-readable summary](/Users/ovd/dev/.remote-dev/composite-scan-20261011/final-summary.json)
are local artifacts. Logs, saved HTML profiles, selected XML, comparisons, input
checks and interrupted-run diagnostics are under
`/Users/ovd/dev/.remote-dev/composite-scan-20261011`. Later results in this section
supersede older statements below that desktop flow qualification remained pending.

## Earlier completed results

| Scope | Qualified result | Evidence |
| --- | --- | --- |
| Hangar distribution | Packaged app, actual loopback MCP and packaged database runtime pass | `20261011-022204-bestbuds` |
| Native Build UI | Build label, project frames, dependency inspector, 109 operations; Reaktor check starts and reaches succeeded/exit 0 | `hangar-native-build-tab-9/launch.json`; `20261011-022836-bestbuds` |
| Build graph | 3,355 nodes, 7,560 wires, 230 graphs, zero findings | Native inspection 9 |
| BestBuds engine | 473 tests in 147 fresh suites pass; layout benchmark median 182.6 ms for the 3,350-task fixture | `20261011-015846-bestbuds` |
| Catalog owner | 10 fresh tests pass after explicit verification targets are classified in Build | `20261011-022204-bestbuds` |
| Reaktor full build | 2,693 tests in 484 fresh suites; zero failures/errors, 18 skips | `20261010-210626-reaktor` |
| BestBuds Android and browser | Debug/release APKs, release AAB and production browser artifacts pass | `20261011-005950-bestbuds`; web transfer `20261011-015113-bestbuds` |
| BestBuds actual iOS app | Debug and Release device-SDK apps, notification extension, exact SwiftPM dependencies and direct Kotlin links pass, signing disabled | `ios-actual-xcode-qualification-6.json` |
| Manna browser/API | All 796 browser/edge and 758 API tests, TypeScript checks and production Vite build pass under the shared pnpm command policy | `composite-pnpm-policy-web-12.log` |
| pnpm command boundary | 51 existing owner tests, the new command-boundary regression and 11 JavaScript kernel tests pass; component, nested Node and Kotlin paths share one environment | `dependeasy-pnpm-command-boundary-2-results/`; `dependeasy-pnpm-command-boundary-3.log`; `composite-pnpm-policy-web-12.log` |
| Reaktor docs deployment | Pinned Wrangler dry run and existing deployReaktor flow pass; live public docs 200, private route 302 to sign-in, internal private asset route 404 | `docs-pinned-deploy-12.log`; `docs-deploy-live-verification-12.json` |
| Composite and IDE | BestBuds/Manna/Reaktor share one framework model; standalone Manna metadata and composite web follow-up pass | `bestbuds-intellij-composite-model.json`; `20261011-014535-manna`; `20261011-015113-bestbuds` |

The evidence files and run receipts live under
`/Users/ovd/dev/.remote-dev/build-layer-migration-20261009` and `.remote-dev/logs`.
The sections below retain the qualification history, including failures and work
that was pending at the time. Later completed receipts supersede those pending
statements. Signed releases, AGP's new native/KMP
producer boundary and the complete Manna Kotlin migration remain unfinished.

## Earlier follow-up qualification

The current Reaktor full build passes with AGP 9.1.1 (`declarative-reaktor-full-build-5.log`,
receipt `20261010-210626-reaktor`): 4,813 tasks in 30m 44s, including 3,992 executed,
505 from cache and 316 up-to-date. Its 484 fresh suites contain 2,693 tests, zero
failures or errors and 18 skips. Transfer succeeds with 28,920 copied paths.
Evidence: `20261010-210626-reaktor-fresh-test-results.json`.

The requested BestBuds design prototype is removed: its standalone target, demo
graph, fixtures, launch flags and prototype-only tests. Components used by the
production app now live in `ai.bestbuds.design.signal`; production destinations
belong to the app. Current deleted source is preserved in
`design-prototype-source-backup.tar.gz`. The former prototype bundle-budget
failure concerns a deleted target. BestBuds full qualification 3 caught a moved
navigation type shadowed by the existing appearance property, and a stale pnpm
lock reference to Reaktor core's former `js` directory. The navigation import is
corrected. That run was stopped with exit 143 after the failures. The explicit
Gradle-owned lock update passes: receipt `20261010-221341-bestbuds`, 161 tasks in
14s, with the new lock copied successfully. The production qualification now
checks design/app tests, Android debug, browser distribution, the Apple arm64 debug
framework, current docs and generated graph/Dagger/package adapters. Receipt
`20261010-221844-bestbuds` completes Android debug, the Apple arm64 debug framework,
the current documentation site, graph and adapter generation. Its 33 fresh JVM
suites contain 264 tests with zero failures, errors or skips. The only failed task
is the existing browser safety guard because the local development flag is true;
the browser rerun explicitly supplies `-Pbestbuds.devTools=false`. Transfer succeeds
with 7,736 copied paths. Evidence: `20261010-221844-bestbuds-fresh-test-results.json`.
Removal does not establish that the remaining product suites pass. Current module
documentation marks the old host removed; its graph and navigation views were
regenerated by their owner.

Browser qualification 2 fails during configuration because the included directory
defaults to `manna` while the workspace refers to `Manna`. BestBuds settings now
declare that included-build name explicitly. Full qualification 4 completes with
two failed test tasks in 1h 5m 50s: 3,835 tasks, 2,972 executed, 490 from cache
and 373 up-to-date. Receipt `20261010-223752-bestbuds` confirms a successful
transfer of 8,073 paths. Its 326 fresh BestBuds suites contain 1,516 completed
test cases, three failures and 13 skips; the desktop flow aggregate did not
complete. Reaktor and Manna reports in this transfer are reused, not fresh tests.
App tests (770), design tests (six), Android debug/release assembly and lint,
all four Apple framework links, the production browser distribution, current
documentation site and the actual desktop MCP probe pass. This is a failed full
qualification despite the completed artifacts. Evidence:
`20261010-223752-bestbuds-fresh-test-results.json`.
The Hangar Build-tab follow-up reuses the generated graph reader
and renderer, keeps application/build graph selections separate, and retains the
shared task execution bindings. Target qualification 5 includes the new source
and regressions, Hangar's runnable distribution, Android debug/release outputs,
device iOS frameworks and the production web bundle. Receipt
`20261010-234506-bestbuds` finishes with two failed test tasks in 59m 39s:
2,395 tasks, 139 executed and 2,256 up-to-date. Its 235 fresh BestBuds suites
contain 773 tests, six failures, no errors and 11 skips; transferred Reaktor and
Manna suites are reused. Transfer succeeds with 8,116 copied paths. The kernel
suite passes 237 fresh tests; the desktop flow aggregate completes in 41m 28s
without the earlier Auth shutdown deadlock but fails UI assertions. Android
debug/release assembly and release bundle, both device iOS framework links,
the production browser distribution and current docs complete. Graph and adapter
generation and the packaged desktop MCP probe also complete. Evidence:
`20261010-234506-bestbuds-fresh-test-results.json`. The prototype
deployment inventory still expected the removed alias; that fixture now follows
the current package inventory and retains surviving mutation/approval checks.
Artifact qualification 7 passes with receipt `20261011-005950-bestbuds`: 2,405 tasks
in 24m 44s, including 1,733 executed, 423 from cache and 249 up-to-date. Hangar's
runnable distribution, Android debug/release APKs and release AAB, both device iOS
frameworks, the Kotlin browser app, website, current docs and generated adapters
complete. Transfer succeeds with 92,868 copied paths. This artifact-only run
freshly executes no Gradle test suites; transferred reports are reused. Evidence:
`20261011-005950-bestbuds-fresh-test-results.json`. The actual unsigned Xcode app
build and focused repair run 4 remain pending. Earlier queued attempts were
canceled before synchronization and do not provide qualification evidence.
Shared tooling now also reads named Gradle workspace targets from the declared
report. It retains the wrapper execution binding and seals the report alongside
build definitions. Unsupported schemas, platforms and command-bearing reports
are not admitted as executable build targets. Receipt `20261011-004609-reaktor`
finds one test fixture failure: macOS canonicalizes the temporary wrapper path
from `/var` to `/private/var`, while the fixture expected its absolute spelling.
The fixture now checks the canonical path. Shared tooling, Compose Flow and
Blueprint owner qualification 2 passes with receipt `20261011-005728-reaktor`:
226 tasks in 1m 25s and a successful transfer of 27,069 paths. Its 58 fresh suites
contain 228 tests, zero failures or errors and one skip. Reused reports are excluded.
Evidence: `20261011-005728-reaktor-fresh-test-results.json`.
The subsequent composite adapter admits included owners through the generated
layout report and executes qualified paths through the BestBuds wrapper. It seals
that layout alongside the graph, deduplicates diamond declarations and omits
conflicts or unknown owners. Owner qualification 3 and the consumer cache
regression remain pending. `buildGraph` now depends on the layout metadata task;
the diamond fixture checks this relationship and configuration-cache reuse.
Root
Gradle properties no longer repeat unused Ktor/serialization/publication pins
or obsolete KMP warning flags. The dangling `maestro:prototype` alias is removed.
Qualification 4 started before these last edits; qualification 5 synchronizes them.
During qualification 4, the engine's 147 fresh suites run 469 tests with three
failures. The surface sweep finds duplicate header IDs across 846 rendered cells;
the shared table now owns each ID and Hangar no longer repeats it on the label.
Two graph ownership fixtures wait for an application without requesting activation,
contradicting the existing lazy first-use contract. They now request activation
explicitly and also check that Data does not activate it automatically. The original
ownership, selection, retirement and timeout assertions remain. These corrections
pass in qualification 5. The Build rename retains the desktop `/deploy` route,
serialized enum and subsystem identities. Qualification 4's packaged MCP check
passes against the actual loopback server on port 56208. The desktop flow aggregate
then deadlocks during Auth-pane shutdown: the EDT holds the graph monitor while
waiting for the auth session; a reader factory holds the session monitor while
waiting for the graph. The Java thread dump explicitly identifies that cycle
(`bestbuds-full4-desktop-threads-2.txt`). The affected test worker was terminated
after capturing the trace, producing exit 143 for that worker. Auth reader creation
and cancellation now run outside the session monitor; graph-owned readers refuse
late acquisition after their owner starts closing. A bounded concurrent regression
checks completion, no late query and exactly-once release. Both concurrency
regressions pass in qualification 5; no security assertion or timeout was weakened.
The full desktop flows finish but still fail other assertions.

The real generated report contains 3,350 nodes and 7,548 wires. Directional local
profiling measures ELK layout medians of 127,809 ms with its additional model-order
override and 1,312 ms after removing that override. All nodes and edges remain;
the existing sorted inputs and random seed remain. The comparison uses the same
report, Java 25 and a 2 GiB heap; it is not a controlled before/after build-time
claim. Raw timings, report identity, both JFR captures and the shared performance
report are in `build-layout-directional-comparison.json`, `build-layout-baseline.jfr`,
`build-layout-none.jfr` and `build-layout-performance-report.json`. A new workspace
scale regression checks complete cards/edges, finite geometry, repeated layout
determinism and the 10-second layout budget. Qualification 5's workspace-sized
fixture reports 3,413.4 ms. The current Blueprint owner checks pass in
`hangar-build-owner-qualification-2.log`.
The repeatable workspace-sized fixture now contains nested project paths to
exercise the Build projection's grouping. It is separate from the real-report
directional profile above and remains subject to the same layout budget.

The real Build selection flow exposes a separate rendering failure: Compose cannot
measure a 126,112 × 178,993-pixel frame. The shared renderer now keeps the world
geometry but measures a viewport-bounded header and draws the full frame body
outside it. The large-frame pixel regression passes in owner qualification 2,
including a distant card and a full frame background while the header is offscreen.
The real Build selection flow remains pending in focused repair run 4.

The direct-definition admission regression now observes automatic foreground-run
drawer opening instead of toggling Runs after a separate snapshot check. The
definition rejection, event and zero-process-start assertions remain; focused
repair run 4 qualifies this fixture correction.

The remaining 35 tracked Webpack configuration fragments are removed after
checking their identical generated contents. Current libraries emit ESM and the
Vite browser adapter does not consume them. Generated Kotlin package manifests
have no Webpack entries. Evidence: `obsolete-webpack-fragments-removal.json`.

The actual BestBuds website qualification passes with receipt
`20261011-004710-bestbuds`: 368 tasks in 1m 12s, including Kotlin producers,
native TypeScript project-reference checking and the normal Vite client/Worker
bundle. All remaining website sources participate after removal of the unused
graph experiment and its compiler exclusion. The transport completes, but copies
92,848 paths because its old task-name heuristic selects all artifact families.
Dependeasy now declares JavaScript entrypoint families in its existing build-layout
report, and the transport consumes that metadata. Nine local classification checks
pass; the Gradle/configuration-cache and scoped-transfer qualifications are pending.

Manna qualification 22 passes with receipt `20261011-005227-manna`: 329 tasks in
2m 15s and successful transfer of 15,001 paths. It freshly runs 758 API tests in
42 files and 796 browser tests in 99 files, with both native TypeScript checks,
Kotlin producers, Pagefind and the normal production Vite client/Worker bundle.
The Gradle test reports in this transfer are reused and are not counted as fresh
Kotlin tests. Evidence: `manna-native-types-consumers-22.log` and
`20261011-005227-manna-fresh-test-results.json`.

Source visibility checks caught Fastlane helpers hidden by the generated `build`
ignore rule on this Mac. The consumer helper is now `domains/ios/artifacts` and
the shared Dependeasy helper is `fastlane/apple/Xcode`; both are visible to Git.
The existing release contracts pass 15 tests and 101 assertions after both moves
(`fastlane-ios-source-reproducibility-2.log`). No signing or upload is performed.

IntelliJ's composite model contains BestBuds, Manna and Reaktor with a single
Dependeasy build. A fresh actual IDEA 2026.2.3 import now succeeds in 2m 26s after
saving the project, restarting the stalled IDE and relinking only the BestBuds
root. Its saved Gradle configuration declares one linked root and one each of
Manna, Reaktor and Dependeasy; the removed prototype is absent. Evidence:
`intellij-composite-import-success-1.json` and `intellij-gradle-after-relink.xml`.
Earlier attempts threw in the Android model importer and a JavaScript parser;
those failures are retained in the diagnostic logs. This qualifies the actual
import, not every product task run through the IDE. Existing C++ plugin dependency
conflicts remain separate from the Gradle import.

Manna full qualification 19 completes Android and all four Apple SDK framework
links, with 519 fresh Kotlin tests in 102 suites and all 758 API tests passing.
Its browser suite passes 794 of 796 tests; the recommendation adapter incorrectly
calls a Kotlin singleton as a static class. The thin adapter now uses the generated
`getInstance()` API. Qualification 20 passes all 796 browser tests, type checking
and the production web bundle, but two assignment API fixtures fail after 22:00
because they assume immediate delivery during the default quiet-hours window.
Those fixtures now explicitly disable quiet hours while retaining the daily cap;
the dedicated attention suite continues to verify the default quiet-hours policy.
Qualification 21 passes all 758 API tests in 42 files (`manna-assignments-reproducibility-check-21.log`),
receipt `20261010-221647-manna`: 178 tasks in 29s and a successful transfer.
Receipt `20261010-220930-manna`
records exit 1, 333 tasks in 2m 54s and a successful transfer of 5,649 paths.
The fixture correction adds no production API, state or dependency. Receipt `20261010-213912-manna`
records exit 1, 2,645 tasks in 19m 42s and a successful artifact transfer. Evidence:
`20261010-213912-manna-fresh-test-results.json` and
`manna-domain-recommendation-full-19-resume.log`.

CMake kernel consolidation qualification 1 passes host interop, TypeScript verification,
all three Android ABIs and both Apple SDK builds. Its owner suite passes 50 of 51
tests; the new prefab fixture incorrectly reads a mapped task output before its
producer executes. The fixture now checks tracked content and the producer
dependency at configuration time; actual CMake execution checks the resolved
arguments. Receipt `20261010-220540-reaktor` records
exit 1, 171 tasks in 2m 37s and a successful transfer. Native orchestration now
delegates dependency, prefab and tool-source preparation to small internal
functions. Extracted prefab contents are tracked inputs, C++23 comes through the
declared task arguments, and unrelated Kotlin language pins are excluded from
native source snapshots. Qualification 2 passes the 51 owner tests but plugin
validation finds three task types without caching declarations. Cinterop definitions
embed absolute paths and dependency verification has no output artifact; both now
declare those limits. Protobuf generation declares content-based tool/source inputs
and is cacheable. The pinned protoc/gRPC tools generate the same two files from
different source/output directories, with no embedded host paths
(`protobuf-relocation-1.json`). Qualification 3 passes the owner tests, plugin
validation, real Protobuf generator, host/TypeScript checks, all three Android ABIs
and both Apple SDK CMake builds. Receipt `20261010-223134-reaktor`: 173 tasks in
2m 44s, 171 executed, two up-to-date and 28,834 successfully transferred paths.
Its 27 fresh owner suites contain 51 tests with zero failures, errors or skips
(`20261010-223134-reaktor-fresh-test-results.json`). The Reaktor full pass above
precedes these edits.

The post-removal source inventory contains 60 consumer build scripts: 4,694 HEAD
lines and 2,339 current lines, each with one Dependeasy block. Dependeasy has 162
Kotlin implementation files totaling 6,625 lines, with a maximum of 181 lines per
file. No owned CocoaPods file, npm/Yarn lockfile or version TOML is present.
Evidence: `declarative-build-tab-static-audit.json`. These are source
measurements, not a runtime performance or cyclomatic-complexity benchmark.

The earlier domain-kernel follow-up passes in `declarative-kernels-qualification-10.log`:
38 fresh owner tests, actual web/native consumers, 349 selected tasks, 1m 9s.
The export fixture exercises a real cyclic pnpm dependency link. Kotlin export
outputs exclude `node_modules`, while a separately checked relative dependency
context is preserved. This fixes recursive Gradle directory snapshotting rather
than masking it with a larger heap.

`declarative-owner-lock-final.log` subsequently passed 39 fresh owner tests in
1m 24s, including the explicit lock-update/frozen-install regression. Manna's
Gradle-owned lock update also passed (`manna-domain-lock-gradle-1.log`, 31 seconds).
Its copied lockfile contains the new Kotlin domain and the current Cloudflare
Vitest plugin; installation remains frozen in ordinary builds.

The later full Reaktor build (`declarative-reaktor-build-final.log`) failed on
package output ownership, C++ reference discovery and one stale source-seal fixture.
The targeted consumer rerun (`declarative-input-ownership-qualification-2.log`)
passes 278 C++/tooling JVM tests, web package checks, host C++/TypeScript interop and
all four affected Kotlin exports. Its owner suite has one failure out of 40:
an unchanged cyclic dependency export is rebuilt. Qualification 4
(`declarative-input-ownership-qualification-4.log`) passes 44 fresh owner tests in
23 suites, host interop and all four affected exports. Export outputs now derive
stable paths from compiler inputs, exclude dependency links during copy, and prune
obsolete compiler files. Cyclic and dangling links, deletion recovery and
configuration-cache reuse are covered. Incomplete registry packages repair from
the frozen lock only when installed metadata is missing. Lazy Apple source-set
configuration also passes its new regression. Qualification 5 adds the export
catalog and generated-asset ownership changes and its Gradle run passes in
3m 6s with 45 fresh tests in 24 suites, zero failures or errors. Its first transfer
ran out of local disk space; a scoped transfer resume succeeds after retaining
current artifacts and removing superseded successful snapshots. Evidence:
`20261010-173356-reaktor-fresh-test-results.json` and
`declarative-input-ownership-transfer-5.log`.

The full Reaktor sweep now passes (`declarative-reaktor-build-final-3.log`, receipt
`20261010-170314-reaktor`): 4,841 tasks in 28m 34s, with 3,654 executed,
440 from cache and 747 up-to-date. Artifact transfer also succeeds. Its run window
contains 2,693 fresh tests in 484 suites, zero failures or errors and 18 skips.
This qualifies regeneration of the untracked compiler exports; it precedes the
generated-asset and final Graph Port adapter changes. Evidence:
`20261010-170314-reaktor-fresh-test-results.json`.

The BestBuds follow-up passes in `declarative-product-qualification-5.log`: server
`bootJar`, packaged desktop database runtime probe, kernel/CLI/server tests,
arm64 Debug framework, generated Dagger/aliases and graph. Its 534 selected tasks
complete in 6m 36s. The receipt's fresh XML contains 250 tests, zero failures and
12 skips; reused reports are excluded. The full product suites remain a separate
qualification. `declarative-qualified-test-results.json` records the run windows.

Ten JavaScript kernel tests pass locally, including shared asset delivery, development-only fixture isolation and DevTools opt-in. Static inventory covers 74 Gradle files. The 62 consumer build scripts,
including Manna's new domain module, shrink from 4819 HEAD lines to
2483 current lines. Settings and diagnostic init scripts are recorded
separately. Every consumer has exactly one Dependeasy block; its bootstrap cannot
apply itself. The 156 Kotlin implementation files have a maximum of 181 lines. No owned
Podfile, podspec, npm/Yarn lockfile or version TOML remains. Evidence:
`declarative-static-audit.json`, `declarative-script-counts.json` and
`declarative-owner-layout.json`.

The updated source inventory excludes Dependeasy's bootstrap build from the
consumer count: 61 consumer scripts, 4,751 HEAD lines and 2,366 current lines.
Every consumer has exactly one Dependeasy block. The owner has 159 Kotlin
implementation files totaling 6,608 lines, with a maximum of 181 lines per file.
No owned CocoaPods file, npm/Yarn lockfile or version TOML is present. Evidence:
`declarative-current-static-audit.json`. These measurements include concurrent
changes and do not measure runtime cyclomatic complexity.

Manna's requested full Kotlin product migration is additional work. The initial
inventory counts 16,283 shared Kotlin lines and 81,261 browser/edge/API TypeScript
lines, excluding tests. Record identifiers and review-plan migration now live in
a Compose-free shared Kotlin domain, with a transport adapter for the browser.
Shared wire records, attribution and work-session timeline rules have followed.
These slices are undergoing native/browser/API qualification; they do not establish
completion of the wider product migration. Native time accounting now follows the
API's overlap, active-interval and future-time rules, with shared regression cases.

Fresh Xcode qualification caught a missing export of the Kotlin Firebase runtime
in BestBuds. Its core dependency and framework export are now explicit. Subsequent
Apple checks found Swift async/error conformance mismatches in the Google Sign-In,
Firebase Messaging and Crashlytics SDK adapters; those adapters now use the imported
signatures. Manna's cross-module DTO smart-cast was also corrected. Both unsigned
hosts are being rechecked. The fresh Manna Debug host caught two missing datetime
extension imports in its new calendar kernel; those imports are corrected and
unsigned Debug qualification 5 passes (`declarative-manna-xcode-debug-5.log`).
BestBuds Debug qualification 4 reaches the final app link but fails because archive
discovery includes an obsolete Hermes archive with a different JSI ABI. CMake now
records the configured root target and its static build dependencies through its
File API. Kotlin cinterop consumes those manifests and actual archive inputs; old
files on disk are not declarations. Qualification 7 passes 46 fresh owner tests in
25 suites, zero failures or errors, plus host interop and both native definition
generators (`declarative-native-library-ownership-7.log`, receipt
`20261010-180319-reaktor`, 2m 5s). Its actual definition excludes the orphan Hermes
archive. BestBuds unsigned Debug qualification 5 then passes the final app link
(`declarative-bestbuds-xcode-debug-5.log`). CMake's target dependencies represent
the build graph; imported target artifacts still require explicit adapter declarations.
Earlier
Apple passes below precede this SDK boundary change and do not qualify it.

The first current Boot image build compiled its JAR but failed while the worker's
Docker credential helper tried to open its locked login keychain. Public builder
and runtime images were pulled with a scoped credential-free Docker configuration;
the user's Docker credentials were preserved. Both images are pinned by digest,
and the common buildpack defaults now reuse cached images. The actual image then
builds successfully in 2m 49s (`declarative-bestbuds-image-final-4.log`, receipt
`20261010-165038-bestbuds`). A probe through its buildpack launcher reports
OpenJDK 25.0.4+9-LTS on the worker's Docker VM. The Linux amd64 image is 579,691,204
bytes. Evidence: `declarative-bestbuds-image-runtime-4.log` and
`declarative-bestbuds-image-results-4.json`. It was neither pushed nor deployed;
this runtime probe does not qualify production application startup.

Manna's first domain full build passes 190 fresh JVM/Android tests but fails five
JavaScript tasks on incomplete registry entries and dangling package contexts.
Qualification 5 reaches 360 fresh native/Node tests with one JS time-zone failure;
the broader web sweep reports 652 passing tests and 10 failing tests across 18
failing files. It also catches one journal buffer type mismatch and implicit
generated-doc input consumption. Time-zone initialization moves with its domain,
Graph Port now exposes source-set-aligned package subpaths, and generated assets
use a separate producer declaration. Browser fixtures explicitly select their
simulated capabilities after Node 24 adds native Web Locks. These corrections and
the media retention, calendar and automation domains are undergoing qualification.
Qualification 7 passes 196 fresh JVM, Android and Node tests in 36 suites, zero
failures or errors, including the new common calendar, automation and cron rules.
It fails on source-set-relative Graph Port paths and worker secret binding types;
these are corrected for qualification 8. Qualification 9 passes 404 fresh Kotlin
tests in 74 suites, zero failures or errors, including the recurrence grammar and
lazy pattern enumeration. Qualification 10 passes all 796 browser/edge tests in
99 files, including the MCP schema snapshot with hash `5c7290c7cfaf` and eleven
stricter date-time patterns. It exposes pnpm symlink type resolution and one stale
API entry assertion. Qualification 11 passes web type checking and all 758 API
tests in 42 files, including the relocated catalogue server tests in workerd. Its
browser check fails four imports of a stylesheet omitted by the new typed Graph
Port exports; that asset export is corrected. The full build in qualification 12 completes all Kotlin, native/framework and
Android compilation and lint stages, plus all 796 browser/edge and 758 API tests.
It records 456 fresh Kotlin tests in 85 suites, zero failures or errors. The one
failure is production CSS resolution of a stale Graph Port source path, corrected
for qualification 13. It takes 21m 46s and transfers reports successfully (receipt
`20261010-182738-manna`); this is not a passing root build.
Qualification 13 also checks shared asset serving, the native legacy-preview adapter,
schedule validation, future editing and immutable revision checks. The former
recurrence implementation is reduced to 233 lines of transport contracts and adapters;
its policy lives in small common Kotlin files. Native descriptions and revision
numbers consume that same kernel. These pending runs do not establish full Kotlin
product migration. The performance module’s owned C++ now follows source-set
layout and its six-line CMake declaration uses the shared C++23 API; owner JavaScript checks and actual Android/device/simulator-SDK libraries pass in
qualification 8 (`declarative-native-source-layout-8.log`, receipt
`20261010-185327-reaktor`): 202 tasks in 32s, ten JavaScript tests passing. A
subsequent check caught that the compilation-database setting was function-local;
it now reaches the caller’s CMake scope. Hermes host flags are supplied only to
Hermes consumers. Qualification 9 passes on the worker (receipt
`20261010-190011-reaktor`): 205 tasks in 1m 38s, 66 fresh JVM tests in 28 suites
with zero failures or errors, plus all ten JavaScript kernel tests. Actual artifacts
for all three Android ABIs and both Apple SDKs have C++23 compilation databases;
paths, sizes and SHA-256 values are recorded in
`declarative-native-source-layout-9-artifacts.json`. The transfer exits successfully.

The generic worker export-catalog fixture and existing scoped agent-distribution
fixture pass locally. Custom export directories and relative context links
transfer without traversing dependency stores. Metadata-only pulls exclude compiler
output; Boot image requests with graph metadata retain JVM scope. Catalog path
escapes fail before copying. The actual owner transfer resume copies 6,052 paths
through the web scope. Manna's explicit lock update 4 copies its lock plus bounded
metadata (nine paths); ordinary installation stays frozen. Unchanged artifacts
share inodes with a prior successful snapshot, while changed files preserve prior
evidence. Both local transfer fixtures pass with the new ownership checks.

Source tracking no longer includes 369 compiler export files totaling 165,633,582
bytes across Reaktor and BestBuds. Their staged removals preserve local outputs;
Gradle owns regeneration. This reduces tracked source and worker source-sync input,
not the measured physical disk allocation. Evidence:
`generated-export-tracking-cleanup.json`. The fresh Reaktor root sweep qualifies
regeneration after this ownership change.

Three more superseded successful snapshots were removed after retaining current
artifacts, sources, all logs and failure receipts. Free space increased by
8,884,977,664 bytes during that bounded cleanup. Evidence:
`declarative-snapshot-cleanup.json`. These are observed disk measurements, not a
controlled performance benchmark.

A subsequent bounded cleanup removes eight superseded successful snapshots and
recovers 27,484,594,176 bytes of observed free space. Sources, logs, receipts,
failure reports and current artifacts remain. Evidence:
`declarative-snapshot-cleanup-2.json`.

Fourteen further superseded successful snapshots were removed while retaining
current/recovery artifacts, all failed snapshots, logs and receipts. Observed free
space increased by 29,055,791,104 bytes. The bounded selection and retained run IDs
are recorded in `declarative-snapshot-cleanup-3.json`; no source or active cache
was removed.

## Shared configuration

[`ToolchainVersions.kt`](src/main/kotlin/dev/shibasis/dependeasy/toolchain/ToolchainVersions.kt)
is the version authority. There is no TOML version catalogue or generated parallel
version file. It declares Kotlin 2.4.21, Gradle 9.7.0 with a distribution SHA-256,
AGP 9.1.1, Compose 1.12.1, KSP 2.3.4, Android SDK 37, NDK 30.0.16248370,
Android CMake 4.0.3, C++23, Swift language mode 6, Node 24.21.0 LTS,
pnpm 12.10.1, TypeScript native CLI 7.0.2, TypeScript compiler API 6.0.3,
and Karakum 1.0.0-alpha.112. AGP now follows IntelliJ IDEA 2026.2's supported
9.1 series; earlier results qualified 9.3.3, while the current full sweeps qualify
the revised selection. Both Gradle workspace membership and runtime discovery
use safe YAML parsing, with SnakeYAML 2.7 pinned in the same Kotlin authority.
Node and pnpm are installed into checksummed, host-specific Gradle caches.
The shared UI module now selects Coil 3.6.3 through one Kotlin library pin to align
its Skiko dependency with Compose 1.12; Linux JVM, Manna root, BestBuds Android artifacts and unsigned Apple host qualification pass.

The Mac/worker Gradle budget is 8 GiB. A fresh BestBuds full build saturated the
previous 6 GiB Mac heap in Kotlin/Native optimization; the identified build was
stopped and restarted with the corrected budget. The restarted compiler progressed
to LLVM/linking with heap headroom. This is diagnostic evidence, not a controlled
speed comparison.

Both Macs have Java 25.0.2 and Xcode 26.6 / Swift 6.3.3. Java 25 runs builds and
product JVMs; Android emits Java/Kotlin 21 bytecode. Dependeasy and the explicitly
compatible framework tooling modules retain 21 bytecode while compiling with 25.
Swift 6.4 is the recommended compiler in the contract; it has not replaced the
compiler bundled with Xcode. The Linux Java 25 build image is pinned by OCI digest;
its JDK patch level differs from the Macs, and apt repositories remain mutable.

The normal wrappers serialize workspace synchronization, execution, and artifact
transfer, and choose this Mac or M1 according to load. Container builds use Dagger
through the worker Docker context. The Linux adapter now budgets a single 2 GiB
Gradle/compiler process and one worker instead of inheriting the Mac compiler heap.
Gradle file watching is disabled in ephemeral containers. The adapter persists
the content-addressed pnpm store beside its shared Gradle and Kotlin/Native caches. The generated workspace and Linux JVM suites have exercised these choices in the worker Docker VM. Static TypeScript validation alone is insufficient.

## Worker experiment reconciliation

The recovered `worker/apple-migration-20261005` branches remain available locally:

| Repository | Experiment commit |
| --- | --- |
| Reaktor | `e73231eb7f020badb1d11da87985939cd0b6d98c` |
| BestBuds | `d167b8f2040e84268093e0500e33c472c91667df` |
| Manna | `5b354cfd2a37fb47c980f1638582103ce4a00e44` |

Their explicit framework/direct Xcode approach was adapted to current sources.
Owned Podfiles, podspecs, and CocoaPods integration were removed. Shared Swift SDK
bridges use exact SwiftPM resolutions and direct Kotlin framework embedding.
No Kotlin experimental SwiftPM importer is required. Once connectivity returned,
worker experiment heads were rechecked and matched these commits. Extra worker
sources, evidence, and the old Hermes checkout were backed up before synchronization.

## Earlier build sweep evidence

Logs are retained in `/Users/ovd/dev/.remote-dev/build-layer-migration-20261009`.
Each result below refers to its named run, rather than old XML left in a build tree.

| Check | Result | Evidence |
| --- | --- | --- |
| Dependeasy foundation and workspace | **31 tests / 17 suites pass**, 37 seconds | `dependeasy-owner-workspace-final-5.log`, `dependeasy-owner-workspace-results-final.json`; lazy DAG/artifacts, pnpm/SwiftPM/CMake, configuration-cache reuse, tool/source pins, bounded cleanup, runtime helpers, Dagger engine drift, generated aliases and delivery metadata, package inventory, included-build ownership, export-context preservation and generated web asset ordering/incrementality. All suites are fresh, dated 00:04–00:05 UTC |
| BestBuds artifacts | **Pass**, 17m 7s | `bestbuds-artifacts-elegance-final.log`; debug/release APKs, release AAB, iOS arm64 release framework, desktop JAR, server JAR; `-Pbestbuds.devTools=false` |
| BestBuds app and kernel JVM tests | **262 and 234 pass**, 11 kernel skips | `bestbuds-test-elegance-final.log`; Node and desktop qualification failed separately in this run |
| BestBuds app Node tests | **286 tests / 35 suites pass** | `bestbuds-web-node-context-final-3.log`, `bestbuds-node-context-results-final.json`; fresh suites dated 23:30 UTC qualify per-file Coil/Skiko runtime resolution and actual Kotlin export dependency context |
| BestBuds JavaScript targets | **Pass**, 2m 23s / 382 tasks | `bestbuds-web-node-context-final-3.log`; frozen installation, app library/Node tests, Company Web, documentation types/site, app browser and design prototype Vite bundles; 146 executed and 236 up-to-date |
| Actual worker bundle resolution | **Config Server and app browser dry runs pass** | `config-worker-context-dryrun-final-2.log`, `app-browser-context-dryrun-final-2.log`; managed Node/pnpm, real compiler-package dependency links, shared immutable Wrangler configuration and current browser assets. These checks do not publish |
| Manna root build | **Pass**, 12m 16s / 2441 tasks | `manna-build-workspace-final-3.log`; 2028 executed, 321 from cache and 92 up-to-date. Android, device/simulator Apple frameworks and workspace graph export pass with the latest owner changes. Web and product-wide configuration-cache qualification remain separate |
| Reaktor root | **Pass**, 8m 39s / 4724 tasks | `reaktor-build-workspace-final-3.log`; 2508 executed, 263 from cache and 1953 up-to-date. Location debug/release lint, JVM/native checks and graph export pass. The earlier full export stalled on execution-time dependency discovery; configuration-time snapshots remove that deadlock |
| BestBuds Node/catalog/architecture | **Pass**, 3m 4s | `bestbuds-build-contracts-final.log`; Node distribution output ownership, catalog and architecture guard checks |
| Unsigned Apple hosts | **BestBuds and Manna pass** | Latest BestBuds Release host passes in `bestbuds-xcode-release-context-final-2.log`; previous Manna `manna-xcode-*` final logs; direct frameworks and exact SwiftPM SDK bridges. Signed archives remain separate |
| Hangar Build source | **Three tests pass on M1**, 1m 34s | `hangar-build-source-worker-final-2.log`, `hangar-build-source-worker-results-final.json`; fresh worker run `20261010-060231-bestbuds` qualifies synchronized source, declared schema, included-build scopes, task identity/deduplication and cross-build edges. The source/test package is `buildgraph`. Earlier Mac Android debug/lint also passes |
| Fastlane launcher and release domains | **Version/lanes checks and 15 release tests pass** | `fastlane-owner-version-final.log` (2.237.0), `fastlane-domains-lanes-final.log`, `fastlane-domains-release-final-4.log` (101 assertions); shared frozen launcher/artifact helpers, production Gradle web entrypoint, exact SwiftPM uploader directory, symbol receipts and failure ordering |
| JavaScript process and worker deployment | **Six tests pass**, 1.1 seconds | `javascript-process-owner-final.log`; literal environment parsing, ordered child-only environment files, exit/launch failure propagation, signal listener cleanup, Wrangler cancellation and immutable temporary configuration |
| Worker graph/adapter transfer | **Collector/merge and transfer lifecycle fixtures pass** | `remote-workspace-outputs-fixture.log`, `remote-transfer-lifecycle-fixture.log`; graph reports transfer on failed builds, Dagger and generated alias reports transfer on success, cache/path exclusions remain enforced, alias merging preserves local authored edits, dependencies, metadata and permissions. Actual worker generation passes in 44 seconds. `worker-workspace-output-transfer-recovery-final.log` and receipt `20261010-045806-bestbuds` qualify transfer of 81,326 paths with no copy error, including graph/Dagger/alias outputs and relative export links. A temporary file-list lifetime bug was corrected and empty transfers now fail |
| Packaged agent distribution transfer | **M1 build and scoped resume pass**, 13 seconds / 40 build tasks, 244 transferred paths | `remote-agent-distribution-worker-final-2.log`, `remote-agent-distribution-worker-resume-final-2.log`, `remote-agent-distribution-results-final.json`; receipt `20261010-061051-reaktor` returns exit 0 without copy error. All 240 delivered libraries match the worker snapshot by SHA-256, only one Conductor jar remains and the actual launcher selects Java 25.0.2. A stale library canary is removed and unlisted resumed-snapshot output never enters the checkout. `remote-agent-distribution-fixture.log` also covers mixed/all/JVM requests, preserved launchers, Android scoping, failed builds and unselected cached distributions |
| Delivered Conductor package | **Copied-package Echo smoke passes**, zero model executions | `/Users/ovd/dev/.remote-dev/engineering-tools-invocation-20261010.json` and `engineering-tools-packaged-helper-20261010.json`; independently verified against delivered worker run `20261010-061051-reaktor`, outside the checkout. Help, info, submit/read/wait, replay, context, memory, runs and orderly owner shutdown pass, with one retained Echo run. The first invocation/startup slice is qualified; wider Gate 1, A2A and unified Hangar ownership remain pending |
| Fresh BestBuds full root build | **Fails**, 53m 1s / 3756 tasks | `bestbuds-build-workspace-final-2.log`; eight engine failures, desktop behavior failures and the desktop MCP backend contract remain. It also exposed per-file Coil/Skiko Node resolution and generated deployment-alias discovery regressions; both build-layer fixes now pass targeted qualification. 2423 tasks executed, 356 from cache and 977 up-to-date |
| Linux SDK provisioning | **Pass** on pinned amd64 image | `dagger-linux-framework-final-8.log`; current Android CLI installs pinned SDK/NDK/CMake packages; that run later lost the Gradle daemon |
| Dependency boundaries | **Linux integration fixture passes** | `dependeasy-boundary-linux-final.log`; lazy resolution, project coordinates/module suffixes, configuration-cache reuse, forbidden dependencies fail. Included in the passing full Mac owner suite |
| Packaged runtime helpers | **Two Linux tests pass**, 3m 4s | `dependeasy-packaged-runtime-linux-final.log`; executable launcher restoration, optional base archive, configuration-cache reuse, jar/access-flag application archive identity. Actual Mac DMG passes in `bestbuds-desktop-dmg-final.log` (3m 46s) |
| Linux JVM container suite | **Pass**, 8m 22s / 209 executed tasks with Coil alignment | `dagger-linux-framework-final-13.log`; Reaktor core, graph, performance suites. Frozen pnpm installation completes in 4.8 seconds with all 465 packages reused. Run 11 lost its daemon during cold pnpm installation; the VM kernel confirmed an OOM kill. Run 12 corrected the compiler strategy through Gradle's project-property system and also passed |
| Karakum generation | **Pass**, 523 nodes covered, zero uncovered | `karakum-cloudflare-final.log`; existing Cloudflare schema, TypeScript 6 compiler API, outputs isolated from production sources |
| Dagger declarations | **Generated worker-VM workspace adapter suite passes**, 5m 37s | `dependeasy-workspace-linux-final-5.log`; workspace, included-build, export-context, pnpm and CMake fixtures. SwiftPM fixture explicitly skips when Swift is absent from the Linux SDK image; Mac SwiftPM qualification passes in the full owner suite. Final generation with cache restoration is recorded separately |
| Dagger metadata and environment variants | **Two Linux tests pass**, 2m 53s | `dependeasy-generated-bindings-linux-final.log`; generated engine metadata, drift detection, declared environment variants, secret-only single-environment entrypoints, generated worker binding/transfer metadata and configuration-cache reuse |
| Dagger cache restoration | **Two worker-VM fixtures pass**, 2m 55s; **Mac fixtures pass**, 5 seconds | `dependeasy-dagger-cache-layout-linux-final-2.log`, `dependeasy-dagger-cache-layout-final.log`; explicit build-cache restoration after deleting the reference output, generated metadata/drift checks and configuration-cache reuse. Reference sources and metadata now have disjoint output paths |
| Workspace generation and drift verification | **Pass with configuration-cache reuse**, 389ms | `workspace-generation-delivery-final-8.log`; Dagger module/metadata, package aliases, included graph export and drift verification; one task executed and 28 up-to-date. Run 7 also restores the Dagger reference from the build cache. Checked-in generated aliases carry only task, effect, worker directory/identity and environment fields; the complete graph stays in its export |
| Generated web assets | **Linux fixture passes**, 2m 51s; included in the passing full Mac suite | `dependeasy-web-generation-edge-linux-final-2.log`; producer precedes types/bundle, unchanged generated assets stay up-to-date, authored source changes rebuild consumers without reinstalling packages, configuration cache is reused. Empty parent directories do not invalidate source snapshots |
| Generated Dagger TypeScript | **Pass** | `dagger-generated-typescript-final.log`; actual generated adapter and shared runtime compile with the pinned TypeScript toolchain |
| Generated deployment discovery | **Nine Reaktor discovery/seal tests pass on M1**, 26 seconds | `reaktor-discovery-worker-source-final-2.log`, `reaktor-discovery-worker-source-results-final.json`; fresh worker run `20261010-060006-reaktor` qualifies synchronized source, generated worker bindings, definition seals, path/command rejection and production approval semantics. The helper lives in the source package `delivery` |
| BestBuds generated deployment binding | **Targeted catalog E2E passes**, 1m 6s | `bestbuds-gradle-deploy-catalog-final-2.log`; Gradle-owned worker aliases bind both dev and production while retaining review/approval gates. This closes the additional deployment-discovery failure exposed by the full desktop suite |
| Mixed build execution and graph export | **Two Linux fixtures pass**, 2m 51s | `dependeasy-graph-configuration-linux-final-2.log`; task dependency discovery now snapshots during selected-task configuration, avoiding mutable Gradle model access under execution locks. Included-build execution and export coexist with configuration-cache reuse. Mac fixtures also pass in `dependeasy-delivery-metadata-mac-final.log` (8 seconds); Reaktor and Manna full-root/export follow-ups also pass |
| Documentation site | **Build, types, content and browser checks pass; deployed** | `docs-vite-*` evidence; 3971 assets at https://reaktor.build, version `d6e7b46d-b46b-47c1-8dd1-cac850b4dd52` |
| Design prototype | Prior **7 browser tests pass**; fresh bundle budget fails | `design-vite-budget-final-3.log`; 20,115,526 client bytes against 20,000,000. Vite bundling passes; budget is unchanged |
| Manna web | **Pass**, 20 seconds / 289 tasks | `manna-web-workspace-final-7.log`; frozen pnpm, Kotlin exports, Pagefind, TypeScript, Vite and graph export. The previously missing nested graph functions are now present in the shared checkout. Pagefind is a declared typecheck prerequisite; source snapshots ignore empty parent directories. Default Vite bundling takes 2.08 seconds with visualization disabled, compared with 18.92 seconds in run 6, where Vite attributed 16.7 seconds to that plugin. This isolates removed work, not a controlled end-to-end speed comparison |

Qualification exposed a new Reaktor JS module missing from the frozen workspace
lockfile. The lockfile was regenerated with pinned pnpm; frozen installation remains
enforced. Kotlin-generated packages are pruned against compiler metadata to prevent
obsolete packages from changing workspace installation. Generated exports are not
hand edited. Newly selected module manifests must be represented in the lockfile.

A later worker-only discovery failure exposed source packages named `build`, which
Git ignored and the worker/Dagger source filters treated as build output. The
generated deployment helper moved to `tooling.delivery`, and Hangar's reader and
tests moved to `workbench.buildgraph`; all consumers were updated. The actual
worker source inventory includes all three files (`build-source-inventory-final.log`).
The fresh discovery and Hangar suites above pass. Both generated Dagger workspace
captures also include the implementations in the worker Docker VM, recorded in
`dagger-discovery-source-capture-final.log` and `dagger-hangar-source-capture-final.log`.
This correction does not establish another full-root build qualification.

Earlier native qualification built Hermes host tools, FlexBuffer/FFI CMake and
cinterop, Android NDK outputs, and Apple framework slices on both Macs. Three
unsigned Xcode hosts passed during that earlier phase; the current scope retains
BestBuds and Manna, with latest source qualification recorded separately above.
No emulator or simulator was started. Simulator framework slices were compiled only.

## Simplification, storage, and reproducibility

Measured line counts against the repositories' then-current HEAD:

| Script | Before | Current |
| --- | ---: | ---: |
| Reaktor root | 99 | 19 |
| BestBuds settings | 138 | 30 |
| BestBuds root | 128 | 64 |
| BestBuds app | 383 | 53 |
| BestBuds engine | 162 | 79 |
| BestBuds desktop | 352 | 86 |
| BestBuds Android host | 82 | 60 |
| BestBuds server | 142 | 35 |

BestBuds Fastlane composition is now 30 lines (from over 1500), with 13 product
domains, each at most 233 lines. Shared filesystem/artifact helpers and the frozen
launcher live in Dependeasy. The legacy Crashlytics uploader path was removed;
archives use the same explicit release DerivedData directory as the SwiftPM uploader.

BestBuds verification and delivery declarations are inline in the root Dependeasy
block; the two applied scripts were removed. Root line counts include those
declarations rather than hiding them in separately applied files.
The focused `verifyDagger generatePackageScripts buildGraph` check passes in 16s
(32 tasks, one executed and 31 up-to-date). All 406 exported targets and 2663 Gradle
task records remain identical, as do the generated Dagger and deployment aliases.
Evidence: `bestbuds-inline-root-gradle.log` and `inline-root-results.json`.

These comparisons include existing concurrent changes; they are not exclusively
attributable to this session. Dependeasy currently has 149 Kotlin implementation
files in domain packages, with a largest file of 181 lines. The HLD and remaining
domain work are in [DESIGN.md](DESIGN.md). Native execution, workspace installation,
generation, provenance, runtime retention, and bounded cleanup moved out of product
scripts into typed tasks and small domain APIs. Ordinary task providers remain the
escape hatch for exceptional cases.
Seven repeated dependency-boundary checks now use one typed task. Their forbidden
groups/modules and messages remain in the product scripts; each declaration is
five or six lines instead of fourteen to seventeen. Configuration-cache reuse and
project dependency metadata pass a dedicated Linux integration fixture.
Packaged Java launcher restoration and class-sharing archive identity also moved
to the desktop domain; their Linux fixture passes and the actual Mac DMG passes.

The documentation workspace migration removed approximately 2.325 GiB of obsolete
nested npm installation. A clean frozen pnpm reinstall reduced BestBuds' logical
node_modules size by a further 1,207,548 KiB (~1.15 GiB). These are logical directory
sizes, not physical APFS allocation. Earlier cleanup removed 156 old CocoaPods
framework output trees. Source backups, signing material, SDKs, and active caches
were retained. Content-addressed desktop jars use live leases and bounded cleanup.
Manna's tracked generated `stats.html` was removed. Bundle visualization is an
explicit Vite `analyze` mode, writes under ignored build reports, and does not enter
normal build input snapshots or deployment assets.
The actual analysis mode passes in `manna-vite-analysis-mode-final-2.log` and writes
a 1,962,623-byte report at `targets/appWeb/build/reports/vite/stats.html`. The subsequent
normal build passes in `manna-web-workspace-final-8.log` (6 seconds); Pagefind and
types remain up-to-date while Vite restores its ordinary production output.
The next unchanged run passes in `manna-web-workspace-final-9.log` (4 seconds),
with Pagefind, typechecking and bundling all up-to-date.
An isolated SDK installer fixture verifies reuse of the pinned `android` CLI,
installation of only missing packages, and no provisioning for a complete SDK.
The old `sdkmanager` presence check was removed to prevent redundant tool downloads.

Warm incremental runs demonstrated configuration-cache reuse and native/command
up-to-date behavior. They are not controlled before/after speed measurements.
Deterministic source identities omit timestamps by default and hash sorted logical
paths and content. Command/CMake outputs are incrementally checked; they are not
advertised as a relocatable remote cache. Gradle plans declare edges without running
backend commands.
Kotlin export dependency links are preserved by synchronization and copied by
worker artifact transfer without copying dependency caches. The remote transfer
fixtures pass. Fresh app Node tests and Config Server/app browser Wrangler dry runs
also pass against real generated package contexts.

Adapter configuration-cache fixtures pass; product-wide configuration-cache
compatibility is not established, and Manna still explicitly opts out.

Worker synchronization now prunes empty source directories without entering links,
build outputs, or dependency caches. An isolated fixture verified those boundaries.
This fixes a retired renderer package surviving as an empty directory on M1 and
causing an architecture test to disagree with the local checkout. The targeted Gradle architecture check passes in `bestbuds-build-contracts-final.log`.

Explicit `agentDist` requests now carry their complete distribution in mixed builds.
Successful transfer mirrors only the owned `lib` directory, removing obsolete jars
without deleting custom launchers under `bin`. Test-only requests do not copy a
cached distribution. Packaging-only requests collect that distribution alone and
skip unrelated build output trees; the qualified resume reduces 24,489 transferred
paths to 244. This is a path-count comparison, not an elapsed-time speedup claim.
Resumed snapshots are pruned to the current manifest before APFS cloning, so a
previous broader pull cannot replay unrelated or failed-build executable outputs.
A later independent packaging run encountered disk pressure while copying cached
Compose output; the scoped transfer fixes that unnecessary collection. Its failed
receipt was preserved by the runtime task owner. The runtime task's independent
copied-package Echo acceptance now passes against the delivered distribution,
as recorded above; it makes no actual model-execution or wider-gate claim.

## Module API rollout

All 43 active multiplatform modules use `dependeasy.module`, with common, web,
Android, Apple and JVM declarations beside their dependencies. Compose library,
application, browser and JVM plugin bundles replace repeated compiler plugin lists;
headless modules retain the base plugin. Specialized source-set hierarchies,
benchmarks, signing and packaging still use ordinary Gradle/Kotlin APIs.

Generated Kotlin objects attach their outputs to compilation, source identities
attach to JVM resources, and web export manifests own their producer ordering.
Compose compiler stability/reports and explicit JVM test discovery are shared
conventions. Test-specific dependencies are declared beside their platform.
SQLDelight and Apollo versions now have Kotlin authorities rather than duplicate
Gradle properties. Unused common Turbine dependencies and dead npm script blocks
were removed. JVM probe classpaths use a typed task with both content and absolute
paths as inputs; exported path files are deliberately not remotely cached.

For this rollout, 55 Gradle scripts went from 3722 to 3116 lines (606 removed), and
BestBuds app went from 127 to 53. These counts include inline declarations and the
specialized Gradle escape hatches. Static checks preserve all 43 modules' target
sets, namespaces and framework exports. Evidence: `module-script-counts.json` and
`module-target-parity.json`. Inactive Reaktor examples remain outside this rollout.

Dependeasy's Kotlin implementation is divided into 149 files by domain; the largest
is 181 lines. The module facade delegates to the platform adapters, and specialized
configuration retains direct Gradle access. Evidence: `module-owner-layout.json`.

The Reaktor full build and both real WebView probe classpath tasks pass in 14m 55s:
4740 tasks, 4070 executed, 505 from cache and 165 up-to-date. Fresh JVM, Android and
Node reports contain 2701 tests with no failures. Four reused suites add 38 tests;
retained reports from skipped simulator tasks are excluded. No simulator was
started. Evidence: `module-reaktor-build.log`, `module-reaktor-task-test-results.json`
and worker receipt `20261010-103347-reaktor`.

Manna's full root build passes in 18m 18s: 2417 tasks, 2001 executed, 322 from
cache and 94 up-to-date. Fresh module reports contain 336 tests in 58 suites with
no failures. Its separate `buildWeb buildGraph` run passes in 54 seconds (289 tasks),
including Pagefind, TypeScript and Vite. Evidence: `module-manna-build.log`,
`module-manna-fresh-test-results.json`, `module-manna-web-graph-final.log` and worker
receipt `20261010-104953-manna`.

The generated Dagger `reaktor-verify` entrypoint passes in the worker Docker VM:
209 selected Gradle tasks, all executed, with a 17m 50s Gradle run. The client and
engine are pinned to 0.21.8; the host's default CLI is older and was not used for
this qualification. This Linux runtime uses x86-64 for the Android tooling and
therefore runs under emulation on M1. Evidence:
`module-dagger-linux-verify-pinned.log`. Cold provisioning and emulation are part
of this run; it is not a before/after performance comparison.

Nine superseded successful local worker-output snapshots were removed after
preserving current/recovery snapshots, all logs and failure receipts. Measured free
space rose from 3,958,218,752 to 50,927,362,048 bytes during this cleanup. Source,
signing material and active caches were retained. Evidence:
`module-snapshot-cleanup.json`.

## Remaining failures and limits

* BestBuds' earlier full engine run reported eight remaining failures after native
  cleanup fixes. Qualification 9 (`declarative-bestbuds-regression-final-9.log`,
  receipt `20261010-193516-bestbuds`) now passes 21 fresh tests in six suites,
  including result export, presentation, cross-pane actions, pane parts, bootstrap
  and agent workspace interaction. Rendering/parity, first-use activation and
  first-use and pixel/parity checks pass qualification 10; workspace safety and
  composite graph checks pass qualification 12. The earlier full
  desktop run's four failing test cases include an aggregate of 65 failed headless
  YAML flows; those flows cover multiple product contracts and remain unresolved.
  Desktop MCP qualification 13 passes the packaged runtime's actual loopback
  endpoint (`declarative-bestbuds-desktop-mcp-13.log`, receipt
  `20261010-210409-bestbuds`): 360 tasks in 41s, successful artifact transfer and
  all 32 existing tool contract names retained. Qualification 11 stopped before tests because the included
  Manna build name needed an explicit alias. These full suites are not claimed green.
* The BestBuds credential guard conflicts with the repository's explicit policy of
  tracking its current private configuration. Credentials were preserved and not
  printed; the guard is not claimed green.
* AGP still uses legacy KMP Android interoperability for the existing native
  integration. New Android KMP plugin migration remains unqualified.
* Physical Android Kotlin/C++/TypeScript interop passes on the connected phone
  (`android-physical-interop-pinned-runtime.log`). Signed archives, publishing,
  Google authentication and live push/crash delivery have not been qualified. No commit or push was made
  by this task. Concurrent product work and unrelated plans were preserved.

Qualification 14 passes (`manna-domain-qualification-14.log`, receipt
`20261010-185546-manna`): 599 tasks in 1m 1s and successful artifact transfer.
Its 340 fresh Android/Node product tests in 60 suites have zero failures or errors;
the common validation/editing tests already passed on JVM and Node in qualification
13. Browser tests remain up-to-date from qualification 13’s 796 passing cases.
Both type checks, the production Vite bundle and all 758 API tests in workerd pass.
This qualifies the shared Vite assets, calendar editing, validation and historical
series adapters. The unsigned Manna iOS Debug host then passes with the current common Kotlin
schedule code (`declarative-manna-xcode-debug-6.log`). The full Manna sweep
passes in qualification 15 (`manna-domain-full-build-15.log`, receipt
`20261010-190333-manna`): 2609 tasks in 23m 57s, with successful transfer of
39510 artifact/report paths. Its 487 fresh Kotlin tests in 93 suites have no
failures, errors or skips; all 758 API tests and 796 browser tests pass, alongside
production Vite bundling, Android packaging/lint and all four Apple framework
links. Generated Dagger, package scripts, build graph and Kotlin export metadata
also pass. The newer common rhythm/history/credit extraction is being qualified
separately in run 16. These checks do not qualify signing or device behavior.

Qualification 16 passes (`manna-domain-qualification-16.log`, receipt
`20261010-192943-manna`): 599 tasks in 3m 22s, successful artifact transfer,
446 fresh Kotlin tests in 84 suites with no failures/errors/skips, all 796 browser
tests and 758 API tests. Both type checks and the production Vite bundle pass.
This qualifies historical rhythm modes, credit ownership/evidence, window targets
and labels, native history closing and the shared upcoming-occurrence projection.
The newer Apple framework links pass qualification 17
(`manna-domain-apple-links-17.log`, receipt `20261010-201714-manna`): all four
Debug/Release arm64 device/simulator frameworks, 290 tasks in 14m 5s, successful
artifact transfer. This qualifies Apple linking of the rhythm and progress slices;
no simulator was launched. The shared progress projection and native learning
adapter pass qualification 18 (`manna-domain-progress-qualification-18.log`,
receipt `20261010-203559-manna`): 599 tasks in 4m 37s, successful transfer,
456 fresh Kotlin tests in 86 suites with no failures/errors/skips, 796 browser
tests and 758 API tests. Both type checks and production Vite bundling pass.

The fourth Reaktor full sweep finishes with three Gradle input-ownership failures
(`declarative-reaktor-full-build-4.log`, receipt `20261010-193959-reaktor`,
31m 14s / 4,800 tasks). All three arise because module-root CMake file trees
overlap Vite's `dist` output. This is not a native compiler or TypeScript error.
The common CMake input kernel now uses separate native source, vendor and CMake
roots, avoiding an artificial JavaScript-to-C++ dependency. The real CMake build
fixture with an unrelated JavaScript producer passes, including incrementality and
source relocation. Security's three Android ABIs, both Apple CMake variants,
release AAR and JVM tests pass qualification 10. Its 52 fresh tests include one
owner failure: `/var` versus `/private/var` fixture identity. The fixture now uses
canonical paths for both positive and exclusion assertions. Qualification 12 passes
all 50 fresh owner tests in 27 suites with zero failures/errors/skips
(`dependeasy-native-composite-qualification-12.log`, receipt
`20261010-210059-reaktor`): 152 tasks in 1m 13s with successful artifact transfer.
This includes the composite naming/configuration-cache fixture and native input
and composition regressions. FFI's five mobile CMake variants, actual Vite
bundle and host bridge pass qualification 11 (`declarative-ffi-native-input-11.log`,
receipt `20261010-205009-reaktor`): 196 tasks in 1m 13s with successful transfer.
The latest full Reaktor sweep is qualification 5 and remains pending.

BestBuds qualification 10 contains 15 fresh tests in four suites: strict table
pixel alignment, AI pane rendering and first-use checks pass. Eight of nine
workspace safety checks pass. The remaining check's drawer helper selected no tab
when a different drawer was already open; it now explicitly opens the Runs list.
The rejection assertions remain intact. Qualification 12 passes
(`bestbuds-composite-and-safety-12.log`, receipt `20261010-205315-bestbuds`): 401
tasks in 3m 27s with successful transfer and 13 fresh tests in two suites, zero
failures/errors/skips. These are all nine workspace safety tests and all four
build graph tests. The graph's direct shared-build parent now also stays canonical
regardless of include order; the full BestBuds follow-up qualifies that extension.

BestBuds includes Manna by default for one IntelliJ composite. Both products keep
the same canonical Reaktor and Dependeasy build paths, with no BestBuds dependency
from Manna. The new `reportBuildLayout` metadata task declares composite identity
and relative directories. Worker transfer consumes it to retrieve participating
included-build outputs; its checks cover Manna ownership, narrow transfers and
invalid absolute paths. Hangar also deduplicates shared build scopes in a diamond
composite. The owner TestKit diamond fixture passes with configuration-cache
reuse. IntelliJ's recorded model has one linked Gradle root and exactly one
Manna/Reaktor/Dependeasy entry (`bestbuds-intellij-composite-model.json`). The
combined root's `projects buildGraph` passes in 23s
(`bestbuds-composite-import-1.log`, receipt `20261010-204603-bestbuds`), with a
single execution of Reaktor's graph export. The explicit `Manna` include alias
keeps CLI task paths and IntelliJ's project display consistent. The subsequent
`projects reportBuildLayout` passes in 13s
(`bestbuds-composite-model-2.log`, receipt `20261010-205817-bestbuds`); it reports
exactly four build identities and copies only eight report files. Included Manna
execution and output transfer pass qualification 3
(`bestbuds-manna-composite-build-3.log`, receipt `20261010-205857-bestbuds`):
`:Manna:domain:jvmTest buildGraph reportBuildLayout` completes in 27s / 45 tasks.
Its 59 fresh domain tests in 14 suites have no failures/errors/skips, including the
new recommendation preview cases. Worker transfer copies 2,381 Manna JVM/report
paths to the correct local checkout, alongside BestBuds and Reaktor outputs.

The local recommendation preview now composes one common Kotlin selection and
candidate kernel. Native and browser adapters preserve their clocks, receipt
identities, categories and stored DTOs. The two existing browser cases plus shared
source-identity/uncertainty and saved-source exploration regressions cover the
extraction. Manna full qualification 19 is pending; the richer server recommendation
pipeline, feature state, synchronization and presentation still require migration.

Obsolete documentation output is removed from source tracking: 696 files totaling
127,472,575 bytes from the former `build-unified` directory. Its 11 modified generated
files were backed up before removal. The unused local Docusaurus build and cache
contained another 417,516,790 bytes; authored Markdown, static assets and scripts
remain. Current generation writes to `dist/client/docs`. Fresh regeneration is
pending in `declarative-docs-output-cleanup-1.log`; evidence for the bounded cleanup
is `legacy-docs-output-cleanup.json`. These are logical file sizes, not physical
APFS allocation measurements.

## Pending qualifications after native inspection

The artifact-7 packaged app opens the renamed Build pane and its Operations,
Targets, Runs and Live tabs. Native inspection shows the generated declaration
source with 3,355 nodes and 7,560 wires. That distribution predates the composite
execution adapter. It also exposes project scopes flattened into one map frame
and duplicate-provider findings inferred from explicitly bound task result ports.
The feature kernel now groups a Build source by project scope and its diagnostic
projection omits that inapplicable inference while retaining cycle checks. The
project-frame/edge-preservation and cycle regressions await qualification. Native
keyboard filtering did not visibly update the field; no operation was run during
this inspection. Evidence: `hangar-native-build-tab-7/launch.json`.

Focused repair run 4 encounters a new `OutOfMemoryError` while the surface sweep
allocates one large contact sheet with all story images still retained. Its existing
512 MiB heap is unchanged. The report now streams all declared cells into pages of
four environment rows, with the same pixel, unique-ID, naming and keyboard-focus
assertions and full finding/count aggregation. The focused surface qualification
and subsequent full engine suite remain pending.

## Follow-up receipts after focused run 4

`20261011-012852-bestbuds` finishes with exit 1 after 11m 41s of Gradle work.
Fresh XML covers 480 tests in 150 suites, with two failures and no skips: the
surface contact-sheet heap exhaustion and the aggregated YAML flow test. All
selected Build flows, the direct-task post-approval seal regression and the
production catalog checks pass. The sole failing selected flow is
`F-CLOUD-15-deploy-view`: step 12 expects `app-service` on screen after clearing
its filter. Steps 7–10 already qualify filter selection, the referenced row and
analytics exclusion. The cleared list now contains many declared workloads and
virtualizes that row off screen. The fixture now observes the cleared selection
and navigates to both rows through existing stable-ID scrolling; exact recheck 5
is pending. It retains every filter, inspector and deploy-order assertion.

`20261011-014112-reaktor` passes the diamond BuildLayout TestKit fixture (one fresh
test). Both `buildGraph --configuration-cache` calls also produce the layout
report; the second call reuses the configuration cache. The fixture verifies
included-build deduplication and declared custom JavaScript artifact families.
The worker receipt records `web` and copies 5,991 paths. This adapter fixture does
not replace the pending actual BestBuds custom-web transfer qualification.

The removed prototype's generated-only directories are deleted on both Macs,
after checking that no source remains outside build/dependency directories.
Removed logical bytes: local 66,635,528; worker 121,968,210. These figures are
logical directory sizes, not measured physical APFS savings. Original source
backup and qualification snapshots remain preserved. Evidence:
`prototype-generated-cleanup.json`.

Actual BestBuds unsigned iOS Debug app qualification 6 passes with Xcode 26.6,
the committed SwiftPM resolutions, direct Kotlin app framework and notification
extension. `hangar-bestbuds-xcode-debug-6.log` ends with `BUILD SUCCEEDED`.
This checks the device SDK app link and embedded extension, not just framework
production. No simulator, signing, archive export, installation or deployment
is implied. The Release app check is running separately.

Manna standalone metadata qualification 23 passes (`20261011-014535-manna`):
`projects buildGraph reportBuildLayout --configuration-cache` takes 19s, stores
its configuration cache and transfers ten metadata paths from the two owning
repositories. The empty Gradle `appWeb` project is absent; its root-owned web
component and package remain. BestBuds composite qualification is pending in
the declared-web transfer run.

Current static audit (`current-build-layer-static-audit.json`): 60 consumer
Gradle files contain 2,347 lines, with exactly one Dependeasy block each. The
Dependeasy bootstrap build is counted separately. Shared Kotlin implementation
has 162 files and 6,640 lines, with a maximum of 181 lines per file. No tracked
CocoaPods file, npm/Yarn lockfile or version TOML is found in these three repos.
These are source inventory measurements, not cyclomatic-complexity measurements.

Composite target adapter qualification 3 passes (`20261011-014620-reaktor`):
20s of Gradle work, six fresh tests in the GradleBuildTargets owner suite, no
failures/errors/skips, and successful JVM transfer of 26,922 paths. Qualified
wrapper paths, both diamond ordering cases, conflicting/unknown owners, platform
and effect gates, layout-report changes and included build-definition changes
retain their existing admission/seal behavior. The combined catalog cache,
latest graph layout and packaged runtime remain in qualification 8.

Surface qualification 5 passes: receipt `20261011-014738-bestbuds`, 358 tasks in
2m 1s, exit 0 and a successful JVM-family transfer of 43,029 paths. The one fresh
surface test renders all 846 cells with zero findings and zero known exceptions.
Contact sheets now use bounded pages within the unchanged 512 MiB heap; no cell
assertion or layout deadline is skipped. Evidence:
`20261011-014738-bestbuds-fresh-test-results.json`. Reused suites are excluded;
the complete engine rerun remains pending in qualification 8.

Declared web transfer qualification 2 passes: receipt `20261011-015113-bestbuds`,
371 tasks in 2m 41s, exit 0 and a successful web-family transfer of 52,961 paths
across Reaktor, BestBuds and participating Manna. The authored `buildBestbudsWeb`
target is classified through Gradle's generated task-family map; `buildGraph`,
`:Manna:buildGraph` and `:Manna:projects` retain that scope. BestBuds' resulting
snapshot has 34,925 regular file paths, 939,108,953 summed logical bytes and
no desktop app, native framework, native compiler cache or Xcode output directories.
These are logical file sizes within this snapshot, not physical APFS disk usage.
Current Vite production generation
and composite ownership pass; transferred test reports are reused. Evidence:
`declared-web-transfer-2-inventory.json` and
`20261011-015113-bestbuds-fresh-test-results.json`. These are scope and logical-size
observations, not controlled build-speed or physical APFS savings measurements.

Cloud flow qualification 5 passes: receipt `20261011-015616-bestbuds`, 362 tasks
in 51s, exit 0 and a successful JVM-family transfer of 43,026 paths. The exact
`F-CLOUD-15-deploy-view` flow preserves referenced-filter isolation and restoration,
workload visibility, inspector and deploy-order checks; it scrolls the declared
virtualized list before requesting offscreen rows. No production filter, deadline
or assertion is changed. Evidence: `20261011-015616-bestbuds-fresh-test-results.json`.
This focused pass does not qualify the unrelated failing flows from full run 5.

Composite runtime qualification 8 passes: receipt `20261011-015846-bestbuds`,
411 tasks in 6m 33s, exit 0 and a successful full-family transfer of 92,274 paths.
Its 148 fresh suites contain 483 tests, zero failures/errors/skips: the full engine
suite contributes 473 cases and the catalog cache/binding owner contributes ten.
The complete 846-cell surface sweep passes within its unchanged heap. The 3,350-task
Build layout fixture passes its unchanged 10-second budget across three measured
iterations: median 182.6 ms, worst 213.0 ms. The packaged desktop runtime dependency
check and actual loopback MCP probe (port 62854) pass. Transferred Reaktor/Manna
suites are reused. Evidence: `20261011-015846-bestbuds-fresh-test-results.json` and
`bestbuds/modules/engine/build/reports/performance/hangar-build-layout.json`.

Native artifact-8 inspection uses a disposable launcher copy and explicit owned
state/database paths. It opens the Build pane on the BestBuds composite workspace,
shows project grouping, 3,355 declaration nodes, 7,560 wires, 230 graph scopes and
zero findings. Native text input finds and selects `bestbuds:buildBestbudsWeb`;
Operations exposes the available `Manna:webArtifacts` entrypoint. The persisted
catalog includes all admitted Reaktor targets, but named verification targets
remain classified under Testing and are absent from Build's Operations view.
That classification is corrected with a regression retaining ordinary module
checks under Testing and the declared target's original effect/binding. Packaged
qualification 9 is pending. Inspection starts no operation, stops only its owned
PID 74860, and retains the isolated runtime evidence in
`hangar-native-build-tab-8/launch.json`. This supersedes the earlier artifact-7
keyboard-filter and state-isolation limitations without qualifying the new correction.

Actual BestBuds unsigned iOS Release app qualification 6 passes too:
`hangar-bestbuds-xcode-release-6.log` ends with `BUILD SUCCEEDED`. Its worker
framework phases pass with receipts `20261011-020925-reaktor` (23s, 6,845 copied
paths) and `20261011-021038-bestbuds` (10m 6s, 11,447 copied Apple-family paths).
The final app embeds `NotificationService.appex`; the Release executable contains
the statically linked Kotlin framework. The active generated build flags disable
developer tooling. Debug and Release app paths, hashes and their embedded extensions
are recorded in `ios-actual-xcode-qualification-6.json`. Signing, archive export,
installation and physical-device behavior remain outside these unsigned app checks.

Final tracked-source wiring audit finds no current CocoaPods integration in the
owned Gradle/Xcode/CMake layer. Historical navigation views still mention the
former integration. Obsolete npm command help is corrected in 12 owned helpers;
six shell helpers and four Node helpers parse successfully, with no behavior
or execution body changed. Upstream WebDriverAgent and before-state evidence retain
their original commands. The optional Firebase MCP runtime launcher still uses
an unpinned npx command and remains a separate unqualified runtime-tooling boundary.
Evidence: `final-tool-wiring-audit.json`.

Native packaged catalog and execution qualification 9 pass. Receipt
`20261011-022204-bestbuds` completes 406 Gradle tasks in 1m 19s and transfers
92,295 paths without a copy error. Its one fresh catalog suite contains 10 tests
with no failures, errors or skips; existing Reaktor/Manna reports are reused.
The actual loopback MCP probe (port 62936) and packaged database runtime pass.
A fresh owned native launch retains the Build label, project frames, zero findings
and the web target's declared bundle dependency. Its Operations panel contains
109 operations and finds `reaktor:frameworkChecks` as LocalEphemeral. Clicking its
existing Run control invokes the BestBuds wrapper with `:reaktor:frameworkChecks`;
receipt `20261011-022836-bestbuds` passes 212 tasks in 26s and transfers successfully.
Thirteen fresh Reaktor suites contain 62 tests, zero failures/errors/skips.
The native Runs row reaches `succeeded`, and the owned control-plane receipt records
exit 0 with exactly one qualification run. Only the owned PID 83380 is stopped;
the user's running Hangar and its histories are preserved. Evidence:
`hangar-native-build-tab-9/launch.json` and both fresh-test-result reports.

The shared JavaScript command environment now makes pnpm run/exec refuse stale
dependencies; frozen pnpmInstall and explicit pnpmLock own installation and updates.
Owner qualification 2 passes all 51 existing tests and all 11 JavaScript kernel
tests. Its new regression initially reused the nested script's receipt because
the fixture omitted its consumed manifest. Declaring that input preserves
configuration-cache reuse and both refusal assertions; focused qualification 3
passes in 9s, including explicit lock repair. No production policy was weakened.

The normal wrappers choose this Mac for the latest web runs based on available
CPU and memory. Combined qualification 11 exposes one machine-dependent Manna
fixture: the ignored personal baby-data import is absent on the worker. The
fixture loader now excludes that import while preserving every permission
assertion and the local file. Combined qualification 12 passes in 1m 43s,
428 tasks (71 executed, 357 up-to-date): Reaktor docs, BestBuds web/browser,
Manna production Vite, all 796 browser tests, all 758 API tests and type checks.
All three locks remain unchanged. These elapsed times describe different warm
builds, not a controlled performance comparison. Evidence:
`composite-pnpm-policy-web-12.log`, `dependeasy-pnpm-command-boundary-2-results/`,
`dependeasy-pnpm-command-boundary-3.log`, `pnpm-policy-and-local-fixture-qualification.json`.

The existing deployReaktor flow rebuilds both documentation surfaces and deploys
successfully with pinned pnpm 12.10.1 and Wrangler 4.148.0, after a successful dry
run. Version: `0003e1df-f08a-40a6-80f8-777ea190265a`. Live HEAD checks return 200
for public docs, 302 to sign-in with private/no-store caching for the private
Hangar design, and 404 for direct access to its internal private asset path.
Evidence: `docs-pinned-deploy-12.log`, `docs-deploy-live-verification-12.json`.
