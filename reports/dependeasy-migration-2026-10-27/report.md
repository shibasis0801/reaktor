# Dependeasy and the Reaktor+ build plane: migration assessment

27 October 2026. Scope: September/main versus the current Reaktor and BestBuds build system, with Manna as an additional consumer. This assessment combines source inspection, a fresh inventory, fresh build-plane measurements, fresh routed artifact builds, and retained qualification evidence.

**Controlled follow-up, 28 October:** the [same-slot paired benchmark](/Users/ovd/dev/reaktor/reports/dependeasy-benchmark-2026-10-28/report.md) supersedes this report's earlier timing comparisons for the JVM target. It separates Gradle from routing/admission time and includes cold, warm, source-edit, identical-source, runtime and cache treatments. Both stacks successfully reused configuration cache in the warmed bare-JAR scenario; earlier rejection evidence here applies to its captured execution state, not every old-stack invocation.

## Verdict

The migration has delivered a much more inspectable and controllable build system. Its strongest benefits are a shared graph across languages and included builds, explicit inputs and tool identities, isolation of mutable build conventions, and execution evidence shared by CLI, agents, and Hangar.

**An overall build-speed improvement is not established.** The retained comparison of the same warm JVM artifact was 12 seconds on main versus 18 seconds on the migrated candidate. Current runs take roughly 16–17 seconds inside Gradle, while routing and output publication add minutes. Receipt/history inspection is already slow enough to produce unsuccessful CLI calls.

**Consumer configuration became smaller; total build-system implementation became substantially larger.** This is a useful trade if the shared implementation replaces duplicated orchestration and remains maintainable. It is not evidence that the whole system became simpler.

My assessment: retain the migration for its graph, execution contracts, and operator/agent integration. Treat latency, framework size, cache behavior across worker workspaces, and remaining integration boundaries as unfinished costs. There is no measured basis for claiming faster application runtime, smaller binaries, lower total memory use, or a financial return.

## Comparison boundaries

“September” and “main” are different snapshots in this workspace:

| Repository | Pinned origin/main | September branch snapshot | Current HEAD at inventory |
|---|---|---|---|
| Reaktor | `b7b0370346b46d6bc915a05fb61ffdef93436235`, 30 September | `7ca5f2ac59364b498304c97dc8597f9c8f0de24e`, 30 September | `a3ca69b619993d95650f0386ee22694f757b228b` |
| BestBuds | `dcc5df82e7cd6e8e6b7cd6ebd7f2bc37ef9ea43d`, **31 August** | `7f956520842af8c9a5cba7e5db32d4dd67ee822c`, 30 September | `b7bf7271f8c3f0cb978478e89964c771b473ba56` |
| Manna | `566a225c068983e6582e2318451bbb50468e6636`, **20 October, already migrated** | Not used as an old-stack baseline | `0825107bc7426404a44117f4eb734a28b9b0fdd6` |

The local Reaktor `main` branch is older still: `42ccdc96e772a10ce9a6580274f73f3fa02e0acc`, 12 September. The primary tables below use the pinned origin/main refs; September branch counts are also reported so the distinction is visible.

The working trees are shared and dirty: the inventory saw 53 changed/untracked Reaktor paths and 122 BestBuds paths. Current source counts include relevant tracked and untracked files. The installed CLI/owner is the live distribution observed during the measurements; it is not a fresh compilation of every current source edit. Its owner JAR is `b5c8e0054469222f5163b8d99db64ed4c6723541721200f10c1dab40b282d913.jar`, reported built/started “27 Oct 15:57”, with 249 operations and capability identity `80d8f242bafe`. At the end of the investigation it reported being one BestBuds commit and three Reaktor commits behind the current source. The BestBuds model hash remained stable during the CLI and in-process model benchmarks. Historical qualifications apply to their captured revisions, not automatically to today's overlays.

Baseline facts and current counts are retained in [inventory.json](/Users/ovd/dev/reaktor/reports/dependeasy-migration-2026-10-27/inventory.json). [Historical source excerpts](/Users/ovd/dev/reaktor/reports/dependeasy-migration-2026-10-27/baseline-source-excerpts.md) retain the settings/bootstrap, native execution, web configuration and September deployment-pane code at those refs. Historical qualification copies and their original paths/hashes are indexed in [evidence-index.json](/Users/ovd/dev/reaktor/reports/dependeasy-migration-2026-10-27/evidence-index.json).

## What changed technically

The old stack already had Gradle conventions, Kotlin multiplatform tasks, native inputs, scripts, Fastlane, and operational deployment screens. It was not devoid of incrementality or approvals. The material change is the connection between these pieces.

On September main, the settings plugin directly invoked `NativeBootstrap.bootstrap()`. That path could fetch external Git sources and run CMake/Make/Ninja to prepare FlatBuffers/Hermes while configuring the build. The old Kotlin/CMake task used `Project.exec`; web conventions configured Kotlin/JS, Yarn, and generated Webpack fragments. Product scripts and Fastlane supplied substantial additional orchestration.

Current Dependeasy separates a stable toolchain/plugin classpath, mutable Gradle conventions, and a model/runtime layer. Pinned source producers and typed task contracts make source/tool/native artifact relationships explicit. Product declarations export a model containing canonical identities, dependencies, kernel specifications, inputs, tools, effects, verification coverage, and declaration locations. Gradle remains the task scheduler; the plane asks Gradle for an observed plan and records execution, rather than replacing its scheduling semantics.

Reaktor+ consumes that model through CLI/MCP/kernel operations and Hangar. The build UI now exposes targets, graph, artifacts, runs, operations, and live observations. It can distinguish a source declaration from a retained run and show whether a receipt matches the current model. The September screen was primarily a generic deployment target matrix/catalog/run surface. The gain is richer build semantics and shared evidence, not the first existence of an operational control plane.

Useful implementation entry points:

- [Toolchain/convention split](/Users/ovd/dev/reaktor/dependeasy/gradle/build.gradle.kts:10), [native source/task registration](/Users/ovd/dev/reaktor/dependeasy/gradle/src/main/kotlin/dev/shibasis/dependeasy/settings/NativeBootstrap.kt:26), and [toolchain pins](/Users/ovd/dev/reaktor/dependeasy/gradle/src/main/kotlin/dev/shibasis/dependeasy/toolchain/ToolchainVersions.kt).
- [Request validation](/Users/ovd/dev/reaktor/dependeasy/model/src/main/kotlin/dev/shibasis/dependeasy/model/GradleInvocationKernel.kt:17), [Gradle planning/execution bridge](/Users/ovd/dev/reaktor/dependeasy/gradle/src/main/kotlin/dev/shibasis/dependeasy/workspace/GradleInvocationTask.kt:35), and [plan cache](/Users/ovd/dev/reaktor/dependeasy/model/src/main/kotlin/dev/shibasis/dependeasy/model/GradlePlanCache.kt:6).
- [Shared model reader and impact analysis](/Users/ovd/dev/reaktor/dependeasy/model/src/main/kotlin/dev/shibasis/dependeasy/model/BuildModelReader.kt:80), [cached owner model reads](/Users/ovd/dev/bestbuds/modules/kernel/src/main/kotlin/ai/bestbuds/reaktor/workbench/agent/BuildModels.kt:25), and [Hangar BuildPane](/Users/ovd/dev/bestbuds/modules/engine/src/jvmMain/kotlin/ai/bestbuds/reaktor/workbench/render/compose/panes/InfrastructurePanes.kt:73).

## Actual benefits and costs by dimension

| Dimension | Benefit obtained | Cost or limitation | Assessment |
|---|---|---|---|
| Cross-language build visibility | One model describes Gradle, native tools, Node/web, verification, packaging, releases, and deployment relationships across included builds. BestBuds exports 1,820 nodes and 2,170 edges. | Export consistency does not prove every external script or runtime dependency is modeled. | Strong architectural gain, backed by model inspection. |
| Consumer authoring | Reaktor Gradle script lines fall 36%; BestBuds 31%. Component/output/node handles replace repeated recipes. | More declarations and adapters live in framework Kotlin; BestBuds root script itself grows 45% against its actual main. | Clear reduction in consumer glue; mixed total simplicity. |
| Build-convention isolation | A retained private implementation-edit probe executed **zero consumer compilation/packaging tasks**, with exact source restoration. Stable toolchain dependencies are separated from mutable conventions. | It still recompiles changed build logic. This says nothing about public API or toolchain changes, and has no equivalent September timing control. | Concrete functional gain; speedup magnitude unmeasured. |
| Reproducibility | Tool/source versions, source identities, environment/options, native contracts, and package execution are more explicit. Declared inputs are content-hashed. | Host SDKs, process behavior, credentials and some ambient reads still exist. Pinned does not mean hermetic. | Substantial improvement in declared reproducibility. |
| Configuration cache | Current JVM artifact builds with the cache enabled succeed and store entries. Retained qualification also demonstrates cache reuse. Old R/B settings bootstrap was rejected by configuration-cache checks. | Fresh routing moved between workspaces and missed the cache; a follow-up in the same slot missed because build option `PATH` changed. Configuration-time dependency-resolution warnings remain. Compatibility is task-specific. | Compatibility improvement; routine speed benefit needs separate measurement. |
| Native/build cache | Native source, compiler/tool identities, configuration, dependency manifests and outputs have better task contracts. | CMake and generic process tasks explicitly disable Gradle build caching because outputs contain host paths. This is not universal relocatable/remote caching. | Better incrementality and correctness; partial caching. |
| Planning correctness | Retained observed plans matched Gradle exactly for five substantial targets, with zero missing/extra tasks. Cached plans are keyed by request and execution context. | Initial observed planning still cost 8.5–15.6 seconds in that qualification. Broad environment/context hashing can conservatively invalidate caches. | Strong correctness benefit; planning has a real cost. |
| Agent/operator experience | CLI/MCP/Hangar share target IDs, source locations, specs, receipts and freshness semantics; less need to infer behavior from scattered scripts. | Fresh basic CLI calls typically take 1.3–2.6 seconds; history calls take much longer. No controlled human-time or token-cost experiment. | High capability gain, uneven responsiveness. |
| Execution safety | Requests bind model/declaration/content digests, owned paths, declared options/environment and deployment/migration environment agreement. Readers reject invalid/stale contracts. | These checks do not prove provider credentials, policies, signing, rollback or production rollout readiness. Approvals existed before the migration too. | Stronger build-request integrity; not a blanket security certification. |
| Test selection | Declared file inputs and dependency edges support explainable affected-target selection. Ordering-only edges are treated separately. | The implementation explicitly reports **partial**, **conservative**, **reuseAllowed=false**; build-declaration edits can select the entire composite. | Useful analysis, not complete minimal CI or automatic test-result reuse. |
| Evidence/debugging | Runs retain task statuses, artifacts, context and model identity; source/recorded/live states are distinguished. A Linux gate was joined through receipts, CLI and Hangar. | Around 0.83 GB of top-level receipt JSON is retained across Reaktor/BestBuds; history lookup parses the history on every request. Older green results can be stale. | Large observability gain with a measured scaling problem. |
| Local/portable verification | Shared portable checks run application JS/JVM and kernel suites on Linux; local/worker execution can produce canonical evidence. | The retained portable gate takes 42m02s. GitHub workflow files are absent from current R/B HEAD; equivalent automatic invocation/availability is a separate operational concern. | Working portability path; no measured reduction in CI cost. |
| Maintenance and upgrades | Shared adapters can fix many consumers once; model and Gradle tests now cover the contracts. | Reaktor build implementation is 9.5× larger. Kotlin/Gradle internal bridges and Android compatibility flags remain; fresh builds warn about Gradle 10 incompatibility. | More capability with a substantially larger ownership burden. |
| Product runtime, binary size, resource cost | No measured benefit established in this investigation. | No equivalent APK/IPA/JS/native size, application startup, runtime memory, total build RSS/CPU or network-byte comparison. | Unmeasured; do not attribute gains to the migration. |

### Size of the authoring tradeoff

| Metric | Pinned main | Current working source | Change |
|---|---:|---:|---:|
| Reaktor Gradle Kotlin scripts | 2,291 lines / 40 files | 1,464 / 54 | −36.1% lines |
| BestBuds Gradle Kotlin scripts | 1,137 / 11 | 779 / 23 | −31.5% lines |
| Reaktor Kotlin build implementation | 2,148 / 31 | 20,409 / 340 | **9.50×** |
| Reaktor Kotlin build tests | No files in this inventory scope | 9,127 / 51 | New framework verification burden and coverage |
| BestBuds buildSrc implementation | No files in this scope | 892 / 14 | New consumer-side implementation |
| Reaktor authored CMake | 515 / 6 | 128 / 11 | −75.1% lines |
| BestBuds authored Ruby/Fastlane | 1,312 / 1 | 402 / 2 | −69.4% lines; release logic partly moved into Kotlin |
| Reaktor root build script | 99 lines | 42 | −57.6% |
| BestBuds root build script | 120 lines | 174 | **+45.0%** |
| R+B package script entries | 436 | 259 | −40.6% entries |
| R+B manually authored package commands | 436 | 65 | −85.1%; current also has 194 generated aliases |

Against the September **branch** snapshots, Gradle script lines are Reaktor 2,956→1,464 (−50.5%) and BestBuds 1,753→779 (−55.6%). The choice of baseline materially changes the headline percentage.

The 20.4k-line implementation count covers Dependeasy/buildSrc source in Reaktor; it excludes the additional Reaktor+ build-plane UI and owner implementation in BestBuds. It is not a count of the entire new plane.

Method: physical lines, including comments/blanks; source inventory excludes build/IDE output, generated TS import/export trees, vendor/external trees, reports and experiments symmetrically. Tests are separate from implementation. Generated package aliases are identified from manifest metadata and counted separately. The method is reproducible in [measure.py](/Users/ovd/dev/reaktor/reports/dependeasy-migration-2026-10-27/measure.py). These counts are maintenance proxies, not complexity scores or equivalent-functionality benchmarks; products/modules also changed during this period.

## Measured build performance

### Retained main-versus-migrated artifact comparison

Same named target and arguments: `:reaktor-core:jvmJar --no-configuration-cache --no-daemon --console=plain`. Each variant's watched sources were stable. Evidence: [retained artifact timing comparison](/Users/ovd/dev/reaktor/reports/dependeasy-migration-2026-10-27/retained-evidence/final-main-artifact-timings-20261026-v4-comparison.json).

| Phase | Main Gradle | Migrated candidate Gradle | Main full wrapper wait | Candidate full wrapper wait |
|---|---:|---:|---:|---:|
| Initial observed | 95s | 17s | 286.7s | 180.9s |
| Warm observed | **12s** | **18s** | **117.8s** | **181.0s** |

The candidate is `9acbd00f2f4a687de50ee50be46cb4aad37021a1`, not today's exact HEAD. Initial is **not** a matched cold build: the candidate reused outputs/cache while main had old build logic to fetch/configure/compile. Main ran in isolated worker slot 1 and the candidate in the ordinary slot 0, with different cache/sync/publication conditions. Gradle/toolchain versions and product sources also differ.

The warm observation is 50% slower inside Gradle and 54% slower end to end on the candidate. That establishes **no speedup for this measured case**; it does not isolate migration overhead or establish a universal regression. Reporting 95→17 seconds as a cold-build win would be misleading.

### Fresh current artifact runs

On 27 October I invoked the configured `./gradlew` wrapper from the primary Reaktor checkout. It routed to `m1-worker` under normal load and appended `:exportBuildModel`. No daemon was kept between invocations. These are unchanged-target observations with cached compilation outputs, not clean builds or compiler-throughput tests. Shared working-tree activity and routing were allowed to continue.

| Run | Gradle | Full wrapper wait | Workspace/cache observation |
|---|---:|---:|---|
| Configuration cache disabled | 17s | 194.1s | Slot 0; 22 actionable tasks, 2 executed, 20 up-to-date |
| Configuration cache enabled, first | 16s | 142.3s | Slot 0; cache entry stored; 1 executed, 1 from cache, 20 up-to-date |
| Configuration cache enabled, repeat | 16s | 252.3s | Routed to slot 1 at a different path; no entry available, new entry stored |
| Configuration cache enabled, follow-up | 17s | 123.8s | Same slot 1; Gradle explicitly rejected reuse because build option `PATH` changed |

The first run's router execution was 18.7s, source sync 11.5s, queue 35.3s, collection 30.5s, and publication 97.9s. Publication includes its own queue, so its queue must not be added again. Gradle's 17s was only about 9% of developer wall time. Some copied outputs were marked unverified or withheld by source/publication checks; an exit-zero artifact command is not evidence that every shared workspace output was published as current.

All four Gradle invocations succeeded. Each of the three cache-enabled runs stored an entry; **none reused one**. The first repeat changed workspace; the follow-up in the same workspace logged `configuration cache cannot be reused because Build option 'PATH' has changed`. The exact origin of the PATH difference was not isolated. This is evidence of current environment/context invalidation, not evidence that configuration-cache storage is broken. All four logs contain 28 configuration-time dependency-resolution warnings apiece, plus Gradle 10 deprecation warnings.

Raw records: [Gradle benchmark](/Users/ovd/dev/reaktor/reports/dependeasy-migration-2026-10-27/gradle-benchmark.json), [disabled-cache log](/Users/ovd/dev/reaktor/reports/dependeasy-migration-2026-10-27/gradle-no-configuration-cache.log), [enabled-cache log](/Users/ovd/dev/reaktor/reports/dependeasy-migration-2026-10-27/gradle-configuration-cache-first.log), [repeat log](/Users/ovd/dev/reaktor/reports/dependeasy-migration-2026-10-27/gradle-configuration-cache-repeat.log), [follow-up log](/Users/ovd/dev/reaktor/reports/dependeasy-migration-2026-10-27/gradle-configuration-cache-followup.log). [analyze.py](/Users/ovd/dev/reaktor/reports/dependeasy-migration-2026-10-27/analyze.py) extracts normalized timing and router fields into `analysis.json`.

## Measured build-plane responsiveness

Fresh CLI processes against the live BestBuds owner, five serial attempts per operation except receipt lookup, which stopped after its second attempt failed. Target: `bestbuds:portableChecks`; tree/story depth 2. Times include JVM startup and local owner RPC. No cold-cache resets. This Mac has 14 CPUs and 48 GiB RAM, uses Java 25, and was under ordinary concurrent development load. These are descriptive samples, not statistically controlled p95 estimates.

| Operation | Successful median | Successful range | Successful attempts |
|---|---:|---:|---:|
| `build list --limit 5` | 2.33s | 1.06–16.67s | 5/5 |
| `build show` | 1.65s | 1.33–1.78s | 5/5 |
| `build tree --depth 2` | 1.35s | 1.33–1.70s | 5/5 |
| `build explain` | 2.62s | 2.41–4.06s | 5/5 |
| `build kernels` | 2.50s | 1.70–4.29s | 5/5 |
| `build receipts` | **21.41s** | One success | **1/2**; other attempt exited 2 after **35.77s** |
| `build story --depth 2` | **14.29s** | 11.11–18.42s | **4/5**; other attempt exited 2 after **35.58s** |

Failures are excluded from successful latency medians. The first measurement script did not retain the failed calls' response bodies, so the exact failure reason is unconfirmed; it would be incorrect to label them definite timeouts. The raw exit codes and elapsed times are retained in [cli-benchmark.json](/Users/ovd/dev/reaktor/reports/dependeasy-migration-2026-10-27/cli-benchmark.json).

One additional diagnostic receipt lookup succeeded in **31.21s**; the failure did not recur in that attempt, so no failure diagnosis was recovered. This separate follow-up is excluded from the original series above and retained in [receipt-followup.json](/Users/ovd/dev/reaktor/reports/dependeasy-migration-2026-10-27/receipt-followup.json).

There is a concrete scaling explanation for slow history requests: both [story](/Users/ovd/dev/reaktor/dependeasy/model/src/main/kotlin/dev/shibasis/dependeasy/model/BuildModelReader.kt:346) and [receipt pagination](/Users/ovd/dev/reaktor/dependeasy/model/src/main/kotlin/dev/shibasis/dependeasy/model/BuildModelReader.kt:568) enumerate and parse receipt JSON across included builds. Response size/retained rows are bounded; work performed is still proportional to retained history. Around the benchmark there were about **11,700 top-level receipt files / 826 MB** across Reaktor and BestBuds. This supports a history-scaling concern, but does not by itself prove the cause of either unsuccessful call.

### Model-read cost in isolation

On the stable 6,627,907-byte BestBuds model, a Java 25 in-process probe ran five warmups and twenty timed samples per operation using the installed CLI jars:

| Work | Median | Range |
|---|---:|---:|
| Cached model read, including content validation | 14.3ms | 13.5–16.2ms |
| Parse/validate model | 43.3ms | 41.8–56.8ms |
| Hash/check 1,078 declaration inputs for freshness | **400.3ms** | 289.8–647.8ms |

The parsed-model cache helps. Freshness checking costs considerably more than parsing in this sample, and history scanning adds a separate cost. These are component microbenchmarks, not replacements for end-to-end CLI latency. Probe and raw samples: [model-benchmark.jsh](/Users/ovd/dev/reaktor/reports/dependeasy-migration-2026-10-27/model-benchmark.jsh), [model-benchmark.log](/Users/ovd/dev/reaktor/reports/dependeasy-migration-2026-10-27/model-benchmark.log).

## Graph and correctness evidence

Fresh exported-model inventory:

| Owner | Nodes | Edges | Declaration inputs | Model size |
|---|---:|---:|---:|---:|
| Reaktor | 1,313 | 935 | 914 | 4.32 MB |
| BestBuds composite | 1,820 | 2,170 | 1,078 | 6.63 MB |
| Manna composite | 1,486 | 1,384 | 964 | 5.05 MB |

All three have zero reported export omissions, duplicate node IDs, dangling edges, or nodes missing declaration sites. Every node has a kernel reference. All 1,248 BestBuds kernels owned by Dependeasy have typed specifications, and all 416 Verify-effect nodes carry coverage metadata. Coverage metadata is not measured code coverage, and zero export omissions is not proof of complete real-world dependency coverage.

The [model reader](/Users/ovd/dev/reaktor/dependeasy/model/src/main/kotlin/dev/shibasis/dependeasy/model/BuildModelReader.kt:11) caps model input at 8 MiB. BestBuds already uses about 79% of that limit, leaving 1.76 MB. This is a scaling constraint to monitor, not a current model-read failure.

Retained execution evidence gives stronger support than declarations alone:

- **Gradle plan fidelity:** [WP5 qualification](/Users/ovd/dev/reaktor/reports/dependeasy-migration-2026-10-27/retained-evidence/wp5-qualification.json) matched five observed plans byte for byte, with no missing/extra tasks: BestBuds appChecks 773 tasks, iOS frameworks 811, worker bundle 558, Reaktor mobile interop 378, and Manna buildAll 3,913. Observed configuration times were 8.5–15.6s. It also qualified a real CLI verification run and an Xcode framework shell phase; no signing/upload was performed.
- **Convention isolation:** [WP13 implementation-edit probe](/Users/ovd/dev/reaktor/reports/dependeasy-migration-2026-10-27/retained-evidence/wp13-final-wp1-invalidation-v6-proof.json) confirms zero consumer compilation/packaging execution across warm/edit/restore phases, exact source restoration, and eleven unchanged compatibility diagnostics. Its observer required configuration cache disabled. It does not mean zero total work.
- **Portable gate:** [26 October Linux qualification](/Users/ovd/dev/reaktor/reports/dependeasy-migration-2026-10-27/retained-evidence/wp22-20261026-v463-portable-proof.json) records **1,068 tests: 1,056 passed, 12 skipped, zero failures/errors**, 201 suites, 527 Gradle tasks, and 2,522s Gradle time. It joins canonical receipts, CLI and Hangar. Its explicit scope is a pinned historical candidate; Hangar correctly marked it as not matching the current model.
- **Existing current test reports:** the local Dependeasy model and Gradle report directories contain 20 + 129 tests across 50 suites, zero failures/errors/skips, last modified on 27 October. These reports were inspected, not re-executed by this investigation, and are not proof for every concurrent source edit.

## Remaining boundaries that matter

The source itself limits several possible marketing claims:

1. **Affected-target analysis is partial.** [affectedInputs](/Users/ovd/dev/reaktor/dependeasy/model/src/main/kotlin/dev/shibasis/dependeasy/model/BuildModelReader.kt:162) explicitly prohibits receipt reuse and conservatively selects the composite on build-declaration changes. There is no evidence here of globally minimal rebuilds or complete test-impact analysis.
2. **External tasks are not all remotely cacheable.** [KotlinCMakeTask](/Users/ovd/dev/reaktor/dependeasy/gradle/src/main/kotlin/dev/shibasis/dependeasy/tasks/KotlinCMakeTask.kt:35) and [ProcessTask](/Users/ovd/dev/reaktor/dependeasy/gradle/src/main/kotlin/dev/shibasis/dependeasy/process/ProcessTask.kt:24) explain why caching is disabled by default. Local incremental builds and configuration-cache support are distinct from relocatable output caches.
3. **Plugin upgrade coupling remains.** [BOUNDARIES.md](/Users/ovd/dev/reaktor/dependeasy/BOUNDARIES.md) documents remaining Kotlin/Gradle internal bridges. Fresh logs show Android legacy-mode deprecations and Gradle 10 incompatibility warnings. The migration improves the adapter boundary but still owns its compatibility maintenance.
4. **Deploy declarations do not establish deployment success.** The current BestBuds graph has 156 Deploy-effect nodes, but this assessment did not publish images, upload mobile builds, exercise production rollout/rollback, or certify signing. Specialist provider/Fastlane paths and other legacy integrations still need scoped parity checks before retirement.
5. **Full application artifact performance is open.** There is no matched cold/warm/source-edit benchmark covering Android, iOS, web, native and portableChecks across both old and current stacks. The single JVM artifact result must not be generalized to those targets.

## Priorities supported by the evidence

1. **Bound history lookup work.** Introduce a small canonical receipt index or per-target latest-receipt lookup, preserving content/freshness verification. Benchmark at current history size and larger retained histories. Pagination alone does not fix the full scan observed here.
2. **Separate build-engine time from developer wait.** Track Gradle execution, worker queue, sync, collection and publication separately. In the fresh sample publication cost exceeds Gradle execution several times over. Address the dominant component before attributing delay to Kotlin compilation or the DSL.
3. **Make configuration-cache reuse routine across actual routing.** Investigate the observed `PATH` build-option invalidation, remove/justify configuration-time dependency resolution, and verify reuse within each worker workspace. Measure cache misses caused by slot/path changes separately from model/context invalidation; retain ownership checks that prevent stale output publication. Do not simply omit PATH from inputs while external tool selection still depends on it.
4. **Control framework maintenance growth.** Treat the 20.4k-line implementation as an owned product: retire duplicate readers/dispatchers only after their concrete callers have parity, keep the Gradle-free model and mutable conventions separated, and reduce internal API dependencies as supported public APIs allow.
5. **Complete a matched artifact benchmark before claiming speed or cost savings.** Use pinned September/main and current revisions, the same host/slot/toolchain where the comparison permits, explicit daemon/cache states, source-content guards, and at least three alternating repeats. Cover a no-op artifact build, a leaf source edit, a private convention edit, and representative Android/iOS/web/native/portable verification targets. Report engine time, developer wait, CPU/RSS, transferred bytes and artifact sizes independently. Preserve real failures in the denominator.

## Reproduction and evidence

The investigation added only this report and measurement artifacts. It did not change product/build implementation or run deployments/device installation.

Run inventory/CLI measurements with `python3 /Users/ovd/dev/reaktor/reports/dependeasy-migration-2026-10-27/measure.py inventory` or `cli`. The `gradle` mode runs the three routed artifact commands; `gradle-followup` appends an additional enabled-cache observation. These overwrite their respective measurement files, so copy the report directory first if preserving this exact run. `analyze.py` summarizes existing records without starting builds. Follow the workspace storage/router instructions before reproducing build runs.

Fresh measurements: [inventory](/Users/ovd/dev/reaktor/reports/dependeasy-migration-2026-10-27/inventory.json), [CLI](/Users/ovd/dev/reaktor/reports/dependeasy-migration-2026-10-27/cli-benchmark.json), [Gradle](/Users/ovd/dev/reaktor/reports/dependeasy-migration-2026-10-27/gradle-benchmark.json), [normalized analysis](/Users/ovd/dev/reaktor/reports/dependeasy-migration-2026-10-27/analysis.json), [model microbenchmarks](/Users/ovd/dev/reaktor/reports/dependeasy-migration-2026-10-27/model-benchmark.log).

The retained [26 October review](/Users/ovd/dev/reaktor/reports/dependeasy-migration-2026-10-27/retained-evidence/final-main-review-20261026-v22-findings.json) contains additional migration acceptance findings. Its open statuses and older counts are historical context; this report uses the fresh inventory and the later portable-gate result where they supersede it.
