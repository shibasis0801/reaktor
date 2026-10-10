# Dependeasy

Dependeasy declares build artifacts and their dependencies. Gradle schedules them;
Kotlin, pnpm/Vite, SwiftPM, and CMake keep ownership of compilation and dependency resolution.
Gradle declares delivery entrypoints; generated Dagger functions and product release
tools invoke those declarations.

See [DESIGN.md](DESIGN.md) for the architecture, domain status, and remaining work.

## Module declarations

Shared modules apply one plugin bundle and declare their platforms and dependencies together:

```kotlin
import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.apiReaktor

plugins { id("dev.shibasis.dependeasy.compose-library") }

dependeasy {
    module("example.shared") {
        common {
            dependencies { apiReaktor("ui", "auth") }
            testDependencies { implementation(Versions.Kotlin.KtorMock) }
        }
        web {
            moduleName = "example-kt"
            exportDirectory = "example-kt"
        }
        android {}
        apple {}
        jvm { bytecode = 21 }
        framework(exports = listOf("dev.shibasis:reaktor-auth"))
    }
}
```

`library` supplies the multiplatform compiler, serialization and KSP plugins.
`compose-library` also supplies Compose and its compiler. Application, browser and
JVM consumers use `compose-application`, `browser`, `jvm`, `compose-jvm` or
`spring-application` respectively.
Plugin bundles configure builds without adding Compose runtime dependencies to headless modules.
Only declared platforms are created. Android uses the module namespace and shared
SDK/bytecode defaults. JVM compilation uses Java 25; `bytecode = 21` retains an explicit
compatibility contract. Test dependencies live beside each platform's production dependencies.

For exceptional compilations, source-set hierarchies or binaries, use `kotlin { ... }`
inside `module`, or ordinary Kotlin/Gradle configuration inside the same Dependeasy block. The declaration
uses ordinary Gradle targets and task providers, which remain available for low-level configuration.
`compose(stability = "compose-stability.conf", reports = "product.composeReports")`
configures compiler stability and optional reports. `testClasses()` explicitly selects
`*Test` and `*Tests` classes; pass patterns when a module uses another naming convention.

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
    val bundle = frontend.script("bundle", "dist")
    val native = cmake("renderer") {
        source = "native"
        target = "renderer"
    }
    val swift = swiftPackage("tools", "apple/tools").build()

    dag("desktop") {
        val renderer = node(native)
        val tools = node(swift).after(renderer)
        target("buildDesktop", node(bundle).after(tools))
    }
}
```

`desktopBuildPlan` prints the graph without compiling or installing anything.
`buildDesktop` runs the selected dependency closure. Ordering is explicit; declaring
an adapter does not attach it to `help` or to unrelated builds. Cycles and duplicate
node IDs fail during declaration. Use project-qualified IDs for foreign tasks.

For a web component, `frontend.build("buildWeb", checks = listOf(generateAssets))`
runs declared checks and generators before typechecking, then bundles with Vite.
The component tracks its source tree, including generated assets; these producer
dependencies keep that input tracking correct on clean and incremental builds.

For file dependencies, carry the producer with its output:

```kotlin
import dev.shibasis.dependeasy.dag.artifact

dependeasy {
    val generated = tasks.named<GenerateSources>("generateSources")
    val sources = generated.artifact { it.outputDirectory }
    dag("generatedWeb") {
        target("buildGeneratedWeb", node(bundle).consumes(sources))
    }
}
```

Here `GenerateSources` is the build's own task type with a `DirectoryProperty`.
`consumes` adds both the producer dependency and a relative-path file input.
An `after` edge only orders work; it does not imply which files are consumed.

## Ownership and source layout

| Package | Responsibility |
| --- | --- |
| `dag` | Lazy task nodes, typed artifacts, edges, cycle checks, readable plans |
| `workspace` | Build entrypoints, included-build exports, package command inventory, generated aliases |
| `dagger` | Gradle-derived Dagger module generation and drift verification |
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

The graph refers to Gradle task providers. `buildGraph` exports the workspace for
Hangar's Build source, retaining build ownership and declared provenance. Build
execution does not depend on the product graph. There is no second scheduler or resolver.

## Toolchain contract

[`ToolchainVersions.kt`](src/main/kotlin/dev/shibasis/dependeasy/toolchain/ToolchainVersions.kt)
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
* Swift packages build with automatic resolution disabled. Resolve dependencies
  explicitly and commit `Package.resolved` before building a package with dependencies.
* CMake uses a distinct build directory for each declared target/configuration.
  Sources, headers, configure arguments, tool identity, and declared dependencies
  participate in incremental checks. Changed configuration clears incompatible
  CMake configuration files. Project native dependencies link their existing
  libraries instead of recursively rebuilding the same source.
* Native source revisions are full commit SHAs in
  [`NativeSources.kt`](src/main/kotlin/dev/shibasis/dependeasy/toolchain/NativeSources.kt).
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

Ordinary `apple { }` modules produce Kotlin libraries. Only an app or deliberate
umbrella module declares an exported framework:

```kotlin
dependeasy {
    module("example.shared") {
        apple { }
        framework(name = "app", exports = listOf("dev.shibasis:reaktor-auth"))
    }
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

The common web build needs one declaration after its component:

```kotlin
web.build("buildWeb")
```

`types`, `vite`, `bindings`, `script`, and their ordinary task providers remain available
for exceptional pipelines. The root plugin does not need a list of `apply false` plugins.

Build and runner entrypoints share one workspace declaration:

```kotlin
dependeasy {
    workspace {
        includedBuild("reaktor")
        target("appChecks") { tasks(":app:jvmTest", ":engine:jvmTest"); effect = "verify" }
        external("iosRelease") { fastlane("ios", "build_release"); platform = "macos" }
        dagger()
        packageScripts()
    }
}
```

Run `generateDagger generatePackageScripts` after changing delivery declarations;
Dagger source and `dagger.json` are generated together, including the Kotlin-owned
engine pin. CI reads that generated version instead of keeping another literal.
`verifyDagger` detects stale checked-in output. `buildGraph` imports active package
scripts directly from authored manifests. Generated aliases point back to Gradle;
manifest dependencies and other authored commands remain in `package.json`.
With `packageScripts()`, frozen pnpm installation depends on alias generation.
Generation reads the declared targets without executing them, so frontend builds
refresh their aliases without recursively building or installing the workspace.
Their generated target metadata carries worker directories and environment tiers,
so Hangar can inspect and bind delivery without parsing a shell selector.
Mac-only entrypoints remain visible in the graph and are omitted from Linux Dagger
functions. External recipes are metadata and runner commands, rather than Gradle
tasks that recursively start Gradle. See [DESIGN.md](DESIGN.md) for the export contract.

Fastlane uses the product's locked Gemfile through the shared launcher. Product
lanes invoke Gradle delivery entrypoints for web publishing, so they inherit the
same bundle prerequisites, pinned JavaScript tools and load routing.

JavaScript command launch and cancellation live in `javascript/kernel/process`. Its
environment-file adapter reads literal exports without evaluating shell expressions
or changing the parent environment. Products select their own files and commands;
the same process helper also launches Wrangler and forwards cancellation before
temporary configuration is removed.

Worker artifact transfer carries `buildGraph` back for local Hangar and preserves
relative Kotlin export links. Generated Dagger source and metadata transfer on
success, without its SDK or dependency caches.
The generated alias report merges only owned scripts and metadata into the local
manifest, preserving authored edits and dependencies made while a worker ran.

Kotlin exports participate in a JavaScript component's producer dependencies:

```kotlin
dependeasy {
    javascript("web", "targets/web") {
        kotlinLibraries("app")
        kotlinLibraries("reaktor-auth", "reaktor-graph", build = "reaktor")
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

Set `exportDirectory = "product-kt"` inside `web { }` when the browser library
has a product-owned export directory. Node distributions use separate build outputs,
so unrelated consumers do not acquire undeclared dependencies on Node copying.

Headless modules can declare their runtime boundary without repeating resolution
and verification code:

```kotlin
dependeasy {
    dependencyBoundary("verifyHeadlessRuntime") {
        forbidGroupPrefixes("androidx.compose", "org.jetbrains.compose")
        forbidModules("reaktor-ui")
        reason.set("The runtime must remain independent of UI libraries.")
    }
}
```

The check participates in `check`, resolves only when executed, and declares its
dependency metadata and artifacts as inputs. Select a different configuration with
`configuration = "jsCompileClasspath"`; ordinary task properties remain available.

Generated Kotlin sources carry their own output directory and inputs. Registration
adds the producer to multiplatform `commonMain` or JVM `main` automatically:

```kotlin
dependeasy {
    val developerTools = localProperty("product.devTools").map(String::toBoolean)
    kotlinObject("generateFlags", "example", "BuildFlags") {
        boolean("developerTools", developerTools)
        string("revision", sourceRevision())
    }
}
```

`nodeScript` returns a typed command task for Node generators. `sourceIdentity`
returns a typed task whose source groups define the files represented by a digest,
and registers its output as JVM resources. `jvmClasspath(name, output)` writes a
multiplatform JVM test classpath through a typed task with declared inputs and outputs.
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

JVM entrypoints share the module's declared runtime. A named compilation associates
with `main`; test probes and command generators reuse that same classpath.

```kotlin
dependeasy {
    jvm { dependencies { implementation(Versions.Tooling.Clikt) } }
    jvmEntryPoint("probe", "example.ProbeKt", compilation = "probe", verify = true)
    jvmLauncher("launcher", "example.MainKt", "bin/example")
    jvmDistribution("applicationDist", "example.MainKt", "example")
}
```

`desktopApplication` owns the Compose/JDK packaging defaults and accepts the
ordinary Compose application DSL. Package formats, heap settings, ports and JDK
modules remain visible product choices. `packagedRuntimeTest` uses the actual
packaged jars and module list, so missing packaged dependencies cannot be hidden
by the development runtime. `springImage` selects the pinned builder, Java 25 JRE
and shared runtime policy; its task receiver remains the low-level escape.

Kotlin browser distributions first write isolated, tracked build outputs.
`exportKotlinLibrary` synchronizes compiled files into the declared export directory
and supplies a relative pnpm dependency link. Its output snapshot excludes that
link, whose target may contain dependency cycles; missing links still invalidate
the export. JavaScript consumers and manifests depend on the export producer.
Direct Kotlin distribution requests also finalize their corresponding export.
