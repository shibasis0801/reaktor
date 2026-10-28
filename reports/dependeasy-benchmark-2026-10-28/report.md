# September/main versus current: controlled worker benchmark

28 October 2026. This report measures Reaktor's `:reaktor-core:jvmJar` and uses the
results to identify improvements to Dependeasy and the Reaktor+ build plane.
The broader architectural assessment is in the
[migration investigation](/Users/ovd/dev/reaktor/reports/dependeasy-migration-2026-10-27/report.md).
Its earlier build timings are not pooled with this experiment.

<!-- results:start -->
## Measured outcome

All **18/18** prescribed builds succeeded. All source-content guards passed. The
current stack is slower in every paired cold, warm and source-edit observation
under the common cache-disabled controls. This workload establishes no build-speed
benefit under those controls. Capabilities and optimization treatments
must be evaluated separately from that finding.

Times are median seconds, with minimum–maximum in parentheses. End to end is
**reserved per-run latency**: the initial reservation/admission delay is separate.

| Phase | September Gradle | Current Gradle | Change | September reserved E2E | Current reserved E2E | Change |
|---|---:|---:|---:|---:|---:|---:|
| Cold build state | 50.35 (50.28–50.71) | 81.92 (81.87–83.10) | +62.7% | 66.55 (65.57–69.16) | 102.62 (102.61–107.66) | +54.2% |
| Unchanged warm | 10.02 (9.96–10.08) | 11.32 (11.18–11.42) | +13.0% | 24.81 (24.16–25.32) | 29.07 (28.24–30.99) | +17.2% |
| One-file edit | 14.30 (14.24–14.44) | 16.98 (16.91–17.00) | +18.7% | 27.95 (27.77–27.97) | 35.49 (34.44–37.97) | +27.0% |

Percentages above compare group medians. The median within-block current-minus-
September differences retain the pairing:

| Phase | Gradle paired difference | Reserved E2E paired difference |
|---|---:|---:|
| Cold build state | +31.59s / +62.8% | +37.05s / +56.5% |
| Unchanged warm | +1.30s / +13.0% | +3.76s / +14.8% |
| One-file edit | +2.67s / +18.7% | +7.54s / +27.0% |

The successful primary reservation plus storage admission took **1258.53s (20.98 minutes)**.
Earlier interrupted admission attempts are retained separately. This campaign was
delayed by existing worker/device work and the cleanup admission lease; the number
is not a normal queue percentile and is not assigned to either stack. Per-run queue
wait once reserved was below one millisecond. All 15 acceptance checks pass in
[verification.json](/Users/ovd/dev/reaktor/reports/dependeasy-benchmark-2026-10-28/verification.json).

## Where the time goes

Median seconds below are independent component summaries. Medians are not additive.

| Component | September cold | Current cold | September warm | Current warm | September edit | Current edit |
|---|---:|---:|---:|---:|---:|---:|
| Snapshot materialization | 2.99 | 3.83 | 2.27 | 2.68 | 2.18 | 2.49 |
| Source sync | 4.18 | 6.69 | 3.78 | 4.67 | 3.65 | 4.59 |
| Execution outside Gradle profile | 2.63 | 2.57 | 2.61 | 1.56 | 1.25 | 2.20 |
| Collection | 0.49 | 2.40 | 0.48 | 2.24 | 0.46 | 2.35 |
| Isolated publication | 0.05 | 0.43 | 0.07 | 0.54 | 0.07 | 0.59 |
| Total non-Gradle | 15.84 | 20.74 | 14.85 | 17.89 | 13.53 | 18.51 |

Remaining non-Gradle time includes the common diagnostics, Java/source checks and
adapter bookkeeping. Shared-checkout publication contention is absent from this
isolated experiment; ordinary developer publication latency can be much larger.

| Cold Gradle profile component | September median | Current median |
|---|---:|---:|
| Startup | 11.45s | 7.95s |
| Settings and buildSrc | 21.60s | 31.12s |
| Configuring projects | 12.74s | 33.62s |
| Target Kotlin task | 3.97s | 8.39s |

Included-build work can overlap these profile categories. Current cold runs also
compile buildSrc, model, toolchain and Gradle adapter code, whereas September has
one convention-plugin compilation. Configuration/build-logic work is a stronger
cold-latency lead than the small requested JAR. The identical-source fixture below
tests how much target-source growth changes this interpretation.

## Resource, incrementality and output observations

| Median observed metric | September cold / warm / edit | Current cold / warm / edit |
|---|---:|---:|
| Gradle process CPU seconds | 129.24s / 26.19s / 25.14s | 163.55s / 30.67s / 32.04s |
| Gradle GC seconds | 0.72s / 0.15s / 0.15s | 0.99s / 0.19s / 0.20s |
| Sum of Gradle heap-pool peak usage | 1.45 GiB / 0.43 GiB / 0.50 GiB | 1.93 GiB / 0.65 GiB / 0.62 GiB |

These are Gradle-process observations, **not total build CPU or peak build RSS**.
No owned compiler process survived the primary boundary samples, so compiler CPU
was missed. Sparse RSS samples sometimes caught only JVM startup on short runs;
the raw lower bounds are retained but do not support a credible warm/edit RSS
comparison. Heap-pool peaks can occur at different times and are not RSS. No energy
or financial-efficiency conclusion follows from these measurements.

Every warm target compilation was up to date. All six edit runs executed compilation
and changed **only `TimeKt.class`** in the resulting JAR. However, both stacks report
the `KT-62686` safety fallback to non-incremental compilation for this edit. Thus the
measured edit rebuild is valid, but a single-file compiler incrementality benefit
is not demonstrated. The shared fallback is an optimization lead. There were no
dependency downloads or FROM-CACHE outcomes in any primary run.

| Requested real artifact / collection | September | Current |
|---|---:|---:|
| JAR bytes | 151,124 | 167,296 |
| Class entries | 96 | 106 |
| Cold collected bytes | 1,297,038 | 16,497,071 |
| Cold collected files | 231 | 2345 |

Native collection uses family `all` on both stacks. Current collects about 16.5 MB
for a 167 KB requested JAR, but its measured collection cost is only about 2.4s.
A scoped JVM artifact collector is useful; collection alone cannot explain the
roughly 32s Gradle cold difference. The real library gained classes/source between
commits; its larger JAR is not evidence that build-framework code is embedded in it.

<!-- results:end -->

<!-- treatments:start -->
## Separate treatments and improvement evidence

These samples are not pooled with the primary matrix. All 23 treatment builds and
all six identical-source builds succeeded with source guards intact. The
[treatment validation](/Users/ovd/dev/reaktor/reports/dependeasy-benchmark-2026-10-28/sensitivity-verification.json)
also checks actual cache reuse, runtime, source equivalence and artifact changes.

| Observer-free treatment | n | Gradle seconds: median (range) | Reserved E2E seconds: median (range) |
|---|---:|---:|---:|
| September warm, cache disabled | 3 | 9.17 (9.15–9.97) | 25.23 (22.74–25.44) |
| Current warm, cache disabled | 3 | 11.21 (11.15–11.33) | 28.96 (28.23–29.67) |
| September configuration-cache reuse | 3 | 2.74 (2.71–2.75) | 14.84 (14.61–15.40) |
| Current configuration-cache reuse | 3 | 3.81 (3.77–3.84) | 22.25 (22.18–23.03) |
| Current native JDK 25 warm, cache disabled | 3 | 11.25 (11.21–11.26) | 28.14 (27.73–28.16) |

Both stacks stored a configuration cache and **reused it in all three attempts**.
For current, reuse reduces median Gradle time from 11.21s to 3.81s (66.0%) and
reserved E2E from 28.96s to 22.25s (23.2%). September also benefits: 9.17s to 2.74s
inside Gradle. This is an actionable optimization for both; it is not a unique
migration benefit in this warmed JVM scenario. Earlier old-stack rejection evidence
concerned different execution states and does not override this successful test.

These cache checks request the bare JAR target. Configuration-cache reuse with the
router’s normal appended model export is **not tested here**. Routine reuse still
needs validation with that exact task list and real PATH/slot/context changes.

Current’s native JDK 25 warm median is 11.25s versus 11.21s on the common JDK 21.
This small descriptive difference does not explain the primary regression. The
observer-free cold samples are September 50.26s and current 81.69s, very close to
their instrumented cold medians. Warm observer-free results retain the regression
as well. The custom observer is not a credible explanation for the main finding.

### Model export cost

| Current export invocation | Gradle | Reserved E2E | New dependency download lines |
|---|---:|---:|---:|
| 1 | 53.88s | 72.21s | 75 |
| 2 | 14.25s | 31.79s | 0 |
| 3 | 13.71s | 32.24s | 0 |

The JAR was warm before invocation 1, but export’s analysis/model dependency paths
had not been warmed. Therefore invocation 1 is **initial export**, not a settled
warm export measurement. Invocations 2 and 3, with no downloads, take 13.71–14.25s
in Gradle and 31.79–32.24s E2E. Their two-sample median is 13.98s / 32.01s, versus
11.21s / 28.96s for the three JAR-only warm samples. This is a sequential treatment
estimate, not three paired steady-export repetitions. All three observed export
invocations remain visible above.

The initial export profile spends 13.788s resolving `:reaktor-code:kotlinAnalysisCompiler`
and 7.278s resolving `kotlinAnalysisRuntime`; aggregate dependency-resolution time
is 22.982s. Root `:exportBuildModel` itself is only 0.839s. These categories do not
form an additive explanation of the full 53.876s build. They identify analysis
toolchain resolution/input processing as a stronger lead than JSON serialization.
The third root export is up to date, yet the whole invocation still exceeds JAR-only
time. See [export diagnostics](/Users/ovd/dev/reaktor/reports/dependeasy-benchmark-2026-10-28/export-diagnostics.json).

### Identical target-source fixture

Both variants compile the identical seven-line original `time.kt`, producing one
class. The same edit changes only `TimeKt.class`. Each cell below is **one sample**;
root declarations, dependencies and compiler/build versions remain native.

| Phase | September Gradle | Current Gradle | September reserved E2E | Current reserved E2E |
|---|---:|---:|---:|---:|
| cold | 49.10s | 78.86s | 68.04s | 98.85s |
| warm | 9.97s | 11.33s | 24.45s | 28.34s |
| incremental | 11.69s | 14.10s | 24.29s | 31.23s |

The cold engine gap remains **29.76s**, versus a 31.59s median paired gap in the real
project. This supports a substantial build-logic/configuration cost beyond target
source growth. It does not isolate a pure DSL effect: Gradle, plugin versions,
classpath and project graph remain different. The fixture edit builds retain the
same Kotlin safety fallback; reducing source size does not prove that it is fixed.

## Improvement priorities

Retain the migration for explicit cross-build contracts, inspectable plans and shared
execution evidence. Consumer Gradle source is smaller, but framework ownership and
cold startup cost are larger. The measured benefits are control and visibility; this
experiment demonstrates no build-speed or total-resource-efficiency win. The new
model and receipts make the following work concrete and verifiable.

| Priority | Change to investigate | Evidence / likely scope | Acceptance criterion |
|---|---|---|---|
| 1 | Make configuration-cache reuse reliable in the actual routed invocation | Bare current JAR: 11.21→3.81s Gradle, 28.96→22.25s reserved E2E | Store and reuse with the normal model-export task list; report PATH/workspace miss reasons; preserve source/output freshness |
| 2 | Reduce source-build bootstrap and configuration work | Current cold 81.92s; identical-source gap still 29.76s; current settings/buildSrc 31.12s and configuration 33.62s | Compare stable prebuilt build-logic artifacts and valid build-cache reuse against the same matrix; retain mutable-convention isolation and exact artifact checks |
| 3 | Avoid materializing the analysis toolchain during unrelated model exports | Initial export adds 75 dependency downloads; analysis compiler/runtime resolution alone is 21.07s | Export declared graph/tool requirements without fetching unused analysis artifacts; bind resolved identities when analysis executes; preserve graph and plan fidelity |
| 4 | Add a direct typed JVM JAR artifact and scope collection | Exact current model has 1,313 nodes / 34 core nodes, no direct JVM JAR node; 2,345 files / 16.5 MB collected for a 167 KB JAR | Discover/build the artifact through its node; copy JAR plus necessary evidence only; retain content/ownership checks; measure the actual E2E reduction |
| 5 | Investigate the shared Kotlin incremental fallback | All six real edit builds fall back with KT-62686; only one class changes | Repeat the identical edit with no full-compilation fallback, identical intended output changes, and affected correctness checks |
| 6 | Measure queue/admission and publication as separate workload classes | This exclusive reservation plus cleanup took 20.98 minutes; normal contention was deliberately excluded from per-run results | Establish ordinary queue/publication distributions by host/device/resource class before changing scheduling; preserve workspace and phone exclusivity |
| 7 | Bound receipt-history lookup work | Prior investigation measured 21–31s successful history calls with ~11,700 receipt JSON files | Index bounded latest-target/history lookup while preserving digest/freshness verification; benchmark increasing retained histories |

Collection is a useful small optimization, not the explanation for the cold regression:
its current primary median is about 2.4s. Likewise, the exclusive benchmark queue is
one campaign observation, not proof that ordinary builds all wait 21 minutes. Existing
secondary slots can serve other workloads. Validate ordinary scheduling before
redesigning it based on this deliberately exclusive experiment.

A build-plane benchmark scenario should preserve pinned revisions, target/output
identity, host/resource policy, reset/daemon/cache state, exact edit, repeated-run
receipts, and these independent timing fields. This would turn the private harness
into a reusable experiment without replacing Gradle’s scheduler. It is a proposed
product improvement; no product implementation was changed in this investigation.

<!-- treatments:end -->

## Comparison and controls

| Control | Value |
|---|---|
| September/main | `b7b0370346b46d6bc915a05fb61ffdef93436235` — `origin/main`, 30 September |
| Current committed stack | `423e607f10b29aae104e39879be962e0e9bb5131` |
| Target | `:reaktor-core:jvmJar` on both revisions |
| Worker | `m1-worker`; M1 Pro, 10 CPUs, 32 GiB RAM |
| Execution directory | `/Users/ovd/dev/offload/.remote-dev/slots/worker/reaktor` for every invocation |
| Contention control | Reserve worker build/Atlas leases 0, 1 and 2 throughout each series |
| Gradle runtime | Corretto 21.0.8 on both; current daemon criteria normalized from 25 to 21 |
| Resource limits | Six Gradle workers; 8 GiB Gradle heap, 2 GiB metaspace; 8 GiB Kotlin daemon heap |
| Process policy | Fresh single-use Gradle JVM; Kotlin daemon reuse permitted for warm/edit; owned survivors stopped for cold |
| Primary caches | Output build cache and configuration cache disabled |
| Incremental edit | Identical `epochTime()` body edit: add `1L` to epoch milliseconds |
| Repetitions | Three paired blocks, alternating stack order; cold → warm → edit within each stack |

Cold means generated outputs, project build state and Gradle execution caches are
removed. Downloaded modules, wrapper distributions, external bootstrap checkouts
and OS filesystem caches remain warm. It is a compilation/build-state cold test,
not a network-install or empty-disk test. Setup is separate.

The two stacks retain their own Gradle and Kotlin/plugin versions: September uses
Gradle 8.14.2; current uses Gradle 9.7.0. Both produce Java 25 bytecode. Fixing the
entire compiler/dependency stack would remove part of the actual migration being
evaluated. The common runtime and resource limits control execution conditions;
they do not make the two implementations identical.

Primary working trees are shared and dirty. Runs use immutable snapshots derived
from the two committed refs, with the common observer and documented runtime
normalization overlaid. Receipts verify worker and staged source contents. The
incremental snapshot changes only the same function body. Neither scratch edit
is published into a product checkout.

The shared frozen-source/router adapter is used for **both** stacks. Its source
verification and isolated publication are controls of this experiment, not measured
speed or safety benefits unique to current Dependeasy. The adapter's opportunistic
`:exportBuildModel` append is suppressed so the primary requested task list matches;
a separate current treatment measures that export's marginal cost.

## What each time measures

* **Gradle:** exact `Total Build Time` from the root Gradle profile, not rounded
  console duration and not transport execution time.
* **End to end:** per-invocation worker wait, materialization, synchronization,
  supervision/polling, collection, isolated publication and source verification.
  It includes common before/after diagnostics. Explicit cold reset time is recorded
  and subtracted; snapshot capture, dependency setup and post-run evidence copying
  are outside it.
* **Reservation wait:** the initial wait to reserve all worker leases is recorded
  separately. Per-run wait under the reservation is not ordinary shared-worker queue
  behavior. Neither this wait nor the excluded pilot is silently added to one stack.
* **Non-Gradle time:** end to end minus Gradle. Transport `execution_seconds`
  includes process launch, supervision and log draining; it is not pure Gradle time.
  Publication queue is already inside publication time.

Gradle profile categories can overlap because included-build work occurs during
settings. Task-duration sums can also overlap. They must not be summed into a false
additive wall-time breakdown.

The common observer records Gradle process CPU, GC and heap-pool peaks. A sum of
heap-pool peaks is **not** peak RSS. Kotlin daemon CPU is derived separately from
owned process cumulative CPU at run boundaries; exited processes may be missed.
Combined observed CPU omits Gradle startup before observer installation and is not
an energy measurement. Periodic process RSS gives a sampled lower bound on the
simultaneous Gradle/compiler resident footprint, not a profiler-certified maximum.

## Workload and maintenance tradeoff

The real artifact has 96 classes on September and 106 on current, approximately
10.4% more. A same-target result therefore measures the actual stack **and workload**
change. An additional one-pair fixture reduces both target compilations to the
identical original `time.kt`; project declarations, dependencies and compiler
versions remain native. That fixture checks source-size sensitivity and does not
isolate a pure DSL effect.

| Committed Reaktor inventory | September | Current |
|---|---:|---:|
| Gradle Kotlin scripts | 2,291 lines / 40 files | 1,464 / 54 |
| Kotlin build implementation | 2,148 lines / 31 files | 20,408 / 340 |
| Kotlin build tests | 0 in this scope | 9,127 lines / 51 files |
| Root build script | 99 lines | 42 |
| Authored package commands | 60 | 3, plus 23 generated aliases |
| Authored CMake | 515 lines | 122 |

Physical lines include comments and blanks. Generated/vendor/output/report trees and
tracked `bin/main` copies are excluded symmetrically. Implementation and tests are
separate; the inventory excludes the additional BestBuds Reaktor+ owner/UI. Thus
consumer Gradle lines fall 36.1%, while the shared implementation grows 9.50×.
This is a maintenance proxy, not a complexity score or equivalent-functionality
cost estimate. See [pinned inventory](/Users/ovd/dev/reaktor/reports/dependeasy-benchmark-2026-10-28/maintenance-inventory.json).

## Using the build plane to improve the build

The plane can connect a target's declared inputs and tool identities to an observed
Gradle plan, its run receipt, and its verified output. That is useful experimental
infrastructure: an optimization can be accepted against preserved evidence rather
than a single console duration. The benchmark harness supplies the controlled
source/cache/resource states that are not yet represented as one model operation.

A fresh live-owner check exposed a coverage gap: `build.list(query="jvmJar")` returned
zero targets with `stale=false`, while `reaktor-core` had 34 modeled nodes. Its web
package contract and module completion marker are not JVM JAR artifact contracts.
The fallback invocation therefore used native collection family `all`. The pinned
current export independently confirms the same 34 core nodes and no direct JVM JAR
artifact node within its 1,313-node model. See the [pinned export coverage](/Users/ovd/dev/reaktor/reports/dependeasy-benchmark-2026-10-28/pinned-export-coverage.json). See the [coverage recheck](/Users/ovd/dev/reaktor/reports/dependeasy-benchmark-2026-10-28/live-coverage-recheck.json)
and [core target list](/Users/ovd/dev/reaktor/reports/dependeasy-benchmark-2026-10-28/live-core-list-recheck.json).

| Dimension | What the migration enables | Limitation to address | Acceptance evidence |
|---|---|---|---|
| Target/output coverage | Typed nodes, inputs, tool identities and output contracts | This JVM artifact is reached through a raw Gradle fallback | A discoverable JVM artifact node resolves to the exact JAR and required metadata; its observed plan still matches Gradle |
| Developer latency | Receipts can separate engine execution from routing phases | Queue, sync and publication can dominate an unchanged build | Preserve Gradle time, queue time, materialization, sync, collection and publication as separate fields; compare the same source and artifact |
| Incrementality | Explainable inputs and dependency edges; isolated mutable conventions | Conservative impact analysis is not complete minimal rebuild selection | Private implementation edit changes only intended outputs; preserve source/content and plan checks |
| Cache effectiveness | Configuration and tool contexts have explicit identities | Compatibility alone does not guarantee routine reuse across slots and environment changes | Report cache store/reuse/miss reason per run and preserve output correctness |
| Resource efficiency | Runs can attach host/process diagnostics to exact source identities | Declared worker/heap limits are not actual CPU, RSS or energy consumption | Compare observed CPU and simultaneous RSS under equal resources, then validate on a representative application |
| Evidence scale | Shared receipts and freshness across CLI/MCP/Hangar | Prior report found history scans scale with retained JSON; this is a separate cohort | Bound latest-target lookup work while retaining digest/freshness checks; benchmark increasing history sizes |
| Maintainability | Less consumer glue and a reusable model/runtime layer | Reaktor's shared implementation is 9.50× larger in the pinned inventory | Track adapters/internal APIs and parity-backed removals alongside script counts and contract tests |

A useful next product feature is a benchmark scenario backed by model nodes: pinned
revisions, requested target, host/slot/resource policy, cold/reset policy, exact source
edit, cache treatment, output validation and repeated-run receipts. It should invoke
Gradle's real scheduler and join existing execution evidence. It should preserve
these experiments' independent timing fields and distinguish controlled reserved
latency from normal queued latency. This is a proposed improvement, not an existing
capability demonstrated by the private harness.

## Experimental record and limitations

Preparation exposed and corrected a private harness path bug before Gradle ran.
A successful unreserved cold pilot was then excluded when storage pressure required
stopping the series. It measured 58.149s Gradle and 318.978s end to end, including
235.213s worker queue; it is diagnostic evidence, not a paired result. The first
exclusive reservation took 845.554s, then local storage admission rejected execution
before a measured Gradle build started. Subsequent pre-run reservation attempts were
released while tightening Atlas-slot and compiler-mode controls.

Before any primary measurement, the setup report showed September's in-process
compiler uses non-incremental compilation. The primary protocol was changed to
daemon mode on both stacks to preserve a meaningful incremental comparison. Setup
and pilot used the earlier mode and are never included in primary medians.

Three paired repeats give descriptive medians and ranges, not a reliable p95 or a
statistical causal estimate. Background OS/owner services remain running and are
sampled. These results do not establish Android/iOS/web/native/full-application
build speed, production reliability, energy use, financial savings or application
runtime performance.

## Evidence and reproduction

The [protocol](/Users/ovd/dev/reaktor/reports/dependeasy-benchmark-2026-10-28/protocol.md)
records controls and pre-measurement amendments. The
[source identities](/Users/ovd/dev/reaktor/reports/dependeasy-benchmark-2026-10-28/snapshots.json),
[environment](/Users/ovd/dev/reaktor/reports/dependeasy-benchmark-2026-10-28/environment.json),
[harness](/Users/ovd/dev/reaktor/reports/dependeasy-benchmark-2026-10-28/benchmark.py),
[raw results](/Users/ovd/dev/reaktor/reports/dependeasy-benchmark-2026-10-28/results.jsonl),
[derived data](/Users/ovd/dev/reaktor/reports/dependeasy-benchmark-2026-10-28/summary.json)
and per-run receipts/profiles/logs are retained here. `summarize.py` analyzes existing
evidence without starting builds. Reproduction requires an owned source checkout,
immutable snapshot capture, storage cleanup and the normal worker leases; disposable
execution sources and caches are cleaned at delivery.

The [cleanup record](/Users/ovd/dev/reaktor/reports/dependeasy-benchmark-2026-10-28/cleanup.json)
records removal of owned worker caches, frozen source checkouts, snapshots and scratch
worktrees. Standard storage cleanup subsequently passed both 40 GB budgets and left
25.21 GB free locally, above the 15 GB floor. Durable manifests, receipts, profiles,
logs and derived results remain with this report.
