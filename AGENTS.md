# AGENTS.md — Reaktor framework

Reaktor is the framework substrate. Product code lives in the consuming repos
(`../bestbuds`, `../manna`); the canonical product guide is `../bestbuds/AGENTS.md` and its design
bar (§2) governs here too.

## 1. First moves

- Read `../bestbuds/AGENTS.md` §2 (design bar), §3.1 (dependency direction), §4.4 (graph-editor
  layer boundaries). They are written from the product side but they bind this repo.
- `README.md` and `LLM_CONTEXT.md` predate the control-plane and Machine Signal work. Trust code
  over both.

## 2. The graph is the foundation

**User direction, 13 September 2026:** "the graph is the king of reaktor, we don't need to
build graph agnostic stuff." This supersedes the former mandatory dual-surface rule.

- Build Reaktor capabilities around the graph's identities, typed ports, ownership, lifecycle,
  queries and commands. Agents, navigation, data and tools participate in that model.
- A separate graph-agnostic API, pure-library kernel or optional graph projection is **not a
  requirement**. Add a conventional facade only when an actual consumer benefits from it.
- Preserve useful existing APIs and ordinary implementation helpers. This direction does not
  require a breaking rewrite or turning every algorithm or log entry into an active node.
- Keep semantic definitions distinct from runtime activations and UI presentation. Headless graph
  hosts must not acquire Compose or workbench dependencies merely to run agents or inspect data.
- One logical graph does not require one global scheduler, process, database or loaded object heap.
  Preserve declared ownership and the layer boundaries below.

## 3. Layer boundaries (do not cross)

```
compose-flow      generic canvas, viewport, pan/zoom/fit, generic edges, handles, minimap
  └─ reaktor-flow Reaktor graph adaptation, measurement, layout, scene, ReaktorGraphEditor,
                  GraphDocument/GraphEditorSession, and the host re-exports consumers need
       └─ app     shell chrome, panes, inspector (bestbuds/modules/engine)
```

Never put Reaktor graph semantics in `compose-flow`, workbench chrome in `reaktor-flow`, or graph
layout in an app module. An app that needs a `compose-flow` type consumes it through a
`reaktor-flow` re-export, never directly.

## 4. Design system

`reaktor-ui/machinesignal` is the single definition of the operator visual language: palette,
provenance families, spacing, radii, type scale, authored component metrics, and the kit built on
them. Values are transcribed from `bestbuds/modules/reaktor.pen`.

- consumers never hardcode hex or geometry; they read `MachineSignal`
- `TruthClass`/`Fact` (`reaktor-core/truth`) carry provenance; `MachineSignal.provenance()` is the
  only sanctioned mapping from truth to color
- parity tests in the consuming app fail when a surface drifts from the design file

`reaktor-tactile` remains a placeholder with no source; do not extend it.

## 5. Build & verify

```bash
./gradlew :reaktor-ui:compileKotlinJvm --console=plain
./gradlew :reaktor-flow:jvmTest --console=plain
./gradlew :reaktor-core:jvmTest --console=plain
```

Composite builds mean a change here rebuilds `../bestbuds` automatically; run
`(cd ../bestbuds && ./gradlew :engine:jvmTest)` when you touch `reaktor-ui`, `reaktor-flow`,
`reaktor-core` or `reaktor-graph`.

Performance work uses the harnesses in `reaktor-performance` and
`reaktor-flexbuffer/flamechart/` — budgets and profiles, never guesses.

## 6. Coding rules

- `commonMain` stays platform-safe; platform APIs go in the platform source sets
- explicit `StateFlow` backing fields; never force-unwrap a port with `.impl!!`
- comments default to none — explain a non-obvious *why* (a constraint or invariant), never what
  the code does
- generated/vendored trees (`build/`, `ts/import/`, `ts/export/`, `kotlin-js-store/`) are not
  hand-edited

## 7. Stop and report before editing if

- a layer boundary in §3 would have to be crossed to make something work
- design tokens would need a second source of truth

## Report to Manna

Work here is tracked in Manna through the `manna` MCP server (https://manna.ac/mcp/private, scopes manna:read and manna:agent). This repository's missions live in the Reaktor space: pass `space: "reaktor"` to the Manna tools.

- At session start, call `manna_version`. If it differs from the version in the server instructions, reconnect `manna` before anything else. Then find the mission you are working on with `list_records` (collection `work`, status `doing`, `space: "reaktor"`) or `search_workspace` (your task in a few words, mode `related`, `space: "reaktor"`), and read it with `get_record` (collection `work`, `space: "reaktor"`). If it is not there, call the same tools without `space`: they then cover your own workspace and every space you belong to, and name each record's space. `list_spaces` lists the spaces and their repositories.
- At session end, call `log_session` with `mission_id`, the mission's `space`, `repo: "reaktor"`, `commits` (every SHA you committed), `pr_url` and `deploy` when there are any, `agent: {harness: "claude-code" or "codex", model, worktree}`, `artifact`, `decisions` and `next_action`. The session is saved in your own workspace and points at the mission in its space. Use a new `request_id` for each session and reuse it if you retry.
- Never mark a mission done. When the work is finished, call `propose_completion` with `mission_id`, the mission's `space`, a `summary` of what was done and how it was checked, `evidence_refs` (SHAs, URLs, session ids) and a new `request_id`. The proposal waits in the mission's space for its owners, who decide.

- 14 Oct 2026 — WP5 final freshness review: compiled root buildSrc Kotlin participates in declaration ownership, alongside dependeasy implementation sources. Extend the shared input policy and its behavioral content-change fixture before commit; acceptance is stale-model rejection on a root buildSrc edit and unchanged configuration-cache reuse. Investigate Manna plan-cache context using temporary per-key hashes only; no private values enter public evidence.

- 14 Oct 2026 — WP5 Manna repeat selected identical task/dependency sets but macOS changed APP_ICON_<pid> between launcher processes. Temporary per-key hashes identified only those two launcher keys. Exclude that exact macOS UI metadata pattern from the context digest; preserve all execution environment inputs. Extend the existing bridge fixture with a changed launcher id and qualify byte-identical repeated planning, execution and receipt correlation. Temporary diagnostics removed from source.

- 14 Oct 2026 — WP6 budget and acceptance before edits: two public contracts (web component and worker fleet), one web contract writer and final Wrangler config writer, extend the existing fleet and package writer, and move the existing import-closure generator into the JS kernel. Kotlin owns environments and config precedence (standalone dev; recursive workspace defaults then local prod, arrays replaced). Preserve source configs and runtime JS behavior. Generate all 20 deployable declarations, remove duplicate deployment scripts/catalogs, and have Vite/tsconfig consumers use generated facts. Acceptance: behavioral contract/config/script-generation fixtures with cache reuse; all 20 real fleet entries and declaration sites; semantic comparison of every final dev/prod config; actual configServer Wrangler dry-run for both environments; CompanyOS/Manna builds and no Kotlin output paths in authored Vite/tsconfig; import-closure fixtures; script counts. External worker deploy is a final owner approval step after these reviewable artifacts exist. No new JS file except the moved generator, no version bump, no new deployment scheduler.

- 14 Oct 2026 — WP7 follows the handoff's bounded Apple task/contract budget. Keep provider, profile and keychain authority intact; use the existing app's iOS 16.0 / Swift 6.0 facts without changing SwiftPM pins. Qualify host drift, one composite physical-iPhoneOS framework path, shared versions and archive privacy/dSYM checks before any owner-present upload.

- 14 Oct 2026 — WP8 budget and acceptance before edits: one native catalog and one generated contract type; extend the existing NativeLibrary declaration and CMake task rather than add a scheduler or CMake functions. Declare native module dependencies, platforms and ABIs explicitly. Kotlin owns source handles, pinned AAR checksums, exact Prefab imports and platform/compiler/SDK/deployment facts; CMake consumes the generated preload/target contract. Keep the current AGP public variant JNI producer because this repository already builds native libraries through its owned CMake tasks rather than AGP externalNativeBuild. Acceptance: existing real native composition/ownership fixtures, changed-contract reconfiguration and cache reuse, actual Android and physical-iPhoneOS native libraries with before/after artifact evidence, and removal of authored CMake source-path/version/probing policy. No C++ edits or dependency upgrades.

- 14 Oct 2026 — WP9 budget and acceptance before edits: three verification kernels (DeviceFlow, KarateSuite, K6Suite), one device-flow contract and one Dagger pipeline contract. Reuse InstallTool for checksummed native runner, Karate jar and k6; move the existing MaestroRunner engine behind a compatible tooling facade. Kotlin owns physical-device selection, app/output references, suite membership/environments, generated aliases and pipeline/host facts; preserve product fixture/age-gate and signing/provider hooks. Keep uninstall opt-in and prohibit emulator/simulator startup. Replace product generated TS with one shared Dependeasy Dagger module that invokes the declared Gradle target without scheduling dependencies. Preserve manual CI dispatch/billing and deploy secrets. Remove obsolete harnesses and task-name routing heuristics. Acceptance: behavioral runner/tool/pipeline/transport fixtures, real Samsung and iPhone flow receipts through the shared CLI, real pinned Karate/k6 qualification, shared Dagger PR container gate, three-root contract/cache qualification and no product generated TS. Read the pinned Dagger 0.21.8 implementation before choosing module loading; no upgrade, new MCP server or second scheduler.

- 14 Oct 2026 — WP10 budget and acceptance before source edits: four existing-gateway read tools, five build CLI commands and one Hangar Build inspector family. Extend the shared model reader for relations and bounded retained plan/receipt pages; carry the exact sealed Gradle plan in the existing pending operation and use full model IDs in the catalog. Kotlin model declarations replace settings/script family inference; the Cloud pipeline projection reads its generated contract. Reuse Desktop IDE navigation for external kernel classes, existing Surface/MachineSignal/blueprint and operation approval. Acceptance: shared CLI/MCP relation and receipt fixtures, canonical catalog identity, exact sealed plan and pagination tests, discovery authority fixtures, Hangar owner walk from pr to appChecks to app JVM tests to kernel/plan/receipt, and existing module/UI checks. Plans configure Gradle only on explicit request and display cost; receipts remain recorded observations. No polling, new module/server, dependency upgrade or approval bypass.

- WP9 container qualification follow-up before editing: the worker engine reports five OOM kills and runs in an 8 GiB Docker VM with unrelated services. Keep the current JVM limits and unrelated workloads. Set two fixed pnpm execution facts in the Kotlin Dagger contract (network concurrency 4, child concurrency 1), verified with the pinned pnpm config reader. This bounds the dependency-install peak for the required real container gate; no user flag, pin change or parallel scheduler. Qualify with a fresh gate and retain failure evidence.

- WP10 real-host qualification follow-up before editing: macOS bundled rsync rejects the numeric --chmod=F600,D700 used for private Gradle requests. Replace only that spelling with equivalent symbolic owner-only modes; preserve request ownership and routing. Acceptance: bundled rsync private-file copy modes, existing router fixtures, and a real routed CLI plan. No new option or permission relaxation.

- WP10 transport qualification: bundled openrsync accepts symbolic --chmod but does not apply it. Preserve source modes with -a and set the copied request to 0600 after verifying its owned path and content digest; the enclosing request directory is already 0700 before transfer. Qualify actual 0600 copying and existing router containment fixtures.

- WP9 native container qualification before editing: checksum/gzip checks pass, but actual GNU tar extraction fails with ENOSYS in the worker amd64/Rosetta path. A temporary diagnostic reproduced the same failure in a disposable directory. Add libarchive-tools and bind the existing tar executable entry to bsdtar inside the Linux runner; retain the JDK image and all source pins. This is fixed OS execution glue, no public choice or task. Acceptance: actual Kotlin archive extraction through the runner and the full local PR gate. Primary reproduction: https://github.com/orbstack/orbstack/issues/2588. Temporary diagnostics are removed before delivery.

- WP9 clean-container qualification before editing: the checksummed Pulumi 3.268.0 archive contains pulumi-language-java, but InstallTool currently extracts only pulumi. Extend the existing installation to its complete bundled executables under a fresh owned distribution directory, and prepend that directory for PulumiSdkTask. Budget: two existing Kotlin files, no new task, pin, public flag or global installation. Acceptance: existing generated-output/configuration-cache fixture, actual Grafana SDK generation using only the pinned bundle, and a fresh local Dagger PR gate. Retain the prior missing-plugin/daemon-disappearance evidence.

- WP10 real-workspace qualification before editing: retainedPlan eagerly validates all JSON files in the shared plans directory, so an unrelated stale Gradle plan with a dangling dependency and legacy web-plan JSON prevent reading a valid current plan. Budget: existing reader selection and its behavioral fixture only. Select the current node/model artifact by its JSON identity before decoding/validating its task graph; retain rejection for an invalid selected plan. Acceptance: mixed-artifact/stale-plan regression, model tests, CLI/MCP consumer checks and the real authenticated gateway retained-plan walk. No swallowed selected-plan errors, source artifact deletion or new recovery policy.

- WP9 fresh-checkout delivery follow-up before tracking artifacts: CI and the shared Dagger runner read dagger/pipeline.json before Gradle starts, but the three regenerated contracts remain untracked. Budget: track the three existing Kotlin-generated JSON contracts only; no workflow bootstrap, handwritten contract, new task or dependency. Acceptance: each tracked contract byte-matches its freshly verified Gradle reference, every workflow/module input is in Git, and the existing local container gate continues against identical contract bytes. Published CI remains unqualified.
