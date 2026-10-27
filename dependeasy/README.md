# Dependeasy

Dependeasy declares build artifacts and their dependencies. Gradle schedules them;
Kotlin, pnpm/Vite, SwiftPM, and CMake keep ownership of compilation and dependency resolution.
Gradle declares delivery entrypoints; the shared Dagger runner and product release
tools invoke those declarations.

See [DESIGN.md](DESIGN.md) for the architecture, domain status, and remaining work.

## Module declarations

A shared module applies one plugin bundle and declares its targets, dependencies and
outputs in one `library` call:

```kotlin
import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.handles.Reaktor
import dev.shibasis.dependeasy.model.*

plugins { id("dev.shibasis.dependeasy.compose-library") }

library(namespace = "example.shared", targets = Targets.All, ui = Compose(stability = "compose-stability.conf")) {
    dependencies {
        common { api(Reaktor.Ui, Reaktor.Auth) }
        commonTest { implementation(Versions.Kotlin.KtorMock) }
    }
    web(module = "example-kt")
    appleFramework(exports = listOf(Reaktor.Auth))
    val unit by tests(Suite.Jvm)
}
```

`library` supplies the multiplatform compiler, serialization and KSP plugins.
`compose-library` also supplies Compose and its compiler. Application, browser and
JVM consumers use `compose-application`, `browser`, `jvm`, `compose-jvm` or
`spring-application` respectively.
Plugin bundles configure builds without adding Compose runtime dependencies to headless modules.
Only the platforms in `targets` are created. Android uses the module namespace and shared
SDK/bytecode defaults. JVM compilation uses Java 25; `bytecode = 21` keeps an explicit
compatibility contract. Test dependencies sit beside each platform's production
dependencies, in `commonTest`, `jvmTest` and the other test blocks.

For exceptional compilations, source-set hierarchies or binaries, use the ordinary
`kotlin { ... }` extension in the same build script. The declaration uses ordinary
Gradle targets and task providers, which remain available for low-level configuration.
`ui = Compose(stability = "compose-stability.conf", reports = true)` configures compiler
stability and optional reports.

A web export can declare `manifest("generateManifest", script, output, inputs...)`.
Dependeasy tracks the export and workspace inputs, orders manifest generation after
production distribution, and ensures direct manifest requests build their producer.

## A readable build graph

Apply `dev.shibasis.dependeasy.pipeline` for a standalone build. The library and
application plugins also expose the `dependeasy` extension.

```kotlin
plugins { id("dev.shibasis.dependeasy.pipeline") }

dependeasy {
    val frontend = javascript("frontend", "web")
    val native = cmake("renderer") { source = "native" }
    workspace {
        val buildDesktop by artifacts { include(node(native), frontend.build("buildWeb")) }
    }
}
```

`./gradlew buildDesktop --dry-run` lists the selected tasks without running them.
`exportBuildModel` writes the same graph to `build/dependeasy/model.json`, which
Hangar, the CLI and MCP read. Ordering is explicit; declaring an adapter does not
attach it to `help` or to unrelated builds.

`frontend.build("buildWeb")` typechecks, then bundles with Vite. Its `checks` and
`generators` parameters run declared checks and generators first. The component
tracks its source tree, including generated assets; these producer dependencies keep
that input tracking correct on clean and incremental builds.

For file dependencies, carry the producer with its output:

```kotlin
import dev.shibasis.dependeasy.handles.*

dependeasy {
    val frontend = javascript("frontend", "web")
    val generated = tasks.register<GenerateSources>("generateSources")
    val bundle = frontend.vite()
    node(bundle).consumes(artifact(generated) { it.outputDirectory })
    workspace {
        val buildGeneratedWeb by artifacts { include(frontend.build("buildWeb", bundle = bundle)) }
    }
}
```

Here `GenerateSources` is the build's own task type with a `DirectoryProperty`.
`consumes` adds both the producer dependency and a relative-path file input.
An `after` edge only orders work; it does not say which files are consumed.

## Ownership and source layout

| Package | Responsibility |
| --- | --- |
| `components` | Typed component declarations: `library`, `webApp`, `workerApp`, `workers`, `androidApp`, `appleApp`, `desktopApp`, `server`, `cli`, `daggerPipeline` |
| `handles` | Typed handles for Reaktor modules, project outputs, nodes and artifacts |
| `workspace` | Build entrypoints, included-build exports, package command inventory, generated aliases |
| `dagger` | Kotlin-owned container toolchain and a shared model-resolved runner |
| `fastlane` | Frozen Bundler launcher and shared filesystem/artifact helpers; product signing and release policy stay in product domains |
| `cloudflare` | Worker fleet dependencies and immutable Wrangler configuration merging |
| `plugins` | Entry points, compiler bundles and the small declaration API |
| `module` | Platform declarations, JVM compilations, launchers, distributions and probe classpaths |
| `compose` | Compiler stability and report configuration |
| `repositories` | Public dependency sources, filtered private packages, explicit local overrides |
| `codegen` | Declared protobuf compiler/plugin inputs and configuration-cache compatible generation |
| `provenance` | Deterministic source identities; optional local build timestamps |
| `desktop` | Application packaging, probes against packaged jars, runtime leases and class-sharing archives |
| `server` | Spring plugin conventions, native Gradle BOMs and pinned buildpack images |
| `interop` | Module-owned C++ exports, mobile TypeScript bundles and native contract checks |
| `verification` | Dependency boundaries, physical-device policy, bounded test state isolation |
| `configuration` | Provider-backed product properties |
| `process` | Declared command inputs, outputs, environment, tool identity |
| `web` | Frozen pnpm workspace, Vite, TypeScript, Karakum, Kotlin/JS bridge |
| `darwin` | Kotlin Apple targets, explicit frameworks, SwiftPM commands |
| `native` | CMake declarations and Android/Darwin integration |
| `settings` | Native source preparation at pinned commits |
| `tasks` | CMake execution, prefab extraction, cinterop definitions, size reports |
| `files` | Bounded cleanup independent of the runtime libraries |

The graph refers to Gradle task providers. `exportBuildModel` writes it to
`build/dependeasy/model.json` with build ownership and declaration sites. Build
execution does not depend on the exported model. There is no second scheduler or resolver.

## Toolchain contract

[`ToolchainVersions.kt`](gradle/src/main/kotlin/dev/shibasis/dependeasy/toolchain/ToolchainVersions.kt)
owns the shared versions consumed by Reaktor, BestBuds, Manna, and this included build.
It is ordinary Kotlin source. Plugin implementations import it directly. The initial
plugin bootstrap, CMake, and the Linux SDK installer read its literal constants;
there is no generated Kotlin version file, properties version catalog, or TOML catalog.

The current contract selects Kotlin 2.4.21, Gradle 9.7, AGP 9.1.1, NDK r30,
C++23, Swift language mode 6, TypeScript 7.0.2, pnpm 12.10.1, and Node 24.21.0 LTS.
Swift 6.4 is recommended; Apple integration also builds with Xcode's Swift 6.3.
Install the Android CMake version in the contract; host CMake may be newer.
These three workspaces run Gradle and compile JVM tools with Java 25. Android
bytecode targets Java 21. Dependeasy itself emits Java 21 bytecode so it can still
be loaded by a consumer running Java 21.

Karakum is pinned in the same Kotlin toolchain contract.
TypeScript 7 supplies the native CLI through the `typescript-native` package alias.
The Node and pnpm executables are pinned, checksum verified, and shared under
`~/.gradle/dependeasy/tools`. Kotlin generates package metadata into the same workspace;
it does not run its own npm or Yarn installer.

Exported Kotlin packages retain a relative `node_modules` link to their compiler
package's pnpm installation. The export shares its dependency context instead of
copying packages or relying on workspace-root hoisting. Export synchronization
preserves that link; worker artifact transfer carries the link without copying caches.

TypeScript 6.0.3 supplies the JavaScript compiler API required by tools such as
Karakum. [`javascript/cli/typecheck.ts`](javascript/cli/typecheck.ts) selects the native CLI
explicitly, avoiding competing `tsc` npm binary links.

The root pipeline plugin installs shared dependency repository conventions. Maven Central
and Google supply public artifacts; JitPack and GitHub Packages are filtered to
their package groups. Obsolete experimental and snapshot repositories are absent.
Maven Local requires `-Pdependeasy.useMavenLocal=true`. Add an exceptional source with
`dependencyRepositories { maven { url = uri("https://example.org/maven") } }`.
Private credentials come from Gradle properties or runtime environment variables;
existing product credential property names remain supported.

## Incremental builds and reproducibility

* pnpm installs with `--frozen-lockfile` from the repository-owned `pnpm-lock.yaml`. Workspaces share one root
  installation. Installation tracks workspace and nested `file:` package manifests,
  `.npmrc`, local package archives, and the lockfile; bundling also tracks linked
  source files. Kotlin package metadata precedes installation; compiled exports
  follow installation and precede JavaScript consumers. Updating dependencies
  is a separate, deliberate lockfile operation.
  Run `./gradlew pnpmLock` to update it with the pinned tools after generating
  Kotlin package metadata. Ordinary builds keep frozen installation; this explicit
  task updates the lockfile without installing packages or running lifecycle scripts.
  Component commands, Kotlin's pnpm bridge and Node scripts inherit the same
  verified toolchain environment. pnpm `run` and `exec` refuse stale dependencies
  instead of installing implicitly; custom Node scripts must declare the files they consume.
* Swift packages build with automatic resolution disabled. Resolve dependencies
  explicitly and commit `Package.resolved` before building a package with dependencies.
* CMake uses a distinct build directory for each declared target/configuration.
  Sources, headers, configure arguments, tool identity, and declared dependencies
  participate in incremental checks. Changed configuration clears incompatible
  CMake configuration files. Project native dependencies link their existing
  libraries instead of recursively rebuilding the same source.
* Native source revisions are full commit SHAs in
  [`NativeSources.kt`](gradle/src/main/kotlin/dev/shibasis/dependeasy/toolchain/NativeSources.kt).
  Checkout preparation is a task dependency, never a settings-time clone or build.
  Existing checkouts are verified and local tracked edits are rejected rather than reset.
* Hermes' host compiler is built with the macOS SDK even when an iOS Xcode build
  invoked Gradle. It is not rebuilt separately for every iOS module.
* Vite owns browser and Hermes bundles. Shared helpers under `javascript/kernel/content`
  also compile authored Markdown and server-rendered React pages without a second
  site framework or bundler. Product navigation, access control and content stay
  with the product. Generated static-site temporary files are excluded from inputs.
* Command and CMake tasks support local incremental execution and configuration
  cache. Their output trees contain host paths, so they do not claim a relocatable
  remote build cache. Prefab extraction and artifact size reports are cacheable.

The DAG is only as reproducible as a task's declared inputs. Custom tasks must
declare their own tools, environment, files, and outputs. Add external source trees
with `javascript.sources(...)` or CMake's `inputs` when outside the adapter's root.

## Apple builds

A library's Apple targets produce Kotlin libraries. Only an app or a deliberate
umbrella module declares an exported framework:

```kotlin
library(namespace = "example.shared", targets = Targets.All) {
    appleFramework(exports = listOf(Reaktor.Auth))
}
```

Xcode's first script phase calls `:app:embedAndSignAppleFrameworkForXcode` and links
the generated framework directly. Its SDK and configuration select the Kotlin slice.
Frameworks link the system SQLite/C++ libraries. Release tasks remain executable
when explicitly requested.

Google Sign-In and Firebase are exact-version SwiftPM dependencies of the Swift
host. Adapters in the owning modules’ `src/iosMain/swift` source sets only expose third-party SDK calls through Kotlin protocols. Kotlin owns token refresh, validation, user defaults, Firebase initialization, and notification policy. The host injects the adapters before starting Kotlin. Third-party Apple SDK headers no longer
require CocoaPods or generated Kotlin bindings across every library.

`reaktor-apple-export` is a small explicit umbrella for core/native validation.
Its debug XCFramework includes device and Apple Silicon simulator slices. Building
a simulator slice does not require starting a simulator.

## Verification

From the Reaktor root, run `./gradlew -p dependeasy test`. Gradle TestKit exercises the graph
and real pnpm, SwiftPM, and CMake commands, including configuration-cache reuse,
changed inputs, missing outputs, and isolation from unrelated work.

See [VALIDATION.md](VALIDATION.md) for checked scope and outstanding limitations.
Builds use the configured load router; device installation and UI checks stay on
the development Mac. Do not start emulators or simulators.

## Keep product scripts declarative

A web app is one component declaration in its own project:

```kotlin
plugins { id("dev.shibasis.dependeasy.web-app") }

webApp(kotlin = listOf(kotlinJsLibrary(projects.app)), tests = Vitest())
```

Its bundle, generators and checks are typed: `WebBundle.Vite`, `WebBundle.Node` or
`WebBundle.Package`; `WebGenerator.Node` and `WebGenerator.WorkerTypes`; `Vitest`;
and `generate`, `check` and `tests` inside the `webApp` block. The low-level
`javascript(...)` component keeps `build`, `types`, `vite`, `bindings` and their task
providers for exceptional pipelines. The root plugin does not need a list of
`apply false` plugins.

A Playwright suite for the app goes in the same block:
`browserTests(Playwright("playwright.config.ts"))` runs after the app's bundle and gets
its own alias. It is not one of the app's checks, so `pr` does not run it. `environment`
sets variables the suite reads, and `run = Run.onDesktopSession()` keeps a headed run on
this Mac. At the root, `nodeTests` runs Node test files. Pass `sources` when a test reads
files outside its own folder, and `live = true` when it measures a deployed system, so it
runs every time.

Phone suites go in a `deviceTests(apps, applicationId)` block. `suite(DeviceSuite(...))`
runs its flows on one phone per run, and `--platform` and `--flow` choose the phone and
the flow. `exchange(DeviceExchange(suite, receive, send, token, sendAfter))` drives two
phones in one run. For each ordered pair, one phone runs `receive`; the other starts `send`
after `sendAfter`, and both flows get the same fresh value in the `token` variable. The
receiving phone must still be running when the send starts, and every pair must pass.

Build and runner entrypoints share one workspace declaration:

```kotlin
workspace {
    val framework = include(Reaktor.build)
    val pr by verify(role = Workspace.Verify) {
        include(verification(projects.app, Verification.Jvm), framework.verify)
    }
    packageScripts()
}
```

The pipeline component declares `daggerPipeline()` in its own build file.

`generateDagger` writes the engine pin and toolchain contract. The shared runner resolves
its requested node and explicit environment from a fresh Kotlin build model inside
the container. Adding a node does not change `dagger/pipeline.json`; there is no
reference snapshot or drift-verification task. Kotlin checks host requirements and
required secret names. Dagger returns the run receipts and declared outputs as a directory.

Local CI starts through `reaktor build run pr` in each repository. No GitHub Actions
workflow is required. `exportBuildModel` imports active package scripts directly from
authored manifests. Generated aliases point back to Gradle. Manifest dependencies stay
in `package.json`, and so do authored scripts that start a dev server or open an ops
tool. A script that builds, tests, deploys or releases is a model node with a
generated alias. Each node gets one plain alias, in the package nearest to its
project. An alias that adds `before` steps, settings or device options keeps its
own name beside that plain alias.
With `packageScripts()`, frozen pnpm installation depends on alias generation.
Generation reads the declared targets without executing them, so frontend builds
refresh their aliases without recursively building or installing the workspace.
Their generated target metadata carries worker directories and environment tiers,
so Hangar can inspect and bind delivery without parsing a shell selector.
Mac and physical-device entrypoints remain visible in the graph. The Kotlin container
resolver refuses them with a host requirement; the native local gate runs them on
their declared host. External recipes remain metadata and runner commands. See [DESIGN.md](DESIGN.md) for the export contract.

Fastlane uses the product's locked Gemfile through the shared launcher. Product
lanes invoke Gradle delivery entrypoints for web publishing, so they inherit the
same bundle prerequisites, pinned JavaScript tools and load routing.
Each store release is a `release(output, lane)` node inside `delivery(after = pr)`.
It runs after the gate, and so do the producers of the outputs it consumes, except
generated contracts such as the release plan. A release therefore never consumes an
output the gate itself builds; that would make the gate depend on itself. When a
build would schedule that, dependeasy stops it before any task runs and names the
release, the producer and the gate. A cross-platform lane such as
`ReleaseLane.Internal` takes a list of outputs.

JavaScript command launch and cancellation live in `javascript/kernel/process`. Its
environment-file adapter reads literal exports without evaluating shell expressions
or changing the parent environment. Products select their own files and commands;
the same process helper also launches Wrangler and forwards cancellation before
temporary configuration is removed.

Worker artifact transfer carries the exported model back for local Hangar and preserves
relative Kotlin export links. Generated Dagger source and metadata transfer on
success, without its SDK or dependency caches.
The generated alias report merges only owned scripts and metadata into the local
manifest, preserving authored edits and dependencies made while a worker ran.

Kotlin exports participate in a JavaScript component's producer dependencies:

```kotlin
dependeasy {
    javascript("web", "targets/web") {
        kotlinLibraries(kotlinJsLibrary(projects.app))
        kotlinLibraries(kotlinJsLibrary(Reaktor.Auth), kotlinJsLibrary(Reaktor.Graph))
    }
}
```

The frozen pnpm installation precedes compilation and export; JavaScript commands
depend on those export producers. A product does not need another
aggregate export task or a Gradle command hidden inside a package script.
The component also declares its build, verification and library entrypoints as web
artifact producers in the generated build-layout report. Worker transfers consume
that declaration, including for custom task names; no product-specific transport
configuration is needed.

Kotlin/JS exports are owned outputs under each module’s
`build/dependeasy/kotlin/library`. JavaScript consumers use the `KotlinJsLibrary`
variant, which carries its producer and file inputs. Node distributions use separate
build outputs.

Headless modules can declare their runtime boundary without repeating resolution
and verification code:

```kotlin
library(namespace = "example.runtime", targets = Targets.Jvm) {
    val verifyHeadlessRuntime by boundary(
        groupPrefixes = listOf("androidx.compose", "org.jetbrains.compose"),
        modules = listOf("reaktor-ui"),
        reason = "The runtime must remain independent of UI libraries.")
}
```

The check participates in `check`, resolves only when executed, and declares its
dependency metadata and artifacts as inputs. Select a different configuration with
`configuration = ConfigurationRef.JavaScriptCompileClasspath`. A pure JVM component
accepts the existing immutable `KernelSpec.DependencyBoundary` and a `Verification`
role, publishing the same check identity while including it in `componentChecks`.

Generated Kotlin sources carry their own output directory and inputs. Declare the
object once, for example in `buildSrc`, and generate it in the component that owns it.
Registration adds the producer to multiplatform `commonMain` or JVM `main`
automatically:

```kotlin
val DevTools = BuildOption.boolean("example.devTools")
val BuildFlags by GeneratedObject("example") {
    val developerTools by flag(DevTools)
    val sourceRevision by revision()
}

library(namespace = "example", targets = Targets.All) {
    val generateBuildFlags by generated(BuildFlags)
}
```

`nodeScript` returns a typed command task for Node generators. Inside a JVM
`library` or `server`, `val buildIdentity by identity(fileName = "build.properties",
sources = listOf(IdentitySource("app", ".", listOf("src/main"))))` declares a named
resource producer. Immutable source/revision data and the generator are recorded
in the canonical graph; the component build includes it and JVM resources consume
its output. Identity hashing, git reads and resource wiring belong to the binding.
Inside `library`,
`val probeClasspath by classpath(output = "reports/probe-classpath.txt")` publishes
the JVM test classpath with canonical ownership and declared inputs and outputs.
`stableRuntime` retains desktop jars without copying downloaded dependencies.
`retainJavaLauncher` restores the background-process launcher to a stripped runtime
image and optionally prepares its base class archive. `sharedClassArchive` derives
an application archive path from access flags and packaged jar content. App heap
settings, supported package formats, and required JDK modules remain product choices.
Test tasks can use `isolatedDirectory` for bounded cleanup. These mechanisms live
in their domains; product selections and application behavior stay in the product.

The common adapters return ordinary Gradle task providers. Configure their declared
arguments, inputs, outputs, and environment directly for exceptional cases, or
compose a product-owned task through `dag`. An escape does not require extending
a giant options object or adding another build runner.

## JVM programs and packaging

JVM programs resolve their compiled `@Entry` declaration and share the module's
runtime. Explicit source ownership separates programs in the same compilation.
Launchers and distributions consume the program's compiled entry through the graph.

```kotlin
library {
    val application by program(sources = FileSet(listOf("src/main/kotlin/example/application")))
    val launcher by launcher(application, output = "bin/example")
    val applicationDist by distribution(application, executable = "example")
    dependencies { implementation(Versions.Tooling.Clikt) }
}
```

`desktopApplication` owns the Compose/JDK packaging defaults and accepts the
ordinary Compose application DSL. Package formats, heap settings, ports and JDK
modules remain visible product choices. Inside `desktopApp`,
`val verifyPackage by packagedRuntime()` resolves the compiled `@Entry` probe and
consumes the published desktop distribution through the build graph. It uses the actual
packaged jars and module list, so missing packaged dependencies cannot be hidden
by the development runtime. `server(image = "registry/application:tag") { dependencies { ... } }`
owns the Spring component, pinned builder, Java 25 JRE and shared runtime policy,
and publishes its build and verification roles through the same workspace graph.

Kotlin browser distributions first write isolated, tracked build outputs.
`exportKotlinLibrary` synchronizes compiled files into the declared export directory
and supplies a relative pnpm dependency link. Its output snapshot excludes that
link, whose target may contain dependency cycles; missing links still invalidate
the export. JavaScript consumers and manifests depend on the export producer.
Direct Kotlin distribution requests also finalize their corresponding export.

Typed library tests declare JVM and Android unit suites separately. `TestDirectory` resolves a named property beneath isolated `State` or the original `HostHome`; state children are created by the existing reset action. Android unit output ownership comes from AGP public variants and task names, and runs on the build host.

Pure JVM components declare `val unit by tests(environment = …)`. Their binding keeps top-level test discovery and headless JVM arguments. `TestInput.rerunWhen` records the exact environment value that requires a fresh run even when inputs match; its default only tracks the input for caching.
