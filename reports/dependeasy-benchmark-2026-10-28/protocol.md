# Paired worker benchmark protocol

Written before the measured runs. Acceptance: three paired repetitions of cold-output,
unchanged warm, and identical non-ABI source-edit builds; exact source identities;
same worker directory and resource limits; successful artifact validation; separate
Gradle, queue, materialization, synchronization, execution/polling, and collection times.

Change budget: one private benchmark harness, one common instrumentation init script,
and this evidence/report directory. No product implementation, public API, dependency,
module, shared router, or default build-setting changes. A detached scratch checkout
supplies immutable snapshots. The incremental snapshot changes only epochTime's body
by adding 1L, identically on both revisions; that snapshot is never published as code.

Revisions: September origin/main b7b0370346b46d6bc915a05fb61ffdef93436235;
current 423e607f10b29aae104e39879be962e0e9bb5131. Product sources differ between
these commits: this is an equal-target stack/workload comparison, with source/task
counts reported; it cannot by itself attribute all differences to Dependeasy.

Target: :reaktor-core:jvmJar. Worker: m1-worker, frozen worker slot, same absolute
source directory for every run. Gradle runtime JDK 21.0.8, six Gradle workers, 8 GiB heap, 2 GiB
metaspace, Kotlin compiler daemon with 8 GiB maximum heap, no persistent Gradle daemon, output build
cache disabled, configuration cache disabled for the common baseline. The common
init script records task and JVM diagnostics. --profile and --info retain raw evidence.

Baseline correction before any builds: 7ca5f2ac is a September development-branch
commit, not the September/main old stack. The requested baseline is the local
origin/main ref at b7b03703 (30 September). Its Gradle 8.14.2 cannot run on JDK 25.
The current snapshot's daemon criteria are therefore changed from 25 to 21 for the
common-runtime experiment only; compiler toolchains remain as declared by each stack.
A current-native-JDK-25 probe will check this normalization's effect separately.

Dependency preparation is timed separately. Each variant uses its own owned Gradle
user home. Cold-output runs delete project build/.gradle/.kotlin directories and all
Gradle-user-home caches except downloaded modules-2 and wrapper distributions.
External tools/bootstrap checkouts and OS filesystem caches remain warm. Therefore
“cold” here means cold generated outputs/build state, not empty disks or cold network.
Warm runs immediately repeat the same invocation. Incremental runs change only the
single implementation expression, preserving prior warm outputs. Every new pair
starts from a cold reset. Pair order alternates September/current, current/September,
September/current. All 18 measured runs must succeed or failures remain explicit.

The shared frozen-source/router implementation performs synchronization, supervision,
artifact collection and source verification. The private harness suppresses only its
opportunistic automatic :exportBuildModel append so the requested Gradle task list is
identical; model export will be investigated separately. Native artifact collection is
retained, with its paths/bytes recorded. Snapshot capture/preparation is outside run
end-to-end time; lock wait, materialization, transport and publication are inside it.
The cold reset is recorded separately and excluded from the user wait comparison.

Use paired medians and ranges; three repetitions are descriptive, not a precise p95.
Capture load/memory and competing JVM processes around each run, record instrumentation
and router hashes, and retain small raw receipts/profiles/logs. Clean owned disposable
snapshots and build artifacts at delivery, then run dev-storage cleanup.

Pre-matrix amendment after the first cold pilot: disk space fell below the 15 GB
floor while other work produced outputs. The matrix was stopped after that pilot,
which is retained but excluded in excluded-runs.json. The harness now enforces the
15 GB floor before every run. After cleanup it reserves all configured worker Gradle
slot leases for the full matrix, with that reservation wait recorded separately.
Every Gradle invocation still executes in exactly the same frozen worker directory.
This prevents other routed Gradle jobs from changing CPU contention between pairs.
Before any reserved measurement started, the reservation was extended to slot 2,
which Atlas uses independently of the configured Gradle build-slot count. Thus all
worker build/analysis leases 0/1/2 are held through each measurement series.
Before any reserved measurement started, the setup compiler reports revealed that
September's in-process compiler mode disables incremental compilation. The primary
matrix was therefore changed to daemon mode on both stacks, with an explicit equal
8 GiB Kotlin heap. Cold resets stop only compiler daemons whose command line names
the owned benchmark Gradle homes, leaving no other variant's compiler resident;
warm/edit runs can reuse their compiler daemon. The
earlier setup/pilot in-process observations are not part of the primary matrix.
Observer process CPU measures the Gradle JVM only; Kotlin daemon CPU is separate.
Always rerun a whole six-run paired block if storage interrupts it; retain and mark
its earlier partial block as excluded. Warm means preserved outputs with a fresh
single-use Gradle daemon, matching the router's normal daemon policy.

Pre-matrix storage admission amendment: ordinary cleanup must defer remote build
artifacts while worker jobs are active. Each series therefore runs standard cleanup
after acquiring all worker leases and before acquiring its graph lease. This cleanup
is outside measured builds and respects the remaining workspace/local/graph locks.
The 15 GB per-invocation free-space guard remains in force.

Separate sensitivity checks (not pooled with the main matrix):

* One paired cold/warm/incremental cycle with identical compile sources: retain only
  the original time.kt in core commonMain/jvmMain. Other project declarations and
  dependencies stay native. This controls target-source size, not root-project or
  compiler-version differences. Scratch overlays and removals are recorded separately.
* One cold and three unchanged warm runs without the custom task observer on each stack, followed
  by a configuration-cache store and three reuse attempts with fail-on-problems.
  Failed cache stores remain evidence rather than being treated as speed measurements.
* Current stack's native Gradle JDK 25 criteria, sharing warmed dependencies, with
  an unmeasured seed followed by three unchanged warm runs without the observer.
* Three observer-free current warm runs explicitly adding :exportBuildModel, to
  measure the marginal cost of the model export normally appended by the router.
