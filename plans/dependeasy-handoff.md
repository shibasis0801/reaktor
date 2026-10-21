# Dependeasy redesign: typed, inspectable build layer — Codex handoff

Written 13 October 2026 for Codex to execute. Owner: Shibasis. The baseline was measured on 13 October against reaktor `75b1f3bd`, bestbuds `98ba3f43` and manna `7fe612e`, plus their uncommitted trees. Everything below is outstanding unless it is marked done.

This plan reopens the build layer. It supersedes two things:
- the 11 October "build layer is done" status in [engineering-tools-plan.md](engineering-tools-plan.md);
- the schema-1 parts of the Dependeasy integration contract in [cli-kernel-hld.md](cli-kernel-hld.md).

The principles of those documents stay:
- one projection, produced by its owner;
- declared facts kept apart from execution evidence;
- staleness judged from inputs, not timestamps;
- only Gradle schedules.

## Status at a glance

| WP | Work | State |
|---|---|---|
| WP0 | Clear the blockers from the 13 Oct review | Blockers committed: reaktor `4786a918`, bestbuds `6129a3ee` / `73da2a7c`; build/unit checks pass, composite Hangar coverage incomplete (14 Oct 2026) |
| WP1 | Split dependeasy into a stable toolchain part and a fast plugin part; one version authority | Split committed: reaktor `bfe3eab1`, bestbuds `17a14295`, manna `8ad7070`; checks and invalidation probe pass; local artifacts published (14 Oct 2026) |
| WP2 | Typed build model; one node clickable end to end in Hangar, the CLI and MCP | Qualified and locally committed: reaktor / bestbuds / tools; receipts and source navigation pass (14 Oct 2026) |
| WP3 | Handles replace strings in every build script | Qualified and locally committed: reaktor `0f3d9595`, bestbuds `d3655c20`, manna `4a1d5d6`; owner-approved Hangar sharding task/helper included (14 Oct 2026) |
| WP4 | Typed kernels with honest inputs | Qualified and locally committed in all three repos; typed fixtures, real JS input probe and CLI specs pass (14 Oct 2026) |
| WP5 | Plans and receipts from Gradle itself | Qualified locally |
| WP6 | Web and Cloudflare adapters | Qualified and locally committed; config-service dev/prod deployments verified (14 Oct 2026) |
| WP7 | Apple: generated Xcode settings and one release plan | Committed; build 25 uploaded to internal TestFlight; both dSYMs accepted |
| WP8 | Native and CMake adapters | Committed; Android/iPhoneOS/desktop builds and warm reuse qualified |
| WP9 | Device runners, test tools, Dagger and CI | Committed; Samsung and physical iPhone launch flows pass with receipts; local Dagger gate fails under OOM pressure; CI unqualified |
| WP10 | Full drill-down in Hangar, the CLI and MCP | Committed; real CLI and isolated authenticated MCP walks pass; live Hangar/installed owner/IDE walk pending |
| WP11 | Qualify and ship the first pass (§10.6) | Complete 15 Oct; Reaktor #18, BestBuds #8 and Manna #1 published; qualification limits retained |
| WP12 | One typed DSL per node kind and per component kind | Qualified and committed 15 Oct: Reaktor `162ca4f3`, BestBuds `3673e133`, Manna `9db316c`, tools `10149f5`; typed workspace scope complete, component scopes WP13 and five-layer tools WP16 |
| WP13 | Three altitudes: components become projects, the root becomes orchestration | Qualified, committed and pushed 20 Oct: grouped root/component migration, three-root model and canonical graph/CLI/MCP parity, IntelliJ sync/navigation,119 framework cases and Java21 isolation qualify. Fresh Desktop450/450, Samsung15/15 and primary Mac cold/warm packaged runtime pass. Source guards stable; no Desktop harness errors. Published on the approved PR branches; Manna iOS signing remains owner-blocked. |
| WP14 | Dependeasy holds no product facts | Qualified, committed and pushed 20 Oct:119 framework cases,10 Ruby release cases/57 assertions, real native producers, all eight Apple source files and three-root canonical graphs/cache repeats pass. CLI/live gateway agree on1202 nodes/899 edges.39 source files stable. Published on the approved PR branches. |
| WP15 | One option system, no ambient reads | In progress: typed option provenance, one host service and a Gradle-free test-environment kernel qualify. Named-source, framework and direct-kernel checks pass. The literal authored-file-read cache gate stays open, including pnpm workspace membership owned by WP18. |
| WP16 | Split mixed dependeasy files; complete the model | Qualified, committed and pushed 20 Oct: real Gradle-free kernels and thin bindings, Kotlin-owned native/source/test policy, complete own specs/tools/declaration evidence. Full119 Gradle+10 model checks and1759 Java21 classes pass; final output-tool fixtures pass. All three exports/warm caches and installed CLI/MCP list/show/tree/explain/kernels match. Published on the approved PR branches; current seven Engine UI failures remain separately recorded. |
| WP17 | Cross-repo access only through handles | Qualified 20 Oct: typed resources/source-package/runtime variants, exact generated dependency links and workspace membership; zero unresolved cross-build input ownership in all three models. Framework119/model10, Java21, three frozen installs/Dagger/cache reuse and current installed CLI/MCP list/show/tree/explain/kernels pass. Real BestBuds browser/docs and Manna API879/browser913 pass. Committed and pushed: Reaktor `a99157ce`, BestBuds `9700b35b`, Manna `66e39464` on the approved PR branches; unrelated telemetry and private draft remain outside. |
| WP18 | Finish the adapters and consumers | In progress: generated web/release/host contracts, JS version authority, redundant aliases, notification contracts, fleet-owned topology/catalog/account discovery, typed load endpoints and fleet-backed log recipes are qualified and pushed. All72 load/worker suites declare13 endpoint keys;4 fresh HTTP fixtures and12 recipe checks pass. Current CLI/MCP and warm-cache checks pass. Strict parameterized aliases, bootstrap policy, remaining script readers and plan/run consolidation stay open. |

### Domain and legacy status

Updated 21 Oct 2026. WP13, WP14 and WP16 functional acceptance are qualified, committed and pushed to the approved PR branches. WP17 cross-repository handles is qualified, committed and pushed in all three repositories with zero unresolved cross-build ownership and current CLI/MCP parity. WP18 adapter removals are active; WP15's literal authored-file-read gate remains open. Current register: `build/dependeasy/receipts/qualification/wp13/full-status.json`; HLD: `full-status-hld.mmd`; complete removals: `legacy-removal-register-current.json`. WP16 final proofs: `wp16-final-model-audit-20261020-v39-proof.json`, `wp16-current-consumers-20261020-v39-proof.json` and `wp16-inspection-installed-20261020-v39-proof.json`.

- ✅ **Build isolation and bytecode — finished (WP1/WP12/WP13).** Stable split and Java21 qualify:119 Gradle+10 model cases,1759 Java21 classes; final alias-tool fixtures pass. Earlier private-edit/restoration probes retain zero consumer compilation.
- ✅ **Build model, IDs and old readers — finished (WP2–WP5).** Canonical model and retained plans/receipts shipped; obsolete graph/layout artifacts removed.
- ✅ **Typed workspace DSL — finished (WP12).** Contextual node DSL shipped; component scope completion remains WP13.
- ✅ **IntelliJ sync and source navigation — finished (WP11/WP13).** Fresh Gradle sync succeeds in 59.860 seconds. The earlier root import and real Cmd+B path from product DSL through WorkspaceScope.kt to WorkspaceCompletion kernel source remain qualified. The actual Repair IDE index rescan fixed 13 indexing problems. Desktop script inspections now show zero errors and two unused delegated task warnings; desktopApp and Installer.Dmg resolve to their included-build Kotlin sources. The original installed CLI/Hangar owner walkthrough is already qualified in WP11; its repetition is not a WP13 gate. Current native input limitations remain in the consumer follow-up. Current evidence: wp13-ide-index-repair-20261019-proof.json. Evidence: intellij-root-product-story-v1-proof.json, root-product-story-v1-ide-kernel-navigation-v3-proof.json, wp13-desktop-ide-refresh-20261019-lint-after.json and wp13-ide-project-reopen-20261019-observations.json in the qualification receipts store.
- ✅ **Root orchestration and component ownership — finished (WP13).** WP13 functional acceptance qualifies: three grouped roots, contextual components, exact task plans, 20 delivery gates, IntelliJ sync/navigation, Java21 isolation and canonical graph/CLI/MCP parity. Fresh Desktop full450/450 and Samsung15/15 pass with stable source; primary Mac cold/warm packaged runtime passes. Owned qualification scope committed and pushed. wp13-desktop-full-ui-20261020-v7-proof.json
- ✅ **Library dependency notation — finished (WP13).** WP13 contextual declarations and authored scripts qualify across all three roots. Framework119cases and Java21 isolation qualify. BestBuds retains four legacy CLI textual references for K1; Tactile keeps its requested missing-input placeholder. Kernel extraction and remaining external adapters are WP16/WP18.
- 🔄 **Web and package.json adapters — working (WP17/WP18/K1).** WP17 published: three-root exact package links/metadata, BestBuds browser/docs and Manna API879/browser913, installed CLI/MCP parity. WP18 unused JS workspace parser removed; remaining version authority, aliases and adapter policies active.
- 🔄 **Apple/Xcode/Fastlane — working (WP13/WP14/WP16).** Apple host/bootstrap bytes and release artifact paths are preserved. Typed release prerequisites, owner-qualified commands, exact Manna auth alias delta, IntelliJ import/navigation and existing framework checks qualify. Installed BestBuds iPhone connect passes. Manna iOS signing remains owner-blocked. WP14 prefix/signing extraction qualifies with identical BestBuds bytes, declared MANNA prefix only and10 Ruby checks/57 assertions. Remaining rendering/kernel and Ruby policy removal are WP16/WP18. Gradle-free SigningKernel now owns settings/export rendering and private writes; existing Apple fixture and direct real-host-target contract checks pass. Archive extraction is applied; fixture and direct real Mach-O/dSYM checks pass. No external provider upload in this qualification. WP18 distribution slice now qualified/published:7 Apple cases,12 Ruby cases/82 assertions;12 commands/21 existing artifacts preserved, Manna unchanged. No external provider writes.
- 🔄 **Native/CMake — working (WP8/WP14/WP16).** WP16 Kotlin-owned native source/executable/vendor/host-test and pinned WebView policy qualifies. Actual host/Android/physical-iPhoneOS and combined WebView cold/warm pass; Security JUnit5/5, four canonical Web checks pass (Partial parser). Locked GoogleTest gitlink included. Dormant React Native retirement remains WP18. WP18 removed all52 obsolete React Native bridge files and its pin;7 checks and exact three-root canonical/native parity pass; pushed52fce43d.
- 🔄 **Device tests and Maestro — working (WP13/WP16).** Child components and suite graphs qualify. BestBuds installed Samsung and iPhone connect flows pass; replacing its Android installation remains blocked by a certificate mismatch. Manna isolated e2e APK and fixture setup pass. The fresh exact15-flow physical Samsung suite passes all15 with797 top-level commands, zero failures/skips, stable guarded source and USB stay-awake restored0→0. Runner output reports1161 expanded nested steps. Native keyboard collapse preserves text; exact Tree and anchored skill-title selectors avoid neighboring labels; every content assertion remains. Retain the earlier11/15 run and focused corrections as history. App checks pass214 JavaScript tests in39 suites; API smoke previously passes50 steps. Manna iOS signing stays owner-blocked. Current full proof: wp13-physical-full-android-20261019-v12-proof.json.
- ✅ **Load/contract tests — finished (WP13).** 16 load suites and 22 aliases qualified. Missing Karate/k6 installer dependencies now use the existing graph After relationship; actual pinned HTTP runner fixtures and three-root canonical model/alias/task parity pass. No new scheduler or installer.
- ✅ **Desktop/Hangar test runners — finished (WP13).** WP13 exact450/450 Desktop flows, JVM68/Engine515 and primary cold/warm packaged runtime qualified for that candidate; owned qualification scope committed and pushed. Current WP16 canonical engine run514/521, auth11 passing, seven UI parity/layout failures; coverage Partial due to source identity. No full-green claim.
- 🔄 **Kotlin analysis and web probe pipelines — working (WP13).** Typed analysis, Compose-flow and Web probe declarations have qualified scoped replacements. Web has no remaining broad dependeasy block; generated bridge bytes, native outputs, task plans and canonical graph ownership are recorded in their proofs. The Apple test-framework output now exposes its correct entry point; physical launch is still unqualified. Plain kernel/tool extraction remains WP16.
- ✅ **Product facts and defaults — finished (WP13/WP14).** WP14 qualifies declared Apple prefixes/application identity, suite environment allow-lists, fleet environments and native import/link/variable facts. Host-tool paths have one toolchain owner.119 framework cases,10 Ruby cases/57 assertions and real Android/iPhoneOS/host producers pass;46 native contracts unchanged, all eight Apple sources expected, three-root node IDs/edges/cache and current CLI/gateway parity pass. wp14-qualification-20261020-v1.json
- 🔄 **Options/settings and ambient reads — working (WP13/WP15).** Typed option resolution, named/redacted evidence, one host service and Gradle-free test environment implemented;119 framework cases,8 focused and3 named-source cases pass. Current root graph checks preserve all identities/edges (R795/382, B1202/899, M924/463). Manna help/buildAll and BestBuds help reuse cache; BestBuds buildAll was invalidated by the deliberate stable-source layout edit. Literal authored-file-read gate remains open for WP18 membership authority; source/report attribution review remains explicit.
- ✅ **Five-layer kernels and model completeness — finished (WP16).** WP16 final local acceptance passes:119 Gradle+10 model cases,1759 Java21 classes, final alias-tool fixtures,371 stable guarded files, complete own specs and explained sites. All three cold/warm exports and fresh installed CLI/MCP list/show/tree/explain/kernels agree. Kernel380 (11 skips), CLI16 pass. Native/WebView cold/warm, Security5/5 and four canonical Web checks pass. Current Engine514/521 has seven UI failures and Partial coverage. Owned qualification scope committed and pushed; WP15 authored-cache gate remains open.
- ✅ **Cross-repository handles — finished (WP17).** Typed resource/source-package/runtime variants, generated links and exact membership published in all three repos. Zero unresolved cross-build ownership; actual consumers and current CLI/MCP exact.
- 🔄 **Local CI and Dagger — working (WP13/WP19/WP22).** All GitHub workflows and old product .dagger folders removed; all three actual V13 Dagger generate/verify/export checks pass. Runtime policy/receipts/output cleanup and final local CI acceptance remain.
- 🔄 **Remote build and transport — working (WP16/WP18).** Normal load-routed wrappers retained. Removed remote Maestro path and legacy export path guesses;33 router cases pass; tools f58323ba/10133300. Local ticket reuse and remaining one-contract transport policy still active. WP18 reproduces stale checkout contract after a successful UP-TO-DATE worker producer; fix active with typed public outputs and observed task evidence. WP18 single-host transport consumer and observed-producer stale public-output repair applied;33 router cases pass; framework and real routed acceptance active.
- 🔄 **Worker fleet and delivery — working (WP13/WP20/WP21).** WP18 fleet metadata owns public secret names, service-client identity and endpoints. Both secrets adapters/auth inventory qualify:14 offline,7 framework+10 model, real21 workers and37 unchanged final Wrangler configurations. Remaining consumers/delivery policy open.
- ⬜ **Server release/rollout — pending (WP23).** Obsolete release manifest removed; rollout-reviewed-image.py and direct deploy.sh remain until typed digest rollout replaces them.
- ⬜ **Secrets and database migrations — pending (WP24).** Move scripts and source readers to typed data-change nodes; preserve live data/authority.
- ⬜ **Pulumi and observability — pending (WP25).** Replace Python/Helm duplicate policy with one Kotlin Pulumi path after acceptance.
- 🔄 **CLI — working (K1/K3).** Primary commands use the graph-owned catalog. The duplicate 300-line parser and 29-file/4,664-line legacy archive are removed and pushed. Human output is qualified (`ee78eb0e`); dynamic completion is qualified and pushed (`ea847f03` / `41ee0fb0`):16 CLI checks,15 real-root queries,18 Bash/Zsh/Fish cases, absent/slow-owner checks and unchanged discovery/cache/model/provider files. Process evidence retention is qualified and pushed (`cb5848a9` / `97debf5e`):433 kernel and232 tooling cases pass (11/1 existing skips). Installed durable-run lifecycle qualifies:16 CLI cases, tooling JVM232/JS42 (one existing JVM skip), four real SIGINT cases and one real owner check. Bounded wait returns124; Ctrl-C returns130 without cancelling the owner run, which remains Running and then Succeeds. Typed build options, remaining caller migration, exact owner reconciliation and required K1-TUI remain open.
- ⬜ **Workspace kernel process and stores — pending (K4).** One graph-owned kernel process and stores; retire duplicate owners without parallel schedulers.
- ⬜ **Hangar face and consumers — pending (WP18/WP26/K5/K6).** Existing build drill-down works; finish thin graph/kernel consumers and delete duplicated discovery/delivery logic.
- ⬜ **Approvals, ledger and catalogs — pending (K7/K8).** One graph-owned plan/approval/receipt path and catalog; delete duplicate providers/discovery paths.
- ⬜ **MCP, identity and authority — pending (K9/K10).** The complete normally upgraded owner decodes the current typed model, serves fresh canonical node/spec/source/relationship reads and completes graph-selected checks. MCP surface unification, kernel identity and authority delivery remain K9/K10; compatibility qualification does not complete those packages.
- ⬜ **Workspace rollout, observability and docs — pending (K11/K12).** Apply the finished contract to every Reaktor+ workspace, replace stale command docs and complete owner acceptance.

### Big picture

- **Orchestration is written in strings.** Workspace targets list Gradle task paths, other builds by name, Reaktor modules by name and settings by property name. Nothing is checked until Gradle runs, and the IDE cannot navigate any of it.
- **The meat lives in many languages.** Kotlin has the authority on paper, but the logic sits elsewhere and the copies drift:
  - release logic in fastlane Ruby;
  - worker lists and environment choice in JS and shell;
  - Xcode facts in pbxproj settings;
  - native dependency discovery in CMake;
  - routing and artifact families in Python and shell.
- **The export cannot be opened.** The build graph has no declaration sites and no kernel references. A quarter of its nodes are "unexpanded". It is re-keyed three times before it reaches Hangar, the CLI or MCP. Agents can see Dependeasy targets but cannot run them.
- **One classloader for everything.** The Android, Kotlin and Compose Gradle plugins are bundled inside dependeasy. Any dependeasy edit changes their implementation hash and re-executes their tasks.

### Detail, measured 13 Oct

**Build scripts (107 `*.gradle.kts` files)**
- 196 colon-path string literals outside settings files: reaktor 118, bestbuds 61, manna 17. Examples: `verify("appChecks", ":app:jvmTest", ":design:jvmTest", ":engine:jvmTest")` and `included("Manna", ":buildAll")`.
- 39 name-based task lookups (`tasks.named("…")`, `it.name == "…"`, `dependsOn("…")`).
- 21 `apiReaktor` or `kotlinLibraries` calls that pass module names as strings. Product scripts spell 15 Reaktor module names, as in `apiReaktor("notification-fcm")` and `kotlinLibraries("reaktor-auth", build = "reaktor")`.
- 25 property-name literals, such as `localProperty("bestbuds.devTools")` and `gradleProperties["bestbuds.devTools"] = "false"`.

**Dependeasy itself (164 Kotlin files, 6,726 lines in `src/main`)**
- 47 name-based task lookups. Examples: `rootProject.tasks.named("prepare${Name}Source")`, `dependsOn("assembleRelease")`, `tasks.matching { it.name == "check" }`, `":${module}:exportKotlinLibrary"`.
- 8 string-typed fields where a closed set is meant:
  - in [BuildTarget](../dependeasy/src/main/kotlin/dev/shibasis/dependeasy/workspace/BuildTarget.kt): `platform`, `effect`, `runner`, `output`, `worker`, `workerDirectory`, `artifactFamily`;
  - `CommandTask.backend`, which takes "vite", "typescript", "karakum" or "pnpm".
- 3 internal API types in 4 files:
  - AGP `BaseAppModuleExtension` in `android/AndroidApplication.kt` and `android/androidConfig.kt`;
  - Gradle `ServiceRegistry` in `web/KotlinPnpmBridge.kt`;
  - Gradle `ProjectInternal` in the uncommitted `workspace/GradleTaskGraph.kt` change.
- 46 hand-built JSON keys across the exports: `buildGraph`, `reportBuildLayout`, `generatePackageScripts` and the per-pipeline `plans/*.json`.
- The task closure is computed at configuration time, and 25–30% of exported nodes stay "unexpanded".
- File-layout guesses: `".../outputs/aar/${project.name}-release.aar"`, `.github_modules/$name`, the `"../reaktor/dependeasy/javascript"` fallback, and the `"**/worker/app-kt/**"` excludes.

**Toolchain**
- Six readers parse Kotlin source with a regex:
  - dependeasy `build.gradle.kts` (`kotlinConstants()`);
  - dependeasy `settings.gradle.kts`;
  - `cmake/kernel/project.cmake`, for the C++ standard;
  - `runtime.ts` in dependeasy, bestbuds and manna, for the Java image.

  A seventh, `toolchain/read.sh`, uses sed inside the Dagger container to read the Gradle URL and checksum.
- Facts kept in several copies:
  - the iOS deployment target (16.0 / 15.0 / 13.0);
  - Swift 6 and C++23, 10 copies each across the two Xcode projects;
  - bundle ids, in Gradle, pbxproj, Ruby and the plists;
  - the team id, in four pbxproj configurations, several Ruby files, `ExportOptions.plist` and `config/mobile-e2e.json`;
  - the pnpm pin, in 46 manifests;
  - TypeScript pins, in 45 manifest entries.

**Exports and consumers**
- Four independent readers:
  - reaktor-tooling `GradleBuildTargets` keeps only gradle-runner build and verify targets. It drops `gradleTasks`, `plans`, `requires`, `bindings` and `environments`.
  - Hangar `BuildGraphSource` serves only the UI; MCP and the CLI cannot reach it.
  - Python `remote_build.py` and `remote-dev`.
  - The legacy CLI's own parser.
- Three ids for one target. Dependeasy's `bestbuds:artifacts` becomes tooling `dependeasy:…`, then operational `tooling/dependeasy-…`, which is lower-cased with separators collapsed. Collisions are silently dropped by `distinctBy`.
- Dependeasy targets are forced into the Deploy domain whatever their effect, so `check.catalog` and `check.start` exclude them.
- No node records where it was declared. MCP only sees a provenance string.

**Outside Kotlin** (from the four inventories)
- **fastlane:** 1,509 Ruby lines plus 287 test lines. About 740 of them are build decisions Kotlin should own: paths, ids, versions, command assembly, devTools injection, Info.plist stamping, a regex patch of `project.pbxproj`, and the privacy and dSYM checks.
- **Apple:**
  - Xcode settings repeat Kotlin's output paths and link flags.
  - The notification extension builds reaktor as a separate Gradle build. In the 13 Oct archive, its framework and the app's were built on different hosts.
  - fastlane disables devTools through `GRADLE_OPTS`, which remote-dev does not forward.
- **Web and Cloudflare:**
  - `WorkerFleet` lists 14 workers, but 6 more deployables ship through hand-written scripts, and 7 other hand-kept worker lists disagree.
  - Wrangler configs are merged in three different ways, and dev versus prod is chosen in about 11 places.
  - Kotlin output paths are hard-coded in Vite configs, tsconfigs and a Node generator.
  - Seven Karakum packages generate their bindings outside Gradle.
- **Runners:** four Maestro harnesses, three xcodebuild command builders, Karate downloaded without a checksum, and an unpinned k6.
- **Dagger:** `runtime.ts` is copied into each product. The generator ignores `executionHost`, so Local-only checks would become Linux functions.

### Keep from the 11 Oct rewrite

The direction of the rewrite is right. These parts stay:
- the `module(ns) { common / web / android / apple / jvm / framework }` DSL;
- the pnpm workspace and `InstallTool` (checksummed Node, pnpm and Pulumi);
- `BuildPipeline`'s separation of `after` (ordering) from `consumes` (artifact flow);
- `SafeFiles` bounded deletes;
- `WorkerFleet` as the authority on workers;
- the removal of CocoaPods, the checked-in `ts/export` bundles, yarn and webpack;
- Dagger generated from Gradle, with the engine pin coming from Kotlin;
- the thin `ci.yml`.

## 2. Rules for this work

1. **Declare with a name, refer with a handle.** A string name appears once, where the thing is declared, and every other use refers to the Kotlin value. Cmd+click then works, and a rename fails to compile instead of failing at run time. Paths to authored files (configs, sources) stay as paths: they are inputs, checked at configuration time.
2. **One authority per fact.** Each version, id, path and list has one owner in Kotlin. Other languages receive it in a generated contract. Nothing parses Kotlin source.
3. **Kotlin decides, adapters execute.** An adapter reads one contract, runs one tool and writes one result. Adapters contain no discovery, defaults, fallbacks, naming conventions, environment choice or ordering.
4. **Public API only.** Do not use Gradle, AGP or KGP internals.
   - Record any exception in a boundary register, `dependeasy/BOUNDARIES.md`, with file, line, reason and removal condition.
   - Gradle's own lifecycle constants, such as `LifecycleBasePlugin.BUILD_TASK_NAME`, count as handles.
5. **Only Gradle schedules.** Ordering lives in Gradle task dependencies and variant wiring. There is no second scheduler in Python, TypeScript, Ruby or shell.
6. **Every node opens to its kernel.** Each node records its id, declaration site, kernel class, invocation, effect, host, inputs and outputs. When dependeasy cannot know a fact, the node lists it as an omission with a reason; it never guesses.
7. **Working path first.** Deliver one node, clickable end to end, before broadening. This follows the delivery priority in `~/dev/AGENTS.md`.
8. **Project rules.**
   - No comments in code.
   - Record the budget and the acceptance evidence before the first edit (bestbuds `AGENTS.md` §2.3).
   - Reuse the reaktor-tooling types (`ToolingCatalog`, `SourceLocation`, `SourceHandoff`, `TaskPlanningPort`, `RunLedger`) instead of adding parallel ones.
   - No source-text architecture tests. Tests are TestKit fixture builds plus real builds of reaktor, bestbuds and manna.
   - Maintained libraries only.
   - Versions live only in Kotlin: no version catalog, no TOML, no properties files for versions.
   - Hangar UI goes through Surface, and its graphs draw through workbench/blueprint. Identifiers are shown in full; secrets never are.
9. **Delivery hygiene.**
   - Commit messages are descriptive, never "progress".
   - Commits that depend on each other across reaktor, bestbuds and manna land together.
   - No toolchain bumps inside design work.
   - Stage only your own hunks.
   - No destructive git, and never `deleteRecursively` a real checkout.
   - Never print secrets or fixture contents; report counts only.
   - `~/dev/tools` (remote-dev, `remote_build.py`) is not a git repository. Back up a file before editing it, or put the folder under git first (decision 6).
   - Gradle builds go through the configured wrappers. Devices and Maestro run on the Mac. Never start emulators or simulators.

## 3. Target design

### 3.1 Three layers

```
build scripts                    handles only: projects.app, Reaktor.Auth, IosLane.TestflightInternal
   │ declare
   ▼
orchestration (dependeasy)       workspace nodes → Gradle tasks + variant wiring
   │ realize                       consumes = variant resolution, after = task ordering
   ▼
kernels (dependeasy)             typed task classes: TypeScriptCheck, ViteBundle, CMakeBuild, XcodeHost …
   │ execute
   ▼
adapters                         tsc, vite, wrangler, karakum, xcodebuild, cmake, fastlane, dagger
                                 each reads one generated contract

projection                       model.json (declared) · plan (Gradle, on demand) · receipts (observed)
   │ one reader: dependeasy-model
   ▼
consumers                        Hangar · reaktor CLI · MCP · remote-dev · Dagger · fastlane
```

- **Kernel.** The Kotlin classes that do the real work. Each is a Gradle task type with typed inputs and outputs, plus one adapter when it needs an external tool. Kernels change slowly. "Kernel" here means dependeasy's kernel, not the bestbuds `ReaktorKernel`.
- **Orchestration.** The `workspace {}` and `module {}` DSLs, which turn handles into Gradle wiring. They change often.
- **Projection.** What consumers read: the declared model, Gradle's plan for one node, and the receipts of runs. It maps onto Reaktor V2's vocabulary: declarations realize nodes, artifacts derive from sources, and receipts observe runs.

### 3.2 Handles instead of strings

| Thing | Today | Handle | Declared once in |
|---|---|---|---|
| Project in the same build | `":app:jvmTest"`, `project(":engine")` | `projects.app` (Gradle type-safe project accessors) | `settings.gradle.kts` `include` |
| Reaktor module used by a product | `apiReaktor("cloudflare")`, `kotlinLibraries("reaktor-auth", build = "reaktor")` | `Reaktor.Cloudflare`, `Reaktor.Auth` | `Reaktor` catalog object in dependeasy |
| Another build | `included("Manna", ":buildAll")`, `includedBuild("reaktor").task(":pnpmInstall")` | `Reaktor.build`, or a `Manna` build handle from the bestbuds settings | catalog or settings |
| A module's output | `":app:linkReleaseFrameworkIosArm64"`, `".../outputs/aar/x-release.aar"` | `appleFramework(projects.app, Apple.IosArm64, Build.Release)` | output kinds in dependeasy, one mapping each |
| Another workspace node | `":appChecks"` inside `verify("pr", …)` | the value returned by `verify("appChecks") { … }` | the declaring call |
| Public node of another build | `":frameworkChecks"` in reaktor | the `Workspace.Verify` role | the role enum; each workspace marks its role nodes |
| Kernel kind | `CommandTask.backend = "vite"` | sealed kernel types | dependeasy kernel package |
| Closed sets | `platform = "macos"`, `effect = "verify"`, `runner = "browser"`, `artifactFamily = "web"` | `Effect`, `Host` and `Runner` enums; the family and macOS requirement derived from output kinds | dependeasy model |
| Tool | ambient `node`, `k6`, `maestro-runner`, unpinned Karate | `Tools.Node`, `Tools.K6`, `Tools.Karate`, `Tools.MaestroRunner` | `Tools` (version + checksums) |
| Build option | `localProperty("bestbuds.devTools")`, `GRADLE_OPTS=-D…` | `BestBudsOptions.DevTools: BuildOption<Boolean>` | product build logic |
| Environment | `TARGET_ENV`, `wrangler.dev.json` selection, 44 `:dev` wrappers | `Environment` handles (`Dev`, `Prod`) | product workspace |
| Fastlane lane | `fastlane("ios", "build_release")` | `IosLane.TestflightInternal` | the Kotlin lane list; the Ruby side fails if a planned lane is missing |
| Native dependency | `.github_modules/hermes`, `prepare${Name}Source`, the `HERMES` switch | `Native.Hermes` | native catalog (source, revision, checksum) |
| Worker | 7 hand-kept lists | entries of `WorkerFleet` | `WorkerFleet` |

How handles become Gradle wiring:
- **Outputs are published as variants.** Each module plugin publishes what its module produces as Gradle variants, tagged with one attribute, `dependeasy.output`. Its values form an enum: Kotlin/JS library, Apple framework, Android APK/AAB/AAR, JVM distribution, worker bundle, and verification results per suite.
- **The mapping uses public APIs only.** From KGP: `binaries.getFramework(…).linkTaskProvider` and the test runs' execution tasks. From AGP: `variant.artifacts.get(SingleArtifact.APK / BUNDLE / AAR)`.
- **Consumers ask for outputs.** A consumer requests an output of a handle; Gradle resolves the variant, runs the producing task and hands over the files. Gradle's own test-report aggregation uses this mechanism, and it works across included builds through dependency substitution. That makes `includedBuild(…).task(…)` unnecessary.
- **`after(node)` becomes task ordering.** `consumes` and `after` stay separate edges.
- **The `Reaktor` catalog lives in the fast plugin tier.** reaktor's `settings.gradle.kts` keeps its Gradle-native `include` lines. reaktor's root plugin fails configuration when the catalog and the included projects disagree.

### 3.3 Drill-down: one node, all the way down

Every node answers the same questions, in this order, and each answer links to the next level.

| Level | Question | Source of the answer |
|---|---|---|
| L0 Workspace | Which builds and nodes exist? | the model's builds and nodes |
| L1 Node | What is it, what is its effect, which host does it need? | the model node: kind, effect, host, label |
| L2 Declaration | Where was it declared? | the `SourceSite` (file and line) captured at the DSL call |
| L3 Composition | What does it include, consume and produce? | the model's `Includes`, `Consumes` and `After` edges, and the node's outputs |
| L4 Kernel | Which Kotlin class does the work, with which settings? | the `KernelRef`: class name plus typed spec |
| L5 Invocation | What exactly runs? | Gradle task paths; for tool kernels, the argv, working directory, environment keys (never values) and tool version |
| L6 Plan | Which Gradle tasks would run, in what order? | a Gradle Tooling API dry run, on demand, with its cost reported |
| L7 Receipts | What happened last time? | receipts written by the in-build listener |

**Declaration sites.**
- Every DSL entry point captures the first stack frame that comes from a `*.gradle.kts` file, using `StackWalker` (a public JDK API), and records it relative to the workspace root.
- A node created by a plugin instead records the module's build file and its kernel class.

**The same node through each surface.**

```
$ reaktor build show bestbuds:appChecks
bestbuds:appChecks   verification   effect Verify   host Any
declared    build.gradle.kts:46
kernel      dev.shibasis.dependeasy.verification.VerificationResults
consumes    bestbuds:app      verification-results jvm
            bestbuds:design   verification-results jvm
            bestbuds:engine   verification-results jvm
invocation  ./gradlew :appChecks
last run    13 Oct 18:02  passed  4m12s  212 tasks (31 executed, 170 up to date, 11 from cache)
```

- **Hangar.** The Build pane shows the same panel:
  - "Declared" opens the IDE at that line through `SourceHandoff`.
  - "Kernel" finds the class through the existing code index (`code.query`).
  - Each consumed node is a link.
  - "Plan" asks Gradle for the task tree.
  - "Last run" opens the receipt.
- **MCP.** The `build_node` read tool returns the same structure as JSON. Agents reach it through `workspace` → `inspect.call`.

### 3.4 The build model and its one reader

A new module, `dependeasy/model`, holds:
- the schema, as `@Serializable` Kotlin types;
- the reader;
- the contract types for adapters.

It depends only on kotlinx-serialization-json and compiles to Java 21 bytecode. It has no Gradle, reaktor-code or graph-runtime dependency, so reaktor-tooling, the bestbuds kernel and Hangar's engine can all use it.

```kotlin
@Serializable
data class BuildModel(
    val schema: Int,
    val producer: Producer,
    val builds: List<BuildIdentity>,
    val declarations: List<DeclarationInput>,
    val nodes: List<Node>,
    val edges: List<Edge>,
    val omissions: List<Omission>,
)

@Serializable
data class Node(
    val id: NodeId,
    val kind: NodeKind,
    val label: String,
    val effect: Effect,
    val host: HostRequirement,
    val declaredAt: SourceSite?,
    val kernel: KernelRef?,
    val invocation: Invocation,
    val inputs: List<FileSet>,
    val outputs: List<FileSet>,
    val tools: List<ToolRef>,
)

@Serializable
sealed interface Edge {
    @Serializable data class Includes(val parent: NodeId, val child: NodeId) : Edge
    @Serializable data class Consumes(val consumer: NodeId, val producer: NodeId, val output: OutputKind) : Edge
    @Serializable data class After(val node: NodeId, val predecessor: NodeId) : Edge
}
```

- **Ids.** A `NodeId(build, project, name)` is written as `bestbuds:appChecks` or `reaktor:reaktor-auth:check`.
  - The build part is the build's own root project name, so an id stays the same whether reaktor is built alone or inside bestbuds.
  - Names are author-declared Gradle names and cannot contain `:`, so each id is unique.
  - Ids never come from labels, class names or order, and consumers use them verbatim.
- **One file.** `exportBuildModel` writes `build/dependeasy/model.json` from typed data. It replaces `buildGraph`, `reportBuildLayout`, the JSON half of `generatePackageScripts` and the per-pipeline `plans/*.json`.
- **No task closure inside.** The model holds only the declared nodes and edges. Gradle's exact task tree comes from the planner (L6). That removes both the "unexpanded" problem and the internal-API code.
- **Staleness.** The model lists its declaration inputs with digests: build scripts, settings, `gradle.properties` and the dependeasy version. The reader recomputes the digests and reports the model as stale when they differ. It never uses modification times or HEAD.
- **One size limit**, defined once in the model module. Today one reader allows 8 MiB and another 16 MiB.
- **Receipts** live next to the model, in `build/dependeasy/receipts/<run>.json`, and use the model's types.

### 3.5 Adapters and their contracts

Every contract follows one pattern:
1. A Kotlin task writes a typed, versioned file under `build/dependeasy/contracts/<adapter>/`. The file carries the schema version, the node id and the node's declaration site, so an adapter's error can point back to the Kotlin line.
2. The adapter checks the schema version.
3. The adapter runs its tool and writes a typed result.

Where the tool has its own config format, the contract uses it: xcconfig for Xcode, an initial-cache file for CMake. Those contracts need no adapter code at all.

| Integration | Stays in its language | Moves to Kotlin | Contract |
|---|---|---|---|
| TypeScript / JS | calls into the TypeScript compiler API (typecheck, the worker bundle's import closure), Karakum, Vite configs and plugins, Wrangler, docs generators, the Playwright runtime | which files and projects to check, Kotlin output locations, worker lists, dev/prod choice, Wrangler config merging, package script generation and merging, deploy order, size reporting, Karakum bindings as Gradle nodes | `web/<component>.json` (Kotlin packages → entry `.mjs`/`.d.mts`, resources, sources, generated tsconfig paths); `cloudflare/<worker>.<env>.json` (final Wrangler config) |
| Swift / Xcode | three forwarding adapters (Firebase, Messaging, Crashlytics), the notification forwarder, a one- or two-line `main.swift`, the pbxproj structure (targets, resources, entitlements, package references), `Package.resolved` lockfiles | framework search paths, link flags, deployment target, Swift version, SDK, bundle ids, team, version and build number, signing selection, the Gradle task each phase runs, the adapter install list, SwiftPM pin checks, BGTask ids | per-target `xcode/<target>.xcconfig` (committed, drift-checked) |
| fastlane (Ruby) | App Store Connect and Play uploads, store build-number queries, certificates, profiles, devices, keychain | versions, artifact paths, ids, xcodebuild and Gradle command tokens, devTools, Info.plist stamping, the pbxproj patch, privacy and dSYM checks, release ordering | `release/<product>.json` |
| CMake | the target structure of `CMakeLists.txt` | C++ standard, deployment target, architecture, native dependency locations, `-D` flags, dependency discovery | `cmake/<library>-<platform>.cmake` (initial cache) |
| Dagger (TS) | one shared module that maps pipeline steps to Dagger calls | image, Gradle URL and checksum, memory policy, Playwright image, apt packages, cache names, which nodes run in CI (never Local ones) | `dagger/pipeline.json` |
| Shell / Python tools | remote-dev transport (sync, ssh, capacity probe); device commands inside the single Kotlin runner | routing policy (today a hard-coded task list), artifact families (today name heuristics), the Xcode variable forward list, package.json merging, `graph-run` plans and receipts | `remote/hosts.json` |

### 3.6 Packaging: a stable toolchain part and a fast plugin part

```
reaktor/dependeasy                     included build "dependeasy"
  buildSrc/                            compiles toolchain/…/GradlePlugins.kt so dependeasy's own scripts can import it
  model/      dev.shibasis.dependeasy:model       schema, reader, contracts; kotlinx-serialization only; Java 21
  toolchain/  dev.shibasis.dependeasy:toolchain   GradlePlugins versions + the settings plugin; implementation AGP, KGP, Compose, KSP, …
  gradle/     dev.shibasis.dependeasy:gradle      every project plugin, kernels, catalogs, Tools, Versions; compileOnly on toolchain
```

- **The `toolchain` artifact holds only what changes with a toolchain bump:**
  - Gradle plugin versions: Kotlin, AGP, Compose, KSP, serialization, Spring Boot, Google services, Crashlytics;
  - JVM bytecode targets;
  - repositories and plugin resolution.

  Consumers apply it once, as the settings plugin, which loads those Gradle plugins into the settings classloader.
- **Everything else lives in `gradle`,** compiled against those plugins with `compileOnly`. Editing orchestration or kernels then changes only dependeasy's own classloader, so Android, Kotlin and Compose task outputs stay valid.
- **Tool versions live in `gradle` as `Tools`:** Node, pnpm, TypeScript, Karakum, Vite, Wrangler, CMake, NDK, Hermes, Karate, k6, maestro-runner, the Dagger engine, the Java image and the Gradle distribution. Bumping Node therefore does not re-run Android tasks.
- **No more regex bootstrap.** dependeasy builds with Gradle's embedded Kotlin (the `kotlin-dsl` or `embedded-kotlin` plugin; the serialization plugin at `embeddedKotlinVersion`), so its settings no longer need to read a Kotlin version. `buildSrc` gives its scripts the plugin versions.
- **`verifyBytecode`** checks that every class in the three jars is Java 21 bytecode (major version 65). It is part of dependeasy's `check`.
- **No added actions on foreign tasks.** Dependeasy code adds no `doFirst`, `doLast` or `onlyIf` to Android, Kotlin or Compose tasks, because those lambdas would tie those tasks' implementation hash back to dependeasy.

## 4. Before and after

Before (bestbuds `build.gradle.kts` and `modules/app/build.gradle.kts`, 13 Oct):

```kotlin
javascript("companyWeb", "targets/companyOS") {
    kotlinLibraries("app")
    kotlinLibraries("reaktor-auth", "reaktor-graph", "reaktor-graph-port", build = "reaktor")
}
verify("reaktorVerify") { included("reaktor", ":frameworkChecks") }
verify("appChecks", ":app:jvmTest", ":design:jvmTest", ":engine:jvmTest")
verify("pr", ":verifyDagger", ":reaktorVerify", ":appChecks", ":jsChecks", ":architectureChecks")
artifacts("iosFrameworks", ":app:linkReleaseFrameworkIosArm64", ":app:linkReleaseFrameworkIosSimulatorArm64") {
    macos(); executionHost = BuildHost.Worker; artifactFamily = "apple"
}
external("webChecks") { pnpm("test:web"); effect = "verify"; runner = "browser" }

val devToolsEnabled = localProperty("bestbuds.devTools").map(String::toBoolean)
apiReaktor("notification-fcm")
```

After (shape, not final names):

```kotlin
val companyWeb = javascript("companyWeb", "targets/companyOS") {
    kotlin(projects.app)
    kotlin(Reaktor.Auth, Reaktor.Graph, Reaktor.GraphPort)
}
val reaktorChecks = verify("reaktorVerify") { includes(Reaktor.build, Workspace.Verify) }
val appChecks = verify("appChecks") { tests(projects.app, projects.design, projects.engine, suite = Suite.Jvm) }
verify("pr", role = Workspace.Verify) { includes(dagger, reaktorChecks, appChecks, jsChecks, architectureChecks) }
val iosFrameworks = artifacts("iosFrameworks") {
    appleFramework(projects.app, Apple.IosArm64, Apple.IosSimulatorArm64, build = Build.Release)
    host = Host.Worker
}
browserCheck("webChecks") { serves(companyWeb); config("playwright.config.ts") }
release("testflightInternal") {
    consumes(iosFrameworks)
    after(appChecks)
    lane(IosLane.TestflightInternal)
    require(BestBudsOptions.DevTools, false)
}

api(Reaktor.NotificationFcm)
```

What changed:
- Every reference is a value the IDE can open.
- The macOS requirement and the artifact family come from the Apple output kind.
- The release's devTools requirement is checked before the artifact is produced.
- Nothing names a Gradle task.

## 5. Work packages

Each package lists its goal, steps, acceptance evidence, budget and what not to do. Record the budget and the acceptance commands before the first edit (§2.3). Packages are ordered by dependency and leverage. Once WP4 is done, WP6–WP9 can run in parallel.

### WP0 — Clear the blockers from the 13 Oct review

Goal: a committed base that CI can configure, without the release-safety hole.

Steps:
1. Land the uncommitted dependeasy work together with the bestbuds and manna changes that depend on it, as paired commits with descriptive messages. This covers `BuildHost`, `artifactFamily`, `Versions.Data.Toml`, the `typecheck.mjs` import fix and `BuildWorkspaceTest`. Leave the `GradleTaskGraph.kt` internal-API change out; WP5 deletes that file.
2. Make Manna opt-in in the bestbuds composite (decision 1). The bestbuds `settings.gradle.kts` includes `../manna` only when a typed settings option is on, and CI never turns it on.
3. Release builds must not carry devTools or dev fixtures, whatever the host or property files:
   - The effective `bestbuds.devTools` value is resolved in one order: command line, then `gradle.properties`, then `local.properties`, then the default. The source that set it is recorded.
   - Xcode's Release configuration forces it off. Dependeasy's `darwin` code reads `CONFIGURATION` once; remote-dev already forwards that variable.
   - Release nodes require it to be off and fail before linking, naming the source of the value (for example `local.properties`).
   - fastlane passes the value as a Gradle argument, which remote-dev forwards, instead of through `GRADLE_OPTS`, which it does not.
4. Make the Dagger generator skip nodes whose host is Local, then regenerate so that `verifyDagger` passes.

Acceptance:
- On the worker, dependeasy `check`, the reaktor framework checks and the bestbuds `pr` target all pass. This is the 11 Oct suite: 55/55, plus the composite `buildAll`.
- A checkout of only bestbuds and reaktor configures (`./gradlew help`) without Manna.
- On m1-worker, with `local.properties` setting devTools to true and fixture files present (the worker mirror holds both), the iOS release build does one of two things. Either it produces a binary with zero fixture markers, or it stops before linking and names the source. Report counts only.
- No commit in one repo depends on an uncommitted change in another.

Budget: one settings option and one requirement check. No other API.

Do not: refactor while fixing; bump versions; print fixture or secret values.

### WP1 — Stable toolchain part, fast plugin part, one version authority

Goal: nothing parses Kotlin source, dependeasy edits stop re-executing Android, Kotlin and Compose tasks, and Java 21 bytecode is enforced.

Steps:
1. Split dependeasy into `model`, `toolchain` and `gradle` as in §3.6, with `buildSrc` compiling the plugin-versions file for dependeasy's own scripts. Build with embedded Kotlin. Delete `kotlinConstants()` and the settings regex.
2. Move the settings plugin into `toolchain`, keeping only stable work there: repositories, plugin resolution and includes. AGP, KGP, Compose, KSP and the other bundled Gradle plugins become `implementation` dependencies of `toolchain` and `compileOnly` in `gradle`. Consumers apply third-party plugins without versions.
3. Replace the six source readers and `read.sh` with generated contracts:
   - `project.cmake` reads a generated toolchain CMake file;
   - `runtime.ts` and `read.sh` read toolchain values that the Dagger generator writes into its output.
4. Replace the internal APIs:
   - `BaseAppModuleExtension` with AGP's public `ApplicationExtension`;
   - `ServiceRegistry` in `KotlinPnpmBridge` with injected public services.
5. Add `verifyBytecode` to dependeasy `check`.
6. Publish dependeasy with its version taken from one constant. This fixes the version regression found in the review.

Acceptance:
- No file outside Kotlin reads a `.kt` file (seven readers today, counting `read.sh`; zero after).
- **Re-execution probe**, in bestbuds:
  1. Run `:appAndroid:assembleDebug` and `:app:linkDebugFrameworkIosArm64` until warm.
  2. Change the body of a private function in `gradle`.
  3. Run both tasks again.

  No AGP, KGP or Compose task type executes. Record the executed-task counts by task type before and after.
- A TestKit fixture with a Java 25 class fails `verifyBytecode`.
- The configuration cache stays green for reaktor, bestbuds and manna.

Budget: three modules, the moved settings plugin, two small contracts and one check task. The only consumer change is how the settings plugin is applied.

Do not: add version catalogs, TOML or properties files for versions; bump any version; keep a regex fallback.

### WP2 — Typed model; one node clickable end to end

Goal: every declared node carries its declaration site, kernel, invocation, effect and host, and Hangar, the CLI and MCP can open a node down to its kernel class.

Steps:
1. In `model`, add:
   - the types of §3.4 and the receipt types;
   - `BuildModelReader`, which checks the schema, applies the single size limit and judges staleness from declaration digests.
2. In `gradle`:
   - capture a `SourceSite` at every DSL entry point and record a `KernelRef`;
   - have `exportBuildModel` write `model.json` from typed data;
   - delete the hand-built JSON in `BuildGraphTask`, `BuildLayoutTask`, `PackageScriptsTask`, `PipelineReport` and `BuildPlan`.
3. Map today's DSL onto the model without changing it. Producers still given as task-path strings get an omission ("foreign task path") until WP3 removes them.
4. Move the readers onto the model:
   - **reaktor-tooling:** replace `GradleBuildTargets` with a lossless mapping from the model to `ToolingCatalog`, taking provenance from `declaredAt`.
   - **Hangar:** `BuildGraphSource` reads through `BuildModelReader` and stops parsing JSON keys.
   - **remote-dev:** reads `remote/hosts.json`. After confirming nothing calls `remote_build.py graph-run`, delete it; it is a second scheduler.
5. The consumer slice:
   - The operational catalog keeps the `NodeId` as a typed field.
   - Build-node operational ids use an injective encoding, and a collision fails loudly instead of being dropped by `distinctBy`.
   - The domain follows the declared effect: Verify → Testing, Build → Develop, Deploy → Deploy. Verification nodes then appear in `check.catalog`.
   - Add `reaktor build show <node>`, the kernel read tool `build_node`, and Hangar's node panel with "Open declaration" and "Open kernel".
6. Update the documents:
   - in `cli-kernel-hld.md`, move the Dependeasy integration contract to the new model and fix the stale link to the deleted `reaktor-cli` sources (line 162 on 13 Oct);
   - update the status line in `engineering-tools-plan.md`.

Acceptance:
- For every declared node, `reaktor build show` prints the file and line of its DSL call, and the kernel class resolves in the code index.
- Hangar opens both in the IDE, and MCP `build_node` returns the same JSON.
- `check.catalog` lists `appChecks`, `jsChecks` and `portableChecks`, and `check.start` runs one of them locally with a receipt.
- Only one reader remains: `GradleBuildTargets` and `BuildGraphSource`'s key parsing are gone.
- Two exports in a row are byte-identical, and the export works with the configuration cache.

Budget: about 15 public model types, one export task replacing three, one CLI command, one read tool, one Hangar panel.

Do not: embed the Gradle task closure; keep schema 1 alive once the readers have moved; add a second way to acquire the model.

### WP3 — Handles replace strings in every build script

Goal: no task paths, module names, property names or closed-set strings where things are used.

Steps:
1. Turn on type-safe project accessors in reaktor, bestbuds and manna.
2. Add the `Reaktor` catalog (modules, the build handle and the `Workspace` roles) and its consistency check against reaktor's includes.
3. Make module plugins publish their outputs as variants (§3.2), with one mapping per output kind, through public APIs.
4. Build workspace DSL v2:
   - `verify`, `artifacts`, `target`, `release`, `browserCheck` and `javascript` take handles;
   - `includes`, `consumes` and `after` take node or output handles;
   - `host` and `effect` are enums;
   - the artifact family and the macOS requirement are derived from output kinds.
5. Cross-build references go through variant resolution of the other build's root project. If that fails for root projects, use the public `includedBuild(handle.name).task(…)` with a path derived from the handle, and record it in the boundary register.
6. Add typed build options. A `BuildOption<T>` has a name, type, default and resolution order, and records the source of its value. Nodes can declare `require(option, value)`.
7. Migrate the reaktor, bestbuds and manna scripts in paired commits. Then delete the string API:
   - `tasks(vararg String)`, `included(String, vararg String)`, `modules(vararg String)`, `recipe` and `gradle(vararg paths)`;
   - `apiReaktor(vararg String)` and `kotlinLibraries(vararg String, build)`;
   - the string fields of `BuildTarget`.

Acceptance:
- The §1 counts reach their targets:
  - path literals outside settings includes: 196 → 0;
  - name lookups: 39 in scripts and 47 in dependeasy → 0. The only exceptions are Gradle lifecycle constants, listed in the register;
  - module-name strings in product scripts: 15 → 0;
  - each of the 25 property names is declared once.
- Before migrating, save the `--dry-run` task list of every node. After migrating, the lists match, or each difference is explained.
- Renaming a project or a catalog entry fails at every use, with file and line.
- The configuration cache stays green, and no new cross-project task access is added.

Budget: nine new handle kinds (module, build, output, node, role, tool, option, environment, lane; projects come from Gradle). DSL v2 replaces v1 rather than sitting beside it.

Do not: keep string overloads "for convenience"; generate Kotlin source for handles; use a version catalog for the module list.

### WP4 — Typed kernels with honest inputs

Goal: each kind of work is a Kotlin class with typed inputs and outputs, and Gradle knows every input, so nothing stays falsely up to date.

Steps:
1. Replace `CommandTask.backend` and the stage strings of `JavaScriptCommandKernel` with kernel task types:
   - JS and web: `PnpmInstall`, `TypeScriptCheck`, `ViteBundle`, `KarakumBindings`, `WorkerTypes`, `WranglerDeploy`, `NodeGenerator`;
   - native and sources: `CMakeBuild`, `GitSource`;
   - tools and reporting: `InstallTool`, `ArtifactSize`.

   Each exposes a typed spec for the model. `CommandTask` becomes the internal process runner.
2. Fix the JS inputs:
   - Kotlin JS libraries are consumed through the Kotlin/JS library output variant as file inputs. This fixes the stale deploys caused by `workspace:` references.
   - pnpm install declares the lockfile and manifests as inputs and a stamp inside `node_modules` as its output, so it runs again after a clean.
   - Kotlin export directories become task-owned outputs under `build/`. Delete the custom prune that removed unknown files.
3. Karakum bindings:
   - give the seven Reaktor `ts/` packages `bindings(…)` nodes;
   - remove their pnpm `prebuild` and `postbuild` hooks;
   - fix `reaktor-tactile/ts/karakum.config.json`, which says `libraryName: "reaktor-db"`.
4. Tool kernels take a tool handle installed through `InstallTool`. Nothing uses an ambient tool.
5. `ArtifactSize` reads typed outputs and becomes the one size reporter. `size-report.mjs` and `bundle-report.mjs` go, and `bundle-budget.mjs` becomes a declared check that reads the same data.
6. `PulumiSdkTask` and `ExtractPrefabTask` delete only inside their own output directory, through `SafeFiles`.

Acceptance:
- Editing a Kotlin file in `:app`'s `jsMain` makes the `bestbudsWeb` bundle and its deploy node out of date. Today they stay up to date.
- Deleting `node_modules` makes pnpm install run.
- No `backend` strings remain, and each kernel's spec shows in `reaktor build show`.
- Every kernel has a TestKit fixture that covers its outputs and its up-to-date behaviour. That includes a delete-boundary test for the two fixed tasks.

Budget: about 15 kernel types.

Do not: reimplement the TypeScript compiler, Vite, Wrangler or Karakum in Kotlin; add fallbacks for missing tools.

### WP5 — Plans and receipts from Gradle itself

Goal: Gradle's exact task tree for any node, on demand, and a typed receipt for every run, using public APIs only.

Steps:
1. Delete `GradleTaskGraph` and the `gradleTasks` closure.
2. Add a planner in reaktor-tooling, as the `TaskPlanningPort` for build nodes. It uses the first-party Gradle Tooling API:
   - it runs the node's invocation with `--dry-run`;
   - it collects task events and `TaskOperationDescriptor.getDependencies()`;
   - it returns a typed plan and reports how long Gradle configuration took;
   - it caches the plan by model digest.
3. Add receipts. A build service registered through `BuildEventsListenerRegistry.onTaskCompletion` writes `receipts/<run>.json`. Each receipt holds:
   - the requested nodes;
   - each task's outcome: executed, up to date, from cache, skipped or failed;
   - durations and failure messages.

   This works whoever starts Gradle: the CLI, Hangar, an Xcode phase, fastlane, Dagger or remote-dev.
4. Implement reaktor-tooling's `RunLedger` over the receipts, and link operational runs to them.
5. Show progress without polling. For runs Hangar starts, use Tooling API progress events. For runs started elsewhere, react when the receipt file appears.

Acceptance:
- For these nodes, the planner's task set equals what `./gradlew <node> --dry-run` prints:
  - bestbuds `appChecks`, `iosFrameworks` and `workerBundle`;
  - reaktor `mobileInteropChecks`;
  - manna `buildAll`.
- A run from an Xcode phase and a run from the CLI both produce receipts that Hangar shows.
- The configuration cache stays green with the listener registered.

Budget: one planner, one listener, one receipt type and the `RunLedger` implementation.

Do not: compute task closures at configuration time; parse console output.

### WP6 — Web and Cloudflare adapters

Goal: one worker list, one environment choice, final Wrangler configs written by Kotlin, and JS tools that read a contract instead of hard-coded paths.

Steps:
1. Write `web/<component>.json`. Vite configs and tsconfigs read it instead of hard-coding the `ts/export`, `build/js` and `*-kt` paths. That covers manna `appWeb` and bestbuds `companyOS` Vite configs, the tsconfig chains, and `generate-js.sh`.
2. Move `worker-bundle-manifest.mjs` into dependeasy's JS kernel as a generic import-closure generator. Kotlin passes its parameters.
3. Make the fleet complete:
   - grow `WorkerFleet` to all 20 deployables, adding accountPurge, archiver, dataProbe, appWeb, companyOS and reaktorWeb;
   - add `Environment` handles;
   - Kotlin writes the final per-environment Wrangler config, merging once with one documented precedence;
   - the deploy kernel deploys that file.
4. Generate package scripts:
   - generate the `deploy*` and `logs*` scripts from the fleet;
   - extend script generation to externals and JS components;
   - a Kotlin task writes the package.json scripts directly, so remote-dev's merge goes.
5. Delete:
   - the jq `predeploy` merges, the shell `TARGET_ENV` selectors and the 17 per-target deploy scripts;
   - `bestbuds/deploy.sh`, which watches paths that do not exist and then commits and pushes;
   - the worker list in `run-worker-karate.mjs`;
   - the build and deploy recipes in `tools/gemini-devops/recipes.json`;
   - the duplicate target catalog in `package.json#reaktor`, keeping `name`, `stores` and `cloud`.
6. Fix the naming trap: Gradle `deployAppWeb` deploys appBrowser, while the package script `deployWeb` deploys appWeb.

Acceptance:
- `reaktor build list --kind deploy` shows 20 deployables with declaration sites, and no other worker list exists.
- Deploying one worker to dev and to prod uses the Kotlin-written config. Any difference from today's merged config is explained.
- No Vite config or tsconfig contains a Kotlin output path.
- Hand-written package scripts drop from 421 to about 140.

Budget: two contract types and the fleet extension. No new JS files except moved ones.

Do not: replace Vite or Wrangler; touch runtime JS (perf tools, docs generators, Playwright configs) beyond removing their build steps.

### WP7 — Apple: generated Xcode settings and one release plan

Goal: Xcode and fastlane read Kotlin facts. No ids, paths or versions are copied by hand, and builds stop rewriting tracked pbxproj, plist and profile files.

Steps:
1. Hold one value each in Kotlin for:
   - the iOS deployment target, applied to Kotlin/Native, CMake and Xcode;
   - the Swift language version;
   - bundle ids, taken from the app declaration;
   - the team id;
   - BGTask ids;
   - the version name and build number, shared with Android.
2. Generate the Xcode settings:
   - `generateXcodeHost` writes per-target xcconfigs: framework search paths, link flags from the framework declaration, deployment target, Swift version, SDK, sandboxing, bundle ids, team, version, and `REAKTOR_ROOT`/`REAKTOR_GRADLEW`/`REAKTOR_KOTLIN_TASK`;
   - `verifyXcodeHost` fails on drift;
   - both targets depend on one aggregate framework phase, whose single line runs from the bestbuds composite root, so the app and notification extension share one Gradle invocation.
3. Info.plists use `$(MARKETING_VERSION)` and `$(CURRENT_PROJECT_VERSION)`. The Release configuration uses `$(BESTBUDS_CODE_SIGN_STYLE)` like Debug; that one-time pbxproj edit removes the need for fastlane's regex patch.
4. The adapter install list comes from the framework export list, and `main.swift` shrinks to one or two lines.
5. `verifyXcodeHost` also compares the `Package.resolved` pins with the versions declared in Kotlin. That catches Manna's stale GoogleSignIn pin. Make `Versions.FirebaseDarwin` the real pin, or delete it.
6. Write the release plan contract, `release/<product>.json`:
   - contents: versions, artifact paths from the AGP and KGP APIs, ids, and the xcodebuild and Gradle command tokens;
   - the lanes read it;
   - delete the Ruby build decisions: version handling, paths, command assembly, devTools injection, plist stamping and the pbxproj patch;
   - also delete the maestro lanes, the `distribute` aliases and `ExportOptions.plist`, and stop tracking `report.xml` (`git rm --cached`).
7. Port the dSYM and privacy checks to a Kotlin `darwin` archive check, which the lane runs after export. Port their 224 test lines to Kotlin too.
8. Kotlin writes the signing xcconfig, replacing the xcconfig half of `prepare-ios-e2e.py`.
9. remote-dev takes its list of Xcode variables from `remote/hosts.json`.

Acceptance:
- There is one xcodebuild command builder (down from three), and about 840 Ruby lines remain (from 1,896).
- `git status` is clean after a release lane.
- The deployment target is the same in Kotlin/Native, CMake and Xcode.
- A TestFlight internal upload works end to end from the plan, run with the owner present for signing.
- The app and extension frameworks are built in one Gradle invocation on one host.

Budget: the Xcode host task and its check, the release contract, the archive check and the signing xcconfig task.

Do not: remove fastlane's App Store Connect, profile or keychain work; adopt Kotlin's experimental SwiftPM import (decision 5); start simulators; untrack signing material in `config/`.

### WP8 — Native and CMake adapters

Goal: CMake files describe targets only. Flags, paths and dependencies arrive in a generated contract.

Steps:
1. Add a `Native` catalog covering Hermes, fbjni, FlatBuffers and Foundation, with source, revision, checksum and recipe for each. Source tasks are referenced by handle. Delete `tasks.named("prepare${Name}Source")` and the `.github_modules/$name` paths.
2. Add a `NativeLibrary` spec on modules, with dependencies by handle, platforms and ABIs.
   - `CMakeBuild` writes `cmake/<library>-<platform>.cmake`.
   - AGP's external native build gets its arguments through the public variant API, so the contract is an input of the configure step.
3. Shrink `api.cmake` and `dependencies.cmake`:
   - remove `fi_auto_dependencies`, the `REAKTOR_NATIVE_DEPENDENCY_*` probing and the switch flags;
   - `apple.cmake` takes its deployment target and architecture from the contract.

Acceptance:
- reaktor `androidNativeLibraries` and `iosNativeLibraries` build, with outputs that are identical or explained.
- No CMake file contains a `.github_modules` path or a version number.
- Changing the contract reconfigures CMake, so the stale `iphone*` CMakeCache failure cannot recur.

Budget: the native catalog and one contract type. No new CMake functions.

Do not: replace CMake or change C++ sources.

### WP9 — Device runners, test tools, Dagger and CI

Goal: one device-flow runner, pinned test tools, and Dagger as a generic executor of a Kotlin pipeline.

Steps:
1. Add a `DeviceFlow` kernel in dependeasy `verification`, with reaktor-tooling's `MaestroRunner` as the engine.
   - Inputs: an app output handle, the app id and the flows directory.
   - Device rule: exactly one physical device or an explicit serial; never emulators or simulators.
   - Runner: maestro-runner, pinned through `InstallTool`. Host: Local.
   - It replaces `reaktor/tools/maestro`, the fastlane maestro lanes, manna's `run-signed-in.sh` and the planning half of `mobile-e2e.mjs`. Keep the product hooks: fixture refresh and the age-gate fixture.
   - Uninstalling an app on a signature mismatch wipes its data on personal phones, so it needs an explicit flag.
2. Add `KarateSuite` and `K6Suite` kernels with pinned, checksummed tools.
   - Suites are declared in Kotlin, and worker suites come from fleet membership.
   - Generate the suite × environment aliases instead of keeping the 111 hand-written scripts.
3. Dagger:
   - write `dagger/pipeline.json`;
   - one shared, hand-written TS module in dependeasy exposes `run(target)` and `list()`;
   - the products stop copying `runtime.ts` and the generated `index.ts`;
   - `.dagger/package.json` and `tsconfig.json` are generated from `Tools`, or removed;
   - CI calls `dagger call run --target pr`.
4. CI: keep `ci.yml`. Delete `mobile-web-performance-once.yml` and `reaktor/.github/publish.yml`, which is entirely commented out and never loaded.
5. remote-dev reads `remote/hosts.json`. Delete its hard-coded local task list and its artifact-family name heuristics.
6. Delete the `scripts/hangar` shell harness (`hangarFlows` already shards), the `tools/typecheck.mjs` symlink hops, and the flamechart scripts that boot simulators.

Acceptance:
- A BestBuds flow runs on the Samsung and on the iPhone from `reaktor build run bestbuds:deviceFlows`, and leaves a receipt.
- Karate and k6 are pinned with checksums.
- `dagger call run --target pr` passes in CI with no generated TS in the product repos.
- remote-dev's routing contains no task names.

Budget: three kernels and two contracts.

Do not: start emulators or simulators; add ordering logic to Dagger.

### WP10 — Full drill-down in Hangar, the CLI and MCP

Goal: every level of §3.3 is one click or one command away, through the existing operation catalog.

Steps:
1. Hangar's Build pane, built through Surface and drawn through workbench/blueprint:
   - its nodes come from the model;
   - selecting a node shows its declaration, kernel, consumed and produced nodes (as links), invocation, plan (on request, with its cost) and receipts;
   - identifiers are shown in full, and environment values never (keys only).
2. CLI: `reaktor build list | show | plan | run | receipts`. `run` goes through the existing plan and approval flow.
3. MCP: add the kernel read tools `build_model`, `build_node`, `build_plan` and `build_receipt`, reached through `workspace` → `inspect.call`. Verification nodes appear in `check.catalog`; deploy nodes stay behind approval.
4. `JvmProjectDiscovery` stops regex-parsing `settings.gradle.kts` and scanning script text for "fastlane", "maestro", "karate" and "k6". Module nodes come from the model.

Acceptance:
- **Owner walkthrough, with no search at any step.** In Hangar:
  1. open `bestbuds:pr`;
  2. go to `appChecks`;
  3. then to the `:app` JVM tests;
  4. then to the kernel class in the IDE;
  5. then to Gradle's plan;
  6. then to the last receipt.
- The same walk works in the CLI and MCP, with identical ids.
- No regex over build scripts remains in reaktor-tooling.

Budget: four read tools, five CLI subcommands and one Hangar panel family.

Do not: add an MCP server or module (the gateway is the existing operation catalog); poll for run state.

## 6. Owner decisions

Codex goes ahead with the default unless the owner answers otherwise.

1. **Manna in the bestbuds composite.** Default: opt-in, and CI never includes it. Alternative: CI also checks out manna.
2. **Google sign-in policy in Swift.** The uncommitted `GoogleSignInSdk.swift` has grown from 33 to 132 lines of OAuth policy. Default: move that policy into reaktor-auth Kotlin, following `DarwinAppleLogin.kt` (AuthenticationServices, Keychain and Ktor), so that Swift only forwards.
3. **Wrangler configs.** Default: Kotlin writes the final config under `build/`, and the authored `wrangler.json` files keep only per-worker facts. Alternative: the authored files stay authoritative and Kotlin only verifies them.
4. **Java 21 bytecode for dependeasy.** Default: keep 21 for IDE and Android Studio sync, enforced by `verifyBytecode`. If no consumer needs it, move to 25 and drop the split.
5. **Kotlin's experimental SwiftPM import.** Default: not now. It would remove the three Firebase adapters, but it is experimental and reverses the decision recorded in [dependeasy/VALIDATION.md](../dependeasy/VALIDATION.md).
6. **Version control for `~/dev/tools`.** Default: put it under git before editing remote-dev or `remote_build.py`.

## 7. Spotted in passing

These are outside the build layer, but the inventories turned them up:
- `workspace` `provider.refresh` sends `{"id": …}` (`KernelEngineering.kt:150`), but `reaktor_provider_refresh` reads `provider` (`McpDoor.kt:251`), so it should always fail.
- `telemetry_refresh` starts an operation, yet it is advertised as read-only.
- **Confirmed 13 Oct:** with the authenticated agent owner mounted, unauthenticated loopback `workspace` → `inspect.call` reaches `agent_transcript` and the unredacted `agent_check_catalog`. The synthetic audit returned 1 transcript event and 1 check task, including their markers, without an Authorization header. Reproduction fixture: [audits/KernelInspectionAccessAuditTest.kt](audits/KernelInspectionAccessAuditTest.kt); temporarily install it in BestBuds' kernel test source set and run `./gradlew :kernel:test --tests '*KernelInspectionAccessAuditTest'`. The fixture is kept outside the normal test suite because it verifies the reported exposure, not the desired policy. No authorization-policy change was made in this build-layer pass.
- The legacy CLI's `self uninstall` deletes all of `~/.reaktor`, including the agent owner's state.
- `reaktor/tools/maestro` uninstalls the app on a signature mismatch, which wipes its data on personal phones.
- Karate is downloaded without a checksum.
- `report.xml` is in `.gitignore` but tracked, and every lane run dirties it.
- Storage rechecked during WP0: 73.8 GB free on 14 Oct. Temporary data is 14.4 GB; the active aggregate grew `.remote-dev` to 22.3 GB, so the router refused the queued checks and cleanup must restore the 20 GB remote limit before another build.

## 8. Appendix: current API → target

| Today | Target |
|---|---|
| `verify(name, vararg paths)` | `verify(name) { tests(…) / includes(…) }` |
| `artifacts(name, vararg paths)` | `artifacts(name) { appleFramework(…) / android(…) / jvm(…) }` |
| `target(name) { tasks(…) }` | `target(name) { includes(node) / consumes(output) }` |
| `external(name) { pnpm(…); effect = "verify"; runner = "browser" }` | a typed kernel node (`browserCheck`, `release`, `deviceFlow`, …) |
| `BuildTarget.platform/effect/runner/artifactFamily` strings | enums, or derived from output kinds |
| `BuildTarget.macos()` | derived from Apple outputs |
| `BuildTarget.included(build, paths)` | `includes(buildHandle, Workspace.Role)` |
| `BuildTarget.modules(lifecycles)` | `modules(Lifecycle.Build)` over Gradle's lifecycle constants |
| `BuildTarget.recipe(…)`, `gradle(…)`, `tasks(…)` | deleted; use `includes` |
| `BuildTarget.fastlane(platform, lane)` | `lane(IosLane.…)` with the release contract |
| `BuildTarget.task(provider)` | kept (already typed) |
| `JavaScriptComponent.kotlinLibraries(vararg String, build)` | `kotlin(projects.x)`, `kotlin(Reaktor.X)` |
| `JavaScriptComponent.types/verify/build(target, vararg projects: String)` | the same verbs, taking handles |
| `CommandTask.backend` | sealed kernel types |
| `BuildPipeline.node(task, id = task.name)` | nodes created from handles, with ids from declared names |
| `apiReaktor(vararg String)` | `api(Reaktor.X, …)` |
| `framework(name = "app", exports: List<Any>)` | `framework(exports = listOf(Reaktor.Core, …))`; the name flows into the xcconfig |
| `localProperty("…")`, `gradleProperties[…]`, `GRADLE_OPTS` | `BuildOption<T>` with `require(…)` |
| `includedBuild(name)` | build handles |
| `workers { include("textModerator") }` | kept as the declaration; every other worker list derives from it |
| `buildGraph`, `reportBuildLayout`, `generatePackageScripts` JSON, `*BuildPlan` | `exportBuildModel` and the contracts |
| `GradleTaskGraph` | deleted; the planner (WP5) replaces it |
| the `kotlinConstants()` regex, `read.sh`, the `project.cmake` regex, the `runtime.ts` regex | `buildSrc` sharing and generated contracts |

## 9. Reporting progress

- After each package, update the status table at the top with the state, commit ids and date, and add one dated line under "Log".
- Record the acceptance evidence: the commands run, the counts before and after, and the receipt paths.
- Stage only your own hunks. If another agent's edits overlap yours, stop and report instead of merging blindly.

## 10. Review of the first pass, and the next steps (14 Oct)

Reviewed on 14 October 2026 against these trees, including their uncommitted changes:
- reaktor `c647c108`;
- bestbuds `f7a13960`;
- manna `6b3d985`;
- tools `a04abaf`.

The numbers come from the authored build scripts, the three exported models and two read-only audits.

### 10.1 Verdict

The first pass delivered the mechanics, but not yet the design.

**Done:**
- Task-path strings are gone from build scripts: 196 → 0 outside settings includes.
- Gradle and AGP internal APIs are gone.
- There is one typed model, with declaration sites and kernel source links.
- Contracts feed Xcode, fastlane, Dagger, CMake, the web apps and remote-dev.
- The regex readers of the version files are gone.
- There is one xcodebuild command builder.
- Ruby is down from 1,896 to 1,320 lines.
- The Dagger code is shared instead of copied.
- A Gradle planner and run receipts exist.

**Not yet:**
- Build scripts still mix orchestration, component declarations and plumbing in one file.
- Below the node level, the DSL is wide and mostly untyped.
- Many dependeasy files mix layers.
- Several decisions moved from strings in scripts to strings inside Kotlin, Python and JS instead of disappearing.
- Nothing is pushed, nothing has run in CI, and the live owner walkthrough has not happened.

### 10.2 What to keep

- Handles for projects, Reaktor modules, outputs, roles, builds, lanes and options.
- Outputs published as Gradle variants, with ordinary dependency substitution across builds and no task fallback.
- The model module, `BuildModelReader`, declaration sites, kernel source links, the planner and receipts.
- The three-tier packaging, and the WP1 probe showing zero foreign recompilation.
- The generated xcconfigs with their drift check and the one-line Xcode phase.
- The release plan read by fastlane, the CMake preload contract, the shared Dagger module with `pipeline.json`, and `hosts.json` for remote-dev.
- `BOUNDARIES.md` as the register (its drift is listed below).
- The evidence discipline: each of the 610 task-set differences that WP3 introduced, across 84 of 217 nodes, has a written explanation.

### 10.3 What is wrong, at a high level

1. **Three altitudes share one file.**
   - The bestbuds root script is 244 lines. About 60 are orchestration, about 90 declare components, and the rest are plumbing:
     - size-budget regexes;
     - argument wiring with absolute paths;
     - file scanning at configuration time;
     - shell commands assembled from strings with `-P` flags inside them;
     - four near-identical device-flow blocks.
   - Module scripts carry whole pipelines:
     - `reaktor-code` runs its own compiler through `JavaExec`, building arguments in `doFirst`;
     - `compose-flow` writes a Markdown report in `doLast`;
     - `reaktorDesktop` forwards ten system properties by name, lists test classes by name and writes a fake completion marker in `doLast`.
2. **The DSL does not say what a node is.**
   - `verify`, `artifacts`, `target`, `release`, `external` and `browserCheck` all configure the same `BuildTarget`. So a verification accepts `fastlane(...)` and `worker = "..."`, and `release` is literally `target`. The model then records most of them as `Target`.
   - Below the node level, about half of the public DSL members take raw strings, command lists, `Map<String, String>` keyed by platform or environment, or absolute paths.
3. **Plumbing moved instead of disappearing.**
   - Dependeasy now hard-codes BestBuds facts: the `BESTBUDS` xcconfig prefix chosen by list position, a `MESSAGE`/`LOGIN_USER` environment allow-list, and the literal `"dev"`.
   - remote-dev carries 179 lines of embedded Python that re-read seven contract shapes.
   - Worker discovery still lives in five Kotlin consumers, two secrets scripts and `pnpm-workspace.yaml`.
4. **The clickable path is not usable day to day.**
   - The CLI refuses to show a node whenever any build script changed since the last export.
   - 233 nodes have the generic `WorkspaceTargetTask` as their kernel, so "open kernel" lands on an aggregator.
   - 548 of 832 nodes have no kernel spec, no node lists its tools, and 644 nodes list no inputs.

### 10.4 Detail by domain

**Build scripts** (67 authored scripts, 2,795 lines)

| Plumbing in authored scripts | Count |
|---|---|
| `System.getProperty` / `System.getenv` | 14 |
| Property or environment names as string literals | 27 |
| `.absolutePath` / `.asFile` | 17 |
| `doLast` / `doFirst` | 5 |
| `tasks.register` / `tasks.named`, `withType` | 10 / 23 |
| `mustRunAfter` / `dependsOn` | 16 |
| Paths into another repository (`"../reaktor…"`) | 14 |
| `packageAlias` with shell text | 16 |
| Command strings (`./gradlew`, `node`, `python3`, `pnpm`) | 22 |
| `-P` / `-D` flags inside strings | 7 |
| File existence checks or listings at configuration | 5 |

- The worst files are the bestbuds root (29 hits), `reaktorDesktop` (26), `reaktor-code` (11), `engine` (8) and `compose-flow` (6).
- There are three ways to depend on a Reaktor module: `apiReaktor(Reaktor.X)`, `api(Reaktor.X.coordinate)`, and `api(projects.reaktorX)` inside reaktor.
- There are two release-safety mechanisms: `require(option, false)`, and an inline `refuseDevToolsBundle` check in appBrowser.
- The bestbuds settings script declares its own option enum instead of using `BuildOption`.
- Manna repeats its bundle id and team as literals.
- Source sets, configurations and tasks are still named as strings: `"jvmMain"`, `"kspCommonMainMetadata"`, `getByName("iosArm64")`, `name in setOf("jsNodeTest", …)`.

**DSL and dependeasy internals**
- **Parallel mechanisms:**
  - one receiver for all node kinds;
  - four command APIs (`DependeasyExtension.command`, `BuildTarget.command`, `JavaScriptComponent.command`, `nodeScript`);
  - `dag{}`/`BuildPipeline`, which keeps its own `target`/`after`/`consume` vocabulary;
  - four code paths that add nodes to the model, plus a string-keyed `taskFamilies` side channel;
  - five option systems.

  `AppleHost` also exposes eight `TaskProvider`s.
- **Surface size:** 225 public members across 30 DSL classes, plus 89 top-level functions. About 47% of the core members take low-level parameters.
- **Files that mix layers:**
  - `ReleasePlanTask`: a planner inside a task.
  - `XcodeHostTask`.
  - `AppleHost`: 14 `tmp/fastlane/ios/...` literals.
  - `DeviceFlow` and `DeviceFlowDeclaration`: six `dependeasy.*` property literals.
  - `WorkerFleet`: reads wrangler JSON and picks dev or prod at configuration time.
  - `JavaScriptComponent`.
  - `KotlinCMake` and `KotlinCMakeTask`: host detection, `/opt/homebrew`, and an `"OpenSSL::Crypto"` switch.
  - `GradleInvocationTask`: five jobs and an embedded Groovy init script.
  - `ArtifactSizeReport`: an inline `node -e` program.
  - `DeclarationSites`: a regex over dependeasy's own sources to find a class line.
- **Hidden tier membership:** the stable `toolchain` module compiles five files picked out of the fast module's source folder. Nothing in those files shows which tier they belong to.
- **Remaining name lookups:** 5 task-name sites, 27 container lookups by literal name (`"main"`, `"commonMain"`), and 4 public `compilation: String` parameters.
- **Boundary register drift:**
  - the line number for `KotlinTestReport` is wrong;
  - the intro claims removals that already happened;
  - two types are unregistered: `DefaultCInteropSettings`, which appears in a public signature, and the legacy `AppExtension`.
- **Tests:** 111 tests, 75 of them TestKit. There is no TestKit coverage for `deviceFlows`, `appleHost`, `dagger`, `benchmarks`, `interop`, `kotlinBrowser`, `desktopNative`, `springImage` or `androidApplication`.

**Model**
- bestbuds has 832 nodes (756 kernel, 70 target, 6 external) and 274 omissions.
- 224 nodes lack a declaration site; 42 of those have no omission explaining why.
- 82 nodes have no kernel; their omission reason is "multiple producers".
- `tools` is empty everywhere.
- Six node fields are still strings: `runner`, `artifactFamily`, `worker`, `workerDirectory`, `bindings`, `environments`.
- The stale schema-1 files `build-graph.json` (10 MB) and `build-layout.json` still sit in `build/dependeasy`, with no writer or reader.

**Adapters**

| Measure | 13 Oct | 14 Oct | Target |
|---|---|---|---|
| Hand-written package scripts | 421 | 222 | ~140 |
| Generated package scripts | 16 | 308 (in the bestbuds root, 244 names for 150 tasks) | one per node |
| Ruby lines | 1,896 | 1,320 | ~840 |
| Worker lists | 7 | the fleet, plus 6 hand lists and 5 Kotlin consumers | the fleet only |
| Maestro engines | 4 | 2, plus helper scripts (remote-dev's points at a deleted script) | 1 |
| Non-Kotlin readers of Kotlin source | 6 | 3 (`worker-connection-read.py`, `migrate-analytics-postgres-to-clickhouse.mjs`, a Playwright spec) | 0 |

- The generated xcconfigs embed `CURRENT_PROJECT_VERSION = 25`, so every build bump rewrites tracked files.
- Manna's Xcode phase uses the `BESTBUDS_` variable.
- JS still decides contract paths, build targets and skiko/sqlite file names, and re-implements pnpm workspace membership.
- `build/dependeasy/kotlin/library` is hard-coded in 18 `link:` entries and 4 imports.
- `pnpm-workspace.yaml` still globs `build/js`.
- JS dependency versions have no authority: wrangler is `4.149.0` in one manifest and `^4.95.0` in another; react is 18 in one and 19 in others.
- `remote-dev` hard-codes the product names, output roots, and the `-kt` and `ts/export` heuristics.

**Consumers**
- `GradleBuildTargets` is gone, and every reader uses `BuildModelReader`.
- Still to fix:
  - reaktor-tooling runs 10 regexes over script text;
  - `BestBudsOperationalCatalog` classifies scripts by their `fastlane`, `maestro`, `karate` and `k6` prefixes;
  - `WorkspaceRepositories` regex-reads `settings.gradle.kts`;
  - `plan` and `run` exist both top-level and under `build`.

**Qualification debt**
- Nothing is pushed: reaktor is 16 commits ahead, bestbuds 15, manna 7. CI has never run.
- The local Dagger gate fails under memory pressure (OOM).
- The live owner walkthrough and IDE opening are pending, and the installed `~/.reaktor` CLI predates the `build` command.
- Hangar aggregate coverage is incomplete (4 failures), and one kernel database-query test fails.
- `maestro:push` appears to call `mobile-e2e.mjs` with its old arguments, and three package scripts point at deleted lanes.
- WP3 changed the task sets of 84 of 217 nodes: 396 tasks added and 214 removed, each explained. Confirm the additions are wanted (for example iOS simulator compile/link and completion manifests) and measure `buildAll`'s time.

### 10.5 The design answer: three altitudes, one home each

| Altitude | It says | Home | It may use |
|---|---|---|---|
| Orchestration | what the product verifies, ships and deploys, in which order, on which host | the root `build.gradle.kts`, inside `workspace {}` only | node handles, output handles, build handles, roles, environments, lanes, `require(option, value)` |
| Components | what one component is and what it consumes | the component's own Gradle project | typed component specs: library, Android app, Apple app, web app, worker fleet, device suites, load suites |
| Kernels and conventions | how the work is done | dependeasy for generic work; the product's `buildSrc` for product data and small conventions | the Gradle API, tasks, providers, files, processes |

Rules:
1. **Scripts do no plumbing.** An orchestration or component script never:
   - registers a task;
   - reads a system property, environment variable or file;
   - assembles a command;
   - names a task, source set or configuration.

   Each such need is a missing kernel or convention; add it there.
2. **Every component is a Gradle project.** Web apps, the Apple host, the worker fleet, and the device and load suites become projects with their own build files. Each then gets a type-safe accessor, its own clickable script and published outputs, and the root refers to it by handle.
3. **Product build logic holds product data, not mechanisms.** That means the Apple identity, device suites, web budgets, load suites and Hangar test settings. Dependeasy holds no product names.
4. **Each altitude is a different plugin.** The root applies `dev.shibasis.dependeasy.workspace`, which offers only `workspace {}`. Component DSLs come only from component plugins. The boundary is enforced by the API, not by a review checklist.

The shape of the bestbuds root afterwards (names are illustrative):

```kotlin
plugins { id("dev.shibasis.dependeasy.workspace") }

workspace {
    includes(Reaktor.build)
    optional(BestBudsBuilds.Manna)

    val appChecks = verify("appChecks") { tests(Suite.Jvm, projects.app, projects.design, projects.engine) }
    val jsChecks = verify("jsChecks") { tests(Suite.JavaScript, projects.app) }
    val pr = verify("pr", role = Workspace.Verify) {
        includes(Reaktor.build, Workspace.Verify)
        includes(appChecks, jsChecks, projects.pipeline)
    }

    artifacts("buildAll", role = Workspace.Build) {
        includes(projects.companyOs, projects.appWeb, projects.reaktorWeb, projects.appAndroid, projects.appDarwin, projects.reaktorDesktop)
        includes(Reaktor.build, Workspace.Build)
    }
    release("testflightInternal") { after(pr); ships(projects.appDarwin, Build.Release, IosLane.TestflightInternal) }
    deploy("workers") { after(pr); fleet(projects.workers, BestBudsEnvironments.Prod) }
    devices("deviceFlows") { suites(projects.deviceTests) }
}
```

A component, for example `targets/appWeb/build.gradle.kts`:

```kotlin
plugins { id("dev.shibasis.dependeasy.web-app") }

webApp {
    kotlin(projects.app, Reaktor.Graph)
    sources(Reaktor.Performance.WebVitals)
    budgets(BestBudsWeb.Budgets)
}
```

### 10.6 Next work packages

Do WP11 first: qualify and ship what exists. WP12 and WP13 are the altitude split, which is the owner's main complaint. WP14–WP18 follow.

#### WP11 — Qualify and ship the first pass

Goal: the existing work is pushed, green in CI and walked by the owner.

Steps:
1. Fix:
   - the `maestro:push` arguments;
   - the three package scripts that name deleted lanes;
   - the failing kernel database-query test;
   - the four failing Hangar flows.

   Finish the in-flight IntelliJ sync change (`KotlinCMake.kt`) as its own commit, or park it.
2. `reaktor build list|show|plan` display a stale model with a stale banner that lists the changed declaration files. Only `run` and evidence reuse refuse a stale model, as `cli-kernel-hld.md` requires.
3. The model export deletes the orphan schema-1 files.
4. Move `CURRENT_PROJECT_VERSION` and `MARKETING_VERSION` out of the committed xcconfigs into a per-run generated xcconfig.
5. Bring the Dagger PR gate under its memory limit, with the memory policy declared in `pipeline.json` and measured. Push the three branches as §10.7 decision 1 describes, open the PRs and run CI.
6. Install the CLI to `~/.reaktor`. Then do the WP10 owner walkthrough: Hangar → node → kernel in the IDE → plan → receipt.
7. Measure `buildAll` before and after WP3 (time and task counts). Remove the extra work unless the owner wants it.

Acceptance:
- CI is green on the pushed branches.
- The owner walkthrough is done with the installed CLI and Hangar.
- `reaktor build show` works on a dirty tree.

Budget: no new API.

Do not: mix WP12 work into these commits.

#### WP12 — One typed DSL per node kind and per component kind

API shape: implement `dependeasy-api.md` (§3, §4.4 and migration step 1). It wins where it differs from the steps below, for example `val x by verify {}` naming and the `Run` modifier chain. The 15 Oct story target in `kernel-handoff.md` §3 applies now: `product`, `verify(parts)`, `artifacts(parts)` and `delivery` compose component-owned facts. Land K0 after WP11 and before this package (kernel handoff D2).

Goal: what you can write depends on what you are declaring, with no string escape hatches.

Steps:
1. Give each node kind its own receiver:
   - `VerifySpec`: tests, includes, after, host;
   - `ArtifactsSpec`: outputs, host;
   - `ReleaseSpec`: the output it ships, its lane, its requirements;
   - `DeploySpec`: the fleet and environment;
   - `ExternalSpec`: only where a tool truly has no kernel yet. List each one in the register with a removal condition.

   Delete `release = target` and `browserCheck = external`. The model's `NodeKind` gains `Verification`, `ArtifactSet`, `Release`, `Deploy` and `External`.
2. Replace the string fields on nodes with types:
   - a `Runner` enum;
   - the family taken from `OutputKind`;
   - a `WorkerRef` from the fleet;
   - a list of `Environment`.

   Remove from the public surface: `bindings: MutableList<String>`, the `worker` and `workerDirectory` strings, `pnpm(String)` and `command(vararg String)`.
3. Keep one way to add a node:
   - fold `dag{}`/`BuildPipeline` into the workspace vocabulary;
   - delete the `taskFamilies` side channel;
   - keep one internal command kernel.
4. Make parameters typed task options.
   - Device platform, flow and artifact policy become Gradle `@Option`s on the device kernel (`--platform android`) instead of `-Pdependeasy.*` properties.
   - Package aliases are generated from a node plus typed options, one alias per node and option set.
   - Delete `packageAlias(File, String, String)`.
5. Reach containers through typed accessors (`sourceSets.main`, `kotlin.sourceSets.commonMain`, compilation handles). No `"main"`, `"commonMain"` or `"test"` strings in public signatures.
6. `AppleHost` stops exposing `TaskProvider`s; consumers use its outputs.
7. Add TestKit coverage for every public DSL entry: `deviceFlows`, `appleHost`, `dagger`, `benchmarks`, `interop`, `kotlinBrowser`, `desktopNative`, `springImage`, `androidApplication` and `workers`.

Acceptance:
- The public API dump (`apiDump` or the binary-compatibility validator) shows no public member that takes a command list, shell text, a `Map<String, String>` keyed by platform or environment, or an absolute path.
- `verify { fastlane(...) }` no longer compiles.
- Model nodes carry their real kind.
- The 16 `packageAlias` calls and 7 in-string flags in scripts are gone.

Budget: five node receivers; the component specs come in WP13. The net public surface shrinks; report the before and after counts from the API dump.

Do not: keep the old receivers as deprecated aliases.

#### WP13 — Three altitudes: components become projects, the root becomes orchestration

API shape: implement `dependeasy-api.md` (§3, §4.1–§4.5, migration steps 2 and 4). It covers the component plugins and scopes, parameters instead of setters, `@Entry`, and test tags. Follow `kernel-handoff.md` §3 for root altitude and component ownership. Keep generated package scripts until K1 supplies each typed CLI replacement; record that mapping before deletion.

Goal: §10.5 holds in reaktor, bestbuds and manna.

Steps:
1. Add component plugins: `web-app`, `apple-app`, `worker-fleet`, `device-tests` and `load-tests`, beside the existing library and application plugins. Each publishes its outputs as variants.
2. Create the component projects:
   - bestbuds: `targets/companyOS`, `targets/appWeb`, `targets/reaktorWeb` (with its docs), `targets/appDarwin`, the worker fleet, device tests, load tests and the Dagger pipeline;
   - manna: `targets/appWeb`, `targets/api`, `targets/appDarwin` and device tests.
3. Root scripts apply only `dev.shibasis.dependeasy.workspace`.
   - Move every component declaration out of them.
   - Move product data into `buildSrc`: device suites as an enum carrying flows, environment and hook; web budgets; load suites; the Apple identity of both products; Hangar test settings.
4. Remove the copy-paste:
   - the four bestbuds device-flow blocks become one suite table;
   - the karate and k6 loops become a suite table;
   - nothing derives node names by string surgery.
5. Give script-hosted pipelines a kernel:
   - the Kotlin analysis distribution in `reaktor-code`;
   - the compose-flow parity report, or delete it;
   - the desktop window-test split and the Hangar shard runner, with no `doLast` marker;
   - reaktor-web's probe framework and alias tasks.
6. Keep one idiom per concept:
   - `api(Reaktor.X)` everywhere; drop `apiReaktor` and `.coordinate`;
   - `require(option, value)` for every release-safety rule; delete appBrowser's inline check;
   - `BuildOption` in settings too.

Acceptance:
- Each root script contains only `workspace {}`. This is structural: the workspace plugin offers nothing else.
- In authored scripts, these counts reach zero; report the §10.4 table before and after:
  - `System.getProperty` and `System.getenv`;
  - property names outside `BuildOption` declarations;
  - `.absolutePath`;
  - `doLast` and `doFirst`;
  - `tasks.register`;
  - configuration-time file checks;
  - command strings;
  - `-P` and `-D` flags in strings.
- Every existing node's `--dry-run` task list is unchanged, or each difference is explained (the WP3 method).
- The owner reads the bestbuds root script and recognises the product's release shape without opening another file.

Budget: five component plugins. Add projects only where a component exists. No new handle kinds.

Do not: move plumbing into `buildSrc` as ad-hoc tasks. Product build logic holds data and small conventions that call dependeasy.

#### WP14 — Dependeasy holds no product facts

Goal: dependeasy contains no BestBuds or Manna names, defaults or allow-lists.

Steps:
1. The xcconfig variable prefix becomes a parameter of the Apple app declaration, so there is no `index == 0` and manna gets `MANNA_`. Rename the variable in manna's Xcode phase.
2. Each device suite declares its own environment allow-list.
3. The fleet's environments replace the literal `"dev"` in `WorkerFleetTask`.
4. The native catalog entry's declared imports replace the `"OpenSSL::Crypto"` switch.
5. Host tool paths such as `/opt/homebrew` come from the toolchain contract.

Acceptance: a TestKit fixture product with its own names produces Apple host, fleet, release and device contracts whose output contains no BestBuds or Manna strings.

Budget: parameters on existing declarations only.

#### WP15 — One option system, no ambient reads

Goal: every ambient value enters through `BuildOption` and shows up in the model.

Steps:
1. `BuildOption`, in its property, system-property and environment forms, becomes the only way to read ambient values: in settings, scripts and dependeasy.
2. A test-environment kernel replaces the per-module `systemProperty`, `System.getenv` and `user.home` code in engine, kernel and reaktorDesktop. For example: `testEnvironment { isolatedHome(); forward(HangarOptions.Flows); liveChecks(KernelLiveChecks.entries) }`.
3. Host detection becomes one service; today 11 dependeasy files read `os.name` or `os.arch`.

Acceptance:
- In all three repos, Gradle's configuration-cache report for `help` and `buildAll` lists no system property, environment variable or file read that originates in an authored script.
- Every such read comes from `BuildOption` resolution and appears in the model's declaration inputs.

Budget: one test-environment kernel and one host service.

#### WP16 — Split mixed dependeasy files; complete the model

API shape: implement `dependeasy-api.md` (§5–§7, migration steps 3 and 5). It covers the five layers per capability, Gradle-free kernels, typed tools, the tree fields in the model, and `reaktor build tree|explain|kernels`.

Goal: each dependeasy file belongs to one layer, and every node reaches a real kernel.

Steps:
1. Physically move the five stable files into `toolchain/src`, and have `buildSrc` share them from there. No cherry-picking out of another module's source folder.
2. Split the mixed files:
   - `ReleasePlanTask` and `XcodeHostTask` separate into plan types in the model plus rendering kernels.
   - `GradleInvocationTask` splits into request validation, a plan cache and the Tooling API runner. A compiled listener replaces its embedded Groovy init script.
   - `WorkerFleet` stops reading JSON at configuration time.
   - `ArtifactSizeReport` measures in Kotlin, using a maintained library for Brotli, instead of an inline `node -e` program.
   - `DeclarationSites` stops regex-reading sources. Use the class name plus the code index, or a build-time index generated from compiled classes.
3. Give each node kind its own kernel. Verifications, artifact sets and releases get kernel classes with specs, replacing the generic `WorkspaceTargetTask`. In Hangar, "open kernel" on an aggregate node also lists the kernels of its consumed producers.
4. Complete the model:
   - every kernel node has a spec;
   - `tools` lists each node's pinned tools;
   - inputs are recorded;
   - each of the 42 nodes missing a declaration site without explanation gets one.
5. Correct the boundary register: the wrong line, the stale intro, and the two unregistered types.

Acceptance:
- No dependeasy kernel node lacks a spec.
- Every tool kernel lists its tools.
- No node is missing a declaration site without an omission explaining why.
- Each run reports omissions by reason, with counts.

#### WP17 — Cross-repo access only through handles

Goal: no product reaches into another repository's files directly.

Steps:
1. Turn each direct path into a published output or a generated entry:
   - the 14 `"../reaktor…"` paths in product scripts and the 6 in Vite configs;
   - the 18 `link:` entries and 4 imports of `build/dependeasy/kotlin/library`;
   - the `build/js` globs in `pnpm-workspace.yaml`;
   - manna reading bestbuds' docs corpus;
   - `import-closure.mjs`, which is located by path.
2. Use outputs such as `Reaktor.Db`'s web resources and sqlite assets, `Reaktor.Performance`'s web vitals and a docs-corpus output. Generate the `link:` and workspace entries from the web contract.

Acceptance: a model check finds no node input outside its own build root unless a resolved variant delivered it. Report violations as omissions; the target is zero.

#### WP18 — Finish the adapters and consumers

Goal: the adapter table in §10.4 reaches its targets.

Steps:
1. **Worker lists:**
   - the fleet contract (`fleet.json`) becomes the only list;
   - the secrets scripts, Playwright maps, karate base URLs, `recipes.json`, `pnpm-workspace.yaml` and the five Kotlin consumers either read it or are generated from it;
   - `AuthDeclarations` stops regex-reading JS.
2. **remote-dev:**
   - product names, output roots and the `-kt`/`ts/export` heuristics come from `hosts.json`;
   - its seven embedded Python contract readers shrink to one reader of one contract, or to a `reaktor` CLI subcommand that answers its questions;
   - delete its Maestro path.
3. **Ruby, down to about 840 lines:**
   - ids, profile names, groups, tracks and paths come from the release plan;
   - drop `ENV.fetch` defaults that duplicate plan values;
   - delete the dead helpers.
4. **JS:**
   - the contract path, build targets and asset names come from the web contract;
   - delete the second implementation of pnpm workspace membership;
   - a Kotlin authority for JS dependency versions (`Versions.Web`), checked against the manifests.
5. **reaktor-tooling:**
   - remove the 10 script-text regexes;
   - `BestBudsOperationalCatalog` classifies by model node kind and effect;
   - `WorkspaceRepositories` reads the model;
   - `plan` and `run` live in one place.
6. Remove the three remaining non-Kotlin readers of Kotlin source.
7. **Package scripts:** one generated alias per node, never several names for one task; hand-written entries down to about 140.
8. Move the checked-in qualification JSON (about 3,100 lines in `plans/audits/`) out of `plans/` (decision 4).

Acceptance: every row of the §10.4 adapter table reaches its target.

### 10.7 Owner decisions for this round (approved 14 Oct)

The owner approved all four on 14 October 2026.

1. **Push: approved.**
   - Push the committed work and open PRs.
   - reaktor and bestbuds: push `shibasis/newDesign` (16 and 15 commits ahead).
   - manna: its 7 commits on `main` go to a new branch, `shibasis/dependeasy`, with a PR into `main`. Never push manna `main` directly.
   - Push commits only. The trees also hold uncommitted files (136, 208 and 47 on 14 Oct) that belong to the owner and other agents. Do not commit, stash, reset or check out those.
   - Later work packages push to the same PR branches once their gates pass.
2. **Components as projects (WP13): approved.** About eight Gradle projects in bestbuds and four in manna.
3. **Package aliases: approved.** One generated alias per node. Docs point to `reaktor build run <node>`.
4. **Qualification evidence: approved.** Move `plans/audits/*.json` into the receipts store and keep a one-line summary per package in the log.

## Log

- 21 Oct 2026 — K3 installed request-context and immediate-plan follow-up qualified and pushed: Reaktor `ade8deef` / BestBuds `bfd5de2a`. The planner exports under the actual request context before sealing; immediate non-approved execution reuses its prepared plan, while approved execution still re-prepares and checks its reviewed fingerprint. Existing checks pass: 433 kernel cases (11 existing skips), 232 tooling cases (1 existing skip), 16 CLI cases and the kernel boundary. After priming the model with a different shell PATH/options, the installed CLI's first request succeeds without retry: `run-1792556166597-1`, five selected tests, zero failures, exact `--tests` in the sealed plan and correlated receipt. Coverage remains Partial for source identity. Proof: `build/dependeasy/receipts/qualification/wp13/k3-request-context-plan-20261021-v3-proof.json`. Full BuildOption projection, affected execution, parameterized aliases and K7 remain open; no provider write or approval bypass.

- 21 Oct 2026 — WP18 Ruby artifact-path policy removal qualifies and is pushed in BestBuds `1deedd48`: Kotlin resolves the remaining four key/certificate/P12/keychain path options; Ruby reads the generated release contract only. Existing Ruby checks pass 12 cases / 96 assertions; actual default, override, restoration and warm-cache generation preserves the 12 command definitions, 36 static artifacts and canonical node IDs/edges. The optional resolved Android APK stays host-derived. Proof: `build/dependeasy/receipts/qualification/wp13/wp18-ruby-path-options-20261021-v3-proof.json`. WP18's remaining alias/bootstrap/script-reader gates stay open.

- 21 Oct 2026 — WP15 worker test discovery qualifies: all5 verification adapter cases pass, including actual pinned HTTP runners, optional suite membership and cache invalidation/reuse. All three root exports preserve exact canonical IDs/edges. Three configuration-time file probes now reuse the tracked value source; unrelated Coverage work remains uncommitted. Evidence: `build/dependeasy/receipts/qualification/wp13/wp15-worker-probes-20261021-v1-proof.json`. Literal authored-read acceptance remains open.

- 21 Oct 2026 — WP15 tracked JS/native path selection qualifies:20 existing adapter cases pass, including host/iPhoneOS CMake fixtures; all six root exports preserve exact canonical IDs/edges and all three warm caches reuse. One tracked value source replaces optional-file/directory probes without changing explicit tool overrides or JS source selection. Evidence: `build/dependeasy/receipts/qualification/wp13/wp15-path-probes-20261021-v4-proof.json`. Worker contract discovery and final authored-read attribution remain open.

- 21 Oct 2026 — K3 conservative affected selection qualifies as Reaktor `d9bf90b1` / BestBuds `4f00dccb`:8 model fixtures,433 kernel checks (11 existing skips),16 CLI checks,18 installed cases and actual stdio MCP parity across all three owners pass. Unknown inputs remain explicit omissions; no execution or receipt reuse is authorized by the selection. The CLI refreshes an old owner catalog when a new operation is described. Public CLI/build guides retire obsolete parser and deployment claims; their graph projections are committed as BestBuds `b46a6f18`, and live browser verification passes after deployment version `179944bd-e0ee-415e-a9e2-3a61af70a67c`. Reaktor `e562fa4f` fixes same-JVM composite tool-install locking, `8821d868` consolidates package-name discovery into one tracked value source, and `813cb3d0` makes actual deployment commands consume prerequisite outputs. Existing tool/checksum/cache/fleet fixtures and the real deployment pass. All12 routed root help/buildAll configuration runs pass; warm BestBuds/Manna buildAll reuse passes, while concurrent edits invalidate other repeats. WP15 literal authored-read acceptance, WP18 typed parameterized aliases and the remaining23 checkpoints stay open. Evidence: `k3-affected-20261021-v4-installed-proof.json`, `k3-affected-20261021-v6-manna-parity-proof.json`, `wp15-pnpm-inventory-20261021-v2-proof.json`, `wp18-tool-install-composite-lock-20261021-v2-proof.json`, `wp18-worker-deploy-input-order-20261021-v2-proof.json`.

- 21 Oct 2026 — K1 primary entry cutover qualifies and is pushed as BestBuds `ea16068e`: the duplicate 300-line root parser/discovery is deleted, schema variants are validated, and operational build actions share the model owner's freshness check. All16 CLI and433 kernel checks pass (11 existing skips); boundary verification passes. The normal distribution and default installer pass, followed by20 installed shell scenarios across all three roots and outside a workspace, including rejection of seven retired root commands. Completion, lifecycle, expressive build options and remaining caller/archive migration still gate K1. Proofs: `k1-primary-legacy-20261021-v7-proof.json`, `k1-primary-legacy-20261021-v6-installed.json`.

- 21 Oct 2026 — WP15 explicit native activation qualifies and is pushed as Reaktor `050a4a76`: CMake files alone no longer activate a native component or Apple interop. Nine native checks pass, including a decoy CMake/header fixture and the real iPhoneOS static library/cache path. All six real cold/warm root exports pass, preserve every prior canonical node/edge and reuse each warm cache. Literal authored-read acceptance remains open; source membership and remaining native probes are being qualified separately. Proof: `wp15-native-activation-20261021-v4-proof.json`.

- 21 Oct 2026 — K1 machine-outcome prerequisite qualifies and is pushed as BestBuds `a1b56631`: all16 existing CLI checks pass. Success, accepted detach handles and followed receipts now preserve the shared typed outcome; five outcome variants each emit one JSON value with their distinct exit codes. The primary command cutover remains open. Proof: `k1-cli-outcome-20261021-v1-proof.json`.

- 21 Oct 2026 — WP15 workspace-membership prerequisite qualifies and is pushed as Reaktor `6b9a94f3`:8 focused checks pass, including changed/removed workspace membership and configuration-cache invalidation/reuse; worker publication finishes. Real Reaktor help/buildAll and BestBuds help configure with zero cache problems. Full WP15 acceptance remains open: routed PATH, concurrent build inputs and Gradle instrumentation eviction invalidate warm attempts; BestBuds release graph needs its declared devTools=false option. Gradle bytecode identifies JABBA_HOME as toolchain discovery, without claiming the literal report gate green. Proofs: `wp15-workspace-input-20261021-v1-proof.json`, `wp15-gradle-jabba-20261021-v1-proof.json`, `wp15-roots-20261021-v5-runs.json`.

- 21 Oct 2026 — The three historical non-Kotlin source readers are retired: the Worker Python reader is deleted, analytics migration no longer reads Kotlin, and the surface fixture checks file existence without reading Kotlin contents. The migration is not executed. Broader fleet/tooling and parameterized-alias gates stay open. Evidence: `wp18-historical-source-readers-20261021-v1-proof.json`.

- 21 Oct 2026 — K1 input prerequisite qualifies and is committed as BestBuds `d5ba5b64`: all16 CLI checks pass, including actual catalog-command parsing and refusal before invocation. JSON input is bounded; duplicate/conflicting/unknown/wrong-type input is rejected from the owning schema. This does not cut over the installed root commands; K1 remains open. Proof: `k1-cli-input-20261021-v3-proof.json`.

- 21 Oct 2026 — WP15/WP18 workspace-input budget, before edits: four existing files, one private named Gradle ValueSource, no new public API, module, dependency, scheduler or stored format. Keep the single authoritative pnpm parser; move configuration reads behind the tracked source and include membership in model declaration freshness. Extend existing workspace/cache and model-freshness fixtures for changed and removed membership, then inspect real configuration reports. Budget: `wp15-workspace-input-20261021-v1-budget.json`.

- 21 Oct 2026 — WP18 load endpoints and fleet-backed recipes qualify and are pushed: Reaktor `5b51455a`, BestBuds `2363b935` and `d3424781`. All72 load/worker suites consume13 typed deployment endpoints, preserving1623 canonical nodes/edges and caller environment precedence;4 fresh HTTP fixtures and local warm-cache reuse pass. Current installed CLI/MCP read the exact fresh model. Twelve recipe checks pass;21 fleet members are accounted for as20 generated log aliases and one explicitly unavailable telemetry alias with its canonical node. No provider or real agent calls. Proofs: `wp18-load-endpoints-real-20261021-v2-proof.json`, `wp18-load-endpoints-current-consumers-20261021-v1-proof.json`, `wp18-fleet-recipe-20261021-v4-proof.json`. WP18 remains open;23 checkpoints remain open including K1-TUI.

- 21 Oct 2026 — K1 prerequisite budget for WP18 parameterized aliases, before edits: two existing CLI files, no new public API, module, dependency, scheduler or stored format. Reject duplicate scalar/positional fields, JSON/named conflicts, unknown JSON fields, wrong JSON types and ambiguous generated option names; retain ordered repeated arrays. Bound JSON stdin before invocation. Acceptance extends the existing CLI behavior fixture, runs CLI JVM checks and directly exercises the catalog-driven CLI. Full K1 remains open for command cutover, global options, outcomes, completion and installer acceptance. Budget: `k1-cli-input-20261021-v1-budget.json`.

- 21 Oct 2026 — WP18 fleet consumers qualify and are pushed: Reaktor `e2488a15`, BestBuds `8995e090`. One typed fleet reader replaces duplicate parsing, authored-config account voting, D1 directory scans and cloud account regex discovery. Catalog operations retain canonical build identities, definition seals and cache inputs. Relevant fixtures31 pass/1 live opt-in skip; full run639 pass/12 skip/3 telemetry failures, retained separately. Current CLI tests/distributions and Engine compilation pass; upgraded CLI/MCP owners agree with all three canonical models. Proofs: `wp18-catalog-kernel-full-20261021-v3-proof.json`, `wp18-fleet-catalog-distributions-20261021-v2.json`, `wp18-fleet-current-consumers-20261021-v2-proof.json` in the qualification store. WP18 remains active;23 checkpoints remain open, including the separately required K1-TUI milestone.

- 21 Oct 2026 — WP18 web contracts qualify: generated #dependeasy/web imports replace copied contract paths; Kotlin supplies targets, asset names and linked Kotlin package metadata. Seven scoped web cases, ten model cases, eight JavaScript cases/typecheck and all three real builds pass. Manna passes913 browser cases and6087 Pagefind sections; BestBuds builds183 public docs pages and its signed-in site. Every prior canonical node/edge survives, with one generator and two Consumes edges added per root. Mixed build output roots now retain complete agent distributions; the real mixed CLI/kernel build,33 router cases and exact generated-contract fixture pass. Current installed CLI/MCP list/show/tree/explain/kernels agree across all three roots and leave models untouched. Scoped staging excludes concurrent Coverage/product and Manna alias edits. No provider writes. Proof: `wp18-web-final-acceptance-20261021-v1-proof.json` in the qualification store. WP18 remains open for aliases, version authority and remaining consumers.

- 21 Oct 2026 — WP18 public release options qualify: eleven settings resolve once as Kotlin BuildOptions and Ruby reads only their release-plan values. Canonical option declarations project environment key names into hosts.json; the router forwards declared keys and caller values take precedence over release.env defaults. Nine fresh framework cases,33 router cases and12 offline Ruby cases/84 assertions pass. Actual scoped BestBuds baseline/override/restore/warm runs pass with1622 nodes/1579 edges unchanged, exact restored release object and configuration-cache reuse; those product runs routed locally, so remote forwarding is fixture-qualified. The scoped candidate excludes another agent's incomplete untracked Coverage.kt without modifying it; the full current tree is not qualified. No provider writes. Proof: wp18-ruby-options-real-20261021-v5-proof.json in the qualification store. WP18 remains active.

- 20 Oct 2026 — WP18 transport slice qualifies: `hosts.json` now projects public producer outputs, Kotlin exports and artifact roots from the canonical model. Python removes the separate fleet/Apple/package/Dagger/catalog readers. Fourteen adapter cases and33 router cases pass; all three real exports preserve canonical identities/edges. Actual worker run `20261020-233837-b1ff2fb6-bestbuds` reuses its configuration cache, reports the release producer UP-TO-DATE and refreshes an intentionally stale checkout with the exact declared release bytes. Private signing files, failed producers, newer-source publication and whole-distribution guards remain enforced. Tools commit `ae073dc5`; remaining bootstrap host policy and other WP18 consumers stay open. Proof: `wp18-remote-hosts-uptodate-20261020-v1-proof.json`.

- 20 Oct 2026 — WP18 release adapter slice qualifies: Kotlin declares public release ids, seven profiles, groups, tracks and paths through the existing canonical release node. Seven Apple framework cases and12 offline Ruby cases/82 assertions pass; fresh BestBuds1.0(25) generation preserves all12 commands and21 existing artifact paths, and Manna preserves7 commands/15 artifacts. Provider-observed profile names, credentials, keychain restoration and fail-stop checks remain enforced; no provider writes. A rejected earlier checkout comparison exposed UP-TO-DATE public-contract copying in remote-dev, retained as pending WP18 transport work. Proof: `wp18-release-distribution-real-20261020-v2-proof.json`. WP18 remains open.

- WP18 paused React Native retirement qualified 20 Oct: removed all52 obsolete bridge files and the unused0.68.5 dependency pin. Seven focused Gradle checks pass; real three-root exports preserve every canonical node/edge and all25 native contract bytes. Modern React DOM modules are unchanged. Evidence: `wp18-paused-react-retirement-20261020-v1-proof.json` in the qualification store.

- 20 Oct 2026 — WP18 qualified adapter slice: the fleet declares public secret names, service-client identity and endpoints; both secrets adapters and AuthDeclarations consume it. Fourteen offline adapter cases, seven fleet framework cases and ten model cases pass; actual 21-worker generation preserves all 37 final Wrangler configuration files. Canonical catalog/repository checks pass. The duplicate JS workspace parser, obsolete Python Worker reader and remote-dev Maestro path are removed; export copying no longer guesses `-kt` or `ts/export` directories (33 router cases). Tools commits `f58323ba`, `10133300`; WP18 remains open for the remaining table rows. Earlier full kernel run398 had one diagnostic wording assertion failure and11 skips; focused correction passes. Proofs remain under `build/dependeasy/receipts/qualification/wp13/`.

- 20 Oct 2026 — WP18 qualification archive: the final tracked `plans/audits/*.json` (542 lines) moved byte-for-byte to `build/dependeasy/receipts/qualification/archive/kotlin-tools-20261013-evidence.json`; all three plan links follow it. Historical bytes remain retrievable from prior Git history and the retained receipt; no benchmark was rerun.

- 20 Oct 2026 — WP17 final acceptance qualifies:119 Gradle+10 model cases, Java21 classes, all three exact package installs/model exports/Dagger contracts and observed configuration-cache reuse. Real BestBuds browser/docs builds and Manna API879/browser913 pass. All three installed CLI/MCP list/show/tree/explain/kernels are identical and fresh; inspection leaves model bytes and mtime unchanged. Source guard137 files holds except the isolated concurrent telemetry pipeline addition; its previously qualified exact bytes are recovered for partial-index publication. The three telemetry package aliases and private Hangar draft remain working-tree-only. Proof: `wp17-final-acceptance-20261020-v14-proof.json`. WP18 starts with model-backed graph repository discovery and removal of unused duplicate workspace parsing.

- 20 Oct 2026 — WP17 final consumer qualification: Manna API879/browser913 pass; public documentation Pagefind indexes6087 sections. The actual BestBuds run exposed a missing owning-pnpm prerequisite on worker manifests, fixed with the existing canonical After edge, then a package-entry alias intercepting exported subpaths, fixed by exact Vite matching. Existing JavaScript runtime tests and typecheck pass. Private docs draft received existing version1 page-port graph metadata. BestBuds browser/docs, final framework/export/cache and current installed CLI/MCP parity remain active. No WP17 publication or completion claim. Current detailed evidence: `full-status.json`, `wp17-real-manna-20261020-v3.json`, `wp17-javascript-20261020-v1.json`.

- 20 Oct 2026 — Publication complete for qualified WP13/WP14/WP16 work: reaktor `05a810b2abbb3ed02a38d3500808aea7053d3cba` (PR18), bestbuds `80c10eb01a8d5f6227d996434e49108860b5fddb` (PR8), Manna `f86a83602f2e6cc733510f6c7de48d69a7dd0526` (PR2, approved shibasis/dependeasy branch). WP17 is active and uncommitted: resource/source-package/runtime variants, metadata-only Kotlin package contracts, generated links and canonical input ownership are implemented. Earlier focused fixtures and both frozen installs pass; current project-lock/model export fixes, corpus/import removal and final CLI/MCP parity remain under qualification. Full status counts are 10 finished, 11 working, 8 pending domains; 23 open implementation checkpoints include all legacy removals. Current facts supersede older publication-pending log entries. Proofs: `wp16-publication-20261020-v2-commits.json`, `wp16-publication-20261020-v2-pushes.json`; current status: `full-status.json`.

- 20 Oct 2026 — WP16 final local acceptance passes:119 Gradle+10 model cases,1759 Java21 classes,371 guarded source files; final alias-tool fixtures pass. All three canonical models have complete own specs and explained declaration sites; declared tool metadata reaches published aliases. Cold/warm exports and current installed CLI/MCP list/show/tree/explain/kernels match across all three roots. Real native/WebView cold/warm, Security CTest5/5, four graph-owned Web checks and direct Gradle-free kernel checks pass. Kernel380 (11 skipped), CLI16 pass. Seven current Engine UI failures remain; coverage Partial. Focused distribution builds corrected a mixed-output transfer mismatch; broader transport remains WP18. Scoped publication active; WP17 follows. Proof: `wp16-final-model-audit-20261020-v39-proof.json`.

- 20 Oct 2026 — WP13 final local acceptance passes exact450/450 unique Desktop flows, no harness errors, all three shard exits0 and454 guarded files stable (run236f2e1c-1d69-4c70-8bf5-0d76bdef6673). Root/grouping, IntelliJ, Java21/isolation, current CLI/MCP/reaktor-graph and physical Samsung15/15 proofs remain recorded. Scoped publication pending; existing Manna signing and native-input boundaries unchanged. WP14 starts under its parameter-only budget:39 production/fixture files, no new types/modules/dependencies/scheduler; validate existing own-named fixtures and three-root canonical identities/aliases.

- 13 Oct 2026 — plan written; baseline counts in §1; nothing started.
- 13 Oct 2026 — WP0 execution started. Budget: one typed BestBuds settings option; one provider-backed release requirement with a Gradle task recording the effective source; no dependency or version changes. Acceptance: routed `:dependeasy:check`, `frameworkChecks`, BestBuds `help`, `generateDagger verifyDagger pr`, Manna `help generateDagger verifyDagger`, TestKit property-precedence/Xcode-release fixtures, an iOS release guard on m1-worker, and a fixture test of unauthenticated `inspect.*`. Commit only the build-layer hunks listed by WP0; retain the internal Gradle graph experiment outside those commits. Storage cleanup passes (92 GB free; temporary 14.2 GB and remote 12.6 GB).
- 13 Oct 2026 — BestBuds `help generateDagger verifyDagger :kernel:test --tests '*KernelInspectionAccessAuditTest'` passed with Manna excluded (60 Gradle tasks, 46 seconds). The MCP exposure is confirmed using synthetic data only; the audit source remains under `plans/audits/`. Fastlane release regression suite passed: 15 tests, 104 assertions. Dependeasy release fixtures are still being qualified; WP0 is not complete.
- 13 Oct 2026 — Worker `:dependeasy:check frameworkChecks` passed: 60 Dependeasy tests and 74 framework tests, zero failures/errors/skips; receipt `.remote-dev/logs/20261013-232339-5e8ed42a-reaktor.json`. Worker iOS release with mirrored local devTools enabled stopped at `:app:requireBestbudsDevToolsDisabledForRelease`, naming `local.properties`; no app release link ran; receipt `.remote-dev/logs/20261013-232801-c946dc9c-bestbuds.json`. BestBuds `pr` exposed two pre-existing uncommitted code-analysis test cleanups rejected by `SafeDeleteGuardTest`; their cleanup now matches `CodeWorkspaceTest` using a non-following `Files.walk` of the test-owned temporary root. These two edits remain outside the WP0 commits, preserving the uncommitted analysis work.
- 13 Oct 2026 — WP0 paired commits: reaktor `4786a91857c86700f949ee3c506638805e3a14af`, bestbuds `6129a3eeac307f30c2583c8c7aac8c9228286cec`; no Manna dependent change existed to commit. The internal Gradle graph experiment and its test, unrelated library pins, OAuth/Xcode edits and the staged `ReaktorV2.pdf` remain outside these commits. Worker `pr` completed with 486/487 engine tests passing; its sole failure is the corrected cleanup guard; composite `buildAll` and a local rerun remain to qualify WP0.

- 13 Oct 2026 — WP1 preparation started. Budget: three modules (`model`, stable `toolchain`, fast `gradle`), shared compiled Kotlin plugin pins through `buildSrc`, two generated toolchain contracts, and one bytecode check. No version bump. Acceptance: Dependeasy TestKit/check, Java 25 rejection fixture, zero non-Kotlin source readers, paired warm BestBuds Android/iOS re-execution counts by task class, and configuration-cache reuse in reaktor/bestbuds/manna. Activate the split after WP0 composite/local acceptance completes; publish local Maven artifacts first to inspect the one-version coordinates.

- 14 Oct 2026 — WP0 composite qualification found the existing cleanup guard also rejects `CodeWorkspaceTest` despite its fresh temporary root and non-following reverse-order `Files.walk` cleanup. Budget extension: recognize that exact fixture-owned cleanup shape in the existing guard; no production delete API or dependency change. Acceptance: local `pr`, including the cleanup guard, then the warm composite aggregate.

- 14 Oct 2026 — Composite `buildAll -Pbestbuds.devTools=false` completed in 29m32s (5,994 tasks: 5,091 executed, 600 cache, 303 up-to-date); its sole aggregate failure was the cleanup guard, corrected in bestbuds `73da2a7c`. Receipt `.remote-dev/logs/20261013-233953-588f6c33-bestbuds.json`. Local `pr` and CI opt-in help were refused before Gradle by the remote storage budget; cleanup and reruns are pending.

- 14 Oct 2026 — Local `pr buildAll -Pbestbuds.devTools=false` ran 6,060 tasks (435 executed, 1 cached, 5,624 up to date) in 30m46s. `pr`, Reaktor buildAll, app/Android builds and both release iOS framework links completed; engine 487/487 and app JVM 273/273 passed. Hangar recorded 224 passed flows, four failures and incomplete coverage after this chat stopped its remaining fixture child; the composite therefore fails and is not claimed green. Receipt `.remote-dev/logs/20261014-001234-7b426002-bestbuds.json`. Carry this application-flow qualification gap while advancing the ordered build-layer packages. The queued CI configuration was again refused before Gradle by the remote storage budget; rerun after cleanup.

- 14 Oct 2026 — CI `help -Pbestbuds.includeManna=true` passed with no Manna configuration. Activated WP1 source move, preserving dirty files. Two credential-containment smoke scripts need their authored source paths updated mechanically after the move; this extends the consumer budget only for those paths. No production policy changes.

- 14 Oct 2026 — WP1 `:dependeasy:check :dependeasy:buildSrc:test :dependeasy:publishToMavenLocal` passed in 1m19s: 60 plugin tests plus two bytecode fixtures, zero failures/errors/skips; all 700 shipped classes have major version 65. The model, toolchain and gradle POMs all use `dev.shibasis.dependeasy`, version `2026.1.6-SNAPSHOT`; fast runtime dependencies exclude AGP/KGP/Compose. BestBuds and Manna `help generateDagger verifyDagger` pass and store configuration-cache entries. Both generated Dagger modules pass a strict TypeScript check using BestBuds' shared SDK through `/Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/wp1-dagger-tsconfig.json`.

- 14 Oct 2026 — WP1 first Android/iOS probe passed in 2m36s (952 actionable tasks; 771 executed, 158 cached, 23 up to date). Warm repeat passed in 8s; its 41 foreign executions are always-running diagnostics/metadata: 29 KMP partially-resolved dependency checks, one Kotlin npm cache setup and 11 Compose runtime compatibility checks. Zero foreign compilation/packaging tasks executed. Reaktor, BestBuds and Manna all reuse configuration-cache entries. Compare the private-function edit against this measured baseline; do not disable correctness checks merely to force the literal total to zero.

- 14 Oct 2026 — WP1 private edit exposed two native rebuild causes: CMake configuration comparison splits multiline tool identity/contract values through `readLines()`, and `NativeExportsTask` rewrites identical headers. Budget extension: compare the exact serialized configuration and preserve unchanged generated header content; no new public API, field or dependency. Acceptance: repeat the warm/edit Android+iOS probe and count any additional foreign task executions against the 41-task diagnostic baseline.

- 14 Oct 2026 — WP1 final native fix/edit probe passed in 14s: the 41 always-running AGP/KGP/Compose diagnostics stayed unchanged; zero additional foreign executions and zero foreign compilation/packaging executions. Counts and receipt refs: `/Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/wp1-task-executions.json`. Restored the private probe edit, then final check/local publication passed in 49s (receipt `.remote-dev/logs/20261014-010125-5f517ceb-reaktor.json`). Paired commits: reaktor `bfe3eab1`, bestbuds `17a14295`, manna `8ad7070`. External Maven publication awaits both configured credentials; local POMs and jars are qualified.

- 14 Oct 2026 — WP2 started. Budget: about 15 model types, one typed export replacing the three declaration projections, one shared reader, one CLI command, one MCP read tool, one Hangar panel; capture DSL declaration sites and kernel references without changing current task wiring. Put tools under git before editing the remote host reader; delete graph-run only after checking callers. Acceptance: deterministic exports and configuration-cache reuse in all three builds; schema/size/digest/identity fixture tests; lossless catalog/effect mapping and injective operational IDs; CLI/MCP equality plus source navigation and one local declared verification run with a receipt. No task closure in the model, no schema-1 fallback.

- 14 Oct 2026 — WP2 qualification passed in reaktor, bestbuds and manna twice with byte-identical exports and configuration-cache reuse. Models contain 52 / 97 / 68 nodes. Shared-reader, digest, identity, catalog/check-start, source-navigation, Hangar render/performance and CLI/MCP-equality tests pass. The packaged primary CLI ran real `bestbuds:jsChecks` successfully: `run-1791921995246-1`, router receipt `.remote-dev/logs/20261014-013648-308f4f2e-bestbuds.json`. Counts and qualification limits: `/Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/wp2-qualification.json`. Deleted the old projections and graph-run scheduler. Budget expanded only for model enums/facts, a second declaration site on shared CMake tasks, and existing primary CLI/inspector seams. Foreign producers retain omissions for WP3; no installed service was changed.

- 14 Oct 2026 — WP2 local commit: reaktor `4618aca4ffc71de9d8d8b4a462e4e05ac56f052b`. Only WP2 hunks are committed; concurrent source and staged artifacts are retained.

- 14 Oct 2026 — WP2 local commit: bestbuds `aff8bb1e977d072abdfe914c7ae1df92c5ad3373`. Only WP2 hunks are committed; concurrent source and staged artifacts are retained.

- 14 Oct 2026 — WP2 local commit: tools `c2ade08f8907badc55f4fc496996c20f42233bcc`. Only WP2 hunks are committed; concurrent source and staged artifacts are retained.

- 14 Oct 2026 — WP3 preparation started. Budget: nine handle kinds (module, build, output, node, role, tool, option, environment and lane), public output variants and a replacement workspace DSL; no version changes or compatibility string overloads. Use Gradle project accessors and owning KGP/AGP providers. Acceptance: save every model node’s routed Gradle dry-run task list before migration; compare after with explained output-variant differences; TestKit variant resolution, option precedence/requirements, catalog consistency and rename failures; configuration-cache reuse in all three roots; inventory path/name/module/property references. Product option declarations must stay in product build logic; small compiled product catalogs may be required for use across scripts.

- 14 Oct 2026 — WP3 root-variant probe passed with normal composite dependency substitution and configuration-cache reuse. Budget clarification: workspace aggregates need a task-owned completion manifest as their public variant artifact; this carries actual producer dependencies across builds. No cross-build task fallback is needed for these roots. Root project coordinates will be explicit, and output selectors use one enum-derived `dependeasy.output` attribute, with enum qualifiers for build type, Apple target and verification suite.

- 14 Oct 2026 — WP3 publication budget clarification: replace dynamic lifecycle task paths with owned publication completion variants and a Local/Remote enum. The bootstrap build publishes lifecycle variants directly because applying its own fast plugin would create a cycle. Delete the unused cross-build publication API; the product Node-test helper remains and uses public KGP binary/test providers instead of task selectors. Use the Gradle lifecycle constants listed in the boundary register.

- 14 Oct 2026 — WP3 task-plan comparison exposed loss of Android native producer dependencies: a fresh DirectoryProperty created in the AGP callback carried a path but no task ownership. Budget extension: one Sync subclass exposing its annotated generated directory. Acceptance: Android native dry-run retains CMake and copy producers; rebuild the AAR and iOS native outputs, inspect all configured JNI ABIs, and repeat the complete task-plan comparison. The KSP registration regression now has a passing configuration-cache TestKit fixture.

- 14 Oct 2026 — WP3 final ownership review: move bootstrap lifecycle publication into one compiled buildSrc helper invoked separately by model, toolchain and gradle. The root only resolves variants; it must not register or look up child tasks. Acceptance: bootstrap check/assemble/local-publication plans retain all three owners, existing test suite passes, and repeat the three-root cache/export and complete node comparison. This replaces the prior script helper within the recorded publication budget.

- 14 Oct 2026 — WP3 inventory includes the disabled historical React build script: its three project references now use Gradle accessors too. It remains excluded in settings and its pre-existing missing flatinvoker-core project/obsolete DSL remain outside this package. No React functionality is claimed.

- 14 Oct 2026 — WP3 identity review caught a receiver-shadowing rename of interopTypeScriptVerify while replacing its string reference with a provider. Qualified the class owner explicitly to retain the baseline node identity. Bootstrap composite dry-run now includes all six plugin-test producer tasks plus its owned completion task. Repeated bootstrap checks pass after the correction.

- 14 Oct 2026 — WP4 preparation while WP3 task comparison runs (no WP4 source edits yet). Budget: seven JS kernel task classes, explicit package-command/lock task types, a shared process-task input contract with an internal process runner, and typed process/native/source/tool/size specs on the existing model. Reuse the installed Node/pnpm tool chain and existing native/source/tool/size owners; move Kotlin exports into owned build outputs and update affected adapters/lock references. Register bindings in the seven current packages, centralize size evidence, and constrain the two SDK/extraction deletes to children of their output. Acceptance: TestKit output/up-to-date/delete tests for each kernel, node_modules deletion rerun, real app jsMain invalidation of web bundle/deploy, model specs visible in the CLI, and three-root cache reuse. No new compiler/bundler implementation or missing-tool fallback.

- 14 Oct 2026 — WP5 API preflight measured on pinned Gradle 9.7.0: two Tooling API dry-runs emit zero TASK events; a real run emits descriptor dependencies. Public TaskExecutionGraph.getAllTasks/getDependencies exposes the already selected dry-run graph, including a cross-build output-variant edge, without computing a closure or parsing console text. Its whenReady hook is not repeated on configuration-cache reuse. Implementation adjustment: the Tooling API planner requests a fresh selected graph with configuration cache disabled and caches plans by validated model digest. Execution progress/receipts use task events with normal configuration cache retained. Evidence: /Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/wp5-api-probe.json.

- 14 Oct 2026 — WP5 cache correctness requirement: the validated model digest must include the fast plugin implementation inputs, because a dependency-wiring change in plugin Kotlin can change Gradle's selected plan without editing a build script. Extend the existing declaration-input projection in its owner; do not add a separate cache authority. This is within the planner's correctness scope and precedes its implementation.

- 14 Oct 2026 — WP3 qualification found routed dry runs transferring old artifacts and potentially installing an APK when an install task is requested. Budget extension before edit: one dry-run guard in the existing remote executor, with a behavioural fixture for both Gradle dry-run spellings and the ordinary build path. Keep load routing and the actual Gradle invocation unchanged; suppress artifact transfer, Xcode-resource transfer and device installation only for dry runs. Acceptance: fixture observes no transfer/install calls, an ordinary mocked install still transfers/installs, and the remaining routed node captures retain their Gradle task sets.

- 14 Oct 2026 — WP3 task comparison resumed after correcting the audit harness: Invocation.Gradle tasks are relative to NodeId.build; included-build execution needs the workspace prefix, as the existing CLI adapter already supplies. Successful captures are retained; failed harness invocations are rerun. No production code change was needed.

- 14 Oct 2026 — WP3 commit preparation excludes the overlapping Hangar desktop script while the question about including the pre-existing sharding helper is pending. The typed desktop output wiring remains in the working tree and its task-plan qualification continues. No pre-existing helper will be staged without an answer.

- 14 Oct 2026 — WP4 integration budget adjustment before activation: remove the unused GradleTaskGraph experiment now, because it references the CommandTask.backend API being replaced. Its deletion was a WP5 step; advancing only this prerequisite avoids rewriting an obsolete internal-API reader. The original uncommitted file is captured in the temporary baseline for review. WP5 still supplies the public planner.

- 14 Oct 2026 — WP3 qualified: 217 baseline node identities retained, 211 Gradle captures (209 unique), six external invocations unchanged, zero failed captures and zero unexplained task-set differences. Final exports are byte-identical with configuration-cache reuse in all three roots. 65 plugin, three model and two bootstrap tests pass; rebuilt AAR/iOS native outputs retain all configured JNI ABIs. Inventory and evidence: `/Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/wp3-qualification.json`. Paired commits: reaktor `0f3d9595`, bestbuds `d3655c20`, manna `4a1d5d6`. Typed desktop and pre-existing reaktor-code overlap remain uncommitted; unrelated staging is preserved. The routed dry-run transfer fix is tools `fba11a1`.

- 14 Oct 2026 — WP4 routed compilation exposed a router bug: worker execution appends the root model task even when Gradle `-p` selects the standalone bootstrap build. Budget extension before edit: respect the selected Gradle project directory when adding router metadata, with mocked root/nested/equals-form cases. Keep load selection and output transfer unchanged. Acceptance: router fixture and the same routed bootstrap compile.

- 14 Oct 2026 — Router selected-build fixture passes: six root/nested/project-option cases plus the three dry-run transport cases. Fix committed locally as tools `e2e2bb5`; retry the routed standalone plugin compilation.

- 14 Oct 2026 — WP4 model review: typed specs must use owner-relative workspace paths, like the existing file sets, so local/worker checkout locations do not change declared facts. Budget clarification: normalize path-bearing fields in the typed spec projection; retain exact tool identity, versions, arguments and external SDK paths. Acceptance: typed fixture spec round-trip plus repeated real model exports.

- 14 Oct 2026 — WP4 corrected full suite passed on m1-worker (router receipt `20261014-053252-6a6c0b77-reaktor`): 68 plugin tests and three model tests, no failures; 962 classes use Java 21 bytecode. A subsequent check was blocked by the 20 GB remote storage budget after output transfer. Close completed builds and run `dev-storage cleanup --wait` before retrying; retain the budget guard.

- 14 Oct 2026 — WP4 final model qualification found bindings and repository tool/install kernels were registered as Gradle tasks without declaration-model references. Budget clarification: register those references at normal DSL configuration through the existing workspace owner; no new node type or graph-time mutation. Acceptance: all seven bindings declarations and installed-tool/install specs appear in the real exported model. Correct the process fixture to repeat the same requested task set, and disable retained file watching in the script-rewriting boundary fixture so its cache checks observe the changed script.

- 14 Oct 2026 — WP4 real export exposed an eager-query violation in native header arguments: mapping a producer task output to a string demands completion during model export. Budget extension: the header owner declares one shared output-location provider for its task and command argument, while the input file retains the producer-backed provider. Acceptance: export without executing native producers, configuration-cache reuse, and existing interop output tests.

- 14 Oct 2026 — WP4 declaration projection adjustment: enumerating TaskInputs files from the root exporter triggers forbidden cross-project configuration resolution. Add one typed file-input declaration (file/tree/configuration/producer/deferred provider) and project public ConfigurableFileCollection declarations without resolving them. Gradle task annotations remain the actual execution-input authority. Acceptance: inspect all models without running producers, deterministic/cache-repeat exports, and fixture output invalidation. Deferred providers are explicitly represented rather than claimed as resolved files.

- 14 Oct 2026 — WP4 real Karakum runs found the authored custom library names were interpreted as installed package names by its default node input strategy. Select the supported plain strategy in all seven local-source configs, retaining their library/module names. The pinned installed Karakum implementation resolves plain inputs from the declared working directory. Acceptance: six populated declarations run and repeat incrementally; Tactile keeps its missing-source placeholder. BestBuds size-check argument now reads the declared report location without demanding report generation during inspection.

- 14 Oct 2026 — Pinned Karakum alpha.112 CLI reifyConfiguration drops inputResolutionStrategy even though its public GenerateOptions/API supports it. Use the installed public generate API through minimal Node glue inside the typed task; no compiler implementation, version bump, CLI fallback or third-party patch. Acceptance: the actual local-source config is honoured, with output and repeat evidence. CLI usage reference: https://github.com/karakum-team/karakum/blob/master/docs/guides/Basic_usage.md; pinned implementation/API inspected locally.

- 14 Oct 2026 — WP4 full web qualification regenerated bindings with alpha.112's newer void namespace, incompatible with the unchanged Kotlin wrapper pin. Add one supported Karakum converter plugin for void type arguments in the existing Node API glue, retaining Unit return types and the current pin. Acceptance: regenerate without editing ts/import, then compile the full Cloudflare-consuming BestBuds web path. The typed fixture also exposed eager declaration-tree enumeration as a configuration-cache input; keep the public file tree deferred in ExportBuildModelTask's input collection so execution snapshots, rather than configuration, observe those files. Acceptance: unchanged requested tasks reuse configuration cache while output/receipt files change. Advance WP6's appWeb naming correction only as needed for WP4's real bundle/deploy input acceptance; no external deploy.

- 14 Oct 2026 — A live root file tree is unsuitable for declaration inputs: Gradle treats its root as overlapping every kernel output, even when those outputs do not match its patterns. Budget extension: one public ValueSource returns only authored Gradle declaration files, isolating directory enumeration from configuration-cache filesystem tracking and output dependency inference. The exporter still snapshots each resulting file as its execution input. Acceptance: typed fixture reuses configuration cache and no implicit producer dependency; three real root models stay deterministic.

- 14 Oct 2026 — Google bindings' only authored declaration is the TypeScript-only Optional<T> helper; Karakum emits typealias Optional<T> = T?, which Kotlin rejects and no Kotlin consumer imports. Use Karakum's supported ignoreOutput for that one helper, keeping the TypeScript export intact. Acceptance: regeneration removes the unsupported generated alias and the full product JS compilation passes. Cloudflare's converter compatibility remains exercised by its real Promise<void> declarations.

- 14 Oct 2026 — WP4 no-cache real probe found pnpmInstall consuming the compiled Kotlin export's package.json as an unowned linked-file input. Installation already depends on KGP's root package metadata through syncKotlinPnpmMetadata; exclude compiled export manifests from installation inputs using the output owner's fixed relative directory. Bundlers continue to consume the full output through KotlinJsLibrary variants. Reuse one constant on KotlinPackageExport for its owned directory; no new scheduling order or compile-before-install cycle. Acceptance: ordinary/no-cache full web builds and the real JS edit probe pass Gradle input validation; frozen installation and node_modules deletion fixture remain green.

- 14 Oct 2026 — WP5 implementation budget before source edits: extend the existing model with a selected Gradle plan, typed task outcomes/durations and receipts; one public Tooling API bridge with planning/execution entrypoints on the load-selected host; one task-completion service; one receipt-backed RunLedger and WatchService delivery; integrate plan facts and receipt artifacts through the existing process/approval port. Transport must synchronize only bounded private request contracts and return typed plans/receipts; structured progress is an explicit protocol emitted by Tooling API events, never inferred from Gradle console text. Acceptance: TestKit exact selected dependencies, configuration-cache repeats, source-digest staleness, failure redaction and receipts from ordinary/bridged invocations; compare the five specified real dry-run node sets; packaged CLI run plus external/Xcode-phase receipt visibility. No version bump, internal API, parallel task catalog or independent scheduler.

- 14 Oct 2026 — WP4 packaged Manna model read found a linked agent worktree counted as declaration files owned by Manna. Budget extension before edit: exclude agent state/worktree folders and symbolic-link directories in the existing declaration ValueSource, and cover an out-of-root linked build script in a behavioral export/read/cache fixture. Keep the reader’s escape check. Acceptance: all three real root reads and fourteen CLI kernel specs, plus the focused boundary fixture.

- 14 Oct 2026 — WP4 qualified and locally committed: reaktor `ce84933977cbf959600b1fc4ab9359d06128dde4`, bestbuds `74e2b494d08bcae492aab126799a7058bb7b5efd`, manna `2e3d69b3b0952a34f7975371b239eb828aa272bb`; declaration boundary follow-up `7566b79d0ff8ebb9ac248976e8ea1e732f921a90`. Full suite: 68 plugin tests, three model tests and nine CLI tests pass; the new boundary fixture separately passes all four BuildWorkspace tests. Java 21 verified on 966 classes. All fourteen typed kernel specs are visible through the packaged CLI on real nodes. Three roots export byte-identically with configuration-cache reuse; six available binding tasks repeat up to date, and Tactile stays a declared missing-source placeholder. The real app jsMain edit changes both bundle and deploy input digests; restored source restores both digests. Evidence: /Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/wp4-testkit.json, /Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/wp4-js-inputs.json and /Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/wp4-qualification.json. Generated bindings are tool-produced and left unstaged; unrelated staging is preserved. No external deployment.

- 14 Oct 2026 — WP5 composite planning evidence: public getDependencies() reports the already prepared dependeasy:model:jar artifact outside the selected dry-run graph. Retain those edges explicitly as dependenciesOutsideSelection rather than add tasks Gradle did not select; selected dependencies remain validated against the exact task set. A public FlowProviders.buildWorkResult finalizer is being qualified to include configuration failures and final build status in the same receipt listener, as required by “every run.”

- 14 Oct 2026 — WP5 real CLI admission exposed two existing wrapper losses: its selected environment was applied after Gradle planning, and artifact expectations replaced the shared planner's required receipt index. Pass the existing private environment/secret-key inputs through DiscoveredJvmWorkspace preparation and preserve the planner's artifact expectations through OperationalControlPlane. No new environment selector or artifact authority. Xcode's existing static-framework phase has already executed successfully using its actual iPhoneOS build settings, and its verified receipt appeared in the shared Hangar state.

- 14 Oct 2026 — WP5 host parity check found remote-dev appended exportBuildModel to remote dry-runs although local dry-runs preserved the requested task vector. That extra metadata task cannot update a model during dry-run and changes the displayed task set. Exclude --dry-run/-m from this existing append behavior; retain export on actual builds. Eight selected-build transport subcases qualify the change.

- 14 Oct 2026 — WP5 final freshness review: compiled root buildSrc Kotlin participates in declaration ownership, alongside dependeasy implementation sources. Extend the shared input policy and its behavioral content-change fixture before commit; acceptance is stale-model rejection on a root buildSrc edit and unchanged configuration-cache reuse. Investigate Manna plan-cache context using temporary per-key hashes only; no private values enter public evidence.

- 14 Oct 2026 — WP5 Manna repeat selected identical task/dependency sets but macOS changed APP_ICON_<pid> between launcher processes. Temporary per-key hashes identified only those two launcher keys. Exclude that exact macOS UI metadata pattern from the context digest; preserve all execution environment inputs. Extend the existing bridge fixture with a changed launcher id and qualify byte-identical repeated planning, execution and receipt correlation. Temporary diagnostics removed from source.

- 14 Oct 2026 — WP5 qualified and locally committed: reaktor `793c92f088cf6806ff9fe921ef4f6b6c6db55016`, bestbuds `d60d80892ebe324a737d23948745267ebda26dc2`, tools `88504da170243a0ad37f05bded26beed6439546a`. Five real selected plans match Gradle dry-run output exactly, including Manna buildAll (3,913 tasks), and repeat byte-identically. Full TestKit: 74 plugin, four model and two buildSrc tests pass; final bridge/build-workspace freshness subset passes nine plugin tests. Java 21 verified on 1,015 classes. The existing Xcode static-framework phase and packaged CLI jsChecks run both succeeded and produced verified receipts visible in Hangar state; CLI receipt matches its operation id and explicit task progress. Public final-build-result flow covers configuration failures; no console parsing in production. Evidence: /Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/wp5-qualification.json, /Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/wp5-plans.json and /Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/wp5-api-probe.json. Unrelated staging preserved; no publication or deployment.

- 14 Oct 2026 — WP6 budget and acceptance before edits: two public contracts (web component and worker fleet), one web contract writer and final Wrangler config writer, extend the existing fleet and package writer, and move the existing import-closure generator into the JS kernel. Kotlin owns environments and config precedence (standalone dev; recursive workspace defaults then local prod, arrays replaced). Preserve source configs and runtime JS behavior. Generate all 20 deployable declarations, remove duplicate deployment scripts/catalogs, and have Vite/tsconfig consumers use generated facts. Acceptance: behavioral contract/config/script-generation fixtures with cache reuse; all 20 real fleet entries and declaration sites; semantic comparison of every final dev/prod config; actual configServer Wrangler dry-run for both environments; CompanyOS/Manna builds and no Kotlin output paths in authored Vite/tsconfig; import-closure fixtures; script counts. External worker deploy is a final owner approval step after these reviewable artifacts exist. No new JS file except the moved generator, no version bump, no new deployment scheduler.

- 14 Oct 2026 — WP6 implementation budget clarification: carry the existing TypeScript parser API dependency (6.0.3, already ToolchainVersions.TypeScriptApi) with the relocated import-closure generator. Preserve test-only and operational Gemini recipes; remove the build/deploy entry points. The former appBrowser deploy alias pointed at the misnamed deployAppWeb and is removed when that target is corrected to the appWeb site.

- 14 Oct 2026 — Advance the WP10 build-list read command only to satisfy WP6 acceptance: read the shared model, filter deployable worker targets, and expose their full IDs and declaration sites. No new catalog or execution path. Manna Pagefind owns the whole public/pagefind directory, including its atomic staging directories; declare that output boundary so parallel typecheck hashing cannot race the generator.

- 14 Oct 2026 — WP6 worker qualification exposed Manna's legacy npm facade resolving stale ts/export files on the Mac. Extend the existing web contract writer to read linked facade metadata and map its published name to the declared Kotlin output only when the workspace dependency and entry basename match. This removes the stale output dependency without adding another contract or handwritten alias. Acceptance: facade fixture points to the declared output, and the same Manna web path succeeds through the configured router.

- 14 Oct 2026 — WP7 budget and acceptance before edits: one Xcode host writer/check, one release contract writer, one Darwin archive checker and one signing xcconfig writer. Product Kotlin owns existing iOS 16.0, Swift 6.0, bundle/team/BGTask/version facts; preserve existing SwiftPM versions and signing authority. Generate tracked host xcconfigs, combine app/extension Gradle phases, replace plist literals and release signing patch, and derive adapter installation from framework exports. Move release command tokens/artifact ownership and privacy/dSYM qualification into Kotlin; retain fastlane provider/profile/keychain mechanics and keep signing material tracked. Remote Xcode variable names come from its generated host contract. Acceptance: behavior tests for host drift/pins/command tokens/archive UUID+architecture+privacy/signing output; actual generated settings and composite physical iPhoneOS framework path; release lane must preserve tracked sources. Prepare a concrete signed archive/export before requesting an owner-present internal TestFlight upload. No simulator, experimental SwiftPM import, source dependency upgrades, new scheduler or C++ change. CMake deployment-target application follows in WP8.

- 14 Oct 2026 — WP6 qualified locally: all 20 deployable targets have declaration sites; 36 generated environment configs match previous merge semantics. Real configServer Wrangler dev/prod dry runs pass without a live deploy. CompanyOS and public appBrowser bundles pass; Manna passes 99 test files / 796 tests and bundles, with repeated configuration-cache reuse. Twelve Gradle contract/backend fixtures, nine JS fixtures, four import-closure fixtures and five transport fixtures pass. The worker rerun exposed a pre-existing npm facade depending on stale local ts/export files; generated aliases now point to the declared Kotlin producer and the full Manna path passes. Script counts and pending live acceptance are recorded in /Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/wp6-contracts.json; remaining suite/environment aliases are handled in WP9. Owner choice for the concrete config-service dev/prod deployment remains pending; continue local WP7 work.

14 October owner decisions: include the existing Hangar sharding task and its helpers in the typed build migration. Keep Reaktor Tactile source-free; its bindings task has a recorded missing `ts/index.ts` input and is not qualified. Deploy config-service dev and prod through the Kotlin-generated configs.

- 14 Oct 2026 — WP7 archive qualification found Xcode launch-environment version arguments were overridden by xcconfig defaults. The release contract now passes the same arguments as an explicit Xcode build setting. Two archive attempts also exceeded the combined temporary budget while Xcode compiled SDK modules; constrain this existing xcodebuild invocation to three parallel jobs, matching the workspace build resource limit. No new user option or scheduler.

- WP7 archive qualification: a bounded file-size trace identified Xcode 26 compilation-cache sparse files (`v8.data` 17,179,869,184 bytes, `v8.index` 8,589,934,592 bytes, `v3.actions` 1,073,741,824 bytes) as the apparent-size storage blocker. Keep the 20 GB temporary budget unchanged. The Apple host contract disables optional compilation caching, and the single Kotlin xcodebuild builder passes that setting globally so SwiftPM targets obey it. Apple documents `COMPILATION_CACHE_ENABLE_CACHING` in its build-settings reference. The aggregate phase also receives the app resource folder through generated settings for Compose resource synchronization.

- WP7 archive retries with both cache settings disabled still initialized the 26.8 GB generic store. The installed libclang recognizes LLVM_CAS_MAX_MAPPING_SIZE; the release contract now caps each mapped cache file at 128 MiB using LLVM’s supported override. Storage accounting and budgets are unchanged. Qualify the next real archive before claiming this fixes the blocker.

- 14 Oct 2026 — WP8 budget and acceptance before edits: one native catalog and one generated contract type; extend the existing NativeLibrary declaration and CMake task rather than add a scheduler or CMake functions. Declare native module dependencies, platforms and ABIs explicitly. Kotlin owns source handles, pinned AAR checksums, exact Prefab imports and platform/compiler/SDK/deployment facts; CMake consumes the generated preload/target contract. Keep the current AGP public variant JNI producer because this repository already builds native libraries through its owned CMake tasks rather than AGP externalNativeBuild. Acceptance: existing real native composition/ownership fixtures, changed-contract reconfiguration and cache reuse, actual Android and physical-iPhoneOS native libraries with before/after artifact evidence, and removal of authored CMake source-path/version/probing policy. No C++ edits or dependency upgrades.

- 14 Oct 2026 — WP7 local qualification: committed Kotlin Apple host/release/signing/archive contracts, the OAuth policy move and thin host consumers. The signed BestBuds 1.0 (25) archive/export passed with two matching binary/dSYM UUID sets; SHA-256 `70fb93266bc5f30abb76b2dd38ef612f6ede7dd890f498b2b1662b009bb2ddcd`. Doctor reports TestFlight build 23. Owner-present internal TestFlight and Crashlytics uploads are pending the already-presented approval request. Installed LLVM CAS mapping is capped at 128 MiB through its supported environment option; measured archive temporary peak was about 2.412 GB apparent, within the unchanged combined 20 GB limit. See `/Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/wp7-apple.json`. The Ruby estimate of 840 lines was not achieved (about 1,044 retained); provider/profile/keychain authority was preserved.

- 14 Oct 2026 — Owner approved internal TestFlight and both Crashlytics uploads while present. The exact qualified build 25 IPA SHA-256 is unchanged. Both dSYMs passed the Kotlin provider-receipt check; App Store Connect accepted the package at 11:23:13 IST. Apple build-list processing and release-note attachment remain in progress. The qualified-IPA reuse adapter is committed; its 9 Ruby tests / 39 assertions pass. Server-side symbol processing and physical crash frames remain unqualified.

- 14 Oct 2026 — Internal TestFlight lane completed successfully at 11:25:58 IST: build 1.0 (25) appeared, release notes were attached, and external distribution remained disabled. Crashlytics accepted both qualified dSYMs; its backend processing and physical crash frames remain separately unqualified. WP7 delivery acceptance is complete.

- 14 Oct 2026 — WP8 committed as 050f339efadc8fde323086852e8086dd09c0689c. All 91 Gradle and 4 model tests passed, with plugin validation/local publication. Actual Android and Apple native libraries and the desktop WebView build passed; the identical warm command reused configuration cache and all 11 CMake producers were UP-TO-DATE. All 95 baseline static archives remain present: 47 match; 48 Apple archives differ after correcting the old CMake iOS 13.0 default to the existing Kotlin iOS 16.0 requirement. Exact hashes, JNI AAR entries and source/compiler/SDK contracts are in /Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/wp8-native.json. No C++ or dependency pin changed.

- 14 Oct 2026 — WP9 budget and acceptance before edits: three verification kernels (DeviceFlow, KarateSuite, K6Suite), one device-flow contract and one Dagger pipeline contract. Reuse InstallTool for checksummed native runner, Karate jar and k6; move the existing MaestroRunner engine behind a compatible tooling facade. Kotlin owns physical-device selection, app/output references, suite membership/environments, generated aliases and pipeline/host facts; preserve product fixture/age-gate and signing/provider hooks. Keep uninstall opt-in and prohibit emulator/simulator startup. Replace product generated TS with one shared Dependeasy Dagger module that invokes the declared Gradle target without scheduling dependencies. Preserve manual CI dispatch/billing and deploy secrets. Remove obsolete harnesses and task-name routing heuristics. Acceptance: behavioral runner/tool/pipeline/transport fixtures, real Samsung and iPhone flow receipts through the shared CLI, real pinned Karate/k6 qualification, shared Dagger PR container gate, three-root contract/cache qualification and no product generated TS. Read the pinned Dagger 0.21.8 implementation before choosing module loading; no upgrade, new MCP server or second scheduler.

- 14 Oct 2026 — WP10 budget and acceptance before source edits: four existing-gateway read tools, five build CLI commands and one Hangar Build inspector family. Extend the shared model reader for relations and bounded retained plan/receipt pages; carry the exact sealed Gradle plan in the existing pending operation and use full model IDs in the catalog. Kotlin model declarations replace settings/script family inference; the Cloud pipeline projection reads its generated contract. Reuse Desktop IDE navigation for external kernel classes, existing Surface/MachineSignal/blueprint and operation approval. Acceptance: shared CLI/MCP relation and receipt fixtures, canonical catalog identity, exact sealed plan and pagination tests, discovery authority fixtures, Hangar owner walk from pr to appChecks to app JVM tests to kernel/plan/receipt, and existing module/UI checks. Plans configure Gradle only on explicit request and display cost; receipts remain recorded observations. No polling, new module/server, dependency upgrade or approval bypass.

- WP9 container qualification follow-up before editing: the worker engine reports five OOM kills and runs in an 8 GiB Docker VM with unrelated services. Keep the current JVM limits and unrelated workloads. Set two fixed pnpm execution facts in the Kotlin Dagger contract (network concurrency 4, child concurrency 1), verified with the pinned pnpm config reader. This bounds the dependency-install peak for the required real container gate; no user flag, pin change or parallel scheduler. Qualify with a fresh gate and retain failure evidence.

- WP10 real-host qualification follow-up before editing: macOS bundled rsync rejects the numeric --chmod=F600,D700 used for private Gradle requests. Replace only that spelling with equivalent symbolic owner-only modes; preserve request ownership and routing. Acceptance: bundled rsync private-file copy modes, existing router fixtures, and a real routed CLI plan. No new option or permission relaxation.

- WP10 transport qualification: bundled openrsync accepts symbolic --chmod but does not apply it. Preserve source modes with -a and set the copied request to 0600 after verifying its owned path and content digest; the enclosing request directory is already 0700 before transfer. Qualify actual 0600 copying and existing router containment fixtures.

- WP9 native container qualification before editing: checksum/gzip checks pass, but actual GNU tar extraction fails with ENOSYS in the worker amd64/Rosetta path. A temporary diagnostic reproduced the same failure in a disposable directory. Add libarchive-tools and bind the existing tar executable entry to bsdtar inside the Linux runner; retain the JDK image and all source pins. This is fixed OS execution glue, no public choice or task. Acceptance: actual Kotlin archive extraction through the runner and the full local PR gate. Primary reproduction: https://github.com/orbstack/orbstack/issues/2588. Temporary diagnostics are removed before delivery.

- 14 Oct 2026 — WP10 implemented and locally committed: reaktor `5b07166c`, bestbuds `92542fa4`; private Gradle-request transfer follow-up in tools `a04abaf2`. Model 9/9, tooling 199 tests (one skipped), Hangar owner-walk fixtures 2/2, Cloud pipeline 1/1 and CLI/MCP fixtures 2/2 pass. The real packaged CLI walks `bestbuds:pr` → `bestbuds:appChecks` → `bestbuds:app:outputVerificationResultsJvm`, shows the KotlinJvmTest kernel, requests Gradle's exact 644-task plan (12,981 ms configuration), then runs all 273 app JVM tests successfully and reads receipt `4648f5fd-6dd1-4144-afa8-5e005ff71de1`. Large model arrays remain reachable through bounded JSON-pointer pages. The receipt truthfully reports a model mismatch after the later iOS variant export. Full kernel qualification records 284 tests, one database-query failure and 11 skipped; the failure reproduces alone. The existing live MCP owner returns `Unknown tool: workspace`; the packaged endpoint refuses unavailable owner identity. Visible IDE class opening could not be observed. The fixture gateway qualification uses existing uncommitted engineering code, preserved outside these commits; clean-checkout and published CI qualification are not claimed. See `/Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/wp10-drilldown.json`.

- WP9 clean-container qualification before editing: the checksummed Pulumi 3.268.0 archive contains pulumi-language-java, but InstallTool currently extracts only pulumi. Extend the existing installation to its complete bundled executables under a fresh owned distribution directory, and prepend that directory for PulumiSdkTask. Budget: two existing Kotlin files, no new task, pin, public flag or global installation. Acceptance: existing generated-output/configuration-cache fixture, actual Grafana SDK generation using only the pinned bundle, and a fresh local Dagger PR gate. Retain the prior missing-plugin/daemon-disappearance evidence.

- WP10 committed CLI/MCP seam follow-up `8d9c7211` uses the committed MCP read tools directly; both tests pass. The earlier workspace → inspect.call qualification remains separately recorded against the existing uncommitted engineering gateway.
- WP9 Pulumi bundle follow-up committed as reaktor `314e8954`. The existing generated-output/configuration-cache fixture, plugin validation and local publication pass; actual `buildGrafanaSdk` succeeds with the bundled Java language executable. Dagger attempt 7 ran 17m55s: native extraction succeeded, then SDK generation reported the missing Java executable and the daemon disappeared; the engine OOM-kill counter increased from five to six. Preserve that failed result while qualifying the corrected gate.

- WP9 settled iPhone CLI run `run-1791970761084-1` correctly recorded failure in receipt `99f4301c-c702-492b-9e65-97e636185e38`: the signed WDA build was reused, iOS timed out enabling automation mode, then app-session creation reset. No product uninstall or simulator was used. Samsung qualification remains passed. Final contract generation/verification succeeds in Reaktor, BestBuds and Manna; all three configuration-cache repeats are qualified. Dagger gate attempt 8 is running with the unchanged pinned tools and the now complete Pulumi bundle. See `/Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/wp9-runners.json`.

- WP10 real-workspace qualification before editing: retainedPlan eagerly validates all JSON files in the shared plans directory, so an unrelated stale Gradle plan with a dangling dependency and legacy web-plan JSON prevent reading a valid current plan. Budget: existing reader selection and its behavioral fixture only. Select the current node/model artifact by its JSON identity before decoding/validating its task graph; retain rejection for an invalid selected plan. Acceptance: mixed-artifact/stale-plan regression, model tests, CLI/MCP consumer checks and the real authenticated gateway retained-plan walk. No swallowed selected-plan errors, source artifact deletion or new recovery policy.

- 14 Oct 2026 — WP10 retained-plan selection follow-up committed as reaktor `0d96140c`. All nine model tests and CLI/MCP consumer checks pass. An isolated instance of the existing authenticated workspace gateway, using the real BestBuds workspace and empty agent runtimes, now completes `workspace` → `inspect.call` through the three canonical nodes, their relation links and KotlinJvmTest kernel, a fresh 644-task plan (13,053 ms configuration), retained plan pagination and receipt reads. The latest receipt is explicitly a dry run; exact execution receipt `4648f5fd-6dd1-4144-afa8-5e005ff71de1` records the successful 273-test run with the truthful later-model mismatch. No operation execution was started. The isolated owner closed; the installed owner was not restarted and still lacks `workspace`. The test HTTP client allowed 120 seconds for explicit configuration after the native client's 35-second fresh-plan request timed out; production timeout behavior is unchanged. Live Hangar and observed IDE class navigation remain unqualified.

- WP10 final generated-model refresh after the reader follow-up: Reaktor `exportBuildModel generateDagger verifyDagger` passed in 12s; Manna passed in 9s. BestBuds consumer/install/export qualification passed in 36s. Owned commits are present in all four repositories; the unrelated staged `ReaktorV2.pdf` remains preserved.

- WP9 fresh-checkout delivery follow-up before tracking artifacts: CI and the shared Dagger runner read dagger/pipeline.json before Gradle starts, but the three regenerated contracts remain untracked. Budget: track the three existing Kotlin-generated JSON contracts only; no workflow bootstrap, handwritten contract, new task or dependency. Acceptance: each tracked contract byte-matches its freshly verified Gradle reference, every workflow/module input is in Git, and the existing local container gate continues against identical contract bytes. Published CI remains unqualified.

- WP9 generated-pipeline delivery follow-up committed: Reaktor `c647c108`, BestBuds `f7a13960`, Manna `6b3d9857`. All three tracked JSON contracts byte-match their freshly verified Gradle references; shared Dagger launch metadata/source and the CI pipeline input are now in Git. No handwritten contract or workflow bootstrap was added. The final packaged CLI read still accepts the current model and reports the canonical app JVM node. Clean-checkout compilation remains unqualified because the shared trees include unrelated uncommitted code.

- WP9 local Dagger attempt 8 failed after 19m38s with Gradle daemon disappearance. The engine OOM-kill counter increased from six to seven. Its last work included the two roots' pnpm installations and reaktor-web JVM compilation; app JVM tests and corrected container Grafana generation were not reached. The complete pinned Pulumi bundle passed actual Grafana generation locally, and all generated-contract checks pass, but the container PR gate is not claimed green. Unrelated worker services and pinned versions were preserved. Remaining acceptance: successful physical-iPhone automation after owner device state changes, successful PR gate when worker memory pressure is resolved, published CI, live Hangar/installed MCP owner/observed IDE walk, and the separately recorded database-query/Hangar coverage failures. See /Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/wp9-runners.json and /Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/wp10-drilldown.json.

- Final storage cleanup passed: temporary work 16.47 GB, .remote-dev 12.66 GB, free disk 64.9 GB. Removed the owned Pulumi inspection archive, compiled qualification driver and closed isolated MCP owner directory. Durable audits and the handoff stay plain, uncommitted local files; no push or publication.

- 14 Oct 2026 — After the owner confirmed the iPhone unlocked and ready, the packaged shared CLI completed `bestbuds:deviceFlows` successfully at 16:47 IST (`run-1791976595441-1`). Exact execution receipt `0b715099-9005-4cb8-9809-734f37f62694` is successful, `dryRun:false`, and matches the current iOS model when read; SHA-256 `ee635f3de28ce82a31aba8cf8593b71392639f3b2e4da006950fda1ea774e6c4`. The report timestamps fall inside this run and show the physical iPhone passing the existing `connect.yaml` flow with its single `launchApp` command. No product install, uninstall or simulator was used. The earlier automation timeout remains recorded as prior-attempt evidence. Both required device-runner paths now have actual shared-CLI receipts; broader iPhone product behavior, the OOM-affected container PR gate, published CI and the remaining WP10 owner/IDE/coverage checks stay unqualified. See `/Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/wp9-runners.json`.

- 14 Oct 2026 — IntelliJ sync follow-up budget before edits: restore the existing Apple OpenSSL search root in the Kotlin-generated CMake settings and extend the existing native composition fixture. No new public type, flag, dependency, tool pin or IDE-only skip. Preserve the uncommitted vendor-glue removal. Acceptance: a real iOS CMake find-package fixture with a declared OpenSSL prefix and configuration-cache reuse, existing native checks, actual security cinterop/commonization, and a successful IntelliJ import. Review all authored Gradle/Kotlin, CMake and package.json layers; record broader changes separately from the sync correction.

- 14 Oct 2026 — IntelliJ Gradle sync now succeeds for the actual BestBuds composite (IDE RESOLVE_PROJECT:6, 96.536s). The Kotlin-generated Apple native contract now restores CMAKE_FIND_ROOT_PATH beside the existing OpenSSL prefix, preserving the vendor-glue cleanup. A real declared-prefix iOS CMake fixture and configuration-cache repeat, security commonization/IDE cinterop copy, all 107 shared Gradle/model tests and the 1,216-class Java 21 bytecode check passed. The full build-layer inventory covers 76 authored Gradle scripts/settings, 14 CMake files and 50 package manifests, and 218 shared Kotlin owner files inventoried, with focused implementation review. Prioritized follow-ups and qualification limits are recorded in `/Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/build-layer-review.json`: Apple native dependency identity/linking, container resource fit, Gradle configuration cost, residual package orchestration, explicit missing-header validation and obsolete/capability metadata. The sync fix and audit remain local and uncommitted; full Dagger gate and broader WP10 acceptance are still unqualified.

- 14 Oct 2026 — Review of WP0–WP10 written as §10. The mechanics landed, but the design did not fully land: orchestration, component declarations and plumbing still share the root scripts, and the DSL is untyped below the node level. WP11–WP18 added. WP11 (qualify and push) goes first; WP12–WP13 split the altitudes. Owner decisions are in §10.7. Nothing was published or committed.

- 14 Oct 2026 — The owner approved all four §10.7 decisions, including the push. The owner handed WP11–WP18 to Codex.

- 14 Oct 2026 — WP11 budget before edits: finish the already-qualified IntelliJ CMake correction as a separate code/test commit. Then extend the existing model reader and CLI read projections so stale declarations remain inspectable with changed-file metadata; keep exact execution and retained-plan reuse fresh-only. Correct the existing package/device entry points and failing tests, remove owned legacy schema-1 exports, and move run-specific Apple versions to generated settings. No new public signatures, flags, task kinds, dependency or pin; no WP12 receiver/component work. Acceptance starts with model reader/CLI freshness and authority fixtures, real dirty-workspace list/show/plan reads and run rejection, existing native/sync evidence, entry-point behavior, Apple drift/configuration-cache fixtures, focused failing checks, then the measured real Dagger gate. Committed-file-only PR delivery follows qualification and the branch destinations recorded in §10.7; preserve foreign work and the staged PDF.

- WP11 device-entry correction before edits: add one product instance of the existing DeviceFlow kernel for the three paired notification flows. The current push script builds each platform once through its existing package alias, invokes that declared target with the existing selection/install properties, and collects reports from the generated contract. Preserve --skip-build behavior and stop after a failed step. Remove the three aliases to deleted fastlane lanes; verify every remaining fastlane alias against the loaded lane registry. No new public kernel API, option, pin, live upload or live notification send during qualification. Acceptance: behavioral child-process fixtures for both directions, build/install reuse, reports and failure stopping, plus package generation and actual lane loading.

- WP11 Apple version correction before edits: the existing XcodeHostTask writes Version.local.xcconfig beside its existing generated host contract, declares that derived output through Gradle outputs.file, and the stable tracked settings include it and reference its resolved versions in Gradle arguments. generateReleasePlan depends on the existing host generator so each release refreshes the run settings. No new public property or task. Acceptance: version changes update only generated settings, preserve tracked bytes, pass drift and configuration-cache fixtures, and resolve correctly through real xcodebuild -showBuildSettings for both Apple hosts.

- 14 Oct 2026 — The review of the delivery layer (Dagger, Pulumi, deploys) and its next steps are in the companion plan `delivery-handoff.md`, as WP19–WP26. WP19 can run alongside WP11–WP18. WP20 onwards follows WP12 and WP13.

- WP11 Hangar correction before edits: all four reported flows reproduce in the current tree. The schema pane correctly reports source-derived repository SQL before a bounded catalog read; move LIVE assertions to observed schema results and assert SRC for topology-only reads. The agent fixture captures a complete source snapshot, so assert its recorded-snapshot label while retaining the review navigation checks. Budget: four existing YAML fixtures only, no production provenance or agent changes. Acceptance: isolated four-flow receipts and the existing complete-coverage aggregation guard.

- 14 Oct 2026 — The owner asked for a declarative, Compose-like orchestration API with click-through to the kernel. Its spec is `dependeasy-api.md`, and WP12, WP13 and WP16 implement it. The owner approved the five delivery decisions in `delivery-handoff.md`.

- WP11 Hangar diagnosis correction before runner edit: the three schema failures include a real private-session rejection, not only obsolete badge expectations. HangarFlowProcesses places java.io.tmpdir and user.home under the selected repository; the existing DatabaseQueryPlanner correctly forbids private session files there. Budget extension: move the runner-owned isolated temporary root to the OS temporary directory and remove it after all owned child processes close. Keep reports in the existing output directory and preserve the database authority check. Acceptance: the four original flows and existing exact-coverage test; reassess badge expectations after reads actually execute. No production API or authority change.

- WP11 focused Hangar qualification passed all four original flows in 33s, plus the complete-coverage aggregation test. Committed BestBuds f28cdb01 moves isolated session/home storage outside the selected repository and deletes it when owned child processes close. The two query-pane/topology flows assert SRC before a catalog read; D1/SQLite still requires LIVE after its actual schema receipt. The original agent partial-snapshot and Postgres live-catalog checks pass unchanged with cold isolation. Earlier badge-only diagnosis is superseded by the private-session rejection. Unrelated graph YAML and staged PDF remain untouched.

- WP11 installed-CLI authority follow-up before edits: real dirty-workspace list/show/plan correctly warn and expose declaration changes, but run currently reports an unknown node because discovery drops stale models. Add a private pre-admission freshness check to the existing Run command when a model exists; extend the existing stale-CLI test to require the explicit stale error without opening the kernel. No public signature or option. Acceptance: packaged dirty list/show/plan succeed and run rejects the stale model before kernel construction.

- WP11 committed-source CLI qualification found the existing CLI/MCP comparison test also calls CodeWorkspace, whose implementation belongs to uncommitted Reaktor code-analysis work. Budget: remove those two unrelated outline assertions from this existing test; retain complete node equality, kernel/source metadata and no-execution checks. Do not import the foreign implementation or claim code-analysis/IDE qualification from this fixture. Acceptance: the same three CLI tests compile and pass using only committed source.

- WP11 hosted-CI checkout correction before edits: BestBuds CI checks out Reaktor main although the paired build-layer work is on shibasis/newDesign, and specifies a repository token secret that is absent. Reaktor is public, so use the default checkout token and pin both existing Reaktor checkout steps to the qualified commit. Budget: the existing workflow only, no trigger or deployment behavior change, new secret or public kernel API. Acceptance: checkout SHA equals the locally qualified Reaktor source and the manual non-deploy CI run passes after push. Reaktor and Manna have no hosted workflows; the owner is being asked how to scope their hosted acceptance while committed-source local gates continue.

- WP11 owner decision, 14 Oct 2026: GitHub Actions is not required. Remove BestBuds’ existing workflow; all CI runs locally through the declared Gradle/Dagger gates. This supersedes the hosted-CI checkout correction and hosted-green acceptance. Preserve branch/PR delivery authorization after local qualification. Budget: delete the single owned workflow and add a concise local CI command/evidence record; no hosted replacement, new scheduler, secret or deployment. Acceptance: no Actions workflows in the three repositories and the existing local gate reports actual results.

- WP11 model stability follow-up before edits: installed fresh planning rejects its own newly exported model because configuration-cache serialization changes ConfigurableFileCollection declarations into resolved file collections. Budget: finalize the existing serialized model node/edge/omission inputs after all project declarations finish, before cache serialization; extend the existing export fixture with wrapped file/tree inputs. No public API, new model field or relaxed digest/freshness check. Acceptance: cold/warm forced exports byte-match without running producer tasks; existing export/planning fixtures and the installed real CLI fresh plan/run/receipt pass.

- WP11 local CI attempt 9 failed after 1709.2s: sampled engine peak 6.077 GB, OOM-kill counter 7 to 8. Gradle daemon RSS reached about 3.0 GB under amd64/Rosetta, beside the shared engine and unrelated worker services. Budget before retry: lower existing Kotlin-owned Gradle heap to 1024 MiB and metaspace to 512 MiB; preserve pins, compiler mode, worker count and unrelated services. Regenerate the three existing contracts and measure the real gate. A separate generateBuildFlags failure appears because the committed-source archive lacks Git metadata required by sourceRevision; add a shallow Git object/index context to the one existing scratch checkout rather than changing revision semantics or copying source again. Acceptance: actual source-revision generation and a passing local gate with no OOM-counter increase; retain any failure evidence.

- WP11 fresh-model qualification passed: Reaktor 62e50110 freezes the existing serialized node/edge/omission properties lazily on first read. Eager lifecycle snapshots were rejected by Gradle task-registration/configuration locks and removed. All 14 export/planning/kernel/package/Dagger fixtures pass, including no-cache/cold-cache/warm-cache exports and selected producer execution. The installed real CLI plans bestbuds:app:outputVerificationResultsJvm successfully; its model bytes remain identical before and after explicit planning. Digest/freshness checks remain strict.

- WP11 installed CLI now completes a fresh bestbuds:verifyXcodeHost run: run-1791995403883-1, Succeeded, exit 0, 42.935s. Attempt 10 of the container gate was deliberately cancelled during SDK preparation after adding oversized Git blob packs to the filtered source; OOM kills stayed at eight. Compacted the existing scratch contexts to HEAD/tree/index metadata (about 742 KB combined packs), verified rev-parse/status still work, and restarted attempt 11. Source files and upstream commit IDs remain unchanged. Storage cleanup after compaction: temp 17.60 GB, remote 12.86 GB, free 61.80 GB.

- WP11 simulator-work follow-up before edits: the pinned Compose plugin attaches test-resource copies to native test link tasks after their initial disabling. Budget: repeat the existing simulator compile/link dependency clearing after project configuration and extend the existing module-wiring fixture; no new public API, task, flag or resource policy. Acceptance: native test-resource producers disappear from the real buildAll plan, framework resources remain, and the dry-run fixture survives configuration-cache reuse without launching a simulator.

- WP11 clean Hangar packaging found the committed ReaktorApps preview still supplies Pair<User, Map<String, Backend>> after SessionKey became typed. The working tree holds a related foreign correction using the newer uncommitted selections property; inclusion authorization is pending. Prepare only a SessionKey import and typed call using committed Backends.profile, preserving the primary working tree. Budget: two lines, no public API or session behavior change. Acceptance: committed-source desktop compilation/package and existing preview checks.

- WP11 attempt 11 exhausted the 512 MiB JVM metaspace limit during composite configuration; no new cgroup OOM kill occurred. The daemon stalled in shutdown and its private log reports OutOfMemoryError: Metaspace. Read-only heap inspection shows 474366 KiB used out of 1 GiB. Cancelled only this owned gate. Budget before retry: raise the existing metaspace limit to 768 MiB, retaining 1024 MiB heap, worker count, compiler mode, toolchain pins and unrelated services; regenerate the three contracts. Acceptance: actual measured local gate completes, no additional OOM kills, and generated contracts byte-match Kotlin references.

- WP11 owner approved inclusion of the existing two-line Hangar preview fix. BestBuds ef268648 imports SessionKey and uses the committed Backends.profile API; the newer foreign selections rename remains uncommitted. Committed-source desktop packaging passes in 48s. A private native Hangar window is mounted and its live MCP build_node reads a fresh source model; the visual owner/IDE/plan/receipt walk remains pending.

- WP11 attempt 12 was killed by a global worker OOM during native distribution commonization (914.1s; sampled engine peak 6.026 GB; OOM kills eight to nine). Pinned Kotlin 2.4.21 source confirms that the commonizer runs in a separate JVM with a default 3 GiB heap; the existing daemon policy did not constrain it. Budget before retry: bound that JVM through the supported kotlin.mpp.commonizerJvmArgs property (512 MiB heap, 128 MiB metaspace, 32 MiB code cache), and use Serial GC for it and the existing one-worker Gradle JVM to reduce native GC overhead. Preserve Gradle heap/metaspace, all pins, task membership and unrelated services. Regenerate the three contracts through Kotlin and verify byte equality. Acceptance: existing Dagger transport fixture and the measured actual local gate complete without a new OOM kill. Retain failed attempts; do not claim a pass before it occurs.

- WP11 app JVM receipt run failed at two stale BudBotMock.start calls in CircleJourneyTest. BudBotMock starts its collector in init. The primary working tree already contains exactly the two removals needed; these are foreign edits protected by §10.7. Budget before qualification: apply those two removals only to the existing scratch context, preserve production behavior and all assertions, no new API. Acceptance: the installed CLI runs the actual app JVM target and retains a successful receipt. Obtain owner authorization before including those existing primary edits in a commit.

- WP11 actual app JVM tests now compile after the prepared CircleJourneyTest removals: 271/272 pass. The remaining application wiring check fails because SessionState consumes default users/hydration names while their existing providers declare userRepository/sessionHydrationInteractor. The primary foreign SessionState diff already contains the two explicit keys alongside unrelated API changes. Budget before qualification: apply only these two consumer keys in scratch, retaining committed SessionKey/Backends APIs and all tests; no new port or production concept. Acceptance: all 272 app JVM tests pass and the installed CLI records the successful actual target. Request inclusion authorization for only the two test removals and these two consumer-key changes after qualification.

- WP11 owner authorized exactly four existing app corrections. BestBuds 9469d323eb4af242cdc829598f1c95a165cc2826 includes only the two obsolete BudBotMock.start removals and the two SessionState consumer keys; all other primary edits and the staged PDF are preserved. Installed CLI run run-1792000322386-1 succeeds in 58.460s with 272/272 app JVM tests, no failures/errors/skips; Gradle receipt 0c9ac67f-6a2a-489a-8b36-9bf8b5d330f0, SHA-256 f44444cb056ba23552af7935ef4d1d420bdaa571d57a6fd531a486700698fee8. Gate attempt 13 captured the previous committed source before these prepared fixes; do not label it qualified against this new commit.

WP11 follow-up budget (14 Oct): no new public API, dependencies, model fields or flags. Adjust the existing Dagger Gradle heap from 1,024 to 1,536 MiB after attempt 13 saturated old generation (699,071/699,072 KiB) while two in-process Kotlin compilations were active; retain 768 MiB metaspace and 512 MiB commonizer limits. Acceptance: the existing Dagger contract fixture, all three drift checks, and a measured local PR gate with no additional OOM kill. Additional committed engine test failures are being diagnosed separately: stale discovery fixture, JUnit-owned symlink cleanup guard, and preview graph rendering. No assertion is weakened without establishing the intended behavior.
WP11 adds a fixed `org.gradle.parallel=false` to the same execution contract: the worker budget of one did not prevent two included-project Kotlin compilations from sharing the exhausted heap. There is no new public option.

WP11 engine test correction budget: test code only; zero public API, runtime state, flags, dependencies or task membership changes. Recognition of JUnit `@TempDir` cleanup must retain the raw recursive-delete guard. The discovery performance fixture must supply 91 exported model nodes and the existing request/plan protocol, preserving its task-count assertion and JFR/timing evidence. Acceptance: the affected existing tests followed by `appChecks`; inspector coverage remains under diagnosis.

WP11 inspector diagnosis: the fixture has 139 shape nodes and 103 inspector nodes. `AppGraph` deliberately separates its 36 routes from inspector nodes. The test incorrectly applied the shape coverage minimum (104) to the inspector projection. The one-line correction checks `graph.shape.nodes` for that existing minimum while still rendering every inspector node. No runtime session/preview change is needed. Acceptance remains the three existing AppGraphShape tests and complete `appChecks`.

- WP11 committed-source appChecks qualification passes through the installed CLI: 751 tests (app 272, design 2, engine 477), no failures/errors/skips; run-1792001466381-1, 389.143s, receipt 5c829201-7188-4fa2-a14c-e306ed03fbc0, SHA-256 947d88720b10b4a2fe06edaa6f194816256ff94eadf2c8ee60c47589af49e808. Native Hangar and live MCP retain the exact 774-task aggregate plan (13,812ms configuration). The queued native plan finished after the actual run and became the latest dry-run receipt; a final actual target run will finish the visual receipt walk. No dry-run is counted as an execution.

- WP11 live receipt navigation diagnosis and budget before edit: Open receipt uses the operating-system JSON association and launched an unconfigured PyCharm, while declaration/kernel links correctly use the running IDE. Change only that button to the existing openInIde(file, 1) seam and update the existing owner-walk assertion; no API or receipt semantics change. Acceptance: the existing BuildGraphPane owner walk and an actual native receipt click open the matching JSON in the running IntelliJ window.

- WP11 attempt 14 cancelled after 1,185.5s during engine JVM compilation: no additional OOM kill (nine to nine), sampled engine peak 5.952 GB. A single in-process Kotlin compiler is active; GC inspection shows tenured 1,048,575/1,048,576 KiB despite a 1,536 MiB total heap, with 268.7s VM-thread CPU. Budget before retry: set Serial GC NewRatio=5 within the same 1,536 MiB heap so tenured can use about 1,280 MiB; retain the 768 MiB metaspace, commonizer bounds, pins and serial Gradle policy. Acceptance: Dagger contract fixture and three generated drift checks, then the actual measured PR gate with unchanged OOM counter. No new public API, host daemon or tool upgrade.

- 15 Oct 2026 WP11 native receipt follow-up: rebuilt Hangar’s actual Open receipt button opens `44a5f724-cea1-402c-b414-b5620752fe8f.json` at line 1 in the already-running IntelliJ. Native capture `live-idea-receipt-navigation-fixed.png` shows the successful receipt and green IDE sync. Its rebuilt inspector correctly reports current model false after later navigation/policy implementation edits and dry run false. Existing two navigation tests and distributable pass.

- 15 Oct 2026 WP11 container budget before follow-up: attempt 15 retained the 1,536 MiB total heap and improved tenured space to 1,280 MiB, but the one active engine compiler again filled it (1,310,719 / 1,310,720 KiB) after 902 seconds; sampled engine peak 5.95 GB, OOM count unchanged at 9. Change only the existing Kotlin-owned runner environment and its three generated contracts: Gradle with 768 MiB heap / 768 MiB metaspace, separate Kotlin daemon with 1,536 MiB heap / 384 MiB metaspace, bounded Serial GC and code caches, fallback disabled. Pinned KGP 2.4.21 source confirms an explicit daemon strategy attempts the compiler daemon first; disable its in-process fallback so a failed connection cannot defeat the split. Keep Gradle daemon=false. Acceptance: existing contract fixture/generation, actual separate process flags and measured complete PR gate. No API, target, pin or VM-memory change.

- 15 Oct 2026 WP11 bounded compiler lifetime before follow-up: attempt 16 verified separate Gradle (768 MiB) and compiler (1,536 MiB) processes, but both embedded Kotlin 2.4.0 and project Kotlin 2.4.21 daemons remained resident. Docker records an engine OOM, exit 137 and automatic restart; sampled peak 5.965 GB and the cgroup counter reset, so this attempt cannot qualify zero OOM kills. Add only the pinned daemon idle lifetime (60 seconds) to the existing Gradle JVM system properties, retaining all heaps, pins and serial scheduling. Pinned daemon source excludes active compilations from idle time and KGP releases its session after each compile. Acceptance: generated contracts and existing transport fixture, actual idle daemon termination, then a complete measured PR gate without an engine restart/OOM. No public API, new task, retry or VM-memory change. Historical paired builds continue in the one scratch checkout; contract generation waits for its restored current sources.

- 15 Oct 2026 WP11 historical WP3 before observation: Gradle buildAll failed in 26m22s (wrapper wall 1,614.866s), 5,954 actionable tasks: 5,066 executed, 814 cache, 74 up-to-date. Twelve failures include committed stale app/engine/CLI code, missing docs build input and absent security Git submodules in the single measurement checkout. Shared caches were reused; no successful/cold-build performance claim. Paired after measurement is running, with the original current source heads restored afterward.

- 15 Oct 2026 WP11 paired historical after observation: Gradle stopped with three Java heap space failures in 3m35s (wrapper wall 275.964s), 3,741 actionable tasks: 484 executed, 3,257 up-to-date. Execution did not complete its 10,507-task planned graph, so no before/after speed claim is supported. The exact planned difference is +93: 57 typed completion markers, 39 unused simulator producers and net three alias/runtime removals. WP11 already removes those 39 producers. Both measured runs used this Mac; the original current committed sources have been restored in the one scratch checkout.

- 15 Oct 2026 — Owner-approved qualification JSON relocation complete: 22 files now live under `/Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/`; relocation digests are retained there. Evidence remains local and unpublished.

- WP0 evidence summary — Blocker corrections committed; synthetic inspection-access test confirmed the recorded access gap. Receipts: `/Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/`.

- WP1 evidence summary — Stable/fast split, Java 21 bytecode and three-root cache reuse qualified; private edit added zero foreign compilation/packaging executions. Receipts: `/Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/`.

- WP2 evidence summary — Shared model identity, reader, CLI/MCP parity and real execution receipt qualified. Receipts: `/Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/`.

- WP3 evidence summary — Typed ownership migration qualified; historical planned graph +93 explained, 39 unused simulator producers removed in WP11; paired actual builds failed and do not support a speed claim. Receipts: `/Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/`.

- WP4 evidence summary — Typed kernel/output fixtures and public task ownership checks recorded. Receipts: `/Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/`.

- WP5 evidence summary — Exact Gradle plans, request freshness and execution receipts qualified through installed CLI. Receipts: `/Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/`.

- WP6 evidence summary — Kotlin-generated web/Worker contracts qualified; approved config-service dev/prod deployments recorded. Receipts: `/Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/`.

- WP7 evidence summary — Signed build 1.0 (25) accepted by internal TestFlight; two qualified dSYMs accepted by Crashlytics; backend symbol processing/crash frames remain unqualified. Receipts: `/Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/`.

- WP8 evidence summary — Native Android/iPhoneOS/desktop build and cache evidence retained; Apple crypto final linking/runtime gap remains in the layer review. Receipts: `/Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/`.

- WP9 evidence summary — Physical Samsung and iPhone launch flows pass with receipts; full Dagger PR gate still in qualification. Receipts: `/Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/`.

- WP10 evidence summary — Installed CLI/native Hangar node→kernel→plan→receipt walk qualified in WP11; external library FQN URI limitation remains explicit. Receipts: `/Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/`.

- WP11 evidence summary — IntelliJ initial/warm sync, 751 appChecks tests and actual native receipt navigation pass; current bounded compiler lifetime gate is running. Not complete. Receipts: `/Users/ovd/dev/reaktor/build/dependeasy/receipts/qualification/`.

- 15 Oct 2026 WP11 bounded compiler lifetime committed: reaktor `fd8721a8f7631dccbed4704f7d751f4180dac03d`, bestbuds `944b1e4b7b53f7e5cf2bf3fd82928d2125421821`, manna `ba7f6aac43bc9aa1a145ab296cc539290e104dc8`. Existing Dagger fixture and all three generation/drift checks pass; contracts differ only in GRADLE_OPTS. Relevant clean-source files match these heads, foreign primary staging is unchanged, and measured local gate attempt 17 is running. No push or passing-CI claim yet.

- 15 Oct 2026 WP11 historical scratch cleanup: discarded 830 completed disposable build/node_modules entries from the one measurement checkout, preserving source, Git metadata, reports, receipts and the open FFI build tree. Shared temporary use fell to 18.738 GB; remote use is 12.867 GB. Restored the exact original 20 GB storage limit and reran cleanup successfully. The other Hangar scratch checkout is preserved. Durable facts: qualification/wp11/storage-cleanup-final.json and historical-output-cleanup.json.

- 15 Oct 2026 WP11 attempt 17 lifetime acceptance: filtered compiler logs explicitly record Idle timeout exceeded 60s, graceful shutdown and shutdown complete for two embedded Kotlin 2.4.0 daemons and one project Kotlin 2.4.21 daemon. The actual PR gate is running tasks; no passing-gate claim yet. Evidence: qualification/wp11/dagger-attempt17-compiler-lifetime.json.

- 15 Oct 2026 WP11 generated SDK compiler isolation before source edits: attempt 17 keeps the engine alive and proves 60-second Kotlin daemon shutdown, but compileGrafanaSdk exhausts the shared 768 MiB Gradle heap in javac. Extend only the existing Pulumi SDK kernel and its existing generated-output fixture: fork Java compilation with a fixed 1,024 MiB heap, 256 MiB metaspace, 32 MiB code cache and Serial GC. Keep the selected Java toolchain, release bytecode, generator, source pins and runner policy. Acceptance: actual generated Java compilation in a separate bounded process, configuration-cache/up-to-date repeat, unchanged generated SDK metadata, then a complete measured local PR gate. No new public API, flag, task, dependency or VM-memory change. Remaining attempt-17 checks continue and their results are retained.

- 15 Oct 2026 WP11 compiler isolation committed: reaktor d22df787f1c20ee4e2a299e69dc619ba38b5b136; existing generated-output tests pass (2 tests), including an actual bounded compiler worker, generated metadata and configuration-cache repeat. Attempt 17 failed only compileGrafanaSdk after 2,716.3 seconds, with 274 completed tests passing, sampled peak 6.362 GB, zero engine OOM events and unchanged engine identity. The full corrected local PR gate is next; no passing-CI or publication claim.

- 15 Oct 2026 WP11 Linux test qualification budget before source edits: attempt 18 compiles the Grafana SDK and engine successfully without an observed OOM, then exposes two test assumptions. The transcript test accepts any identical New reply and reads 90 events before the final 92-event refresh; give replies unique identities and await the final run and its own output, preserving all retention/count/partial assertions. The AI render test requires manna-api although the approved BestBuds CI contract excludes Manna; assert that its rendered presence exactly matches the declared inventory, preserving real BestBuds worker checks. Budget: these two existing test files only, no production behavior, API, dependency, target or runner change. Acceptance: targeted test classes, repeated transcript check, and a complete measured local PR gate; retain the failed attempt evidence.

- 15 Oct 2026 WP11 CI host correction budget before source edits: attempt 18 executes the native desktop suite inside linux/amd64 emulation, records a 34.849 ms frame median against the unchanged 16.7 ms budget, then OOM-kills the 512 MiB test JVM at about 2.8 GiB RSS after 387 of 477 engine tests. The existing desktopChecks is already Local; appChecks and its pr parent must record the same host requirement because they consume that engine suite. Extend the existing portableChecks to include the existing Dagger contract check and Reaktor framework verification, retaining app/design/kernel JVM and app JavaScript checks. Require both native host pr and Dagger portableChecks in CI.md. Budget: existing BestBuds root host/composition facts, its generated pipeline and local CI instructions only; no new node, task, API, test filter/skip, dependency, benchmark relaxation or VM-memory change. Acceptance: existing pruning fixture and generated drift check; portable contract excludes appChecks/pr/desktopChecks but retains every headless check; actual complete native pr and measured Dagger portableChecks both pass. Preserve the failed original container pr evidence and explain this host graph difference.

- 15 Oct 2026 WP11 cross-host model export budget before source edits: completed attempt 18 also fails Reaktor exportBuildModel on Linux because the Apple CMake tool identity and generated metadata filenames realize xcrun-backed SDK settings. Keep Apple nodes and their Macos host requirement. The CMake tool identity records the already-pinned CMake/Ninja versions; SDK version, compiler, path and metadata checksum remain nativeContract execution inputs. Set module metadata output filenames directly from the declared library and platform, retaining their existing bytes/paths on macOS. Budget: existing CmakePlatform and KotlinCMake declaration files plus the existing NativeInputs fixture; no public member, model field, task, dependency or SDK fallback. Acceptance: a provider that rejects SDK evaluation must allow Apple kernel/model-output inspection; existing native input/composition/cache fixtures and actual Linux export through the portable gate, preserving macOS SDK contract inputs.

- 15 Oct 2026 WP11 corrected host/model commits: BestBuds 1a0e6087bb11e4d04ce54cebe842a1683b66d63a declares native pr/appChecks hosts and expands the existing portableChecks; targeted classes pass 11 tests and the final-turn transcript check passes five repeats. Reaktor 22227888ed064815ec92f36e9b9026c89c0bffef keeps Apple SDK reads at execution; nine existing native/Dagger fixtures pass. Both complete CI gates are running on these heads. The source guard caught earlier scratch cleanup removing 71 authored build scripts/documents (967,468 bytes); their exact committed bytes and modes were restored before either current gate, with primary repositories untouched. The old cleanup summary is superseded by this correction. Under the owner allowance, corrected qualification uses a temporary 22 GB cap after the protected active data prevented cleanup at 20 GB; restore the original limit after the run. No push or passing-CI claim.

- 15 Oct 2026 WP11 native pr passes on committed heads 22227888/1a0e6087/ba7f6aac: 375.723 seconds, 1,120 tests (app JVM 272, app JavaScript 296, design 2, engine 477, Reaktor framework 73), zero failures/errors/skips; all engine performance assertions retain their budgets. Portable attempt 19 passes 73 framework tests and fails sourceRevision because scratch refs advanced without portable commit/tree objects; no new OOM or engine restart. Refreshing only scratch shallow Git metadata (756,780 bytes across three repositories) makes standalone revision/status reads pass. Attempt 20 was interrupted after the source guard found native-generated tracked outputs; the separate next command had incorrectly launched despite the guard failure. Exact HEAD bytes are restored before attempt 21, whose guard passes all three roots; it is running. Native source heads remain unchanged. No push or passing portable-CI claim.

- 15 Oct 2026 WP11 JavaScript linker budget before source edits: portable attempt 21 fails only app compileTestDevelopmentExecutableKotlinJs with Java heap space after 754.7 seconds; 73 framework tests pass, sampled engine peak 6.348 GB, no new OOM kill or restart. Raise the existing bounded Kotlin compiler heap from 1,536 to 2,048 MiB in the Kotlin Dagger owner; retain Gradle/commonizer/Node/compiler metadata limits, 60-second lifetime, one worker, source pins and every test. Budget: one fixed execution fact and the three generated pipeline contracts, zero new API/task/option/dependency or VM-memory change. Acceptance: existing Dagger fixture, three generation/drift checks and a complete measured portableChecks. Native pr already passed 1,120 tests on the prior implementation; qualification must record the new heads and unchanged native execution path. Latest actual IntelliJ sync RESOLVE_PROJECT:8 passes in 72.236s; Manna help/export/drift passes in 6s. Exact original storage policy restored; cleanup passes at temporary 19.765 GB and remote 12.867 GB after removing only 1.930 GB of completed owned dependency outputs.

- WP11 native qualification correction before edits: the exact-head native repeat ran 1,120 tests in 367.827 seconds, with one failure in the unchanged desktop graph performance test. Its stack identifies concurrent mutation of PortCapabilityImpl listeners during BestBuds graph wiring, not a performance budget violation. Qualify the two existing uncommitted owner/agent changes: atomic immutable listener snapshots in PortCapability.kt and the one cancellation/emission regression plus AtomicInteger import in LiveGraphReadTest.kt. Budget: these two existing files only, preserve the public constructor and listener API, no dependency, flag, task kind, graph semantic rewrite or performance threshold change. Acceptance: the new regression rejects the committed implementation, passes the proposed implementation, and the existing port/graph checks plus native pr gate pass. Include these foreign changes only after the owner authorizes their exact scope under §10.7. Portable attempt 22 continues independently against the currently committed sources.

- WP11 compiler heap correction before the next edit: portable attempt 22 reached app JS executable linking with the declared 2,048 MiB compiler, no new OOM kill or engine restart. Live JDK jstat evidence shows old generation 1,747,648/1,747,648 KiB occupied, 140 full GCs and 409.119 seconds in full GC. Cancel only this run’s owned Dagger CLI rather than continue GC thrashing. Budget: raise the one fixed compiler heap fact from 2,048 to 2,560 MiB in the existing Dagger kernel and regenerate its three contracts; no public API, flag, pin, VM limit or unrelated process change. Acceptance: existing Dagger behavioral fixture, all three generated-contract drift checks, measured fresh portable gate with engine identity/OOM counters and test results. Retain attempt 22 as cancelled and unqualified.

- 15 Oct 2026 WP11 current candidate qualification: the existing listener regression rejects committed code with an IndexOutOfBoundsException; the exact two-file immutable-snapshot proposal passes 63 port/graph tests and the complete native pr gate (1,120 tests, zero failures/errors/skips, 347.687s), with unchanged desktop performance budgets. The owner inclusion decision remains pending under §10.7. Fixed 2,560 MiB compiler contracts are committed at Reaktor c7e4d67c, BestBuds d33b0950 and Manna e3af55b7; the existing Dagger fixture and all three drift checks pass. Manna help/export/drift passes on these heads in 32.118s (configuration/model/contract only). Portable attempt 23 runs these committed sources plus the frozen two-file proposal (tree ac1b357e17d11e390d418b7e4e1fd253eba231e7); no committed-only, pushed-branch or completed-WP11 claim is made.

- WP11 native container platform budget before edits: attempt 23 fails app JavaScript linking when the worker kernel globally OOM-kills its compiler (3,163,904 KiB anonymous RSS; engine counter one to two, engine identity unchanged). The 8.324 GB ARM worker is executing an AMD64 Dagger engine and explicitly forced AMD64 Java/browser containers. Registry manifests confirm the existing pinned Java index and existing Playwright version both include linux/arm64. Budget: one fixed execution-platform fact in the existing Kotlin-owned environment, its three generated contracts, and the existing shared TypeScript adapter reading that fact for both images and the Hermes cache namespace. No public API/task/owner option, image/version/checksum change, VM-memory change or unrelated-service action. Acceptance: existing Dagger fixture and three drift checks, actual ARM Java/Gradle/compiler processes and platform-separated native cache, then the full unchanged measured portable gate. Retain attempt 23 as failed; do not claim desktop/Android runtime coverage from this portable gate.

- 15 Oct 2026 — Native ARM execution correction committed: Reaktor 4c944566, BestBuds f5ef834a, Manna b48dbe8. Existing Dagger fixture, all three generated contract drift checks, and strict shared-adapter TypeScript checks pass. Attempt 24 qualifies the unchanged portableChecks target using these commits plus the exact pending two-file listener proposal (tree 56180f97f2bd213bcfdebbd42fd831109e7c0a0b). The prior amd64-on-ARM attempt 23 failed with a global compiler OOM and remains failed evidence. Original 20 GB storage limits restored; pre-run temp 19.788 GB and remote 12.868 GB. No push or WP12 source work yet.

- 15 Oct 2026 — Repeated actual IntelliJ Build → Sync Project with Gradle Files after the native ARM contract commits. RESOLVE_PROJECT:9 succeeds; resolution 34.811s, complete sync 39.026s. This observes the primary composite working tree with foreign edits preserved. Evidence: qualification/wp11/intellij-arm-sync-{start.json,result.txt}.

- 15 Oct 2026 — ARM compatibility correction budget before edits: actual pinned Android NDK clang --version in the owned ARM container fails with missing x86_64 ELF loader. Preserve those existing Android artifact targets on AMD64. Extend the existing Kotlin Dagger writer to derive per-target platform overrides from Android output kinds over the declared prerequisite graph, encode them in the existing environment map, and let the shared TS adapter read those facts. Extend the existing pipeline fixture and regenerate three contracts; no public API/model field, task, owner option, pin, VM or service change. Acceptance: direct and transitive Android output fixtures choose AMD64 while portable checks retain ARM; three drift checks, strict adapter typecheck, actual pinned NDK startup in AMD64, and unchanged full portable gate. Current attempt 24 remains evidence for its frozen pre-override source only.

- WP11 idle-retention correction budget before edits: attempt 24 completed app JS/JVM tests, but the worker globally killed Java while the completed JS compiler still retained about 3.0 GB RSS. Its daemon log ends after cleaning session 3 at 22:42:46.160 with no shutdown, and the next JVM daemon begins at 22:43:28.479; kernel OOM and process-disappearance evidence correlate that retained compiler. Pinned Kotlin 2.4.21 daemon source keeps lastUsed current during compilation and checks idle lifetime after compilation; clean sessions permit graceful shutdown. Change only the existing fixed idle lifetime from 60 to 5 seconds; preserve compiler heap and all tests, pins, services and VM memory. Acceptance: Dagger fixture and three contract drift checks, then full measured portableChecks with no new OOM kill. Retain the failed run and all test failure evidence.

- WP11 kernel/host correction budget before edits: recovered attempt-24 JUnit XML proves EXPLAIN fails because Android platform-tools/sqlite3 is EM_X86_64=62 and shadows the host in the ARM runner PATH; the batch cancellation regression records five started reads instead of three. Extend only the existing adapter OS package list with sqlite3 and append SDK tools after host tools. Move the existing three-slot admission to each caller of the private connection check, and check the enclosing batch cancellation after acquiring a slot. Extend the existing cancellation regression with repeated runs; preserve all assertions, slot count, authority and public APIs. Budget: existing TypeScript adapter, KernelConnections.kt and KernelConnectionBatchTest.kt, no new task, tool pin, public flag, scheduler or test exclusion. Acceptance: real ARM SQLite EXPLAIN, existing kernel connection/query tests, unchanged complete measured portable gate.

- WP11 host/kernel corrections committed at Reaktor caaf8a90, BestBuds 43658169, Manna f1502438. Dagger fixture and all three generated drift checks pass; strict TypeScript passes; 18 connection/query tests pass, including 25 repeated cancellation runs with every original assertion. The exact pinned Android NDK starts on amd64 (read-only existing SDK evidence), and native ARM SQLite returns the expected INTEGER PRIMARY KEY plan. Attempt 24 remains failed (883 observed tests, two failures, 11 declared skips; global compiler OOM). Fresh full portableChecks attempt 25 starts from these commits plus the same pending two-file listener proposal, tree 1096e3b12655846ca2ae579b77faedd92aaceb4a; OOM counter starts at three. No push or passing CI claim.

- 15 Oct 2026 — WP11 final candidate qualification: Reaktor caaf8a90, BestBuds 43658169 and Manna f1502438 plus the exact two-file listener proposal (tree 1096e3b12655846ca2ae579b77faedd92aaceb4a) passed both local gates. Native pr: 1,120 tests, zero failures/errors/skips, 361.831s. Dagger portableChecks attempt 25: 883 reported tests, 11 existing opt-in/live-service skips, zero failures/errors, 671.9s; sampled engine peak 6.280 GB, OOM counter 3 to 3, unchanged engine identity/no restart. Actual compiler logs show graceful JS shutdown before the next compiler; native ARM SQLite returns the expected index plan. Current Manna help/export/drift passed in 33.604s and actual IntelliJ RESOLVE_PROJECT:10 sync passed in 40.614s. Completed owned node_modules removed; exact original 20 GB storage limits restored, cleanup within both budgets (temporary 19.800 GB; remote 12.868 GB). Inclusion approval for PortCapability.kt and the single LiveGraphReadTest regression remains pending under §10.7. Nothing pushed/PR-created; WP11 shipping and WP12 source work wait for that final approval. Evidence: build/dependeasy/receipts/qualification/wp11-qualification.json.

- 15 Oct 2026 — Read the owner’s updated API and delivery handoffs. WP12/WP13/WP16 follow `dependeasy-api.md`, including delegated names, story vocabulary, typed component scopes and direct Gradle-free kernels. New work uses Maestro for product behavior and direct CLI kernel execution, with existing framework checks retained; no new kernel unit-test suite. The pending WP11 listener inclusion still waits for exact authorization under §10.7. WP19’s independent obsolete-runner cleanup may proceed alongside WP11 as explicitly allowed by delivery-handoff §5; its budget and acceptance are recorded there.

- 15 Oct 2026 — Continued the explicitly independent WP19 cleanup while preserving the WP11 shipping boundary. Cloud compile/process checks, ten existing server checks and three Worker dev dry runs pass; the delivery companion log and qualification/wp19 contain exact scope, source hashes, remaining deployment work and the current Kubernetes TLS validation limit. The API handoff remains authoritative for WP12/13/16. No new kernel unit tests, commits, push, PR or live write. The already-qualified two-file listener inclusion remains the outstanding WP11 owner decision.

- 15 Oct 2026 — Incorporated kernel-handoff.md: K0 follows the WP11 commit, then WP12/WP13 build the product/verify/delivery story directly. K0 source inventory and backup hash are retained under qualification/k0; no K0 source was staged or committed. Independently qualified WP19 Worker action matching: production catalog resolves 36 production/dev variants through 20 model deploy nodes, rejects stale/missing models, and gives no action to undeclared browser/external app-service. Both existing Deploy Maestro flows pass (47.795s). Evidence is qualification/wp19/deploy-matching-candidate.json. WP11's two qualified listener file hashes are unchanged; their pending inclusion approval still gates shipping and K0. No new commit, push, PR or live write.

- 15 Oct 2026 — Owner approved both qualified listener files. Committed only those files as Reaktor 16b08315, preserving the exact qualified tree 1096e3b12655846ca2ae579b77faedd92aaceb4a. Published Reaktor #18, BestBuds #8 and Manna #1 from their authorized branches; no merge. WP11 is complete with native/portable/IntelliJ evidence and its recorded coverage limits. Publication receipt: build/dependeasy/receipts/qualification/wp11/publication.json. K0 now proceeds before WP12 under kernel-handoff D2.

- 15 Oct 2026 — K0 landed after WP11 under owner decision D2. Four scoped commits pushed on existing Reaktor #18 / BestBuds #8; 746 reported module cases, 27 existing skips, zero failures/errors, including the required CLI/MCP owner case. Primary foreign work and staged PDF preserved; only the five approved graph metadata fields joined the candidate. K0 proof and commit mapping live in qualification/k0. WP12 now starts with the 15 Oct product/verify/artifacts/delivery API, following dependeasy-api and kernel-handoff §3.

- 15 Oct 2026 — WP12 budget and acceptance before source edits: implement the approved workspace plugin/story vocabulary (product, verify(parts), artifacts(parts), delivery, typed include roles), five distinct node receivers, delegated Kotlin names and Run modifiers. Use immutable typed specs and one Gradle binding path, preserve cross-build output variants and local routing. Then convert every affected caller, remove the generic workspace/command/package-alias escape hatches and fold the DAG entry into the same vocabulary; no deprecated aliases or toolchain/dependency bumps. Keep generated package commands until each K1 typed-CLI replacement exists, generating them from node + typed options rather than shell text. Device options move to the existing physical-device tasks; component-owned project migration remains WP13. Acceptance: existing TestKit fixture builds (no new kernel unit suite), positive and rejected DSL compilations, runtime model kinds/dependencies/spec sites, configuration-cache reuse, exact API dumps before/after, Gradle-free kernel plan invocation where added, then real Reaktor/BestBuds/Manna configuration/export and IntelliJ sync. First prove one product verification and delivery dependency end to end before widening. Baseline public signatures: BuildWorkspace 17, BuildTarget 22, DependeasyExtension 48, BuildPipeline 6 (constructors included, synthetic bridges excluded); qualification/wp12/public-api-before.*. Preserve all foreign working-tree hunks, and keep this package separate from K0 and WP19.

- 15 Oct 2026 — WP12 model contract qualification: WorkerRef replaces the old scalar worker and separate directory. Advance only the build-model schema from 2 to 3 and reject older schemas before decoding node fields, so retained exports request regeneration instead of failing with a type error. Budget: existing model, reader and schema fixture only; no compatibility branch or new contract. Acceptance: current model round-trip, schema-2 rejection with exportBuildModel guidance, three real exports and consumer compilation.

- 15 Oct 2026 — WP12 qualification in progress: full TestKit/model invocation 20261015-133922-f3c1dd68 succeeds in 2m22s, with final counts pending copied XML. Real Reaktor scripts configure after the Cloudflare ConfigurationRef import; an over-broad qualification command requested its nonexistent generatePackageScripts task and is retained as a failed invocation. Actual IntelliJ RESOLVE_PROJECT:11 identified the new mobile preparation receiver collision; qualified the environment property explicitly. RESOLVE_PROJECT:12 was cancelled after 75.961s, so it is not sync acceptance. Roots/exports, consumer compilation and a successful final IDE import remain required before WP12 delivery. Seven typed external declarations and the release adapter now have explicit WP16 removal conditions in the boundary register.

- 15 Oct 2026 — WP12 real export source-site correction budget before edits: all six three-root invocations pass, but the semantic audit found the automatic prepareNativeTools aggregate has no declaration because settings lifecycle registration has no .gradle.kts stack frame. Extend the existing internal source-site resolver to use an exact Kotlin stack-frame source path within the known dependeasy source roots when no script frame exists. No public API, option, directory scan, task or model-field change. Acceptance: every real story node carries both declaration and kernel source; retain existing source/determinism/cache fixtures and rerun full framework qualification.

- 15 Oct WP12 consumer qualification: the existing unsupported-schema fixture still replaced schema 2 after the wire contract advanced to 3, so its mutation became a no-op. Budget: update that fixture by copying the decoded model with schema = 1, preserving the old-schema rejection assertion; no new API or behaviour. Acceptance: existing CLI declaration/seal checks and Hangar compilation. IntelliJ cancellation traced to Kotlin script import reading the Stubs index while it requires rebuild; temporary method tracing removed and original bytecode restored before IDE cache repair.

- 15 Oct WP12 IntelliJ correction budget before edits: cache rebuild proved RESOLVE_PROJECT:0 succeeds, then reproduced a JavaScript plugin StackOverflowError indexing Hermes parser stress fixtures under each root .github_modules. Add one internal root IDE convention, called by pipeline/workspace plugins, using Gradle IdeaModel.module.excludeDirs for that dependency checkout directory. No authored source, CMake compilation, dependencies or public API changes. Acceptance: existing TestKit root fixture generates the correct IDEA exclusion and reuses configuration cache; real IDE imports the exclusions and repeats sync successfully after index repair. Keep this correction separately evidenced from the already-passed 120 framework checks.

- 15 Oct WP12 IDE source-ownership correction budget before edits: sync now passes twice after the vendored-fixture exclusion, but IntelliJ shows ambiguous duplicate DSL functions. Toolchain/buildSrc declare the full Gradle plugin source directory with compilation filters; IDE source sets consequently overlap. Move only the three existing bootstrap constant objects to one shared bootstrap source directory, compile that exact root in buildSrc/toolchain, and remove the broad roots/filters. No new module, public symbol, option, pin, task or runtime behaviour. Acceptance: unchanged compiled constant signatures, bytecode checks, full framework checks, CLI/Hangar consumers, actual sync and primary-root IDE diagnostics/navigation. Retire only completed owned scratch native outputs to recover the required storage budgets.

- WP12 bootstrap ownership follow-through: include the new bootstrap source root in the existing declaration-freshness policy and extend its existing content-change fixture. This preserves stale-model rejection for the moved constants; no new state or API. The running full-framework source freeze predates this one-line policy addition, so qualify this final model change with the focused existing reader suite before publication.

- 15 Oct WP12 router compatibility budget before edits: the private Gradle-request transport still accepts only build-model schema 2, so a schema-3 node can be planned but cannot execute remotely. Update that existing guard to schema 3 with exportBuildModel regeneration guidance, and extend the existing request-containment fixture to resolve real declared task targets and reject stale schema 2. Keep the separate hosts-layout schema 2, request schema 1, ownership, digest and private-file checks unchanged. Acceptance: existing router checks plus current three-root model resolution through the transport without invoking deployment or device actions; no new flag, state or contract.

- 15 Oct 2026 — WP12 qualified and committed: Reaktor `162ca4f3`, BestBuds `3673e133`, Manna `9db316c`; local tools `10149f5` (no remote configured). Full framework run reports 121 cases in 44 suites with no failures/errors/skips; final bootstrap-freshness reader check passes 7 cases, tooling consumer checks 14, router checks 9; Hangar main/test compilation and all three model/Dagger exports pass. Verified 1,296 Java-21 classes. Repaired IntelliJ's Hermes JavaScript indexing overflow and overlapping toolchain/buildSrc roots; actual composite sync passes repeatedly and primary-script source navigation resolves. Script-receiver members shrink 292→288, while the whole artifact grows with typed specs; the three moved constant objects preserve all 24 signatures. Component declarations and lower-level task/tool receivers remain explicitly assigned to WP13/WP16. Full WP11 CI gates were not repeated for this package. No live deployment/device action, plan publication or unrelated source inclusion. The staged PDF and foreign working-tree hunks remain intact. Exact evidence is in build/dependeasy/receipts/qualification/wp12, with publication mapping in publication.json. WP13 starts next.

- 15 Oct 2026 — WP13 budget and acceptance before source edits: five component plugins (web-app, apple-app, worker-fleet, device-tests, load-tests), contextual component scopes beside the existing library/application plugins, and projects only for existing deployables/suites. Reuse current specs, kernels, output variants and typed node handles; no new handle kind, scheduler, dependency, version or user flag. Product buildSrc contains immutable facts and small declaration composites only. Migrate authored component scripts, move suites/budgets/identity out of roots, and replace script-hosted task actions with owning kernels plus source entry/tag metadata. Preserve generated package aliases until K1 replacements exist. Acceptance: capture all current scripts/model nodes/package commands and representative Gradle dry-run task sets before edits; qualify one real web component through published variants/root verification first; then all component families, existing framework/TestKit checks and configuration-cache reuse, three-root model/contract generation, explicit task-list differences, API/forbidden-script counts and actual IntelliJ sync/navigation. Root declarations must read product → verification → delivery. Keep foreign primary hunks and staged files intact; use the existing qualification scratch checkouts without copying them. WP14 product-default removal and WP15 ambient host-service work remain separate. Baseline and source hashes are in qualification/wp13/scripts-before.json.

- WP13 alias compatibility follow-through before edits: relocating CompanyOS removes its two existing root package command names. Expose the web component's existing bundle/typecheck/build NodeHandles in its contextual scope and reuse the current alias writer there, so scripts preserve package command names as data pointing at typed values. Remove the single buildAlias parameter in favour of this one existing mechanism. Manna also preserves its deploy alias against the declared child value. No new task, handle kind, flag, stored contract or runner. Extend the existing web component fixture to assert all aliases and verification-before-deploy ordering; qualify the real web components and generated command key sets before proceeding.

- WP13 web-budget binding before edits: preserve BestBuds' existing asset metrics, limits and performance report while moving them from the root into appWeb ownership. One immutable WebPerformance fact record and one WebAppScope child declaration bind the existing ArtifactSizeReport and NodeGenerator adapter; product buildSrc holds the facts. Keep the existing adapter pending WP16, with no new measurement implementation, task class, runner or budget choice. Publish it through the component's verification role, retain all package aliases and root report paths, and compare the real bundle/report and dry-run dependencies.

- 15 Oct 2026 — WP13 web migration in progress: CompanyOS earlier component build passes (13 files, 9,383,799 bytes), and expanded web/DSL fixtures pass 10 cases with 1,326 Java-21 classes. Primary IntelliJ composite import RESOLVE_PROJECT:5 succeeds in 73.318s; CompanyOS script has green inspections and webApp declaration navigation opens the Kotlin source. Web budgets now belong to appWeb with immutable product facts; docs and Manna web use component projects, and legacy package command names are retained as typed aliases. These newest budget/docs changes still need behavioral and real-build qualification. Manna retry reached Vitest but failed loading a non-explicit-relative generated tsconfig; its extends spelling is corrected locally, unqualified. Thunderbolt 10.77.77.2 now routes through Wi-Fi and SSH times out; the final framework wrapper failed before Gradle. Reconnect/wake question is pending. Resume remote run 20261015-161051-a51f6fb4-manna before synchronizing new worker source, then qualify the current frozen scope, aliases/cache, remaining component families and roots. No WP13 commit/publication or live action. Evidence and exact outstanding acceptance: qualification/wp13/progress.json.

- WP13 connectivity follow-through: the owner reports m1-worker reconnected and awake, but local Thunderbolt interfaces and bridge remain inactive, the hardware inventory sees only the Dell display, and SSH still times out through the Wi-Fi route. IP configuration is intact; no network configuration or routing override was made. Independent pinned Vite 8.3.4/Oxc reproduction confirms the Manna tsconfig correction: with the referenced file present, bare build/... fails with the same TSCONFIG_ERROR and explicit ./build/... succeeds. This qualifies parser resolution only; the real Manna build and latest framework/web budget checks remain required. Evidence: qualification/wp13/tsconfig-relative-check.{mjs,json} and progress.json. No new source edit or WP13 publication.

- 16 Oct 2026 — WP13 resumed after interruption. Worker framework run 20261015-173737-39ca9ff0-reaktor actually completed successfully: 12 tests across WebContracts, DslEntryPoints and TypedKernelExecution, zero failures/errors/skips, 1334 Java 21 classes; normal recovery copied 30935 files without error. Manna explicit-relative tsconfig retry reaches 54 passing tests but 83 suites fail package resolution: generated Manna-domain metadata exists before the tests, while the authored manna-domain facade still points at its obsolete export directory. Correction budget before edits: extend the existing WebContractTask facade-to-producer mapping using same-owner manifest and entry-file metadata, plus its existing behavioral/cache fixture; no new public API, path convention, package pin or runtime edit. Acceptance: direct facade mapping without a workspace dependency, unrelated same-filename isolation, cache/input freshness, then actual Manna tests/types/Vite/Pagefind and aliases. Both temporary storage budgets remain below 20 GB.

- 16 Oct 2026 — WP13 package-alias comparison found that component-backed root deployments lose their generated aliases: PackageScriptsTask selection only includes worker-metadata targets, external calls and releases. Extend that existing selection to the existing Deploy kind and assert the root alias in the existing web-component fixture. Budget: one predicate and fixture assertion, no new script spelling or API; old alias keys must survive until K1. BestBuds CompanyOS missing aliases are in the current source but await latest package regeneration.

- 16 Oct 2026 — WP13 typed dependency budget before edits: two contextual dependency receivers at the existing KMP and JVM bindings, with api/implementation ModuleHandle overloads and JVM testImplementation where used; reuse Gradle/KGP notation for all other dependencies. Replace apiReaktor and authored .coordinate calls, and delete apiReaktor after its final consumer migrates. No CharSequence implementation, reflection, notation registry, new module, pin or option. Extend existing DSL entry fixtures to inspect actual dependency coordinates (single and multiple handles, ordinary project/Kotlin notations, configuration-cache reuse); then qualify three real root models and IntelliJ. This addresses KGP api(Any) shadowing extensions instead of merely renaming calls.

- 16 Oct 2026 — WP13 settings option budget before edits: move the existing option resolver into the toolchain binding and give Settings the same resolve/option overloads as Project, using the existing model dependency and canonical BuildOption types. Preserve all current resolution precedence and strict parsing. BestBuds settings declares IncludeManna and CI through BuildOption; CI presence, including empty text, remains a hard exclusion via existing OptionSource evidence. No new model type, option name, module, pin or fallback. Extend the existing settings fixture with default/property/system/command-line and empty-environment evidence plus cache invalidation/reuse; rerun existing option checks, all three real models and IntelliJ. Keep this source separate from the already-frozen web qualification until that run completes.

- WP13 settings preflight constraint: toolchain currently has no model runtime dependency, which protects the WP1 compiler/plugin classpath split. Do not add the entire model/kernel jar to toolchain merely for BuildOption. The preceding proposed resolver move is not implemented; qualify a narrow options boundary before changing this dependency direction. Web qualification remains on the unchanged toolchain.

- 16 Oct 2026 — WP13 real BestBuds export exposed an eager task-output read in the new web-budget adapter: model serialization resolves its argv before measurements execute. Correct only this binding to use the immutable declared report location for argv, while retaining the producer-backed input and explicit measurement dependency. Extend the existing web-component fixture to export the budget node and reuse configuration cache, then rerun the real web/model command. No new task, API, runner or budget policy.

- 16 Oct 2026 — WP13 load-test component budget before edits: add the approved load-tests plugin and a loadTests declaration over an enum-keyed immutable LoadSuite table (Karate/K6 cases, required/optional environment-file paths, tags and PackageScript aliases). Enum identifiers are the node names; no name rewriting or lookup. Reuse the existing pinned suite kernels and WorkspaceCompletion verification variant, with root-relative execution/source semantics and component-owned reports. Move all 16 BestBuds standalone suites and 22 aliases to targets/loadTests and immutable buildSrc facts, preserving manual-only root selection. Delete migrated workspace suite declarations, retaining the existing internal fleet binding. Extend the existing pinned real-HTTP framework case to cover the component, root variant, aliases, export privacy, optional-file presence and configuration-cache reuse; compare every real suite spec/alias and dry-run against baseline. No new scheduler, tool, dependency, version or user flag.

- 16 Oct 2026 — WP13 transport follow-through before edits: the real web build passes but its budget node has no declared artifact family, causing a conservative full transfer and a storage-limit refusal on the next command. Extend the existing internal task reference binding with an artifact-family override and mark the two web-budget tasks as web; output kinds and tool routing stay unchanged. Extend the existing web component fixture to assert both model and hosts contract families, then qualify the real mixed web command and cache reuse. No new public API, output kind, option, pin, transfer heuristic or scheduler. Retire only completed task-owned native packaging outputs and transferred payloads, retaining reports and source.

- 16 Oct 2026 — WP13 cross-host web acceptance correction before edits: normal routing sent Manna's repeat to this Mac; all 796 tests passed, then Vite could not find SQLite under the obsolete root build/js/node_modules path. The installed dependency exists in the declared Reaktor.Db Kotlin library's exported dependency context. Add one contextual WebAppScope.assets(from: OutputHandle, paths: List<WebAsset>) declaration, restricted to the existing KotlinJsLibrary kind, and bind its paths through that published output and dependency context. Reuse WebAsset, WebContractTask assets, existing producer edges and bundle source inputs; remove the two authored generated-build paths from Manna. Budget: one scope method and its internal binding, no new type, handle kind, task, schema, tool, pin, fallback or option. Acceptance: extend the existing web-component fixture to resolve the declared dependency asset and retain cache reuse; real Manna tests/types/Vite/Pagefind on normal routing, plus unchanged BestBuds web acceptance. Retain the failed local attempt; no cached-worker-only claim.

- 16 Oct 2026 — WP13 follow-through: all 16 load suites preserve their normalized execution specs, 22 aliases and 18 dry-run tasks after ownership/report moves; the repeat reuses configuration cache. All 6 web-contract cases pass on the dependency-assets revision, including byte-change invalidation of the bundle alone and cache reuse. Primary IntelliJ RESOLVE_PROJECT:10 succeeds in 66.931s resolution (73.300s full sync) with 17 source hashes verified. Merged 53 generated package commands across 7 changed manifests, preserving all 167 authored scripts and unrelated package fields. Manna run 20261016-131453-7fc1f17c-manna lost SSH during tests; its final result is unknown and must be recovered before synchronizing source. Thunderbolt hardware sees only the Dell display; bridge/en3 are inactive despite intact static configuration. Final Manna tests/types/Vite/Pagefind, actual SQLite assets and BestBuds unchanged warm-cache acceptance remain unqualified; WP13 remains in progress and uncommitted. See qualification/wp13/progress.json.

- 16 Oct 2026 — WP13 resumed after owner restored Thunderbolt. Recovered 20261016-131453-7fc1f17c-manna: exit 1, 790 passing tests with two Vitest worker startup timeouts; retained as a failed attempt, with no cause asserted. Fresh Manna tests/types/Vite/Pagefind pass 99 files/796 tests on both routes; the two SQLite dist assets byte-match the published Reaktor.Db dependency. Unchanged worker repeat 20261016-140829-dde42fbf-manna passes in 9s with configuration-cache reuse. BestBuds CompanyOS/appWeb budgets/Reaktor Web/docs pass twice in 20261016-135654-d24ad8e8-bestbuds and 20261016-140238-cd687cfd-bestbuds; both web-only transfers complete without copy errors, and the repeat reuses cache. Dagger child ownership and removal of the unused compose-flow parity report are implemented but remain under qualification. Fresh baseline contracts/task trees, framework fixtures, all-root cache checks and a new IntelliJ sync are required before accepting that slice. WP13 remains in progress, unpublished and uncommitted.

- 16 Oct 2026 — WP13 Dagger/catalog slice qualified: 13 focused framework cases pass, and normal primary generation/verification/model export succeeds in Reaktor, BestBuds and Manna. All three actual pipeline contracts byte-match their generated child references; eight current package manifests and all 20 frozen source hashes stay unchanged through those runs. The portable dry-run unions contain 577/3552/2061 tasks and store/reuse configuration cache after fixing WorkerFleet's captured provider. Membership matches after the documented child moves, aggregate additions and removal of verification-triggered generation; BestBuds/Manna raw ordering differs and remains recorded. No full local CI execution is claimed. Actual BestBuds default-composite IntelliJ sync RESOLVE_PROJECT:11 succeeds in 122.911s; Manna was excluded by its absent opt-in and still needs an explicitly included IDE check. A worker metadata collection gap omitted child Dagger references; nine router checks and normal recovery of the successful Reaktor run now qualify schema-1 reference collection and private-signing exclusions. Separately committed current BestBuds docs package commands are preserved. An external snapshot commit contains the earlier WP13 source, while this run's Tools transport fix and regenerated contracts are tracked separately; WP13 remains in progress. Exact evidence: qualification/wp13/{primary-dagger-generated-files,worker-provider-cache-proof,dagger-component-review,dagger-reference-transport-proof,primary-package-preflight-review}.json.

- 16 Oct 2026 — WP13 Manna identity budget before edits: one product buildlogic object with two immutable bundle/team facts, consumed by the existing Apple, Android and signed-in device declarations. Three authored files only; no framework API, task, option, pin, dependency, entry-point move or signing change. Preserve suffix selection and production-id refusal. Acceptance is byte-identical Apple host facts, unchanged model nodes/edges except source/declaration locations, real host/Dagger verification and an actual IntelliJ import including Manna. See qualification/wp13/manna-identity-budget.json.

- 16 Oct 2026 — WP13 Manna identity slice qualified: 684 nodes and 140 edges preserve semantics and membership, Apple host bytes and all three Xcode configuration files stay unchanged; host/Dagger verification and model export pass in run 20261016-154308-62b578c3-manna. Actual IntelliJ RESOLVE_PROJECT:12 imports Manna/buildSrc/Android/web/pipeline successfully in 149.069s; after restoring the exact original opt-in properties, RESOLVE_PROJECT:13 imports the default workspace successfully in 100.141s. All 22 source hashes remain unchanged. An initial proof assertion used an unchanged persisted .idea/gradle.xml file; its failure is retained and scope is established by the actual resolver module records. Source remains uncommitted; no app compilation, signing, device action or live write. WP13 remains in progress.

- 16 Oct 2026 — WP13 Apple component budget before edits: one approved apple-app plugin, one immutable AppleAppSpec, one enum qualifying existing GeneratedContract variants, and internal binding of the existing Apple tasks. Move both products into targets/appDarwin, publish host/release/fleet contracts, and resolve device app paths and web release NodeIds through those outputs. Derive command paths from owning task providers; preserve root-relative artifacts and remove the old mutable appleHost API and root aliases after callers migrate. No new handle kind, kernel, scheduler, tool, dependency, pin or option. Acceptance: existing Apple behavioral/cache fixtures expanded for child ownership and variant consumption, actual host/release/fleet generation and verification in both products without deployment, explicit model/task/path differences and IntelliJ import. Preserve Reaktor Graph identities, typed relations, ownership and existing graph-owned command execution; no parallel runtime graph. Full domain and legacy status is tracked in qualification/wp13/full-status.json.

- WP13 Apple qualification follow-through: the typed accessor fixture caught use of a nonexistent projects.rootProject accessor; use the generated root-name accessor in both fixture and BestBuds. The new contract link also makes WorkerFleet.release and its release selector flag unused. Remove that field, duplicate-release check and final flag caller in this slice rather than retaining the dead mutable route. No added API or compatibility alias; qualify actual web command identity through the fleet contract.


- WP13 Apple Android-order qualification before edits: BestBuds configures its Android owner before the new Apple child, exposing the old cross-project onVariants callback as too late. Extend the existing ModuleOutputs Android callback to retain the public AGP variant objects in one internal owner extension; Apple reads and copies release inputs after project evaluation. No new public API, task, contract, dependency, flag or scheduler. Remove Apple's own callback registration, add existing typed APK/bundle consume relations to its canonical release node, and extend the child ownership fixture with an already-evaluated Android owner. Acceptance: existing Apple/DSL tests and cache reuse; actual BestBuds/Manna host/release/Dagger/model generation without compiling APKs/frameworks or deployment; exact retained artifact/command comparison and real IntelliJ sync.

- WP13 Apple lifecycle qualification before edits: the new component's ordinary assemble must not select KGP embedding, which fails outside Xcode when SDK_NAME/CONFIGURATION/TARGET_BUILD_DIR/ARCHS/FRAMEWORKS_FOLDER_PATH are absent. Reuse the two existing published application Release framework variants (iOS arm64 and simulator arm64), preserving the old root iosFrameworks selection, and publish the component Build role as well as Apple. Keep the separate Xcode phase entry. Budget: one existing binding and its existing fixture, no new public symbol, task, option, dependency or scheduler. Acceptance: dry-run assembly selects exactly those link tasks and no embed/sign task; model contains typed consume edges; focused framework checks, real product host/release/model parity and warm cache checks.

- WP13 next fleet/API ownership budget before edits: apply the approved worker-fleet component plugin in BestBuds targets/workers and Manna targets/api. BestBuds gets the existing fleet through a top-level contextual workers declaration with immutable environment/prerequisite/contract-test parameters; delete root WorkspaceScope/BuildWorkspace workers entry points, mutable fleet prerequisite/environment setters and verification getter. Prerequisites become existing published OutputHandles; retain service-binding edges and all generated aliases until K1. Manna's standalone API needs its existing TypeScript/test/worker-types verification and deploy path without adding a browser bundle: use the same approved component plugin with a narrow workerApp declaration over the existing JavaScriptComponent and published Verify/Build/Deploy roles. Budget: one component plugin, two component entry functions, one immutable contract-test parameter type and one immutable standalone worker spec; no new module, handle kind, scheduler, dependency, pin, ambient option or deploy action. Preserve exact source configs, environments, hooks and outputs except documented owner/name/report relocation. Acceptance: existing web/DSL fixtures, canonical graph IDs/consumes/service edges, all 20 final configs byte-identical, alias comparison with only qualified task moves, deployment dry-run task parity and cache reuse, actual Manna API tests/typechecks and IntelliJ import after all ownership changes. No live deploy is part of this slice.

- WP13 Apple export ownership correction before edits: real BestBuds export on Gradle 9.7 rejects resolving `:appDarwin` configurations from the root export task. Resolve model file sets through the exporting project's own consumer configurations, while preserving the declaring project's node identity, invocation and source/kernel site. Alias invocation reads need only the declared Gradle path and must not resolve unrelated output files. Budget: existing BuildTarget/BuildWorkspace and existing component fixture only; no new API, type, graph, task, flag or dependency. Acceptance: child-owned outputs export without running their producers, both consume edges remain canonical, unchanged cache reuse, then the real BestBuds/Manna metadata runs. Retain the rejected run as failure evidence.

- WP13 device ownership budget before edits: use the approved device-tests plugin, one contextual deviceTests/suite/prepare binding and two immutable facts (DeviceSuite and flow selection). Both products move suites into targets/deviceTests; BestBuds stores its five suite facts in buildSrc and keeps every generated alias until K1. Reuse DeviceHook and the existing Process kernel for the two preparation steps; delete BestBudsPreparations and WorkspaceScope.deviceFlows rather than moving ad-hoc task code. Published Android/Apple contract handles retain canonical graph consumes edges; platform selection and optional installation must not eagerly build either app. Resolve roots from the workspace, enumerate flow globs through declared input providers, and treat optional environment membership as inputs. Preserve physical-device-only execution, signing/team/hook/harness behavior and explicit uninstall opt-in. No new handle kind, scheduler, dependency, version pin or CLI flag. Acceptance: extend the existing device options fixture for child ownership, selected-platform graph edges, no producer execution, cache reuse, changed flow/optional-environment membership and rejection of undeclared selections; compare all six real execution specs, aliases and dry-run task sets with only explained owner/report changes, then run the existing physical-device acceptance. Preparation nodes use existing Process receipts; setup/build aliases remain available through typed component values.

- WP13 fleet transport correction before edits: actual metadata-only BestBuds generation succeeds, but generateWorkerFleet is absent from the hosts task-family table, making normal collection fall back to all compiled outputs. Register its existing task as the GeneratedContract kernel node, assert the metadata family in the existing fleet fixture, and retain the typed published contract identity. Budget: existing fleet binding and fixture only; no transport heuristic, output kind, option, tool, dependency or pin. Acceptance: focused existing fleet checks, canonical model/contract ownership, normal metadata-only product collection and unchanged cache reuse. The active first collection finishes; pause only this task's driver to avoid repeating the wasteful transfer.

- WP13 device follow-through before wiring: repeated Thunderbolt interruption leaves completed BestBuds worker metadata uncollected. Preserve its observed Gradle exit 0 and failed recovery separately. Complete the already-budgeted device ownership removal now, then qualify one frozen combined Apple/fleet/API/device revision through existing framework cases, all product metadata/spec/cache/alias/task comparisons, physical devices and IntelliJ. Apple/worker comparison may normalize only the separately checked device owner moves and device aggregate/role additions; no unexplained node loss is allowed. This avoids freezing the replacement indefinitely on transport while retaining every acceptance gate. Remove duplicate flow-alias beforeBuild seeding because the unchanged beforeSuite hook owns the same setup; retain both build-alias preparation nodes.

- WP13 Kotlin analysis budget before edits: one contextual kotlinAnalysis declaration over source, launcher and test directories; reuse the existing pinned compiler/runtime and Process kernel. Move the existing integration test unchanged into one associated test compilation so class-name filters disappear. Keep compileKotlinAnalysis/kotlinSemanticDistZip/kotlinSemanticTest, the ZIP layout, JVM 25 target and assertions. Budget: three binding/tool files, the existing script and one source move, no dependency/module/project/pin/option/handle kind/scheduler. Register existing canonical nodes, explicit compiler/archive/test dependency edges and the existing JvmDistribution variant. Acceptance: actual existing analysis integration test and repeat cache reuse, archive entry/output comparison, model metadata without producer execution, explained dedicated test compilation task additions and final IntelliJ import. Remove all three script task registrations and doFirst actions.

- WP13 combined framework follow-up before edits: v6 reports 21 cases with one failure, the device fixture first repeat invalidates after its own initial package generation. Preserve all four XML reports. Budget: one existing fixture bootstrap invocation before the selected platform/cache scenario; no production change or weakened assertion. Generate aliases first, then require unchanged selected-command cache reuse, unchanged manifest bytes, flow/optional-environment membership invalidation, privacy, graph ownership and no producer execution. Real product cold/warm checks remain required.

- WP13 device fixture follow-through before edits: v7 retry reaches and passes the selected-command cache assertion, then Gradle rejects the fixture's empty --flow argument. Budget: only the existing fixture runner omits --flow when selecting all declared flows, matching the actual CLI contract; all graph, cache, changed membership and privacy assertions remain. No production API or behavior change. Preserve v7 retry XML reports and run the complete 21-case framework selection again.

- WP13 analysis ownership correction before edits: real BestBuds v8 export fails configuration-cache serialization because root model reading first resolves the child analysis classpaths. Budget: the existing analysis binding and existing DSL component fixture only; no new API, task, dependency, pin, option, scheduler or graph. Materialize the two external compiler/runtime file sets inside the owning task configuration, where Gradle holds the child project lock, retaining exact ordered command arguments and declared classpath inputs. Extend the existing root/child fixture with kotlinAnalysis and assert typed command metadata, no compiler/archive execution and unchanged cache reuse. Acceptance: all 21 existing framework cases, real BestBuds/Manna metadata cold/warm, original analysis integration test and byte-identical archive/spec comparison. Keep the v8 failure receipt.

- 16 Oct 2026 — WP13 analysis ownership correction imports successfully in actual IntelliJ (RESOLVE_PROJECT:22, 109.617s), all 45 frozen source hashes unchanged; Manna remains excluded by default and gradle.properties is unchanged. Framework v9 did not start Gradle: normal routing rejected shared remote storage after another completed packaging run copied 89,547 paths. Same-source retry is prepared behind normal cleanup. No WP13 completion or product execution acceptance is claimed.

- WP13 analysis ownership second correction before edits: v9 root-only composite export reproduces the same exclusive-lock failure while creating the lazy compiler task; that task configuration is still evaluated under the exporting root. Retain v9 receipt 20261016-200315-47ee9898-bestbuds (195 metadata paths, no copy error). Resolve only the two pinned external compiler/runtime classpaths directly in the declaring component, before registering its lazy tasks; this captures immutable file sets under the owning project lock and adds configuration work only to the analysis component. Keep source-input configuration identities and exact command order. Budget remains the same existing binding and DSL fixture, with no new public API/task/dependency/pin/option/graph. Strengthen the existing fixture with root-only export and unchanged cache reuse before any child task is selected. Acceptance remains the full framework selection, real composite export/cache checks, unchanged original integration test/archive/spec and final IntelliJ import.

- 16 Oct 2026 — WP13 owner-declaration revision passes actual IntelliJ RESOLVE_PROJECT:23: 71.202s full sync, 45 source hashes unchanged, default Manna exclusion and exact original properties retained. Root-only export regression and real composite/framework/product/analysis qualification remain pending behind normal storage cleanup; no WP13 completion claimed.

- WP13 v10 real root-only composite preflight succeeds: 20261016-201651-e4df0abc-bestbuds, Gradle 26s, configuration cache stored, 275 metadata paths collected with no copy error. Actual IntelliJ RESOLVE_PROJECT:23 already passes on the same 45-file revision. Framework/product/cache/graph/alias and real analysis qualification follows; WP13 remains incomplete.

- WP13 analysis graph follow-through budget before edits: reuse the existing KernelArtifact and NodeHandle.consumes mechanisms for the compiler classes → archive → integration-test chain. Replace ordering-only declarations with typed file consumes, retaining the same canonical IDs, dependency ordering, compiler inputs, archive payload and test behavior. Budget: the existing analysis binding and its existing DSL fixture only; no new public type/API/task/option/dependency/scheduler. Acceptance: the existing root-only export fixture records both typed Consumes and After edges without executing producers, unchanged cache reuse, then actual analysis integration/archive comparison. Apply after the active 45-file qualification freeze completes; do not invalidate its evidence mid-run.

- WP13 settings boundary budget before edits: the stable settings plugin needs the existing canonical BuildOption without depending on volatile model/Gradle implementation or exporting its heavy toolchain to Gradle-free consumers. The repository reaktor-module rule justifies a dependency split for this concrete cost. Move existing option types and their serializers, with unchanged packages/binary names, into one small Java-21 :options leaf shared by :model and :toolchain; move the existing Gradle option resolver into its stable owner and add Settings overloads using the same precedence implementation. Budget: one internal included-build module, two Settings overloads, no new option type, user flag, version, scheduler or runtime owner; reuse the existing serialization dependency. Include the leaf's sources in the shared declaration-freshness policy and bootstrap publication. Existing Project resolution and CLI/model contracts stay equivalent. Acceptance: existing option precedence/freshness/framework checks, one owning JAR per moved class, Gradle-free model/leaf classpaths, Java-21 verification, all-root metadata and configuration-cache checks, actual IntelliJ sync/default+opt-in settings behavior with Manna never included in CI, and the WP1 implementation-invalidation probe. Apply after the active focused analysis qualification; keep this boundary change independently reviewable.

- WP13 authored-alias follow-through before edits: two existing BestBuds perf commands still invoke removed root task bestbudsWebBundleBudget, although the generated alias already resolves to canonical bestbuds:appWeb:budget. Replace only those initial calls with pnpm run bestbudsWebBundleBudget; preserve both alias names, trailing shell bytes and every other manifest field. Budget: one manifest/two values, no API/task/flag/dependency or removed alias. Acceptance: exact two-command normalization in product parity, complete generated mapping and actual nested bundle invocation through normal routing. Record these intentional authored-script changes separately from owner/foreign edits.

- WP13 composite guide correction before edits: update only the existing BestBuds AGENTS setup paragraph from the stale default-inclusion claim to the approved local.properties opt-in and CI-presence exclusion. No new option, behavior or source API. Preserve all other guide bytes; actual Settings/default/opt-in/CI checks remain required for the canonical resolver migration.

- 16 Oct 2026 — WP13 contextual-library slice budget before edits: one immutable LibrarySpec, typed target sets and a narrow library/dependency scope, reusing the current target bindings and published output variants. Migrate health, sensors and surface first; no new plugin, module, handle, graph kind, dependency, pin, option or scheduler. Acceptance: extend the existing real DSL fixture for target/dependency parity, invalid target rejection, export without compiler execution, canonical output ownership and configuration-cache reuse; compare the three real task sets/model IDs and run routed framework/bytecode checks. Prepared budget: qualification/wp13/library-component-v13-budget.json. Activate after the current V12 product snapshots finish; WP13 remains incomplete.

- 16 Oct 2026 — WP13 qualification sequencing adjustment: options runtime boundaries, both actual IntelliJ modes and BestBuds cold/first-repeat contract generation pass. The first repeat reconfigured with Gradle reporting only a changed plugin input; its input diagnostic membership is identical, so exact cause is not claimed. Cancelled only the still-queued own stable-repeat process before Gradle, retaining its cancellation receipt. Combine the remaining product/cache/alias acceptance with the contextual-library slice after recording the three-library baseline; no package advance or claimed cache acceptance.

- 16 Oct 2026 — WP13 library fixture correction: the new case queried iosMainImplementation before KGP creates its default hierarchy. Keep the failed normal-router receipt; capture the same six dependency collections in the lazy probe task configuration after project evaluation. Production library binding unchanged, all assertions retained. Java-21 verification passed for 1,430 classes; qualification retry remains required.

- 16 Oct 2026 — WP13 same-primitive expansion budget before edits: prepare secrets, media and notification-fcm for the narrow library scope, including the existing Compose plugin. No new API/type/flag/dependency/graph kind/scheduler. Preserve the original target order, dependency declarations and Firebase transport-isolation comment. Record the actual ten-target task selection and canonical graph before activation; qualify exact task/spec/output/edge parity afterward. Candidate remains inert pending the first library fixture and both baselines.

- 16 Oct 2026 — WP13 library source-navigation budget before edits: baseline published outputs can pair a root convention line with a child script (surface line 37, old script 21 lines). Capture the actual library call site as one private Gradle extension value of the existing SourceSite type. Existing output publication projects that fact into Node.declaredAt, retaining canonical identity, producer/kernel, host, paths and edges. Three existing source files including the existing behavioral fixture; zero new public API/type/flag/dependency/graph kind/scheduler or stored model field. Require exact declaration-line assertions and unchanged cache reuse plus real library parity. Candidate inert pending active fixture retry.

- 16 Oct 2026 — WP13 product-root budget before edits: the BestBuds and Manna root declarations already orchestrate component outputs. Switch those two roots from the pipeline plugin/broad dependeasy wrapper to the existing workspace plugin and receiver, preserving group metadata and every canonical node, edge, task selection and generated alias. Move the BestBuds browser handle inside workspace. Zero new public API/type/parameter/flag/dependency/stored state/graph kind/scheduler. Record actual root-only graph/dry-run/alias parity, existing workspace fixture coverage and final IntelliJ import. Reaktor root tooling/publication ownership is a separate remaining conversion. Prepared candidate remains inert while current library/product sources are frozen.

- 16 Oct 2026 — WP13 component-role graph budget before edits: route both component and story role publication through the existing publishRole helper, adding the canonical Includes edge from each WorkspaceCompletion output to its existing producing node. Remove the device-only duplicate edge/publication. Zero new public API/type/parameter/flag/dependency/stored field/graph kind/scheduler; no execution or task-selection change. Extend the existing web/story behavioral fixture to assert all five producer relationships, preserve the device assertion, and compare real model node identities/specs, added role edges, contracts, aliases and cache behavior. Candidate remains inert during the active library/product qualification freeze.

- 16 Oct 2026 — WP13 library-test dependency budget before edits: extend LibraryDependencies with commonTest and jvmTest, reusing the existing platform test-dependency receivers and validating that jvmTest has a declared JVM target. Migrate surface-compose and Manna domain without changing dependency order, target order or domain version. Two public receiver methods; no new type, model field, task, module, pin, option, graph kind or scheduler. Extend the existing library behavioral fixture to inspect all eight actual dependency configurations and reject absent JVM targets; require real task/model/output parity and configuration-cache reuse. Candidate remains inert during the current source freeze.

- 17 Oct 2026 — WP13 Koin dependency cleanup budget before edits: all three real useKoin callers use only its defaults; its source-list branches and four public choices add no behavior. Replace it with commonKoin() on the existing common dependency receiver, preserving the same three pinned coordinates. Migrate location and notification to the narrow library scope and update graph's existing common dependency block, then delete useKoin and unused imports. One parameter-free public dependency helper replaces the existing public extension; zero new type, dependency, pin, field, option, graph kind or scheduler. Extend the existing library fixture with actual Koin coordinates and cache reuse; compare real target/dependency/task/model parity. Candidate stays inert until the current source freeze and the test-slot slice finish.

- 17 Oct 2026 — Completed the source review of all 13 authored CMake files. The removal register distinguishes the four small active contract/entrypoint bindings from exact-source, vendor, benchmark and host-test policy that still belongs in Kotlin. No native code or acceptance claim changed. First six-library after task check passes; expanded comparison and product gates continue. The actual CI-present Settings command passes Gradle with the worker’s explicit CI=true; collection remains pending and the empty-value fixture evidence is kept separate.

- 17 Oct 2026 — Registered `package-empty-v1-budget.json` before the inert candidate: remove three obsolete empty npm manifests (Auth root, IO root, old MCP js) and two stale workspace memberships (MCP js and absent BestBuds .dagger). Their main files do not exist and only the default failing test script remains; real TypeScript packages are retained. Existing pnpmLock/frozen install and exact dependency/model parity must qualify activation. No production file changed.

- 17 Oct 2026 — WP13 workspace-root retirement preflight: both existing product models contain an unreferenced root outputModuleBuild backed by completeModuleBuild, added by the broad extension. Neither root package manifest exposes a direct alias. Budget the removal of exactly that synthetic output/task in each product when switching to the workspace plugin; all named orchestration, child outputs, relationships and generated aliases still require actual parity. This is a read-only observation, not candidate acceptance.

- 17 Oct 2026 — Six narrow libraries qualify against actual normal-wrapper before/after evidence: 63 canonical nodes, six incident relationships and ordered task lists of 64 and 793 preserved; each published output navigates to its library call. Existing DSL suite passes five cases with configuration-cache reuse and Java-21 verification of 1,430 classes. Fresh model export and metadata collection complete (187 paths, no copy error). Original storage-admission failures retained; prescribed cleanup restored both budgets. Product contract/alias/cache and physical qualification remain; WP13 is incomplete.

- 17 Oct 2026 — WP13 networking-removal budget before inert candidate: a three-repository source search finds one useNetworking caller, IO, with defaults only. After the qualified Koin migration, move the identical existing common/platform coordinates into IO’s dependency slots and delete Dependency.kt, including the unused Source lists, withSources, dependency and useNetworking APIs. No new API, type, parameter, flag, stored field, dependency, graph kind or scheduler. Require exact seven-configuration dependency reports, task/model/graph parity, existing framework/bytecode checks and IO JVM checks. IO code generation and module settings stay in scope for later component conversion; no production source changed here.

- 17 Oct 2026 — WP13 continuation is guarded behind the two existing runs, with no duplicate Gradle request: finish current device/fleet/Apple comparisons and the same-source next-library baseline before activating test slots, default-only Koin cleanup and canonical role edges. The subsequent root, networking and empty-manifest qualifiers stop on the first failure. Networking acceptance now includes actual IO JVM and existing framework/bytecode checks; npm acceptance compares both actual pnpm lockfile documents and frozen installation, permitting only the obsolete empty importer and its exact model input. Full legacy status now explicitly includes CLI command families, 21 retained standalone main/two e2e source files plus wrapper, duplicate owner discovery and remote-dev Maestro policy. No production edit, package advance, commit or acceptance claim in this preparation.

- 17 Oct 2026 — WP13 JVM-only component budget before inert candidate: expose the existing `library` name as one JVM-only overload backed by the current JvmModule/Gradle compiler boundary, then migrate compiler and IDB while preserving Java 21 and IDB protobuf generation. One public overload, zero new type, dependency, plugin, module, flag, graph kind or scheduler. Extend the existing DSL suite for actual compiler/dependency facts, passive export, protobuf prerequisite, call-site navigation, wrong-plugin rejection and cache reuse. Require real task/dependency/model parity and actual compiler/IDB compilation with Java-21 class headers. Remaining native-boundary model completion stays WP16; no active source changes while the current qualification is frozen.

- 17 Oct 2026 — Before the next library activation, qualification review found that SurfaceCompose test-slot migration had framework coverage and actual task parity but no direct before/after test-configuration report. Add one normal-wrapper complete dependency report on each side, require every actual per-configuration dependency tree to match and require populated common/JVM test inputs. The existing continuation captures this before activation and its final library proof depends on the new parity check. No production API or source change.

- 17 Oct 2026 — WP13 CLI acceptance correction: the installed 14 October CLI fails `build show` on the current model’s ArtifactSet kind, while current source supports it. Budget: zero production API/source/dependency changes; rebuild the existing distZip through normal routing, validate an isolated install with workspace help and exact canonical build list/show/incident relationships, then use the existing installer to update the launcher while retaining its prior distribution. Attach this check after actual product-role qualification, clean up the isolated install, and retain stale/source truth. This repairs installed compatibility only; K1/K3 command wiring and legacy removal remain pending. Baseline failure: qualification/wp13/cli-installed-v13-read-baseline.json.

- 17 Oct 2026 — WP13 Reaktor-root budget before inert candidate: move the existing buildTooling and publishing methods from the broad extension into the existing workspace receiver, then switch the Reaktor root to its workspace plugin. The root retains ownership of shared JavaScript tooling and module publication aggregates; no child owner, dependency, flag, model field, graph kind or scheduler is added. Retire only the root’s unreferenced outputModuleBuild/completeModuleBuild pair. Extend the existing real publication fixture for versioned JAR output, canonical publication outputs, passive root ownership and cache reuse. Require actual ordered root/tooling/publication task and canonical graph/alias parity, plus actual tooling execution and final IntelliJ import. Four-file candidate remains inactive behind the current component/library checks; budget: qualification/wp13/reaktor-root-v1-budget.json.

- 17 Oct 2026 — V13 qualification now passes actual Manna API/cold/warm checks, both product device task selections, all three Dagger exports, device spec/30-alias parity and Apple/worker contract comparisons. Fixed only the ignored comparison readers for nullable aggregate specs and root-owned karateSetup; retained the original failed driver. One independently committed Manna auth alias is pinned as an exact before/after hash delta in component-v13-concurrent-config.json. Continued from snapshot with unchanged 64 guarded files and five retirements, without duplicating completed builds. WP13 remains incomplete.

- 17 Oct 2026 — WP13 suite installer graph budget before inert candidate: Karate and k6 already depend on their root-owned installers in Gradle, but those relationships are absent from the exported canonical graph. Extend their two existing declaration functions with after(rootProject.node(tool.installation)), reusing NodeHandle/BuildWorkspace and the same installer identities. Extend the existing pinned real-HTTP verification fixture to assert both relationships, passive export and cache reuse. Two files; zero new public API/type/parameter/flag/state/dependency/graph kind/scheduler and no task-selection change. Activate separately after current guarded slices; require exact real model additions and ordered task parity.

- 17 Oct 2026 — Activated only the prepared test-slot/Koin/role-edge slice: 16 files, 75 guarded source files. The prerequisite actual SurfaceCompose dependency report succeeded; its reader initially missed description-free Gradle headers, so the exact successful report was reparsed with all header forms and retained without another build. Prescribed cleanup passed; actual 16-case framework and Java-21 qualification now runs. Subsequent root/networking/manifest/JVM/CLI slices remain guarded; the two-file suite-installer graph candidate is separately budgeted.

- 17 Oct 2026 — V14 library test/Koin qualification passes all 16 existing framework cases, 1,428 Java-21 classes, actual ordered task and canonical model comparisons. The corrected report reader identifies 523 SurfaceCompose configurations: 508 exact trees, plus 15 unchanged pinned tool dependencies that were unresolved before and resolve now. Original failed resolution/parser/storage evidence is retained. Product role/contract qualification and current CLI launcher qualification follow; WP13 remains in progress.

- 17 Oct 2026 — WP13 actual Manna role qualification found outputWorkspaceCompletionDeploy missing its Includes relationship to appWebDeploy. Before editing, budgeted one production line and one existing fixture assertion: reuse publishRole with the same execution node and receipt file, zero new public API/types/parameters/dependencies/state/graph kinds. Fresh existing framework suites and exact three-root node/contract/edge comparisons are required. The stopped product attempts and exact stale build-25 baseline recovery remain evidence.

- 17 Oct 2026 — WP13 role-effect correction budget before edits: the existing output publisher labels every workspace role Verify. Correct only its mapping and extend the existing real web/workspace fixture: Verify remains Verify, Deploy becomes Deploy, Build/Android/Apple/Web become Build. Zero new API, parameters, flags, state, dependencies, graph kinds or scheduler. Preserve exact canonical IDs, producers, typed relationships and invocation/output bytes; qualify actual exports in all three roots and Java-21 bytecode. Existing recursive graph safety already follows Includes/Consumes; this finding does not establish an approval bypass. Current CLI launcher passes both isolated and installed 901-node reads, including ArtifactSet. Structural root qualification stopped before activation because local dev tools are enabled; repeat both sides with explicit bestbuds.devTools=false.

- 17 Oct 2026 — WP13 JVM, npm and Reaktor-root qualification: Two JVM-only build scripts now use the narrow library entry point. Actual compiler/IDB output contains 324 Java-21 classes and IDB protobuf Java output; eight canonical nodes, two relationships, dependency reports and 24 ordered tasks are preserved, with six existing DSL cases passing. Three unused npm manifests and two obsolete workspace entries are removed; both frozen pnpm installs pass with unchanged resolved packages/snapshots and generated aliases. Reaktor now uses the workspace receiver; 587 canonical nodes and 116 relationships are preserved, and the five ordered build/tooling/publication selections match (8,826 / 344 / 147 / 6,572 / 6,615 tasks). The unused synthetic root output is retired. The existing real publication fixture and actual tooling verification pass. One warm tooling run recalculated for a changed plugin input; the bounded next repeat explicitly reused the configuration cache. Publication output declaration paths still combine child ownership with root call-site lines in the old binding; a separate bounded provenance correction is prepared. WP13 remains open for remaining components, the product story, final IntelliJ import/navigation, physical checks and the invalidation probe. Evidence: library-jvm-v1-proof.json, package-empty-v1-proof.json, reaktor-root-v1-proof.json under build/dependeasy/receipts/qualification/wp13.

- 17 Oct 2026 — WP13 contextual bindings and unused-facade budget before activation: add one `LibraryScope.bindings()` declaration backed by the existing Karakum producer and canonical graph Includes edge; migrate Google without changing its dependency or generated-source contract. Remove the uncalled `swiftPackage`, `SwiftPackagePipeline.build` and public `dependencyRepositories` facades plus the unused `SwiftPackagePipeline` type; retain the internal repository configuration used by both plugins. No new public type, model field, dependency, flag, handle kind, graph kind or scheduler. Existing DSL/web/planning/publication checks, Java 21, actual Google generation/compilation and three-root model/dependency/task/alias/cache parity are required. Tactile remains the owner-approved missing-input placeholder. Detailed preimages and budget: `build/dependeasy/receipts/qualification/wp13/library-bindings-prune-v1-budget.json` and `unused-build-facades-v1-budget.json`.

- 17 Oct 2026 — WP13 source-slot budget before inactive candidate: one `LibraryScope.sources(directory, targets)` method and one immutable `LibrarySpec.sourceDirectories` map replace Crypto's authored source-set name lookups with existing typed library targets. Reuse the existing KGP source binding; add no type, dependency, plugin, flag, handle kind, graph kind or scheduler. Require the existing DSL fixture, exact actual dependency and selected-task comparisons, actual JVM/Android/JavaScript compilation, unchanged JVM/Android class content, Java 21 and three-root canonical graph/model/alias/cache parity. This candidate remains inactive until the component and bindings/removal slices qualify. Budget: `build/dependeasy/receipts/qualification/wp13/library-sources-v1-budget.json`.

- 17 Oct 2026 — WP13 late-ordering correction budget before inactive candidate: public NodeHandle.after currently adds a Gradle prerequisite and canonical After edge without updating a declared workspace node’s immutable spec; delivery bindings also retain the original gate list. Retype the existing ordering list to NodeHandle and bind the current spec/handles through the existing owner, preserving external-task behavior and cycle checks. Zero new public API, type, field, dependency, flag, graph kind or scheduler. Extend the existing delivery story fixture, first require a fresh failure on the old code, then qualify actual producer gating, 30 existing framework cases, Java 21 and three-root model/task/alias/cache parity. Candidate stays inactive until the current bindings and source-slot qualifications finish. Budget: `build/dependeasy/receipts/qualification/wp13/late-ordering-v1-budget.json`.

- 17 Oct 2026 — Late-ordering preflight found one real mismatch: BestBuds `:appWeb:checkWeb` already depends on `budget` in Gradle and the canonical After edge, but its immutable kernel spec has an empty after list. Permit only that exact spec field correction; all other node fields, IDs, graph relationships, aliases and task selections remain under parity checks. Include the four existing pipeline cycle/laziness/ownership cases with the 30 existing component cases (34 total); no new test cases. Evidence: `build/dependeasy/receipts/qualification/wp13/late-ordering-v1-existing-web-spec-gap.json`.

- 17 Oct 2026 — WP13 Crypto baseline completed all three real compiles, but the qualification reader wrongly required Java 21 for product JVM classes. Current pinned product defaults are JVM Java 25 (26 classes, major 69) and Android Java 21 (27 classes, major 65); Dependeasy itself remains Java 21. Correct only the reader, retain the failure and successful baseline receipts, then require identical actual class bytes after source-slot migration. No product or toolchain setting changes.

- 17 Oct 2026 — WP13 source-slot fixture correction budget before edit: the new negative assertion dereferences iosMain before KGP completes the hierarchy. Inspect all present source sets and require the shared directory in exactly Android/JVM, absent from every other source set. Existing fixture file only, zero API/type/parameter/state/dependency/graph additions. Retain the failed fresh JUnit evidence; rerun the existing focused framework selection, then all real compiler/model parity acceptance.

- 17 Oct 2026 — WP13 contextual native budget before activation: Security moves to library with typed native links, Apple C-header parameters, generated bindings and processor module handles. One contextual NativeLibrary receiver; five public methods, one declaration-time processor list, remove the now-unused darwinNative facade; no new plugin/dependency/flag/handle/graph kind/persisted field/scheduler. Existing NativeContract and KotlinProcessorKernel retain execution ownership. Require the existing native/DSL fixtures, real Android arm64 and iPhoneOS libraries, exact compiler/native contracts, class/dependency/task/model/alias parity and cache reuse. Candidate remains inactive until current source-slot and late-ordering proofs pass.

- 17 Oct 2026 — WP13 native candidate review: typed ModuleHandle dependencies currently always become external coordinates, which cannot preserve an intra-build compiler/core project dependency. Before activation, extend the existing KotlinDependencies binding with one internal Project.dependencyNotation helper: exact same declared build identity uses the handle project path, otherwise its external coordinate. Reuse it for typed API/implementation and processor wiring. Extend the existing DSL case to prove both branches, without resolving fixture coordinates; no new public API, handle, discovery or dependency. Exact real dependency/task comparisons remain mandatory.

- 17 Oct, WP13 current consumer check: `checks.affected` fails before selection because the running workspace owner model jar has no `KernelSpec.DependencyBoundary`. Both its installed conductor and existing agent-dist contain SHA256 `06ca76f9b8c58202db12de4f201de433234d71824e48a9af24633b03d5153537`; the current qualified model jar contains the type. Prepare a fresh agentDist and exercise the real owner after the current component qualifications. No runtime/kernel source edit or protocol change is proposed.

- 17 Oct 2026 — WP13 Security baseline fails before contextual native migration: Karakum-generated union declarations require newer Kotlin/JS support. Budget before edits: one fixed KotlinLibraries coordinate and Security JS dependency, using the exact 2026.8.4 wrappers version in pinned Karakum v1.0.0-alpha.112. Keep shared BOM, compiler/generator pins and generated/product source unchanged; qualify actual compilation and resolution before using this as the native baseline.

- 17 Oct 2026 — Security baseline runtime-only trial resolves JsUnion but actual union Companion generation still fails. Budget amendment before edits: use the existing upstream Seskar compiler extension via a fixed 4.66.0 toolchain dependency matching Kotlin 2.4.21; the current Karakum bindings owner applies it directly. Retire unused Versions.Seskar 4.25.0. No generator/product source edit, new flag or public option. Actual Security and Google JS compilation plus existing binding/framework cases must pass.

- 17 Oct 2026 — WP13 Security JS baseline repaired and qualified: the installed Karakum generator needs its 2026.8.4 runtime and Seskar 4.66.0 compiler support for the pinned Kotlin 2.4.21 compiler. Security and Google actual JS compilation, dependency resolution, 19 existing framework cases and 1,437 Java-21 implementation classes pass. Shared wrappers BOM is unchanged; unused Seskar 4.25.0 constant retired. Failed runtime-only evidence retained. Native migration has not yet been activated. Evidence: `build/dependeasy/receipts/qualification/wp13/security-js-runtime-v2-proof.json`.

17 Oct — Security contextual native qualification passed (`library-native-v2-proof.json`): 28 existing framework cases, 1,439 Java-21 implementation classes, 10 unchanged dependency configurations, 125 byte-identical class/native/binding/contract outputs, and the exact ordered 239-task selection. Actual JVM, Android, JavaScript and iOS compilation passed; all three Android ABI libraries and the iPhoneOS archives were checked. The three roots preserve all previous canonical nodes, relationships, omissions and alias bytes, adding only the four component-role nodes and five typed relationships. Output provenance now points to the actual component declaration, including four ports that previously fell back to an internal helper. Three warm configuration-cache runs pass. Retired the unused `darwinNative` facade. WP13 continues with the MCP consumer refresh, FFI interop and remaining component scopes; it is not complete.

WP13 current-consumer qualification (17 October): `mcp-model-consumer-v1-proof.json` records the complete current owner build, seven existing checks and the kernel boundary, exact installation of 262 distribution files, normal upgrade, and an actual MCP affected-check operation against all 969 current canonical nodes. `intellij-native-v2-proof.json` records an actual BestBuds import completing in 81.085 seconds, zero root-script errors, successful targeted IDE compilation, and Kotlin navigation for workspace/verify. Android/Kotlin compatibility warnings remain in the cleanup inventory. These prove the current native slice; final WP13 import and remaining component scopes are still pending.

FFI contextual interop qualification (17 October 2026): 28 existing framework cases and 1,439 Java 21 implementation classes pass; 85 actual compiler/native/generated outputs are byte-identical and the baseline 298-task order is preserved. Removing the unused processor avoids 31 extra metadata prerequisites. Canonical graph relationships, all three warm caches and all four gateway checks pass (run-1792206703544-1). Six existing cross-project Flexbuffer declaration locations remain explicitly pending; this does not close WP13. Evidence: build/dependeasy/receipts/qualification/wp13/library-interop-v1-proof.json.

Apple-only component qualification (17 October 2026): Both narrow Apple-only libraries preserve all eight actual iPhoneOS framework files byte for byte, both dependency trees and all 78 ordered link tasks. Eighteen existing framework checks and 1,441 Java 21 classes pass. Three-root canonical nodes, aliases and omissions are preserved; four Build-role nodes and four real typed relationships are added, without claiming portable verification. All three warm caches reuse. The broad appleLibrary facade is removed. The excluded obsolete React Gradle entry also qualifies, with all 51 historical files and all 43 settings inclusions preserved. WP13 remains incomplete. Evidence: qualification/wp13/library-apple-only-v1-proof.json and paused-react-entry-v1-proof.json.

WP13 declaration-owner qualification (17 October 2026): 30 existing framework cases and 1,441 Java 21 implementation classes pass. All six known Flexbuffer locations now point to the real owning component. Precise task and root sites are preserved; actual 85 FFI output files and 298 ordered tasks are unchanged. Canonical nodes, relationships, aliases, omissions and warm cache reuse pass in all three roots. The failing regression and two rejected candidates are retained. The temporary broad-module source capture retires with that facade later in WP13. Evidence: qualification/wp13/component-declaration-owner-v3-proof.json.

17 Oct 2026 — Tooling contextual-library qualification passed: 219 unchanged actual JVM results (one existing skip), nine dependency configurations, 122 ordered tasks, eight existing framework cases and all 1,035 implementation classes at Java 21. Of 1,630 compiler files, 1,627 are byte-identical; the three Kotlin/JS metadata files contain only 29 exact worker/local source-path changes and the two derived fingerprint fields, verified against the pinned compiler format and actual frozen source. The strict failed comparison is retained. All three canonical models preserve existing contracts, aliases and omissions, adding four role nodes, the existing real boundary and six relationships. Warm caches and both gateway checks pass (run-1792213118155-2691). The catalog path remains unmapped by gateway inventory and is explicitly covered by the direct framework/boundary evidence. WP13 remains open. Evidence: qualification/wp13/library-tooling-v1-proof.json and library-tooling-v1-gateway-proof.json.

17 Oct 2026 — GraphPort/MCP/Performance contextual TypeScript qualification passed: 52 identical JVM case results, 27 dependency configurations, eight existing framework cases, all 1,035 Java-21 implementation classes and all 506 compiler/native/contract files are preserved. All three real TypeScript checks and four native libraries pass. Canonical models retain existing contracts under the three declared internal producer renames, adding 12 role nodes and 18 typed relationships per root; aliases, omissions and warm caches qualify. The same 388 selected tasks remain: exactly two GraphPort bridge prerequisites move earlier after their own export; the other 386 retain their order. All nine graph-selected checks pass (run-1792214051836-3221). Three broad receivers and two repeated test-name filters are retired; authored npm commands remain for K1. WP13 remains open. Evidence: qualification/wp13/library-typescript-checks-v1-proof.json, task-order-proof.json and gateway-proof.json.

- 17 Oct 2026 — WP13 BestBuds Design qualification: existing narrow library/Compose/dependency slots replace its broad receiver and repeated class glob. Both existing palette cases pass, all 445 compiler files byte-match and all nine dependency reports match under the same task realization. Actual graphs preserve all 1,088 task identities, 3,881 prerequisites and explicit ordering; the complete 158-task BestBuds sequence is unchanged. The initial console interleaving and mismatched dependency-report realization failures are retained with exact follow-up evidence. Canonical model preserves 1,017 existing nodes and 629 edges and adds only four component role nodes and five typed role edges; aliases/omissions and warm cache qualify. Both graph-selected checks pass in run-1792215061843-3892. Evidence: qualification/wp13/product-design-library-v1-{proof,gateway-proof,task-graph-proof}.json. No new API, runtime or token edits. Devtools source-slot qualification follows; WP13 remains in progress.

17 October 2026 — WP13 Devtools shared sources qualify. The existing typed target source slots now own the same three shared Kotlin files. All 35 actual JVM results, 694 byte-identical JVM/Android/JavaScript/iPhoneOS compiler files and 12 exact resolved dependency graphs pass. The remaining 493 selected tasks retain their order; eight resource tasks had actual baseline NO-SOURCE/SKIPPED results and are retired. All three roots preserve existing contracts, aliases and omissions while adding four role nodes and five typed edges; actual warm caches and both graph-selected checks pass (run-1792215826482-4728). Raw task/dependency comparison failures and their precise proofs remain retained. Core/UI TypeScript emission is next; WP13 remains incomplete.

- 17 Oct 2026 — WP13 Core/UI TypeScript emission qualified: 135 actual JVM cases, 1,592 byte-identical compiler/emitted files, 24 resolved dependency graphs, exact 594-task identity/prerequisite/order parity under two owned internal prefix renames, eight existing framework cases and Java 21, all 499 BestBuds engine cases, three-root model/alias/cache parity and all six graph checks (`run-1792217214930-5351`). Existing contracts/aliases remain; exactly eight role nodes and twelve typed relationships are added. Core broad receiver and four redundant helper exclusions retire. UI classpath/probe binding remains explicit WP13 work. Proofs: `build/dependeasy/receipts/qualification/wp13/library-typescript-emission-v1-proof.json` and `library-typescript-emission-v1-gateway-proof.json`. Next is the bounded Conductor/Cloudflare test-environment slice; ComposeFlow all-JVM/Android policy remains separate.

- 17 Oct 2026 — WP13 Conductor/Cloudflare test environments qualify: eight existing framework cases, 1,041 Java-21 implementation classes, 1,255 byte-identical compiler files, 223 unchanged JVM results (15 existing skips), all 14 Node cases, 20 resolved dependency graphs and exact 457-task identities/prerequisites/order/runtime settings. All three roots preserve prior contracts/aliases/omissions, adding eleven owned nodes and fourteen typed relationships; warm caches reuse. Isolated and installed CLI reads agree with all 1,044 current nodes. Seven existing consumer cases, actual Kernel/Cloudflare boundaries, complete normal MCP owner upgrade and three graph checks pass (`run-1792218516290-1`). Conductor/Cloudflare broad receivers and raw test wiring retire; per-case Mocha settings stay distinct from whole-task deadlines. The owner backup is removed after the terminal receipt. Seven framework files remain outside gateway discovery, explicitly covered by direct framework/consumer evidence. Proofs: qualification/wp13/library-test-environments-v1-{proof,consumers-distribution-proof,gateway-proof}.json. Next is Android application component scope; WP13 remains incomplete.

- 17 Oct 2026 — Android application component scope qualifies within its eleven-path budget. Twelve existing framework cases pass; implementation/model bytecode stays Java 21. BestBuds preserves all 1,262 signed APK entries and four compiler/manifest files; Manna’s managed fixed-source pair preserves all 1,120 entries and 21 files while unrelated Blueprint work continues. Exact AGP/signing/shrinker/dependency policy, all 2,640/1,865 composite tasks and their prerequisite/order relationships are preserved. Independent included-build dry-run log interleaving varies; every within-build constraint passes. All three roots preserve prior contracts, aliases and omissions with two owned Build nodes/two edges per application and warm-cache reuse. Installed CLI and actual gateway reads pass; actual IntelliJ sync succeeds in 52.845 seconds, with zero Android-script diagnostics and authored androidApp source navigation. The raw AGP facade is retired. Frozen-run failures and corrected worker-origin manifest evidence remain recorded; no physical-device or portable Android Verify claim is made. Proofs: qualification/wp13/android-app-v1-{proof,cli-proof,gateway-proof,model-bytecode-proof}.json and intellij-android-v1-proof.json. WP13 continues with remaining libraries, desktop/probes and final product acceptance.

- 17 Oct 2026 — Compiled-entry foundation qualifies: source `@Entry` replaces the WebView program main-class string and raw JavaExec declaration. Five existing framework cases, isolated Gradle-free Java execution, actual source/class contract, exact 222-task/classpath/JDK parity, Java 21 model bytecode, three-root canonical metadata/alias/cache comparisons and actual warm resolver reuse pass. All 503 Engine JVM cases pass on the exact worker snapshot; two independent concurrent local Engine edits remain explicit and final current-source qualification stays at package closure. Seven current consumer cases, both dependency boundaries, complete normal owner upgrade, exact isolated/installed CLI and live gateway reads, a fresh normal local model refresh and six graph-selected checks pass (`run-1792227466740-1`). The owned owner backup is removed after the terminal receipt. Normal worker qualification caught the ignored new Core annotation; it is now explicitly tracked and subsequent real worker compilation passes. Original failures are retained. Proof: `qualification/wp13/compiled-entry-v1-qualified-proof.json`. Desktop composition, tags, real Hangar outputs, full WebView pipeline and final IDE/physical acceptance remain WP13; five-layer completion remains WP16. Next is the contextual library probe classpath slice.

- 17 Oct 2026 — WP13 contextual probe classpaths — direct checks passed, qualification continues: seven existing framework cases, exact 310-task graph/dependency/order parity, four resolved configurations, byte-identical UI/Web reports and all 654 compiler files pass. The UI final broad receiver is removed; both existing report producers have typed canonical kernel specs. Reaktor/BestBuds cold and warm metadata checks pass. Manna metadata and the current consumer/Engine/gateway checks remain pending after normal storage admission failures; no package completion claim. The bounded desktop component/compiled-entry budget and real-task baseline capture are prepared, with no desktop activation before terminal qualification. Evidence: `qualification/wp13/library-probe-classpaths-v1-{task-graph,actual-reports}-proof.json`.

- 18 Oct 2026 — WP13 FlexBuffer contextual component qualifies: exact 295-task dependency/order/runtime/compiler/dependency parity; 91 JVM, 39 JavaScript and 39 Android cases pass without failures or skips. Four native libraries, eight native contracts and 115 generated Kotlin files are byte-identical. All three roots add only four component roles/five typed edges; aliases/cache and fresh installed CLI/live MCP pass. Canonical graph checks succeed in `run-1792269751683-6032`. The unused broad `nativeLibrary` facade is next; processor/benchmark/test/compiler policy glue and final WP13 acceptance remain. Evidence: `qualification/wp13/flexbuffer-component-v1-proof.json`.

- WP13 continuation: the unused broad `nativeLibrary {}` facade is removed. Five existing native composition/ownership cases pass; all three root model/alias/cache checks pass, including an exact fresh BestBuds old/new node-and-edge comparison. Installed CLI and live MCP agree on current canonical identities and ownership. Contextual native declarations and CMake implementations remain. Compiler codegen retirement is the next active removal; WP13 remains working. Evidence: `build/dependeasy/receipts/qualification/wp13/unused-native-library-facade-v1-proof.json`.

- WP13 continuation: five unused compiler codegen files (116 source lines, 15 class entries) are removed. Seven fresh compiler cases pass; 115 generated Kotlin files and all 12 retained compiler classes are byte-identical, Java 21. The only retained JAR entry change is the Kotlin module index, which drops the deleted package facade. Canonical IDs/edges remain; graph check `run-1792270787512-7721` succeeds. Evidence: `build/dependeasy/receipts/qualification/wp13/unused-codegen-prune-v1-proof.json`. Web library migration follows; WP13 remains working.

- WP13 Web contextual library qualified (18 Oct): 399 exact selected tasks, eight dependency graphs, 21 compiler flag sets, 12 fresh JVM cases, Android/iPhoneOS/instrumented compilation and seven byte-identical outputs. Three-root roles/aliases/cache and current CLI/MCP pass; graph-owned JVM/JavaScript/WebBridge run `run-1792271425728-7893` succeeded. Receipt: `build/dependeasy/receipts/qualification/wp13/web-component-v1-proof.json`. Native/probe and empty-test glue remain; WP13 stays active.

- WP13 Engine component qualification (18 Oct 2026): contextual Android/JVM dependencies retain 174 exact selected tasks, six resolved dependency graphs, nine compiler flag sets and JVM/Android runtime/discovery/isolation settings. All 504 fresh existing JVM cases pass with zero failures/errors/skips; Android main compiles and Android unit/instrumented tasks contain no sources. Three-root model/alias/cache, current installed CLI/live MCP and graph-owned check `run-1792272223089-8429` pass. Evidence: `build/dependeasy/receipts/qualification/wp13/engine-component-v1-proof.json`. Test environment/source identity glue and WP13 final gates remain.

- 18 Oct — WP13 unused `jvmClasspath` receiver facade retired. One existing contextual framework case, UI 210/Web 151 ordered classpath entries, all three canonical models/aliases/warm caches and current installed CLI/live MCP views pass. Dependeasy implementation remains outside automatic check discovery; direct qualification is retained. Proof: `build/dependeasy/receipts/qualification/wp13/unused-jvm-classpath-facade-v1-proof.json`. WP13 remains in progress.

- 18 Oct — WP13 Cloud contextual component qualified: 656 exact task relationships, 15 resolved configurations, 16 compiler flag sets, local process-boundary E2E and JVM/Android/iPhoneOS compilation. All 2,475 generated SDK class files and 66 Cloud JVM files are byte-identical. Three roots add only four roles/four edges; aliases/cache, installed CLI/live MCP and canonical check `run-1792273298142-9387` pass. Cloud has no JUnit cases; the process E2E is the executable evidence. SDK and three command entries still need typed ownership. Proof: `build/dependeasy/receipts/qualification/wp13/cloud-component-v1-proof.json`.


WP13 continuation — Kernel component qualified (2026-10-18): 16 selected task relationships, four resolved configurations and test/compiler policy preserved; 336 fresh cases (11 skipped), boundary/launcher/distribution passed; 986 compiled main files byte-identical. Three-root canonical metadata, package aliases, warm cache, four current CLI/MCP views and graph run `run-1792275156736-9999` passed. Explicit publishers now retain precise authored source sites while inferred publications retain component ownership, verified in the existing DSL fixtures and all three models. Parser source-identity coverage remains partial and is still WP13 work. Kernel boundary/entry/distribution/test policies and the final WP13 gates remain open. Server contextual component is next; WP14+ are unstarted. Evidence: `build/dependeasy/receipts/qualification/wp13/kernel-component-v1-proof.json`.


### Server contextual component — scoped qualification (18 October 2026)

74 selected tasks/relationships, four resolved dependency configurations, compiler/test/image facts and bootJar behavior are preserved. Nineteen fresh JVM cases completed without failures/errors (one skipped). Current three-root model, aliases, warm cache and CLI/MCP views qualify; the canonical graph check run-1792276041670-10302 succeeded. Parser coverage is partial because source identity is unavailable; this remains WP13.

Removed the springImage facade/BootBuildImage receiver escape and unused tools getter. The initially removed nodeScript facade has a real existing boundary fixture consumer: it is restored, and both affected existing fixtures pass. No image was pushed or rollout performed. Typed source identity and test discovery remain; WP13 is still in progress. Evidence: build/dependeasy/receipts/qualification/wp13/server-component-v1-proof.json.


### CLI contextual component — scoped qualification (18 October 2026)

Sixteen fresh CLI cases, installed help/build-list, all 24 existing task relationships plus one compiled entry resolver, four resolved configurations, three-root canonical roles/aliases/cache and current CLI/MCP views pass. Graph-owned check run-1792277007175-11219 succeeded, with partial parser coverage retained honestly. The application main class now comes from compiled @Entry; a Gradle javac module-main convention cycle found by the existing fixture is corrected for this classpath-only component. Raw main-class/flag/test receivers and the named KernelBoundary lookup are removed. The complete legacy CLI target remains until K1/K2 command parity. WP13 remains in progress. Evidence: build/dependeasy/receipts/qualification/wp13/cli-component-v1-proof.json.


### Browser contextual component — scoped qualification (18 October 2026)

The existing mixed-component fixture and real production bundle pass. All 106 selected tasks, two resolved dependency graphs, seven compiler settings, module/resource facts, 127 bundle files and 126 processed resources match the baseline. Three-root ownership, aliases/cache and current CLI/MCP views qualify. The broad browser facade, mutable settings and authored source-set plumbing are removed; canonical componentBuild/ModuleBuild ownership feeds the existing reaktor-graph projection. No Browser verification task exists, so this records actual build evidence without inventing a check result. The shared Vite adapter and explicit foreign database resource path remain; the latter is WP17. WP13 remains in progress. Evidence: build/dependeasy/receipts/qualification/wp13/browser-component-v1-proof.json.


### Manna JavaScript runtime — scoped qualification (18 October 2026)

All 198 actual JavaScript cases pass before and after, with identical test identities. The 81-task closure, Node preloads and file inputs, Skiko files, two resolved dependency graphs and 19 compiler-setting sets are exact. The existing mixed-library fixture passes; three-root canonical models, package aliases and warm cache qualify. The complete current CLI/kernel distributions and normally upgraded MCP owner decode the new strict TestEnvironmentSpec preload field. Installed CLI shows current Manna node/spec/relationships; MCP reads are source-fresh in its active BestBuds workspace. Manna gains one canonical verification node and two typed relationships. Its last broad dependeasy block is removed and all tracked authored-script legacy-pattern counts are zero. BestBuds App still uses the shared kotlinNodeTests facade, so that facade is retained until its owner converts. Existing reaktor-graph projection remains the single definition model; no invented graph-run evidence for opted-out Manna. WP13 remains in progress. Evidence: build/dependeasy/receipts/qualification/wp13/javascript-test-runtime-v1-proof.json.


### Contextual source identity — scoped qualification (18 October 2026)

Engine and Server now declare identity as contextual values, and their component build roles include the generated resources in the canonical graph. The mutable sourceIdentity facade is removed. All 73 requested tasks/relationships, 2003/1517 source entries, revision facts and processed resource bytes qualify; the worker router adds only its documented model export. Two existing framework cases, three-root models/package aliases/warm cache, seven fresh reader cases, complete installed CLI/kernel distribution and four source-fresh live MCP views pass. Graph-selected Engine and Server checks complete 504 and 19 JVM cases respectively with no failures/errors and one existing Server skip. Coverage remains Partial in the current result parser. IntelliJ resolves Engine identity to its source declaration; final reimport/navigation, Hangar process refresh and physical gates remain. A mixed-build artifact transfer defect was confirmed and retained for the runner package; the separate normal full distribution build passed and was installed without JAR overlays. WP13 remains in progress. Evidence: build/dependeasy/receipts/qualification/wp13/source-identity-v1-proof.json.


### Server owned test discovery — scoped qualification (18 October 2026)

The server component now owns the existing explicit JVM test discovery policy, removing the final Server authored dependeasy block with two added and four removed lines and no public API addition. All 74 tasks and relationships, discovery/filter/classpath/system settings, five resolved configurations and compiler/image facts match. The unchanged component fixture, three-root models/package aliases/warm cache, current installed CLI and four source-fresh live MCP views pass. Graph-selected Server check passes all 19 cases including one existing skip; fresh copied XML was checked against authoritative worker hashes and exact baseline case identities. Parser coverage remains Partial (162 unrecognised lines). Mutable image/rollout remains WP23; shared discovery helper and WP16 separation remain live. WP13 remains in progress. Evidence: build/dependeasy/receipts/qualification/wp13/server-discovery-v1-proof.json.


### Engine test environment — scoped qualification (18 October 2026)

Engine now declares JVM and Android unit suites through typed component values. Its final broad Test block, ambient host-home read and authored path/task setup are removed. The existing mixed-target fixture passes; all 174 tasks and relationships, six resolved dependency configurations, discovery/runtime/compiler facts match, with one intentional existing JVM EAWT access flag. All three roots, package aliases and warm cache qualify. Seven reader cases and normal full CLI/kernel distributions qualify; the installed CLI and four actual live MCP views remain source-fresh after the graph run. Canonical model gains three nodes and four includes/consumes edges consumed by the existing activation owner. Run run-1792282806385-1 succeeds with 842 fresh cases, 15 existing Conductor skips and Android unit NO-SOURCE, verified against authoritative worker XML hashes and prior case identities where available. Coverage remains Partial (175 unrecognised lines; source identity unavailable or changed during execution). Kernel live freshness/discovery and remaining eight script owners/final gates stay open. WP13 remains in progress. Evidence: build/dependeasy/receipts/qualification/wp13/engine-test-environment-v1-proof.json.


### Kernel typed JVM tests — budget before activation (18 October 2026)

Continue WP13 with the existing pure JVM component and TestEnvironment model. Seven files, zero public types/dependencies/plugins/flags/runtime state; one contextual JvmModule.tests declaration, one nullable TestInput.rerunWhen value for the exact existing live-input string, one product KernelTests fact and one private single-suite configuration guard. Keep top-level discovery fixed in the pure JVM binding, preserve headless JVM arguments, and reuse the state/home primitive. Actual before/after tasks/dependencies/test/compiler facts, the extended existing JVM framework fixture (real isolated tests, excluded nested case, default cache reuse and two identical live-input re-executions), three-root metadata/cache, current complete CLI/MCP readers and graph-selected Kernel/boundary checks are required. Fresh pre-edit Kernel baseline: 336 cases, 11 existing skips, zero failures. Service/distribution/boundary migrations and WP14+ remain outside this slice. Evidence/budget: build/dependeasy/receipts/qualification/wp13/kernel-test-environment-v1-budget.json.


### Kernel test environment — scoped qualification (18 October 2026)

Kernel now declares its JVM suite through a component value, removing the authored broad Test block and ambient environment reads. All 13 tasks, four dependency graphs, six recorded environment inputs, discovery/classpaths/arguments and compiler settings match. The existing JVM fixture proves isolated home, runtime classpath, nested-class exclusion, normal cache reuse, repeated live value 1 execution and cacheable value 2. Three-root models, package aliases, warm cache, seven reader cases, normal full distributions and current CLI/MCP pass. Canonical model adds one wrapper and two typed relationships while preserving the check identity. Run run-1792284538867-1 passes all 336 cases with 11 existing skips; fresh reports match the original case identities and authoritative host evidence. Parser coverage remains Partial. Kernel service/boundary/distribution, eight script owners and final WP13 gates stay open. Evidence: build/dependeasy/receipts/qualification/wp13/kernel-test-environment-v1-proof.json.


### Kernel component boundary — budget before activation (18 October 2026)

Seven existing files. Reuse KernelSpec.DependencyBoundary and the existing Gradle task, add only JvmModule.boundary(policy, verification, description) for the present pure-JVM owner, and share policy binding with existing KMP components. Zero new types, dependencies, plugins, flags or stored state. Keep the KernelBoundary published identity and exact forbidden dependency behavior; add its named child to canonical componentChecks. Retire the mutable extension and unused task setters. Existing two behavioral fixtures, real task/runtime/dependency/policy comparison, three-root models/aliases/cache, current installed CLI/MCP and graph-selected fresh Kernel checks are required. Service/distribution and WP14+ remain outside this slice. Budget: build/dependeasy/receipts/qualification/wp13/kernel-boundary-v1-budget.json.


### Kernel dependency boundary — scoped qualification (18 October 2026)

Kernel now declares its immutable dependency policy as a component value. The old mutable facade, explicit output publication and three unused task setters are removed. All 15 selected tasks, four dependency graphs, resolved coordinates, runtime/compiler facts, policy, description and result path match. The intentional new componentChecks dependency moves the adjacent boundary task before componentChecks. Both existing fixtures pass, including lazy export, JVM/KMP ownership, forbidden group/module failure and warm cache. Three-root model/alias/cache evidence and fresh installed CLI/MCP pass using unchanged complete reader binaries. The canonical model adds one named node and one Includes edge, preserving the published check identity and existing runtime activation owner. Run run-1792285428693-304 passes all 336 cases with 11 existing skips and exact fresh case identities. Parser coverage remains Partial. Eight script owners, Kernel program ownership and final WP13 acceptance stay open. Evidence: build/dependeasy/receipts/qualification/wp13/kernel-boundary-v1-proof.json.


### Kernel service program — budget before activation (18 October 2026)

Resolve the service main with the existing compiled @Entry kernel. One contextual program declaration owns both the existing service task and its printed command, preserving three options, required workspace, stdin, classpath, Java version and process lifetime. Budget: four immutable declaration/spec types following TestEnvironment and one JvmCommand spec; one contextual program method and pure argument resolution; no new plugin, module, dependency, flag, handle kind, state or runtime owner. Existing ModuleWiringTest must prove laziness, compiled discovery, safe shell quoting, missing required option behavior, cache and stdin. Preserve actual launch/runtime/dependency facts with only one entry-resolver task addition. Qualify three-root models/aliases/cache, normal complete readers, installed CLI/live MCP and graph-selected Kernel checks. Local Conductor entry/distribution and final WP13 acceptance remain open.


### Kernel service program — scoped qualification (18 October 2026)

Kernel service is a contextual program value with a compiled @Entry and an immutable typed launch environment. Its command shares that entry and environment. Authored main-class strings, option assembly and raw stdin receiver are removed. All 13 existing selected tasks and their relative order, four resolved dependency graphs, Java 25 toolchain, runtime classpath, JVM flags, description and quoted arguments are exact. One compiled entry resolver is intentionally added. The extended existing fixture proves lazy export, required-input failure, boolean/string options, safe quoting, actual inherited stdin and warm cache. Three-root metadata adds exactly three nodes, two Consumes edges and their two framework-created After edges; aliases remain byte-identical. Fresh complete CLI/MCP distributions pass seven existing reader/graph cases and both runtime boundaries, followed by current installed CLI/live MCP views. Graph run run-1792286701872-1 passes 336 fresh cases with 11 existing skips; parser coverage remains Partial. Local Conductor entry/launcher/distribution, eight remaining script owners and final WP13 gates remain open. Evidence: build/dependeasy/receipts/qualification/wp13/kernel-service-program-v1-proof.json.


- 18 Oct 2026 — WP13 local program ownership budget before edits: two immutable binding values (ProgramHandle/ProgramDeclaration), two existing JVM launcher/distribution spec variants, one optional owned source FileSet on program/entry resolution and two contextual launcher/distribution functions. One handle kind is justified by the existing service and Conductor plus their real generated launchers. No module, plugin, dependency, flag or persistent state is added. Sources remain path data; compiled @Entry remains the authority and fails for zero/several entries in an owned source scope. Preserve existing runtime/toolchain/stdin/arguments, task names, paths, package aliases and launcher behavior. Launchers consume compiled entry artifacts, never the runnable program task. Retire unused broad facades after all callers migrate. Acceptance and measured limits: build/dependeasy/receipts/qualification/wp13/kernel-program-ownership-v1-budget.json.

WP13 kernel-program-ownership-v1 budget clarification before edits: 14 source paths rather than 13, to delete the unused internal String-main jvmCommand overload with its public facade; implementation/fixture line allowances and public API counts remain unchanged.

WP13 kernel-program-ownership-v1 binding revision before final edits: zero new handle kinds, reusing NodeDeclaration and NodeHandle with internal immutable compiled-entry/compilation facts. The candidate ProgramHandle/ProgramDeclaration is removed. Public additions are only the two contextual launcher/distribution methods and their two immutable model specs; 14 source paths and existing line budgets remain. Candidate evidence is retained separately and final checks rerun.


### Kernel Conductor program ownership — scoped qualification (18 October 2026)

The Kernel declares both programs with compiled @Entry source ownership. Existing NodeDeclaration/NodeHandle values carry immutable compiled-program binding facts; there are zero new handle kinds. Launcher and distribution declarations consume the Conductor value without starting either executable. The copied main class and three unused broad facades are removed. Fourteen existing selected tasks, their facts/order, four resolved configurations, runtime arguments, JVM settings, toolchain and launcher facts are exact; resolveConductEntry is the sole additional selected task. Three-root canonical metadata adds six nodes and ten producer edges, retains the published distribution identity, keeps alias bytes and warm cache, and refreshes complete installed CLI/MCP readers. Distribution file names/modes, launcher bytes, dependency contents and bounded Conductor output pass parity. Graph run run-1792289477467-320 passes 336 fresh cases with 11 existing skips; parser coverage remains Partial. Conductor runtime compatibility stays until K1; WP13 remaining owners and final acceptance remain open. Evidence: build/dependeasy/receipts/qualification/wp13/kernel-program-ownership-v1-proof.json.


- 18 Oct 2026 — WP13 Cloud SDK/program ownership qualifies: contextual generated dependency and three compiled @Entry programs replace the broad block, copied main classes and absolute-root option assembly. All 658 old selected tasks, 15 resolved dependency graphs and runtime/compiler/discovery facts retain parity; only three entry resolvers are added. All 2,475 SDK classes and 2,478 JAR entries are byte-identical; the fourteen changed owner classes preserve instruction listings. Two existing framework cases, seven reader/check cases, all three canonical model/alias/warm-cache comparisons and complete installed CLI/live MCP reads pass. Each root adds nine owned nodes and fourteen producer relationships, retaining every old identity and edge. Graph run run-1792291425001-1 succeeds; its JVM task is honestly NO-SOURCE, while the actual process E2E and read-only Wrangler inventory pass separately. SDK extraction stays WP16, legacy CLI stays until K1. Six script owners and final WP13 acceptance remain; FlexBuffer is next. Evidence: build/dependeasy/receipts/qualification/wp13/cloud-program-ownership-v1-proof.json.


- 18 Oct 2026 — WP13 FlexBuffer JVM compiler policy qualifies: the contextual library parameter and immutable LibrarySpec replace the authored KotlinJvmCompile receiver and three switches. All 295 selected tasks, runtime settings, 22 compiler argument sets and resolved dependencies match. The existing fixture proves explicit-off/default-on behavior and warm cache; 91 JVM +39 JavaScript +39 Android cases preserve exact identities/outcomes, alongside 12 native artifacts and 115 generated Kotlin files byte-for-byte. All three canonical models/aliases/warm caches and installed CLI/live MCP pass. Graph run run-1792292236664-635 succeeds with fresh 91/39 case parity; its current catalog selects JVM/JS, while Android is directly qualified. Existing reaktor-graph typed projection is unchanged. Test discovery/logging, processors and benchmark/profile ownership remain next; compiler kernel extraction stays WP16. Evidence: build/dependeasy/receipts/qualification/wp13/flexbuffer-jvm-policy-v1-proof.json.


- 18 Oct 2026 — WP13 FlexBuffer test ownership budget before edits: extend TestEnvironment with conventional/annotation discovery and standard streams, one shared benchmark environment, six existing source files and one enum. No new module/plugin/dependency/flag/handle/runtime owner. Keep Benchmark-named tests; remove obsolete generated-class globs only with exact 91 JVM/39 JS/39 Android case evidence. Reuse the existing contextual KMP binding, including its fixed macOS desktop module access, without coupling that access to discovery or adding a one-suite switch. Extend the existing library fixture with real annotation-discovered Benchmark tests, output logging and warm cache; qualify task/dependency/compiler parity, native/KSP bytes, all three models/readers and graph relationships. Budget: build/dependeasy/receipts/qualification/wp13/flexbuffer-tests-v1-budget.json.

- 18 Oct 2026 — WP13 FlexBuffer test ownership qualifies: six existing files, 61 added / 10 removed. Immutable discovery/logging replaces the raw Test receiver and three generated-class globs. Existing library fixture proves actual Benchmark-named JVM/Android cases, conventional defaults, stdout and warm cache; Java 21 bytecode and seven reader cases pass. All 295 task/compiler/dependency facts, 12 native artifacts and 115 generated Kotlin files are preserved within the declared runtime delta. Three models retain all old identities/edges and add three nodes/four typed relationships; aliases/cache and complete installed CLI/live MCP agree. The unchanged canonical gateway now selects all three suites: 91 JVM +39 JS +39 Android cases retain exact identities/outcomes in run-1792293872289-1. Existing typed reaktor-graph projection is unchanged. Processor/benchmark/profile ownership is next; WP13 is not complete. Evidence: build/dependeasy/receipts/qualification/wp13/flexbuffer-tests-v1-proof.json.


- 18 Oct 2026 — WP13 contextual processor ownership budget and active qualification: eight existing files, one immutable ProcessorOption value, zero new dependencies/plugins/state. Library dependencies own the typed processor declaration and exact two compiler option keys. The mutable broad facade is removed. The existing wiring/cache fixture, 295-task/compiler/dependency comparison and all three canonical models/aliases/warm caches pass. Fresh graph-owned JVM/JavaScript/AndroidUnit checks are running; generated/native byte parity and full scoped proof remain pending. KSP Gradle-free kernel extraction remains WP16. Evidence/budget: build/dependeasy/receipts/qualification/wp13/flexbuffer-processor-v1-budget.json.


- 18 Oct 2026 — WP13 contextual FlexBuffer processors qualify: eight files, 56 added / 30 removed. The mutable broad processor facade and authored raw option keys are retired in favor of component dependency ownership and immutable typed keys. The existing fixture/cache check, 295 ordered tasks and their relationships, seven dependency graphs, compiler/test settings and all three canonical models/aliases/caches pass. All 169 cases have fresh exact outcomes across graph runs run-1792295579555-1865, run-1792295883107-3713; the first run reused JavaScript, so a second graph run regenerated only its retained XML outputs. All 115 generated Kotlin files, 103 runtime sources and 12 native/contract artifacts are byte exact. Existing reaktor-graph projection remains unchanged. Dedicated KSP kernel extraction stays WP16. Benchmark/profile ownership is next; WP13 remains open. Evidence: build/dependeasy/receipts/qualification/wp13/flexbuffer-processor-v1-proof.json.


- 18 Oct 2026 — WP13 unused JMH configuration removal qualifies: two existing files, 16 added / 20 removed; the mutable declaration, six public setters and configure receiver are retired. The existing DSL/cache fixture, 305-task actual settings comparison, real Java 21 benchmark compilation, all three canonical model/alias/cache comparisons and graph run run-1792296572969-4240 pass. All 169 case identities/outcomes are preserved; JVM/Android executed freshly and JavaScript reused its exact previously qualified reports. Runtime, generated and native bytes are unchanged. Full benchmark/profile ownership remains open. Evidence: build/dependeasy/receipts/qualification/wp13/flexbuffer-jmh-prune-v1-proof.json.

- 18 Oct 2026 — WP13 contextual Node suite qualifies: four existing files, 3 added / 10 removed; no new types/API/parameters/dependencies/state/graph kinds. The broad node helper and NodeBenchmarkKernel are retired. The existing framework/cache fixture, 305-task actual compiler/dependency/benchmark/Mocha comparison, all three canonical model/alias/cache comparisons and current installed CLI/live MCP pass. One named node and two typed ownership edges are added; the existing JavaScript check identity is preserved. Graph run run-1792297325681-6083 succeeds with all 169 exact case outcomes; JVM/Android execute freshly and JavaScript reuses its byte-exact qualified reports. All 115 generated Kotlin files, 103 runtime sources and 12 retained native/contract artifacts are unchanged. Evidence: build/dependeasy/receipts/qualification/wp13/flexbuffer-node-suite-v1-proof.json.

- 18 Oct 2026 — WP13 JMH allocation launch qualifies: one existing file, 4 added / 3 removed, zero new API/types/parameters/dependencies/state/graph kinds. Lazy producer files repair the empty classpath; tracked signature exclusions repair the signed aggregate JAR. After an explicit JAR rebuild, the real Java 25 process lists all 22 compiled workloads, and the ordinary launch reuses configuration cache. This is list mode, not a scored benchmark. The existing framework/cache fixture, 305-task facts, three-root models/aliases/cache and current CLI/MCP pass; 169 prior case outcomes are reused, with no fresh graph-test claim. Full typed benchmark/profile ownership remains WP13; WP14+ unstarted. Evidence: build/dependeasy/receipts/qualification/wp13/flexbuffer-jmh-launch-v1-proof.json.

- 18 Oct 2026 — WP13 typed JVM benchmark ownership is qualified: eight existing product files, 187 added / 67 removed; one DSL function with two parameters, six types, no new dependencies/state/handle or graph kind. The named jvmBenchmarks declaration owns compiled discovery, both runners and reports: 22 exact methods/source sites, four canonical nodes and six typed relationships. All 304 existing selected task rows are exact; three-root aliases/cache and current installed CLI/MCP views pass. The graph run preserves all 169 case outcomes. A real score/GC smoke qualifies execution and artifacts, without claiming performance scores. The broad selector API, old allocation task, duplicate plugin runners and active command examples are retired. Soft runner ordering stays in Gradle’s exact plan because canonical After would force an extra run. Apple/C++/profile ownership and final combined WP13 desktop/IDE/physical gates remain open; WP14+ unstarted. Evidence: build/dependeasy/receipts/qualification/wp13/flexbuffer-jmh-ownership-v1-proof.json.

- 18 Oct 2026 - WP13 C++ reference ownership is qualified: eight existing files, 47 added / 17 removed; one contextual declaration, no new type/dependency/state/graph kind. The existing CMake spec owns the named reference producer and its tracked JVM consumer, with one canonical node and two typed relationships. Five existing framework cases, 308 actual task facts, the byte-identical executable and all 169 graph-owned case outcomes pass; JavaScript report reuse is identified separately from fresh JVM/Android execution. All three model/alias/cache comparisons and current installed CLI/live MCP views pass. The broad cpp facade and retired producer name are removed. Apple/profile ownership and final combined WP13 desktop/IDE/physical acceptance remain open; WP14+ unstarted. Evidence: build/dependeasy/receipts/qualification/wp13/flexbuffer-cpp-reference-v1-proof.json.

- 18 Oct 2026 - WP13 Apple benchmark ownership is qualified: six existing files, 47 added / 10 removed; one contextual declaration, zero new parameters/types/dependencies/state/graph kinds. The named bench aggregate owns both existing native release links through three canonical nodes and two Includes relationships. Six existing framework cases, both actual ARM64 links, all 371 prior task relationships, 22 compiler flag sets and 169 fresh graph-owned case outcomes pass. Native binary arguments preserve the exact three flags; contextual binding changes only the order of two independent options, with actual iPhoneOS and simulator-target Mach-O deployment versions checked. No simulator was started and no physical native benchmark execution is claimed. All three model/alias/warm-cache comparisons and current installed CLI/workspace-kernel tool views pass. The Codex MCP connection stopped responding after the workspace owner restarted; fresh installed workspace calls reached the same kernel tool. The new owner lacks the completed run and latest-check lookup, so the prior terminal and exact source-guarded reports are retained with partial coverage; durable owner state remains K4. The broad apple facade and copied native entry string are retired. Dedicated native direct execution remains WP16. Profiling and final combined WP13 desktop/IDE/physical acceptance remain open; WP14+ unstarted. Evidence: build/dependeasy/receipts/qualification/wp13/flexbuffer-apple-benchmarks-v1-proof.json.

- 18 Oct 2026 - WP13 compiled profile ownership is qualified: eight existing files, 102 added / 59 removed; two contextual declarations and no new types, dependencies, state or graph kinds. CPU and API programs consume compiled @Entry producers through four new canonical nodes and four existing relationships. All 374 prior task facts and ordering, 22 compiler flag sets and native configuration are preserved. Main identities and Java 25 are verified by actual execution; the final normal-routed local capture also preserves ordered classpaths and profiler arguments. Earlier worker tool failures remain explicit. Actual unchanged workloads produced one CPU and 24 phase HTML reports; three controlled missing-tool executions fail as intended. Real execution exposed a nullable annotated Provider input; it is now non-null, with the failed receipt retained and the amended source requalified. Six existing framework cases and 169 canonical case outcomes (130 freshly executed, 39 exact reused JavaScript results), three model/alias/warm-cache comparisons and fresh CLI/workspace-kernel views pass through the existing reaktor-graph projection. Coverage remains partial. The original worker baseline lacked profiler tools, so no baseline report success or performance comparison is claimed. Broad mutable profile declarations and copied JVM entry strings are retired. Opt-in test profiling, shell launcher retirement and remaining WP13 modules/gates follow; WP14+ unstarted. Dedicated direct profile kernel/tool extraction stays WP16. Evidence: build/dependeasy/receipts/qualification/wp13/flexbuffer-profile-programs-v1-proof.json.

- 18 Oct 2026 - WP13 opt-in test-profile component ownership is qualified: five existing files, four added / thirteen removed. LibraryScope directly invokes the unchanged TestProfileKernel; the sole authored caller moves into library. The broad Benchmarks class, its extension, its compiled/JAR entries and FlexBuffer's final dependeasy/benchmarks block are removed. The used immutable Profile spec and distinct ReaktorTests.Benchmarks environment remain. Six existing framework cases pass freshly; all 376 prior task facts, 22 compiler flag sets and native configuration are exact. Every configured Test task has one opt-in profiler, disabled by default. All 169 canonical outcomes pass (130 fresh JVM/Android, 39 exact reused JavaScript), through unchanged reaktor-graph. Three models, package bytes, warm caches and six current CLI/kernel views match, with no new node or edge. Kernel/tool extraction stays WP16. JVM/Android/JS launcher removals, IO/Web/App/Desktop glue and final WP13 gates remain; WP14+ unstarted. Latest full desktop result remains 419 passed / 31 failed. Evidence: build/dependeasy/receipts/qualification/wp13/flexbuffer-test-profile-component-v1-proof.json. Manna session logging currently returns Tool log_session not found; local evidence/status remain recorded and the mission is not marked done.

- 18 Oct 2026 - WP13 JVM profiling shell retirement qualifies: generate-all.sh invokes the canonical jvmFlameChart declaration through the normal wrapper. The duplicate generate-jvm.sh is deleted, including build/classpath polling, PID guessing, attach fallback and swallowed errors. Four lines added / 106 removed across two files. The actual new launcher produced fresh 131,311-byte CPU HTML on this Mac with the unchanged 50,000-iteration workload; a separate worker run correctly failed for its missing profiler. The existing shell parse error and empty-glob exit are repaired. All 169 canonical outcomes pass through unchanged reaktor-graph (130 fresh JVM/Android; 39 exact reused JavaScript). No new node, edge, type, dependency or deletion API. File contents were retired through code.plan/code.apply and the resulting empty entry structurally removed under the owner's existing legacy-removal authorization; no shim remains. Android/JavaScript launchers remain unqualified and unchanged. IO/Web/App/Desktop glue and final WP13 gates remain, including the failed full desktop result (419 passed / 31 failed). WP14+ unstarted. Evidence: build/dependeasy/receipts/qualification/wp13/flexbuffer-jvm-launcher-retirement-v1-proof.json.

- 18 Oct 2026 - WP13 model qualification exposed an occupied worker checkout whose local lease had already been released. The existing router now checks remote admission for every slot, including slot zero, while preserving its original base-slot capacity policy. Two existing files, 22 added / 12 removed; the amended existing regression fails before the fix and all 33 existing router cases pass after it. The ordinary Manna wrapper export and warm-cache run also pass. Kernel edit receipts are retained; Python tool checks have no canonical module/check owner and that coverage boundary is explicit. Remaining transport work stays in WP18. The latest concurrent desktop F-GRAPH result is 16 passed / 21 failed, retained separately from the earlier 68 desktop cases and two focused flows; current combined desktop acceptance remains open. Evidence: build/dependeasy/receipts/qualification/wp13/router-orphan-slot-admission-v1-proof.json and desktop-concurrent-fgraph-20261018-summary.json.

- 18 Oct 2026 - WP13 IO typed generated Kotlin ownership qualifies: eight files, 91 added / 20 removed. Kotlin variable names define the generated object and field; FrameworkOptions supplies the value, preserving the existing BuildKonfig.SERVER namespace, public getter and resolved value. The existing component installs its producer through four canonical nodes and five typed relationships. Two existing framework cases, 233 exact task facts, seven exact dependency graphs, 44 freshly executed IO cases, Android/iPhoneOS compilation, three-root models/aliases/warm caches and seven current installed CLI/workspace-kernel views pass. Existing input-descriptor/manifest lifecycle and cached host-coordinate deltas are recorded explicitly; concurrent DeviceFlow work is preserved outside this qualification. Coverage remains Partial. The IO foreign plugin DSL, named build-task lookup, broad facade and explicit test selector are retired. Unused BuildKonfig classpath/pin removal follows separately; plain kernel/tool extraction remains WP16. Physical Android/Node profiling, Web/App/Desktop and final WP13 gates remain open; full desktop evidence remains 419 passed / 31 failed and WP14+ unstarted. Evidence: build/dependeasy/receipts/qualification/wp13/io-generated-object-v1-proof.json.

- 18 Oct 2026 - WP13 native follow-up: the unused BuildKonfig dependency and all three pins are removed and qualified (four lines removed; three existing framework cases, actual IO compilation, dependency absence, exact three-root model/package/cache parity). Web desktop native ownership also qualifies (30 added / 20 removed in five files): its existing CMake task is now a canonical producer included by componentBuild, and the broad mutable facade is retired. Native library, packaged resources and both contracts are byte-identical; the amended existing framework case, 12 fresh JVM and five bridge cases, two-root model/package/cache comparisons and current CLI/kernel views pass. Graph coverage remains Partial because the owner cannot attach all source identities; final combined Manna/three-root acceptance remains required. The latest focused desktop Graph result is 24 passed / 13 failed; the full suite remains 419 passed / 31 failed. Remaining Web bridge/assets/probes, App/Desktop and final WP13 gates are open; WP14+ unstarted. Evidence: build/dependeasy/receipts/qualification/wp13/io-buildkonfig-retirement-v1-proof.json and web-desktop-native-component-v1-proof.json.

- 18 Oct 2026 - WP13 contextual Web desktop program qualifies: 45 added / 38 removed in eight existing files; final Project.jvmProgram facade retired. Typed native access is shared by direct and launcher execution. Existing producer/resolver identities remain; one command node and two relationships are added. The existing framework case, 12 fresh JVM and five bridge cases, warm caches and current complete CLI/kernel views pass. The primary Mac created its native window and passed the Unicode typed bridge check. It required explicit process termination and its Gradle wrapper exited 1; clean window termination is not claimed. All prior graph identities remain after 50 classified route/tool/JS-descriptor context changes; exact whole-model parity is not claimed. Complete built agent output was collected through the existing worker collector after a mixed-family collection omission, without a JAR overlay. Coverage is Partial. Typed Web empty-suite policy also qualifies (17 added / 9 removed; one existing framework case extended, no new cases): 399 task facts, eight dependency graphs, 21 compiler tasks and all five discovery policies match. Three nodes and four relationships expose the Android/JavaScript fixture suites through the existing graph; JVM remains strict. Four graph-selected checks, 12 fresh JVM and five bridge cases, cache reuse and five current CLI/kernel views pass. Android/Kotlin-JS callable fixtures have no discovered-case count claim. Coverage remains Partial. Evidence: build/dependeasy/receipts/qualification/wp13/web-empty-test-policy-v1-proof.json. Web bridge/assets also qualify (87 added / 57 removed in six existing files): the mutable KotlinTemplate API and broad facade are retired; real generated Kotlin/JavaScript bytes and 399 selected task facts are identical. One generator node and five ownership/dependency relationships are added with every prior identity and edge preserved. One existing framework case, five fresh Node bridge cases, four canonical graph checks, exact two-root model/package parity after declared changes, warm caches and nine current CLI/kernel views pass. JVM/Android tasks were UP-TO-DATE; the 12 retained JVM cases byte-match prior qualified XML and are not claimed as fresh executions. Template full serialized spec/plain kernel extraction and remaining JavaScript factory callers remain WP16. Evidence: build/dependeasy/receipts/qualification/wp13/web-bridge-assets-v1-proof.json. Typed physical Apple probe also qualifies (65 added / 32 removed across four existing files): Web has no remaining broad dependeasy block. Final native-output publication fixes an existing main/test collision and premature main-link task realization; the actual arm64 iPhoneOS debug/static test framework now exports startDeviceWebViewProbe. Two opt-in graph nodes and one edge replace the incorrect main-framework output. The changed native task/ABI evidence is explicit, with no byte-parity claim. Both default canonical models retain all IDs/edges after source moves/hashes, package bytes and warm caches; current CLI/kernel views and four graph checks pass. One existing framework case is extended; 12 JVM cases are reused, five bridge cases run freshly, and parser coverage remains Partial (190 lines). No physical-device launch is claimed; full native kernel separation remains WP16. Evidence: build/dependeasy/receipts/qualification/wp13/web-apple-probe-v1-proof.json. App generation, Desktop and final WP13 acceptance remain open; full desktop evidence remains 419 passed / 31 failed, latest focused Graph 24 passed / 13 failed; the two newer focused Memgraph flows pass. WP14+ unstarted. Evidence: build/dependeasy/receipts/qualification/wp13/web-desktop-probe-program-v1-proof.json.

- 18 Oct 2026 - App contextual component qualifies (83 added / 36 removed, seven existing files): Compose report facts, worker-manifest ownership and typed JVM/Android/JavaScript suites use nine new nodes and twelve existing-kind graph relationships. All previous IDs/edges survive; 210 task facts, eight dependency graphs and 22 compiler flag sets retain behavior except the existing scoped JVM macOS event-access flag. One existing framework case, 754 fresh App cases, both root model/package comparisons and warm caches, and current full CLI/kernel views pass. Parser coverage remains Partial (339 lines). Evidence: build/dependeasy/receipts/qualification/wp13/app-component-v1-proof.json.

- 18 Oct 2026 - App typed generation/release requirements qualify (132 added / 31 removed in ten existing files): delegated immutable flags, revision and conditional private text fields replace the final broad App block. Generated current bytes and JVM signatures match; the obsolete former-namespace source/class are removed. Five existing framework cases and 754 fresh App cases pass; 318 task facts, eight dependency graphs, 22 compiler settings, two JVM settings and four framework facts are exact. Six nodes/five relationships retain every prior ID/edge. Both root exports, package bytes, warm caches and current CLI/kernel consumers pass. Coverage remains Partial (260 lines/source identity); initial diagnostic assertion failure and passing retry are retained. Evidence: build/dependeasy/receipts/qualification/wp13/app-generated-fields-v1-proof.json. Desktop selected-only ordering is next, budgeted at 180 added / 60 removed lines in ten existing files, no dependency, one immutable canonical OrderedAfter edge. Preserve mustRunAfter without selecting Engine tests or inheriting host/authority; qualify existing fixtures, actual task order, current readers and reaktor-graph projection. Detailed budget: build/dependeasy/receipts/qualification/wp13/desktop-soft-order-v1-budget.json. Root release story and final combined WP13 gates remain; WP14+ unstarted.
