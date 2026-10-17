# Dependeasy build layer

Dependeasy owns reusable build mechanics. Each repository declares its modules,
artifacts, dependencies, and product constraints in Kotlin. Gradle evaluates the
declarations and schedules work; the language tools compile and resolve packages.
The build layer does not introduce another scheduler, dependency resolver, or
product runtime dependency.

```mermaid
flowchart TD
    Product[Product declarations in Kotlin] --> API[Dependeasy domain APIs]
    API --> Graph[Gradle DAG: nodes, artifacts, edges]
    Graph --> Kotlin[Kotlin and Android]
    Graph --> Web[pnpm, TypeScript, Karakum, Vite]
    Graph --> Apple[SwiftPM and direct Xcode frameworks]
    Graph --> Native[CMake and native interop]
    Tools[Kotlin toolchain and source pins] --> API
    Graph --> Reports[Plans, identities, test and size reports]
    Graph --> Dagger[Generated Dagger runner]
    Graph --> Hangar[Declared build graph in Hangar]
    Packages[Authored package.json commands] --> Reports
```

## Design rules

* Keep declarations separate from execution. Applying a plugin or reading a build
  plan must not install dependencies, clone repositories, or compile native tools.
* Use small domain APIs that compose. A JavaScript component declares its sources
  and Kotlin producers; its install, types, bindings, and bundle tasks can then
  participate in any DAG. Avoid a universal project object with hundreds of options.
* Return ordinary typed Gradle task providers. The common API supplies defaults;
  an exceptional case can configure arguments, environment, inputs, outputs, or
  compose an existing task without adding a new runner or bypassing the graph.
* Carry the producer with an artifact. File consumption adds both a dependency
  and a tracked input. Ordering alone does not establish data consumption.
* Give execution tasks declared state. Gradle properties, file collections,
  providers, and injected services replace execution-time access to `Project` or
  captured build-script state. Declare machine-specific tools and configuration.
* Place a mechanism in its domain. Source generation, provenance, native interop,
  workspace installation, and desktop jar retention have separate implementations.
  Product choices remain visible at their call sites.
* Retain real constraints and qualification failures. Do not disable lint, weaken
  type checks, raise budgets, or add compatibility stubs to make a build appear green.

## Domains and status

Final source-audit follow-up: authored command help must describe the current pnpm
commands. Budget: update obsolete npm usage text in the existing Keploy, CompanyOS
and Manna import helpers, and remove the unpinned alternate profiler launcher hint.
No execution body, product behavior, tool, dependency or API changes. Preserve
vendored driver sources and historical evidence; optional MCP provider launchers
are a separate runtime-tooling boundary, not build configuration.

Native kernel consolidation uses the existing CMake task and platform types. Its
budget is internal dependency/tool preparation functions, with no new public API,
stored state or dependency. Acceptance is the owner input and composition tests,
then the FFI host check, three Android ABIs and both Apple SDK builds. Native
sources, CMake fragments and consumed libraries must invalidate compilation;
unrelated language version changes must not. Kotlin remains the C++ standard's
authority for both mobile and standalone CMake declarations.

* **Toolchains:** Kotlin owns versions; Gradle, Node and pnpm downloads are checksummed;
  native revisions are pinned. Java 25 runs builds, with Android bytecode at 21.
  Next: qualify compiler updates per target and make Linux OS package resolution less
  dependent on mutable distribution repositories.
* **Graph:** Lazy nodes, typed artifacts, cycle checks, included-build ownership and
  schema-1 exports feed Hangar's Build source. Some plugin task closures cannot expand
  during declaration discovery; shell bodies remain explicitly opaque. Next: define observed
  execution receipts separately from declarations before adding live state.
  BestBuds is the combined IntelliJ root for both products and Reaktor. Shared
  framework scopes are deduplicated when the products export a diamond graph.
* **Kotlin/Android:** Forty-three active shared modules use `module(namespace)` with
  common/web/Android/Apple/JVM declarations. Compiler plugin bundles, automatic
  generator inputs, per-platform test dependencies and explicit test discovery
  replace repeated mechanics; AGP keeps signing and lint ownership. Next: qualify
  AGP's new KMP Android plugin with the existing NDK/CMake integration.
* **JavaScript:** One frozen pnpm workspace installs producer-dependent Kotlin exports;
  Vite, Karakum and the TypeScript native CLI/API have defined roles. Next: resolve
  product bundle/runtime budgets and qualify browser flows. Registry entries with
  missing package metadata trigger repair using the frozen lock; complete installations
  stay incremental. Compiler export copying excludes dependency trees, preserves
  relative contexts and prunes obsolete compiler files. Custom export directories
  are declared in a Gradle catalog for worker and IDE artifact transfer.
* **Apple:** Explicit frameworks link directly in Xcode; exact SwiftPM resolutions
  supply SDKs through module-owned `src/iosMain/swift` adapters. Swift only injects
  and exports SDK calls; initialization, validation and notification behavior remain
  Kotlin. There is no shared Apple SDK source folder. Next: qualify signed archives and SDK
  behavior on physical devices.
* **C++:** C++23, typed CMake tasks, pinned inputs, prefab/cinterop and shared dependency
  libraries reduce repeated work. Hermes reuses its host compiler. Module-owned C++ installers and TypeScript bundles
  participate in the same graph. Android packages the pinned NDK runtime rather
  than a transitive prefab runtime. Next: keep upstream
  language exceptions explicit and expand ABI checks as targets are added. The
  performance module now shares the same source-set layout and CMake declaration.
  Security now aligns its owned C++ sources with Kotlin source sets and uses one
  native library instead of duplicate C API/core compilations; this change is in
  qualification. Its host OpenSSL discovery still needs replacement by pinned
  target-specific crypto artifacts; a passing static
  library build alone does not qualify those imported artifacts for an app link.
* **JVM applications:** Programs, named probe compilations, quoted launchers and
  distributions share one classpath kernel. Desktop packaging exposes the Compose
  DSL; Spring uses native Gradle BOMs and a pinned buildpack builder. The actual
  packaged desktop runtime probe passes. The actual worker Docker image builds with
  Java 25, and its launcher reports JRE 25.0.4. Next: qualify production startup and release.
* **Generated content and checks:** Typed Kotlin objects, Node generators, shared
  Markdown/Vite content and dependency boundaries replace repeated plumbing. Next:
  move additional bespoke generators when a real consumer needs the common mechanism.
* **Provenance/storage:** Deterministic identities, shared caches, desktop jar leases,
  packaged launchers/class archives and bounded cleanup control repeated output.
  Compiler exports are excluded from source tracking. Metadata requests retain the
  selected platform's transfer scope. Next: measure cold/warm builds under controlled load.
* **Runners/releases:** Load-based Mac/M1 routing, generated Dagger/manifest aliases,
  worker binding order and shared command cancellation connect delivery declarations.
  Fastlane uses a frozen launcher and shared artifact helpers, with product release
  domains kept readable. Next: qualify credentialed releases and consolidate remaining
  authored workflows while preserving their product checks.

## Hangar build workspace follow-up

Composite execution acceptance: the Build operations catalog must expose named
build/check targets from BestBuds, Reaktor and Manna through the BestBuds wrapper.
Native qualification finds that named verification targets retain the ordinary
module-check classification and therefore appear only under Testing. Keep these
explicit Dependeasy workspace entrypoints in Build alongside their artifact targets;
ordinary discovered module tests/checks still belong to Testing. Budget: one catalog
classification branch and its existing cache/binding regression. Preserve the shared
execution binding, effect, platform gate and definition seal; add no state, declaration,
runner or API. Requalify the owner suite, packaged app and native target search,
then launch the existing Reaktor framework check from Build and observe its real
Gradle result through an owned runtime. This uses the existing declaration and
runner, with no new verification target or effect override.
Use the existing generated build-layout map to admit included-build identities;
unknown owners and arbitrary nested task paths remain unavailable. Deduplicate
diamond declarations and reject conflicting definitions, retain platform gates,
and seal the layout report with the graph and existing composite source definitions.
Budget: extend the existing bounded report adapter and shared discovery seal, with
owner tests for qualified wrapper paths, diamond ownership and definition changes.
No new runner, task declaration, public API, stored state or dependency.
Exporting `buildGraph` also produces the existing layout report, so the same
command works through a local IntelliJ build as through the worker. Qualify this
task relationship with the diamond metadata fixture and configuration-cache reuse;
reading either report still performs no compilation or installation.

Acceptance: the former Deploy tab is labeled Build, opens the generated Dependeasy
graph, retains the existing task runner and artifact/release views, and qualifies
Hangar plus BestBuds Android, iOS and web artifacts. Keep persisted mode identities
compatible. Use the existing graph projection and execution bindings; do not create
another scheduler, graph format or build declaration source. The change budget is
one shared build-graph reading function, one small graph view, existing pane/state
wiring, and a bounded report adapter in shared tooling discovery. Named Gradle
targets reuse its existing wrapper binding; their shared source seal also includes
the report read during discovery. External commands are not inferred from that
report. A separate graph selection belongs to the Build
pane so viewing build tasks does not overwrite the application graph's selection.
The projection groups tasks by their Gradle project paths beneath each build.
Acceptance retains task identities and dependency edges, nested project ownership,
and the workspace layout budget. These scopes derive from the exported task paths;
they add no declaration, graph format, API or stored state.
Failing desktop flows retain tagged bounds and interaction states alongside their
existing screenshot. This diagnostic excludes field values and adds no product
state or API; it distinguishes a missing target from an unavailable interaction.
The mode-frame check follows the existing route identity while asserting the new
pane label; a display rename must not require migrating persistent route keys.
The production catalog contract removes the deleted prototype deploy alias from
its expected inventory while retaining all surviving approval and mutation checks.

Worker transport must also reconcile the generated desktop distribution after a
successful transfer. Acceptance replaces one jar version, removes its obsolete
copy, retains the current launcher/runtime and leaves unrelated directories intact;
failed and scoped transfers do not reconcile that distribution. Extend the existing
owned agent-library reconciliation with Compose's generated main/app directory.
No new command, configuration, cache or dependency is required.
Hangar's `desktopPackage` target uses its existing host-native Compose producer on
Mac and Linux. Its declaration must be portable; Dagger still produces Linux
artifacts through the same task, and Apple-only targets retain their macOS gate.

BestBuds' separate website must participate in this qualification alongside its
Kotlin browser app. Acceptance builds its Kotlin exports before shared TypeScript
checks and its normal Vite client/Worker bundle, retains reference-asset pruning,
and exposes the web artifacts through the generated graph. Reuse the JavaScript
component API and package command escape hatch; the package's public build command
delegates to Gradle while its bundle command never calls Gradle. The budget adds
one component declaration and one workspace target, with no new API, flag, state,
tool or dependency.
The website's unused, incomplete graph experiment is removed after verifying it
has no importers. Its compiler exclusion is removed too, so all remaining website
sources participate in type checking.
Shared type checks use the native compiler's build mode so solution configurations
check their referenced projects and retain compiler-owned incremental results.
Acceptance rejects an error in a referenced project and passes after correcting it,
then builds the actual website. Replace the per-project invocation with this native
operation; add no configuration parser, orchestration layer, API or dependency.

Qualification also repairs duplicate table-header automation IDs: the shared
table owns those IDs, so Hangar's header labels must not repeat them. The existing
surface and header-layout checks are the acceptance evidence. This repair adds no
API, state or dependency.

Two graph ownership fixtures must explicitly request their inspected application:
constructing a Data pane deliberately leaves that application unloaded. Keep the
first-use navigation regression and the ownership/retirement assertions; do not
restore eager loading or extend their deadlines to satisfy incompatible setup.

The complete build report also exercises the graph layout kernel at workspace
scale. The repeatable test fixture includes nested project task paths so it also
qualifies project grouping. Acceptance preserves every card and dependency, finite geometry, repeated
layout determinism and a measured layout budget. A JFR capture of the real report
identified ELK's additional input-order sorting as the bottleneck. The budget for
this repair is removing that override: sorted graph inputs and the existing random
seed retain deterministic layout. Add no graph truncation, scheduler, public API,
flag, stored field or dependency. The existing ELK regressions and a large declared
build benchmark qualify the change. ELK documents `NONE` as its
[default ordering strategy](https://eclipse.dev/elk/reference/options/org-eclipse-elk-layered-considerModelOrder-strategy.html).

The full Hangar sweep found a reproducible shutdown deadlock between the kernel
graph and an auth reader factory. Acceptance closes the session while its factory
waits for its owner, disposes a late reader exactly once without submitting a
query, and keeps graph-retirement tests and the full desktop sweep passing. The
repair budget moves factory calls and cancellation outside the auth-session
monitor and uses the existing lifecycle state to reject a closing owner. No new
public API, state, timeout, dependency or approval bypass is needed.

The same sweep exposes a driver race when typing into a workspace field during
its initial read. Acceptance keeps the Agent review and acceptance flows intact
and waits for the existing field's enabled semantics before setting text. The
budget is one readiness check in the shared scene driver, using its existing
timeout; add no sleep, longer deadline, fixture bypass or product state.

Older AI flows also contradict the existing overview-without-pin flow: clearing a
subject leaves the visible scope overview, while explicitly closing the inspector
hides it. Acceptance checks the overview title and absence of the previous subject's
details after clearing; keep close, pin, activation and row-identity checks. Update
only those stale fixture expectations. An Agent keyboard-menu flow waits for its
existing menu's enabled state before typing. These repairs add no product API,
state, dependency or deadline.

Analytics qualification preserves scope isolation: changing environment clears the
previous connection's facts, and returning to production requires a new read.
Screen metrics and Cloud's app links still link to the selected Kotlin application,
so opening those panes requests its existing background activation as Graph and UI do.
Data and Build continue to open without activating it. Extend the first-use test
and retain the screen-link flows; add no API, state, dependency or eager startup.
The canned product timestamps include their UTC offset so frozen-clock assertions
retain the same meaning on both Macs, independent of the process time zone.
The existing `hangar.flows` selector also accepts its JVM property through Gradle
for focused worker checks; an ordinary build still runs the full sweep. Radar's
missing-configuration flow asserts the actual reason, retaining its reachability
and recovery checks.

## Manna Kotlin migration

Manna already has shared Compose features and native services. Its browser, edge,
and API implementations still contain approximately 81,000 lines of TypeScript,
so build modernization alone does not complete the product migration. Kotlin is
the intended owner of product rules, state transitions, services and presentation.
Browser, Cloudflare and Apple adapters expose platform capabilities to those rules.

The first extracted `manna:domain` module has no Compose, React or Reaktor runtime
dependency. Native callers and an exported Kotlin/JS package consume its identifier
and review-plan migration rules, wire records, attribution and origin descriptions.
Work-session records and their interval transitions, overlap accounting, clipping
and local-day calculation, including unrecorded gaps, also live in this domain. Native code and the JavaScript
transport adapter use the same timeline kernel; `buildWeb` includes web unit tests.
The TypeScript adapter converts transport values;
it does not maintain another copy of the rules. Its Kotlin producer precedes web
checking and bundling through the ordinary Dependeasy declaration. Native record
codecs and UI projections stay outside the reusable wire and policy module.
Manna API and browser testing now declare the same Vite/Vitest/Wrangler versions;
Cloudflare's current Vitest plugin replaces its legacy pool package.
Media retention validation, removal-receipt reasons and the authority required to
choose deletion also move into the common domain; its TypeScript adapter supplies
wire field types and keys. Platform storage queries retain their adapter boundary.
Calendar validation, canonical millisecond timestamps, local days, DST gap/fold
resolution and quota windows now share another common kernel. Native timestamp
generation and JavaScript recurrence adapters both consume it. Numeric POSIX cron
parsing, validation and date matching also move to the common schedule kernel;
the JavaScript adapter only converts sets and arrays. RRULE grammar and lazy
pattern enumeration now share small common kernels too. Calendar/cron occurrence
expansion, exception membership, original-time revision ownership, inclusive limits
and DST deduplication are undergoing native and JavaScript qualification in Kotlin.
The native legacy preview adapts to those same kernels. Schedule validation, immutable
revision checks, future editing, exception ownership and revision-aware previews now
share common Kotlin implementations, qualified through qualification 14. Calendar and quota
checks remain separate small kernels; native and browser descriptions use the same
interpreted rule. Locale-specific date captions remain presentation adapters.
Automation draft records, bounds and pure preview decisions also share a Kotlin
kernel. The native store and browser adapter supply local evidence and create
receipts; evaluating a preview performs no platform effects. Unsupported triggers
no longer fall through to unrelated memory events in the browser.
Historical rhythm modes, credit ownership, window targets and upcoming occurrences
also share qualified common kernels. The next progress slice consolidates outline
identities, correction history, unit winners and provider requirements. Both the
native learning screen and browser/API adapters use that same projection. This
slice passes qualification 18, with all four Apple framework links passing in 17.

Continue by domain: remaining wire contracts, synchronization and services,
feature state and presentation, then Worker routes and capabilities. Preserve the
current public URLs, stored records, principal/workspace isolation and receipts.
Existing browser/backend tests must qualify each switch before its TypeScript
implementation is retired. Platform SDK declarations can remain TypeScript and
Karakum-generated Kotlin bindings; product logic belongs in Kotlin. Do not bundle
the complete Compose app into Workers to reuse a small domain rule.

## Common and exceptional declarations

```kotlin
dependeasy {
    val frontend = javascript("frontend", "targets/web") {
        kotlinLibraries("app")
        kotlinLibraries("reaktor-auth", build = "reaktor")
        sources("../shared-ui")
    }
    val bundle = frontend.vite()
    bundle.configure { environment.put("PUBLIC_ORIGIN", "https://example.org") }

    frontend.build("buildWeb", bundle = bundle)
    workspace {
        includedBuild("reaktor")
        target("verification") { tasks(":check"); effect = "verify" }
        external("iosRelease") { fastlane("ios", "build_release"); platform = "macos" }
        dagger()
    }
}
```

The declaration expresses the product selections. The component owns workspace
installation and source tracking, and the DAG owns dependencies. The configured
task remains an ordinary Gradle task; a product-owned producer can join through
`node(...)` and `consumes(...)`.
Generated web assets use `build(..., generators = listOf(...))`: they are explicit
bundle inputs, excluded from authored source snapshots. Checks and generators
remain separate declarations, so a verification task cannot silently consume
another task's outputs through a broad directory scan.

## Two planes and module ownership

Each consumer has one `dependeasy` block. It states platforms, dependencies,
programs and artifacts in the orchestration plane. Domain implementations own
command execution, source generation, installation and packaging in the kernel
plane. Task receivers provide the low-level escape without forcing implementation
loops into the top-level composition. The bootstrap plugin build is the exception:
it cannot apply a plugin that it is still compiling.

C++ and TypeScript sources follow Kotlin source sets (`src/commonMain/cpp`,
`src/commonMain/typescript`, and platform-specific siblings). Mobile bridges own
their runtime, exports and callbacks. Current Android/iOS adapters exercise
Kotlin ↔ C++ ↔ TypeScript; JVM FFM and JS Wasm are future adapter boundaries, not
implemented parity claims. Third-party Swift adapters live in their owning
module source sets and forward to Kotlin protocols; they contain no policy.

## Sources of truth and graph contract

Gradle owns artifact dependencies, entrypoints, runner selection and worker fleet
membership. `package.json` owns JavaScript dependencies and authored package commands;
the frozen pnpm lockfile owns resolved packages. Kotlin owns toolchain versions.
SwiftPM and Xcode retain their native package/link contracts. These are distinct
domains, rather than competing copies of the same task list.

`generateDagger` renders runner functions from Gradle entrypoints, copies the
shared Dependeasy runtime, and emits `dagger.json` with the Kotlin-owned engine pin.
CI reads that generated engine version. `verifyDagger` compares checked-in output with a separate
generated reference and fails on drift. It does not repair a stale adapter during CI.
`generatePackageScripts` owns only explicitly generated deployment aliases; it preserves
authored scripts and dependencies and excludes its own aliases from its input identity.
It also emits target metadata for Reaktor's delivery binding and a bounded alias
transfer report; worker synchronization merges only those owned manifest fields.
Generating the entire manifest would create an unnecessary bootstrap coupling today.

`buildGraph` writes `build/dependeasy/build-graph.json` without running compilation,
package installation, deployment or Fastlane. Schema 1 separates declared state from
execution, identifies tasks by build owner plus absolute task path, includes pipeline
edges and available Gradle dependency closures, imports active package commands, and
nests explicitly included build exports. Unexpanded tasks retain a diagnostic class;
opaque script bodies retain their authored command. Generated package aliases and
declared package invocations have explicit edges. No shell dependency parser guesses
an execution graph.
Dependency discovery snapshots values while the selected graph task is configured.
Execution consumes that snapshot; it never queries Gradle's mutable build model while
execution holds build locks. The adapter fixture checks mixed included-build execution
and graph export with configuration-cache reuse.

`reportBuildLayout` is separate, small machine metadata: Gradle declares composite
names and relative directories once. Worker transfer selects participating build
owners from that map instead of assuming the invoking repository owns every output.
The report does not compile, expand task closures or introduce another composite
definition. The diamond fixture verifies one shared framework and configuration-cache
reuse. Platform artifact filtering and shared snapshots continue to control storage.

Hangar's Build source projects this export into existing graph shapes, typed ports,
scopes and inspector facts. An absent export requests an explicit export; reading the
surface never starts a build. Declared targets carry no live health or execution state.
External recipes that call Gradle, including Fastlane, execute outside a running Gradle
task to avoid recursion and workspace-lock deadlocks.
The Fastlane Apple kernel assembles Xcode build, archive and export commands from
argument tokens. Product recipes select signing and release policy around those
operations; they do not concatenate another Xcode command implementation.

## Qualification

[VALIDATION.md](VALIDATION.md) records actual build results and limits. Incremental
observations are not controlled before/after speed measurements. Storage figures
must distinguish logical directory size from physical APFS allocation. A successful
root Gradle build does not imply that a separately declared web or signed release
target passed.

Shared Vite asset declarations now serve and emit the same named files, filter
font directories without following dependency links, and isolate development
fixtures from production bundles. DevTools backend delivery and bootstrap ordering
use a separate opt-in adapter. Manna declares asset policy in its config and leaves
filesystem traversal, middleware and bundle hooks in Dependeasy.

Fastlane source reproducibility: rename the ignored consumer source `fastlane/domains/ios/build` to `artifacts` and update its import. Acceptance is Git-visible source, Ruby syntax and the existing release contract suite; add no API, flag, state or dependency.
The same ignore rule hides the shared `fastlane/apple/Build` helper on this Mac. Rename it to `Xcode` and update the consumer and shared contract fixture; acceptance and change budget are unchanged.

Large graph rendering acceptance: focusing a task inside a 126,112 × 178,993 world frame must render without exceeding Compose constraints. Preserve world dimensions, edges, viewport transforms, header semantics and full-frame fill/border. The kernel budget is an optional physical content size and clipping policy in the existing node render style; the graph API measures frame headers within the viewport while Blueprint draws frame bodies in world coordinates. No graph truncation, deadline extension, scheduler, stored field or dependency. Qualify the shared renderer with a large-frame composition regression and the real Build flow.

Worker transfer scope acceptance: a custom JavaScript entrypoint must transfer web outputs and required metadata without copying native frameworks or desktop caches. The website qualification took 1m 12s to build but copied 92,848 paths because the transport did not recognize its authored task name. Dependeasy will declare JavaScript entrypoint families in the existing build-layout report; the worker consumes this generated metadata and records the chosen family in its receipt. Retain conservative full transfers for unknown or mixed targets and backwards compatibility for older receipts. Budget: one task-family map in the existing report, internal registration by the JavaScript API, and a bounded transport reader. No new consumer DSL, version source, build scheduler or dependency. Qualify custom task names, configuration-cache reuse and mixed/unknown transfer selection.
Artifact traversal also skips the existing downloaded native-source cache (`.github_modules`) and Xcode DerivedData: their outputs belong to the consuming Gradle build directories. These caches are neither source declarations nor transfer artifacts.
Build navigation consistency: the existing Run-menu action opens the Build pane, so its label and internal callback name must say Build. Preserve its semantic/persisted route identity and navigation-only behavior; remove the unused deployment-busy parameter. The menu contract and existing mode-opening flow qualify the rename, with no execution or approval behavior added.

Definition-seal UI qualification: after approving the foreground direct task, observe the workbench's automatic Runs drawer opening. The old helper checks a snapshot and later toggles Runs, so it can close the drawer after the foreground-run effect opens it. Budget: replace that helper call in this regression with the existing visible-drawer assertion. Preserve the exact rejection message, failed status, event evidence, one admission attempt and zero process starts; add no retry, sleep, timeout, product state or approval exception.

Build header qualification retains the existing selected-operation cancellation
contract: Stop is enabled while the selected k3s read runs and disabled after the
selected desktop build succeeds, even while the earlier read remains active.
Correct the stale flow expectation and check both states. No product cancellation
behavior, deadline, fixture executor or approval rule changes.

Bundler removal acceptance: retire the 35 tracked, identical generated `webpack.config.d/99-prod-sanity.js` fragments in Reaktor and BestBuds. Current libraries emit ESM and the browser uses Dependeasy's Vite API with minification disabled; no owner writes or consumes these fragments. Verify exact generated contents before removing them, and keep custom source untouched. Budget: deletion only; no replacement bundler, package, version pin or consumer API. The current production web build qualifies the active Vite path.

Manna web ownership acceptance: `targets/appWeb` has no Gradle build file or Gradle-task consumers; its root JavaScript component already owns its producers, checks and bundle. Remove the empty Gradle project include and document the real root build contract, preserving its package, files, URLs and standalone/composite entrypoints. Budget: one include removal and current target documentation; no plugin, task alias or backend change. Qualify standalone/composite graph export and the existing production web build.

Surface report memory acceptance: the full Lab sweep must retain every cell's
pixel, unique-ID, accessible-name and keyboard-focus checks within its existing
512 MiB test heap. Qualification 4 exhausts that heap when it retains all cells
for a story and then allocates one second, large contact sheet. Stream the existing
cell declarations into contact sheets of four environment rows, release each page
before the next, and keep findings/counts aggregated for the complete sweep.
Budget: the existing test orchestration and one private page-size constant; no
product API, skipped cells, weaker assertions, larger heap or longer deadline.
The first page retains the story filename, with numbered subsequent pages. Qualify
the complete surface sweep and the engine suite.

Build graph domain acceptance: native inspection shows that generated project scopes
reach the outline but the application feature heuristic flattens every build task
into one frame. The shared feature kernel must group a Build source by its declared
project scope, preserving every task and dependency edge, deterministic order and
the workspace layout budget. Generic `result` ports have explicit task bindings;
they must not be reported as ambiguous dependency-injection providers. Retain cycle
and missing-dependency analysis. Budget: one Build-source branch in the existing
feature kernel and duplicate-provider analysis, with project-frame and diagnostic
regressions in the build graph owner suite. No new graph format, state, scheduler,
public API, hidden tasks or relaxed layout budget.

Prototype cleanup acceptance: the removed design prototype leaves only an ignored generated build directory. Confirm that it has no tracked source and contains only `build`, then remove that exact directory on both Macs so later artifact transfers cannot restore it. Preserve the existing source backup and qualification snapshots. Budget: generated-output deletion only; no transport behavior, product API or cache eviction policy.

Cloud deploy flow acceptance: the referenced filter passes and isolates `app-service`; clearing it restores 43 declared workloads, so that row is legitimately outside the virtualized viewport. Observe the unselected chip and scroll by existing stable row IDs before asserting the referenced row and returning to the analytics row. Preserve the filter, inspector and deploy-order checks. Budget: fixture navigation only; no production filter change, assertion removal, timeout increase or retry. Qualify the exact failed flow.

Interop documentation acceptance: replace the stale hello-only/source-path description with the implemented module API, mobile ownership contract and qualified bidirectional checks. Separate the current synchronous JSON bridge from the older, unimplemented FlexBuffer frame/stream proposal. Budget: documentation only; no API, protocol, runtime behavior or new claim beyond retained qualification evidence.

Packaged Build inspection acceptance: launch a fresh Hangar distribution with its own database and control-plane state, preserving the user's running app and histories. APFS-clone the app into the qualification directory and add explicit existing JVM properties to that copy's launcher configuration; create the owned runtime directory outside the workspace with owner-only permissions. Verify the Build label, project frames, graph counts and declared target discovery without starting product operations. Budget: disposable qualification fixtures and evidence only; no production launcher setting, runtime path behavior, new API or dependency. Terminate only the owned process after inspection.

Android plugin boundary: AGP 9.1.1's new KMP library API has no `externalNativeBuild` or prefab configuration. The current legacy opt-ins therefore remain a real migration limit, rather than flags to delete without replacement. The next native migration separates Android CMake/AAR producers from the single-variant KMP libraries, preserves module-owned `src/androidMain/cpp`, and consumes those AARs through `androidMain`. Dependeasy should own the producer mechanics and settings identity; the consumer declaration should retain one native/interop block. Enable the existing host/device tests explicitly under their new source-set names and qualify publication, JNI packaging, prefab, KSP, Compose and Android release consumers before removing the root opt-ins. This follows the [Android plugin's native-build boundary](https://developer.android.com/kotlin/multiplatform/plugin); it is not implemented or qualified by the current artifact sweep.

Manna orientation acceptance: replace the root README's nonexistent module and incomplete setup description with the actual shared-domain, native app, web/API and composite build ownership. Update only the current-contract paragraph in AGENTS.md with the recorded qualifications, retaining all reporting and product instructions and the explicitly historical baseline. Budget: documentation only; no product capability claim, API, source deletion or build behavior change.

Native Build execution acceptance: after the rebuilt isolated app exposes the existing Reaktor frameworkChecks verification target in Build, start that exact LocalEphemeral operation through its existing Run control. Confirm the sealed Gradle binding remains the BestBuds wrapper plus :reaktor:frameworkChecks, observe the terminal result in Runs, and retain the owned run receipt. Budget: qualification only, one existing local verification operation, no new target, effect override, approval change, production launcher configuration or product API. Keep the user app and state untouched.

Reproducible JavaScript command acceptance: pnpm's default verification may run an implicit mutable install before run/exec; an actual global version inspection rewrote the clean lock. Keep installation owned by pnpmInstall (frozen) and pnpmLock (explicit updates). Shared verified toolchain environment must make all component, Node-script and Kotlin pnpm executions refuse outdated dependencies rather than resolving them. Budget: one internal computed environment provider consumed by existing execution paths, one owner regression that bypasses installation and verifies refusal plus an unchanged lock, and the real web pipelines. No public API, workspace option, dependency, retry, installation bypass or resolver.

Command-boundary fixture correction: the nested Node script consumes the web manifest, so declare that input with the existing sourceFiles property. Without it Gradle correctly reuses the earlier task receipt and never invokes pnpm. Budget: fixture input declaration only; retain configuration-cache reuse, both refusal assertions and explicit lock repair. Production execution policy and API remain unchanged.

Local Manna web qualification found a machine-dependent fixture: memoryD1 loads the git-ignored 0006_seed_baby_data.sql personal import on this Mac, while the worker has only schema migrations. Exclude that existing private import in the fixture loader. Budget: one existing filter and an ownership comment, no new API, migration manifest, option, dependency, product database change or weakened assertion. Acceptance: the unchanged complete signup/permission suite and all browser tests through the Gradle-owned web build, with the local import preserved.

Composite scan acceptance: `:reaktor:build` and `:Manna:build` select only their root lifecycles. Add lazy module lifecycle selection to the existing BuildTarget, and compose one `buildAll` target in each existing workspace block. BestBuds joins the three full module scopes and existing production artifact/check targets. Export actual selected module task references, including tasks registered later; projects without the lifecycle contribute nothing. Budget: one composable public method, three target declarations and an owner TestKit regression. No scheduler, plugin application loop, lifecycle replacement, skipped checks or relaxed deadline. Qualify lazy selection, configuration-cache reuse, graph ownership and a full BestBuds composite scan.

Pulumi SDK acceptance: the baseline scan fails because the local ambient CLI is 3.213.0 and lacks `gen-sdk --server`, while the worker CLI is 3.267.0. SDK generation must use one Kotlin-pinned, checksum-verified 3.268.0 executable through the existing InstallTool, without changing the provider or global installation. Budget: one version constant, four host checksums, one internal setup function and a host-selection helper shared with the JavaScript installer. No dependency, public tool option, alternate command or download mechanism. Qualify installation, actual Grafana Java generation/compilation and subsequent task reuse.

CMake scan acceptance: the baseline Ninja graph contains six module scanners and one collation command for each six-source FlexBuffer Android ABI. Ordinary header-based C++ needs neither. Default CMAKE_CXX_SCAN_FOR_MODULES to OFF only when the caller has not specified it; explicit module file sets retain CMake's scanning rules. Remove forced verbose output from the shared CMake kernel and Android arguments. Budget: one conditional default and two deletions; no consumer flag, compiler standard change or runtime change. Qualify native/mobile builds, preserve header tracking, and retain before/after Ninja graphs. Report command reduction separately from build wall time; the broader final scan is not directly comparable to the partial baseline scope.

Credential-check acceptance: both existing checks must participate in the full composite build. BestBuds' tracked config directory is an explicit owner exception, while private material outside it and GitHub token literals remain rejected. PipelinePlugin owns repository setup, so checks must not require a redundant consumer declaration. Budget: optional pathspecs in the existing shared scan, two check corrections, two build target dependencies and owner regressions for strict/exempt scanning and automatic repository setup. Preserve runtime credential markers, private-import checks and every config file. No new public Kotlin API, dependency, stored policy or credential migration.

Full-scan Hangar acceptance: reproduce all 43 failed flows against the current kernel and UI contracts, then qualify the complete unfiltered desktop suite and BestBuds buildAll composite. Retain stable automatic source intent, truthful provenance and remote health, real navigation findings, cancellation receipts, keyboard actions, clipping and viewport checks. Budget: existing flow navigation and assertions updated for implemented contracts; corrections in existing owners only where focused evidence proves a defect, with owner regressions. No new public API, configuration flag, dependency, state schema, skipped flow, relaxed assertion, longer deadline or retry. Kernel topology changes must not be hidden to recover obsolete counts or findings.

Composite graph export acceptance: BestBuds currently serializes the exact same Reaktor graph both directly and inside Manna. Emit each included build definition once, preserving all unique descendants, task ownership, dependencies, standalone Manna expansion and schema. Qualify a diamond composite with configuration-cache reuse and compare the actual exported task identities and byte size. Budget: private traversal in the existing export task and its existing TestKit owner; no public API, schema, configuration, state or dependency.

Automatic connection qualification: returning to Data currently runs automatic connection checks as foreground operations, clearing a pending deployment approval and replacing the selected manual receipt. Reuse the control plane's existing quiet request path for the automatic batch while retaining foreground semantics for explicit checks. Budget: internal visibility for the existing quiet request overload and one private parameter distinguishing the two existing callers; no public API, persisted field, schema, dependency or retry. Qualify pending-plan and selection retention, explicit check selection, three-slot admission and cancellation, and the live-read flow.

Composite export size acceptance: the complete deduplicated workspace document is 8.7 MB and fails Hangar's existing 8 MiB admission limit. Compact serialization preserves the exact parsed document while reducing it to about 4.2 MB. Budget: remove pretty-printing in the existing BuildGraphTask and extend its existing diamond regression; no limit increase, truncation, schema, configuration, dependency or API. Qualify actual exporter bytes, canonical definitions, configuration-cache reuse and the real Build flow.

Build graph consumer acceptance: Hangar tests read the root workspace export but do not declare its producer. Use the existing workspace graph TaskProvider as a Test input inside the current dependeasy block so clean IDE/Gradle builds generate it first and graph changes invalidate the consumer. Budget: one input declaration and the existing module-lifecycle TestKit fixture; no new task, API, adapter, scheduler or guessed path. Qualify automatic producer selection, no execution cycle, configuration-cache reuse and the full composite.

Timeline hit acceptance: overlapping network calls are drawn in different rows, but clicking a row currently chooses by horizontal distance across all calls. The full timeline flow selects /circles rather than the timed-out request beneath the pointer. Budget: row-aware selection in the existing TimelineTab, one internal pure selector and a derived pointer snapshot of the already-published row mapping, plus an owner regression. No persisted state, public API, flag, dependency, deadline, retry or weaker flow assertion. Qualify overlapping bars, shifted time ranges, absent rows and the unchanged complete replay/brush/selection flow.

Timeline clock qualification: the focused screenshot reveals that early fixture history has negative monotonic timestamps. Empty packed rows initialized to zero incorrectly appear occupied, placing every overlap in row zero. Extend the existing owner budget by one initializer using Long.MIN_VALUE, and qualify the row packing and hit selector together for negative, zero and positive clock origins. Preserve real clock advancement, the pointer coordinates, all flow assertions and existing packing complexity; remove the temporary diagnostic screenshot step after inspection.

Warm-cache fixture acceptance: the full Dependeasy suite correctly restores the module fixture's graph producer from the shared build cache, but its assertion requires fresh execution. Accept SUCCESS or FROM_CACHE while retaining the existing parsed graph, producer selection, consumer receipts and configuration-cache assertions; additionally assert the producer is up-to-date on repetition. Budget: existing TestKit assertions only; no cache disabling, production behavior, API, flag, dependency or task change. Qualify the complete Dependeasy suite against the warm cache.

Weekly-review fixture acceptance: fractional-minute fixture endpoints cross midnight during the full API suite, so the daily whole-minute totals sum to 29 while the unbroken session is 30. Align the fixture clock to minute boundaries and exercise the unchanged review projection across midnight in UTC and Asia/Kolkata. Budget: the existing private fixture clock, two private fixture arguments for deterministic boundary cases and two owner regressions; no production rounding, schema, clock, public API, dependency or weaker total assertion. Qualify the complete API suite and its two type checks.

Table comparison acceptance: the full engine report measures load rising from 10.12 to 13.47 across three rounds. The candidate always runs second; a ratio of independent medians then compares different rounds. Alternate order through one private pairing helper reused by wheel and key measurements, and take the median of candidate/reference ratios within the actual measured pairs. Retain three rounds, 600 measured wheel frames, warm-up, the 1.1 relative limit, absolute 120/60 Hz budgets, navigation, scroll distance and zero-rebuild assertions, and report every raw round plus the normalized comparison. Budget: existing benchmark orchestration and derived statistics only; no production hot-path change, retry, skipped check, new option, dependency, larger budget or deadline. Qualify the existing collection/perceptual parity owners, then the unfiltered engine and composite suites.

Composite native-memory acceptance: Manna's Release simulator-framework link exhausts the 8 GiB Gradle heap in Kotlin/Native devirtualization analysis. The load-routing wrapper also overwrites repository JVM properties, creating a second authority. Raise the three roots' Gradle maximum heap to 12 GiB and let their checked-in Gradle properties supply both Gradle and Kotlin budgets on either Mac. Retain other projects' current defaults, command-line overrides, three workers, load routing and Kotlin's 8 GiB compiler budget. Budget: bootstrap properties, removal of duplicate wrapper defaults and matching operational documentation only; no native optimizer disabling, cache eviction, retry policy, dependency, task or public API. Qualify the failed Release framework link and then the unfiltered BestBuds composite scan, recording memory and routing evidence.
