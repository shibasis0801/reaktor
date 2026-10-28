#!/usr/bin/env python3
from pathlib import Path
import json,datetime
h=Path(__file__).resolve().parent
p=h/'performance.md';s=p.read_text()
s=s.replace('The candidate overlays only the seven named owned source files.','The primary candidate overlays only the seven named owned source files. A later, separately labelled default-cache qualification adds the one-line Reaktor root property.')
s=s.replace('- The router consumes the declared JVM public output only when its producer participated successfully.', '- Reaktor now defaults to `org.gradle.configuration-cache=true` after the actual JAR-plus-export invocation passes cold/store, ordinary reuse, a real Kotlin body edit, and build-declaration invalidation checks. This changes the Reaktor root; it does not enable the default in BestBuds or Manna.\n- The router consumes the declared JVM public output only when its producer participated successfully.')
s=s.replace('scope.\n\n## Experiment controls','scope. Fresh server prerequisites also execute all 17 CLI cases successfully; this does not qualify the full kernel suite or the failing server-image prerequisites.\n\n## Experiment controls')
s=s.replace('Configuration-cache store/reuse/edit is a separately labelled treatment of the actual two-task invocation.','Configuration-cache store/reuse/edit is a separately labelled treatment of the actual two-task invocation. That treatment removes the phase-observer init script because it is incompatible with configuration reuse; ordinary follow-up probes also remove the HTML profiler. They are diagnostic treatment comparisons, not another pure single-factor or September/current matrix.')
s=s.replace('reductions are1.7% cold,9.2% warm and17.2% on the edit','reductions are 1.7% cold, 9.2% warm and 17.2% on the edit').replace('collection is2,412→35','collection is 2,412→35').replace('and21.54→5.61','and 21.54→5.61').replace('collection is2,415→38','collection is 2,415→38').replace('and21.59→5.68','and 21.59→5.68')
a=s.index('The actual two-task configuration-cache treatment stores')
b=s.index('\nFirst cache stores',a)
s=s[:a]+'''The actual two-task configuration-cache treatment stores and reuses with strict problem handling. Baseline three warm reuses have a 4.67s Gradle /16.78s reserved-E2E median, versus 15.51s /28.41s without configuration cache. Its source edit takes 10.42s /22.93s and reuses configuration. The candidate's first profiled reuse takes 4.59s /13.28s. Its second profiled reuse exits 1 after `BUILD SUCCESSFUL`, with `Cannot invoke "org.gradle.api.tasks.TaskState.getSkipped()" because "this.state" is null`. The failed attempt remains failed and is excluded from successful timing medians.

The pinned Gradle 9.7 profiler's `TaskExecution.getStatus()` contains that exact unchecked dereference ([local bytecode diagnostic](gradle-profile-diagnostic.txt)). This is a strong match to a profiler failure, not a captured failing stack trace or an upstream fix. No exception was suppressed. A profiled repetition passes after cache invalidation; four ordinary unprofiled reuses then all pass, with **4s rounded Gradle time** and **13.21s median reserved E2E** (12.89–13.37s). A real Kotlin body edit reuses configuration and passes at 10s rounded Gradle /19.27s E2E; only `TimeKt.class` changes, exactly as in the primary matrix.

The checked-in default is qualified separately with no cache CLI switches, no init observer and no profiler. All six commands pass:

| Default invocation check | Gradle console, rounded | Reserved E2E | Cache/model behavior |
|---|---:|---:|---|
| Cold build with dependencies available |86s|93.95s|Stored successfully; one diagnostic sample, not a new paired cold median.|
| First warm invocation |15s|25.42s|Invalidated by Gradle build option `TOKEN`; stored again. This miss is retained.|
| Next warm invocation |4s|13.27s|Reused; JAR and model export up to date.|
| Next warm invocation |4s|12.49s|Reused; JAR and model export up to date.|
| Root build declaration comment changed |19s|28.31s|Invalidated by `build.gradle.kts`; export reran for changed `declarationFiles`.|
| Original declaration restored |15s|26.19s|Invalidated again; export reran.|

All six preserve the exact original JVM JAR bytes. [Default-cache proof](default-cache-proof.json) records per-command source identities, cache outcomes, task outcomes and log hashes. The actual default is now enabled **for Reaktor**, within this qualified JVM invocation scope. Other task families and the BestBuds/Manna roots retain their own acceptance. The intermittent `--profile` completion failure remains a diagnostic limitation.
''' +s[b:]
s=s.replace('Configuration reuse, cold bootstrap, ordinary admission queues, bounded history reads, representative Android/web/native targets and application→service→Hangar tracing remain separate measurements.','Configuration reuse is now implemented and qualified in the actual core-JAR invocation. Cold bootstrap, ordinary admission queues, bounded history reads, representative Android/web/native targets and application→service→Hangar tracing remain separate measurements.')
s+='''

## Next performance budgets

These are proposed acceptance targets, not measured improvements:

| Priority | Dimension and current evidence | Next implementation/qualification | Proposed acceptance |
|---|---|---|---|
| P0, delivered | Warm configuration and artifact ownership | Default cache, lazy analysis export, typed JAR collection | Keep 4–5s cache-reuse Gradle path and byte-exact artifacts; detect declaration changes. |
| P1 | Cold regression: original controlled 81.92s vs September 50.35s; identical-source gap 29.76s | Reduce settings/build-logic and project configuration; evaluate prebuilt versioned build logic and remaining eager JS-metadata resolution seen in the real distribution build. Preserve private-plugin isolation. | Recover September cold parity on the same frozen target, worker, dependency state and toolchain; three alternating pairs before claiming a gain. |
| P1 | E2E still around 13s despite 4s Gradle on cache reuse | Measure and reduce sync, verification, supervision and required evidence transfer separately; retain source/provenance checks. | Warm reserved E2E below 10s on this target, then ordinary-admission p50/p95 from real runs. |
| P1 | 12-minute publication queue on the full suite; 29.8-minute slot queue on the owner distribution | Quantify queue occupancy and foreground publication constraints before changing admission. Keep devices and shared checkouts safe. | No global p95 claim from campaign admission. Establish a week of actual per-run queue and execution data first. |
| P2 | Shared KT-62686 compiler fallback | Evaluate a separately pinned toolchain candidate on body/ABI edits and Android/web/native outputs; do not bypass correctness checks. | Demonstrate real compiler incremental work plus exact output correctness, not just one changed JAR class. |
| P2 | Resource/cost coverage incomplete | Record whole process-tree peak RSS, CPU, network and cache/storage growth across representative target families. | Performance gains do not trade for OOMs, unbounded storage, stale models or failed release gates. |

The new graph and receipts provide the identities needed to measure these dimensions. They do not by themselves make compilation, queues or provider calls faster.
'''
p.write_text(s)
# Full current architecture is kept byte-identical between editable diagram and report.
p=h/'hld.mmd';m=p.read_text().replace('matched distribution build pending<br/>live owner remains stale','new distributions installed<br/>supported upgrade timed out; old owner live').replace('cache reuse investigating · cold cost open','cache default qualified for JVM<br/>cold / queues / resources still open');p.write_text(m)
p=h/'report.md';s=p.read_text();a=s.index('```mermaid\n')+len('```mermaid\n');b=s.index('```',a);s=s[:a]+m+s[b:]
a=s.index('## Implementation follow-up —')
s=s[:a]+'''## Implementation follow-up — 28 October, from 15:40 IST

The audit above is retained as history. The performance continuation changes product code and does not certify the whole build plane. [Performance report](performance.md), [acceptance and budget](completion-plan.json), [bounded product patch](implementation.patch), [owned router patch](tools-implementation.patch) and [source-hashed proof](implementation-proof.json) describe the candidate. Foreign staged and unstaged work is preserved; no commit or push was made by this continuation.

- **Typed JVM archives and bounded copy-back:** nine paired cold/warm/edit comparisons produce byte-identical JARs; each body edit changes only `TimeKt.class`. Cold collection falls from 2,412 paths /21.54 MB to 35 /5.61 MB: 98.5% fewer paths, 73.9% less payload. The paired changes do not establish a Gradle speedup.
- **Lazy analysis model:** source/compiler/runtime declarations and pinned tools are exported without resolving unused analysis configurations. Real pinned analysis compilation and a complete export pass; behavioral fixtures reject eager analysis resolution.
- **Configuration reuse:** Reaktor's root now defaults to configuration cache. Four ordinary repeated reuses pass at 4s rounded Gradle time and 13.21s median reserved E2E. A source edit reuses at 10s /19.27s; six actual default-cache commands pass cold/store, reuse and declaration change/restoration. The first default warm miss and one failed profiled completion callback remain explicit in the [cache proof](default-cache-proof.json). This is qualified for the actual core-JAR-plus-export invocation, not every root or task family.
- **Fresh checks:** all 129 plugin +25 model cases and all 68 router/build/retention cases pass. Normal full-suite copy-back/publication completes (`20261028-155858-d50ad0ec-reaktor`); its 12-minute publication queue is separate from Gradle's 5m27s. All 17 CLI cases execute and pass during the real server prerequisites; a fresh full kernel suite is not claimed.
- **Concrete server blocker:** the real `:serverImage` attempt fails app architecture and Hangar prerequisite tests. The own attempt stops after failures; no gate is skipped, and no image, digest, push or rollout is claimed. [Failure evidence](server-attempt-failures.txt) and [transport](server-attempt-transport.json) retain this boundary.
- **Distribution versus live owner:** `:kernel:agentDist :reaktorCli:distZip` builds and completes normal publication. The main CLI installs through its existing transactional installer; all 276 agent distribution files install byte-exact. The supported owner upgrade times out after 300 seconds. The old owner remains live and omits one new-model node; exact drain-blocker identification and a successful matching-owner rollout remain open. Task admission is explicitly reopened. All 475 pre-inventory history files remain present. [Install/upgrade proof](owner-install-proof.json) and [live status](owner-after-upgrade.json) distinguish installed bytes from runtime adoption.

### Actual benefits and costs across dimensions

| Dimension | Evidence of benefit | Cost / remaining constraint |
|---|---|---|
| Cold speed | Typed graph identifies setup, configuration and execution separately. | Original controlled migration result is 50.35→81.92s Gradle, 62.7% slower. The new no-cache follow-up also establishes no Gradle gain. Cold bootstrap is the top remaining compute regression. |
| Warm/edit speed | Actual cache reuse now reaches about 4–5s Gradle; real edited-body reuse passes. | Both September and current stacks benefit from cache. Cache-disabled migration results were 13.0% slower warm and 18.7% slower on edit. Cache store/invalidation has a real cost. |
| Transfer scope | New typed JAR collection cuts the measured cold payload by 73.9% and paths by 98.5%, preserving exact bytes. | Required models/indexes/reports still exceed the 167KB JAR. Other target families and historical transport faults need their own qualification. |
| Build logic isolation | Earlier scoped private-plugin implementation edits caused zero consumer compilation/packaging. | API/toolchain changes invalidate differently; isolation has not recovered cold setup cost. |
| Discoverability and source navigation | Typed identities, outputs, edges, origins and tools feed the same CLI/Hangar/model views; JVM archive coverage is now added. | Completeness is only meaningful for modeled nodes. The live old owner leaves one new node out, and compilation metadata predates build-script edits. |
| Consumer glue | WP18's retained inventory records 216 generated aliases and 80 intentional authored scripts across 43 manifests. | Framework ownership and adapter maintenance grow. Generated aliases do not remove native compiler/resolver responsibilities. |
| Scheduling and portability | Gradle retains closure/scheduling; portable WP22 receipt records 1,056 pass /12 declared skips. | Fresh all-platform qualification, host boundaries and mixed-host release execution remain open. |
| Authority and provenance | Sealed plans, fingerprints, environment/version joins and retained receipts are implemented and qualify in slices. | Authenticated principal/distinct-approver parity, refresh withdrawal semantics and final store adoption remain open. |
| Operability and latency | Runs, artifacts, logs and receipts can be joined to exact subjects and sources. | Real publication/admission waits can dominate a successful build; history reads previously took 21–31s. No whole-system p95, peak-RSS or cost win is established. |
| Delivery | Approved dev apply/rollback and several release adapters have recorded acceptance. | Server image prerequisites fail now; Pulumi live adoption, push receives and final legacy-writer retirement are open. |
| CLI experience | Both interfaces work; main CLI is reinstalled and 17 fresh cases pass. The HLD defines one operation contract and packaging direction. | Two distributions/launchers, cached versus live identities, `development` branding, unsupported agent `workspace --help`, caller migration and TUI remain consolidation work. |

The [performance report](performance.md#next-performance-budgets) ranks the next measurable work. Cold bootstrap, normal queue distributions, bounded history, representative Android/web/native targets, whole-process resources and application→service→Hangar tracing remain open. Server/Pulumi rollout still requires a concrete qualified candidate and the owner go required by BestBuds AGENTS.md; there is no deployable server candidate to approve yet. No legacy production writer was retired.
'''
p.write_text(s)
plan=json.loads((h/'completion-plan.json').read_text());plan['progress'].update({'cacheAcceptance':'Accepted for Reaktor root actual JVM JAR+export invocation; six default commands and four ordinary reuses pass. One --profile callback failure retained separately.','installedOwner':'Matching main CLI/agent distributions built, normally published and installed; supported owner upgrade times out after 300s, old owner still live, admission reopened.','performance':'18 primary paired samples pass with byte-exact artifacts. 73.9% less cold transfer payload; no no-cache Gradle improvement. Warm cache reuse about 4–5s Gradle; cold/queues/resources remain open.','cliQualification':'17 fresh CLI cases pass during real server prerequisites; full fresh kernel suite not claimed.'});plan['updated']=datetime.datetime.now().astimezone().isoformat();(h/'completion-plan.json').write_text(json.dumps(plan,indent=2)+'\n')
e=json.loads((h/'evidence.json').read_text());e['implementationFollowup']={'observedAt':datetime.datetime.now().astimezone().isoformat(),'proof':'implementation-proof.json','performance':'performance-summary.json','defaultCache':'default-cache-proof.json','server':'server-attempt-proof.json','ownerInstall':'owner-install-proof.json','liveOwner':'owner-after-upgrade.json','historicalAuditPreserved':True};(h/'evidence.json').write_text(json.dumps(e,indent=2)+'\n')
print('Updated report, HLD, performance findings, acceptance and evidence index.')
