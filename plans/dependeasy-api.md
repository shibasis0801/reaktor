# Dependeasy API: declarative orchestration, discoverable kernels

Written on 14 October 2026. This is the API that WP12, WP13 and WP16 of `dependeasy-handoff.md` implement, and that WP20–WP26 of `delivery-handoff.md` build on. Where those packages and this document differ on the shape of the API, this document wins. This file is not published.

## 1. What we want

- **Readable:** opening any project's build file tells you what that project is, what it consumes, what it produces and what you can run, without reading plumbing.
- **Declarative:** the orchestration reads like Compose or React. It is a tree of typed declarations, composed from functions, with values flowing down.
- **Navigable:** from any declaration, command-click goes to its DSL function, then its spec, then its kernel, then the tool it calls. Every hop is a Kotlin symbol, never a string, registry, reflection or convention you have to know.
- **Powerful kernels:** the kernels do all the real work. They are typed and reusable outside Gradle by the CLI, MCP and Hangar.

## 2. Why today's scripts read as plumbing

Measured in the current scripts and `DependeasyExtension`:
- **Names as strings.** Almost every element takes a task name as its first argument: `kotlinObject("generateBuildFlags", …)`, `jvmEntryPoint("desktopMcpE2e", …)`, `javascript("coreTypeScript").library("buildCoreTypeScript", …)`. The name is a string key into Gradle's registry, so nothing navigates to it.
- **References as strings.** Main classes (`"ai.bestbuds.reaktor.MainKt"`), compilations (`"test"`, `"desktopMcpE2e"`), configurations (`"kspCommonMainMetadata"`), lists of test class names, and system property names.
- **Foreign DSLs passed straight through.** `androidApplication(id) { ApplicationExtension }`, `springImage { BootBuildImage }` and `desktopApplication(main) { JvmApplication }` hand the script AGP, Spring and Compose internals. After that point the script is configuring tools, not declaring a component.
- **One grab-bag receiver.** `dependeasy {}` offers 45 members that mix component kinds (`module`, `androidApplication`, `springImage`), task helpers (`command`, `nodeScript`, `jvmClasspath`), code generation, tool access and the workspace. Autocomplete therefore shows everything everywhere.
- **Presence by empty block.** `android {}` and `apple {}` switch a target on because the block exists.
- **Escape hatches in scripts.** `tasks.withType<Test>().configureEach`, `tasks.named<Test>(…)`, `tasks.register<Test>("windowTest")`, `doLast`, `System.getProperty` and `os.name` all appear in the desktop script.

## 3. The design in one page

1. **A build file is a component.** Each project applies one component plugin and declares one component: `library`, `androidApp`, `appleApp`, `desktopApp`, `webApp`, `server`, `workers`, `deviceTests`, `loadTests`, `docs` or `observability`. The root applies `workspace` and declares only orchestration.
2. **Declarations are values.** Every declaration returns a typed handle. You refer to it by passing the value, never by name. Passing an output into another declaration creates the dependency, the way props flow in React.
3. **Names come from Kotlin identifiers.** `val pr by verify { … }` names the node `pr`, as Gradle's own `by registering` does. Renaming the variable renames the node, and command-click on any use goes to the declaration.
4. **Parameters, not setters.**
   - Required facts are required parameters.
   - Defaults are parameter defaults that show up in the IDE's parameter hints.
   - Content lambdas hold children: slots, as in Compose.
   - Each scope offers only what makes sense there, enforced by `@DslMarker` receivers, the way `RowScope` and `ColumnScope` work.
5. **Strings are data, never references.** A string may hold an external fact: a bundle id, a namespace, a file path inside the project, a URL. A string may not point at anything inside the build: a task, project, configuration, source set, option, output, test class or main class.
6. **One element is one spec, one kernel, named in place.** Each DSL function builds an immutable spec and emits it with its kernel, written as a direct reference: `emit(WorkerDeploySpec(…), WorkerDeploy)`. This is Compose's `ComposeNode(factory, update)` pattern. There are no registries, service loaders, reflection, `afterEvaluate`, `plugins.withId` reactions or `configureEach` by name.
7. **Cross-cutting concerns are a modifier chain.** Host, timeout, cache, environment matrix and required options attach through `run = Run.onWorker().timeout(20.minutes)`, in the same way as Compose's `Modifier`. Children inherit the chain unless they set their own, and the tree view shows the inherited values.
8. **Reusable pieces are plain functions.** A product's build logic defines `fun WorkspaceScope.storeRelease(…)` from the primitives. Such a function is a component boundary, like a composable: it is clickable, and the model records it in the node's call path.
9. **Sources declare themselves.** A build script cannot see its module's code, so references go the other way. An entry point carries `@Entry` in source, and a test that needs a window carries a tag annotation. The kernel resolves these from compiled classes, fails when there are zero or several entries, and records the resolved source location in the model. No main-class or test-class strings appear in scripts.
10. **The model is the rendered tree.** Every node records:
    - its parent and children;
    - its declaration site and component call path;
    - its spec, with each default marked as a default;
    - its kernel and its tools.

    `reaktor build tree` prints the tree, and Hangar's Build view draws it through Blueprint.
11. **The root is the product's story, not a list of build nodes.** Added 15 Oct. The root says, in order:
    - what the product is made of: `product(...)`;
    - how we know it works: `verify(parts)`;
    - how it ships: `delivery { ... }`.

    Story functions compose facts that the components own. For example, `verify(bestbuds)` includes each part's own declared verification, so the root never lists suites, flows, files or environment files. The lower primitives stay available for the rare case.

    `kernel-handoff.md` §3 has the full rules, the three no-magic tests (read, click, explain) and where each part of today's root goes.

## 4. What scripts look like

These examples use real projects. Names are illustrative; the shape is the point.

### 4.1 A library: `reaktor/reaktor-ffi`

Today: a `dependeasy {}` block with `module("…") { common {…}; android {}; apple {}; web {}; jvm {} }`, `nativeLibrary { libraryName = "ReaktorFFI" … }`, `interop { cppModule("api/Diagnostics.hpp", "…") }` and `dependencies { add("kspCommonMainMetadata", projects.reaktorCompiler) }`.

```kotlin
plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.ffi", targets = Targets.Android + Targets.Apple + Targets.Web + Targets.Jvm) {
    dependencies {
        common { api(Reaktor.Core, Reaktor.Flexbuffer) }
        processors(Reaktor.Compiler)
    }
    native(name = "ReaktorFFI", platforms = NativePlatforms.Mobile + NativePlatform.Host) {
        links(Native.Hermes, Native.Fbjni)
        uses(Reaktor.Flexbuffer)
    }
    interop {
        cpp(header = "api/Diagnostics.hpp", namespace = "reaktor::interop::diagnostics")
        typescript()
    }
}
```

### 4.2 An application module: `bestbuds/modules/app`

Today: `kotlinObject("generateBuildFlags", "ai.bestbuds.app.data.services", "GeneratedBuildFlags") { boolean("devToolsEnabled", …) }`, fixture paths built with `rootProject.file(...)`, a path into reaktor for `import-closure.mjs`, and three idioms for Reaktor dependencies.

```kotlin
plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "ai.bestbuds.shared", targets = Targets.All, ui = Compose(stability = AppCompose.Stability)) {
    dependencies {
        common { api(projects.design, Reaktor.Core, Reaktor.Ui, Reaktor.Auth, Reaktor.Graph, Reaktor.Service, Reaktor.Db) }
        web { api(Reaktor.Cloudflare) }
        android { api(Reaktor.NotificationFcm, Firebase.Crashlytics, Firebase.Messaging) }
        apple { api(Reaktor.NotificationFcm) }
    }
    generated(AppBuildFlags, DevAuthFixtures)
    web(module = "bestbuds-kt") { workerManifest(sources = projects.workers) }
    appleFramework(exports = Reaktor.Core + Reaktor.Auth + Reaktor.NotificationFcm + Reaktor.Telemetry)
    tests(Suite.Jvm, Suite.JavaScript) { javascript(runtime = CloudflareTestRuntime) }
}
```

`AppBuildFlags` and `DevAuthFixtures` are objects in BestBuds build logic, for example `object AppBuildFlags : GeneratedObject("ai.bestbuds.app.data.services") { val devToolsEnabled = flag(BestBudsOptions.DevTools) }`. Command-click on `AppBuildFlags` lands on them.

### 4.3 The desktop app: `bestbuds/targets/reaktorDesktop`

Today, 117 lines:
- `tasks.withType<Test>().configureEach` with ten forwarded system properties and an `os.name` check;
- a list of five test class names;
- `tasks.register<Test>("windowTest")`;
- a `doLast` that writes "verified";
- `jvmEntryPoint("hangarFlows", "ai.bestbuds.reaktor.HangarFlowProcessesKt", "test", …)`;
- the Compose `JvmApplication` DSL passed straight through.

```kotlin
plugins { id("dev.shibasis.dependeasy.desktop-app") }

desktopApp(packageName = BestBudsDesktop.PackageName, installers = Installer.Dmg + Installer.Msi + Installer.Deb) {
    dependencies { implementation(projects.engine, Reaktor.Mcp, Reaktor.Web, Reaktor.Ui) }
    runtime(heap = 768.megabytes, settings = BestBudsDesktop.Runtime)
    stableRuntime()

    val unit by tests(Suite.Jvm, environment = HangarTests.Isolated) { skip(TestTag.Window, TestTag.HangarFlow) }
    val window by tests(Suite.Jvm, run = Run.onDesktopSession()) { only(TestTag.Window); after(unit) }
    val flows by tests(Suite.Hangar, environment = HangarTests.Flows, run = Run.timeout(125.minutes)) { after(unit) }
    val packaged by check(PackagedRuntime) { after(flows) }
}
```

- `HangarTests.Flows` is an object in BestBuds build logic that lists its forwarded options as `BuildOption`s.
- The test kernel owns isolation, the host-OS JVM flags and result publishing.
- The MCP end-to-end program becomes its own small component (`targets/reaktorDesktop/e2e`) with one `@Entry`.

### 4.4 The BestBuds root

Today the root is 259 lines. It holds:
- 61 quoted paths and 35 string node names;
- 15 `packageAlias` calls, 15 `if`s, 6 loops and 14 file reads;
- 10 hand-built host requirements;
- five near-identical device-flow blocks.

Revised on 15 Oct. The previous draft removed the plumbing but still read as build-system nouns: suites per project, roles, and an `artifacts` list repeating the parts. This version reads as the product's story.

```kotlin
plugins { id("dev.shibasis.dependeasy.workspace") }

workspace(delivery = BestBudsDelivery) {
    val framework = include(Reaktor.build)
    includeIf(BestBudsOptions.IncludeManna, BestBudsBuilds.Manna)

    val bestbuds = product(shared = projects.app) {
        clients(projects.appAndroid, projects.appDarwin, projects.appWeb, projects.appBrowser, projects.reaktorDesktop)
        services(projects.workers, projects.reaktorServer)
        sites(projects.reaktorWeb, projects.companyOs)
        tools(projects.reaktorCli)
    }

    val pr by verify(bestbuds) {
        include(framework.verify)
        contracts(projects.workers)
        check(BestBudsChecks.CredentialContainment)
    }
    val phones by verify(projects.deviceTests, BestBudsFlows.Everything, run = Run.onPhones())
    val web by verify(projects.appWeb)
    val everything by artifacts(bestbuds)

    delivery(after = pr) {
        val schema by migrate(BestBudsDatabases.Main)
        val backend by deploy(bestbuds.services) { after(schema) }
        val sites by deploy(bestbuds.sites)
        val testflight by storeRelease(projects.appDarwin, Lane.TestflightInternal)
        val play by storeRelease(projects.appAndroid, Lane.PlayInternal)
    }
}
```

What each piece is:
- **`product(...)`** returns a typed `Product`. Its `clients`, `services`, `sites`, `verify` and `artifacts` are plain properties built from the lists given.
- **`verify(bestbuds)`** includes each part's own verification, which the part declares in its component.
- **`delivery(after = pr)`** makes every child wait for `pr`. The `after` edges inside it give the order.
- **`storeRelease`** is a three-line function in BestBuds build logic. It calls `release(...)` and adds `require(BestBudsOptions.DevTools, false)`. Command-click opens it, and the model records it in the call path.

### 4.4.1 Device tests: `bestbuds/targets/deviceTests`

This component absorbs the five device-flow blocks and 15 package aliases from today's root.

```kotlin
plugins { id("dev.shibasis.dependeasy.device-tests") }

deviceTests(apps = BestBudsApps.Debug, defaults = BestBudsDevices) {
    val connect by suite(BestBudsFlows.Connect)
    val everything by suite(BestBudsFlows.Everything, environment = BestBudsEnvironments.Dev)
    val login by suite(BestBudsFlows.Login, environment = BestBudsEnvironments.Dev)
    val push by suite(BestBudsFlows.Push, environment = BestBudsEnvironments.Dev)
    val production by suite(BestBudsFlows.Production, environment = BestBudsEnvironments.Prod)
}
```

- **Platform, single flow and screenshots** are typed options of the device-suite kernel. So `pnpm maestro:ios:login:screenshots` becomes `reaktor build run login --platform ios --flow login-screen --screenshots`, with the same arguments in MCP and Hangar.
- **`BestBudsFlows.Everything`** names a directory and its exclusions. The kernel lists the directory when it runs; the script reads no files.

### 4.5 A component: the worker fleet, `bestbuds/targets/workers`

```kotlin
plugins { id("dev.shibasis.dependeasy.workers") }

workers(delivery = BestBudsDelivery) {
    val textModerator by worker(projects.textModeratorServer)
    val imageModerator by worker(projects.imageModeratorServer)
    val chat by worker(projects.chatServer) { binds(textModerator, imageModerator) }
    val messaging by worker(projects.messagingServer) { binds(chat) }
    val archiver by worker(projects.bestbudsArchiver, environments = Environments.ProdOnly)
    contracts(Suite.Karate, environments = Environments.All)
}
```

- Bindings are values, so the deploy order is the binding graph.
- Routes, vars, D1, R2 and Hyperdrive come from the delivery declaration and each worker's own facts.

## 5. Inside dependeasy: one capability, five layers

Every capability (Kotlin library, web app, worker fleet, Apple app, image rollout, Pulumi stack, and so on) lives in one package with the same five layers. Command-click walks through them in this order.

| Layer | What it is | Rule |
|---|---|---|
| Declaration | The scope class and the element functions | Builds a spec, then `emit(spec, Kernel)`. Nothing else. |
| Spec | Immutable `data class` | Every field typed, serializable; this is the model's `kernel.spec`. File fields are typed `FileSet`s. |
| Kernel | `object X : Kernel<Spec>`, or `DeployKernel<Spec>` with plan, apply, verify and rollback | Plain Kotlin over a `KernelContext` (files, tools, logger, receipts). No Gradle types. Callable by the CLI, MCP and Hangar, and by dependeasy's existing test suites without Gradle. |
| Gradle binding | One generic `KernelTask` per kernel shape, plus the bridges to AGP, KGP and Compose | The only layer that touches Gradle or third-party plugin APIs. Configuration-cache safe. Bridges are listed in `BOUNDARIES.md`. |
| Tool | `object Wrangler : Tool` with typed calls such as `deploy(config, message): WranglerVersion` | Command-line flags exist only here. Versions and checksums come from `ToolchainVersions`. |

Contracts for the TypeScript, Swift, Ruby and CMake adapters are typed outputs of kernels, under `contracts/`.

A full command-click path, from the root script:

`val workers by deploy(projects.workers)` → `fun WorkspaceScope.deploy(fleet: FleetHandle, …)` → `emit(FleetDeploySpec(…), FleetDeploy)` → `object FleetDeploy : DeployKernel<FleetDeploySpec>` → `override fun plan(…)` → `Wrangler.dryRun(…)`.

Every arrow is a symbol the IDE resolves.

Discovery works three ways:
1. Autocomplete inside a scope lists exactly the elements valid there.
2. Command-click walks one capability down to its tool.
3. `reaktor build kernels` lists every kernel with its spec fields and tools.

## 6. Seeing the orchestration

- **The build file.** Read it top to bottom: the component, its children, and its runnable nodes as `val` names.
- **`reaktor build tree [project]`.** Prints the declared tree with each node's kind, spec summary, inherited `Run` values, declaration site and kernel. `--expand` adds the Gradle tasks inside each kernel.
- **`reaktor build explain <node>`.** Prints the spec field by field. It marks each default and says where the default is defined, and it shows the call path through product functions such as `storeRelease`.
- **Hangar's Build view.** Draws the same tree through Blueprint. Clicking a node opens its declaration site in the IDE; clicking its kernel opens the kernel source.
- **Errors.** Validation errors name the declaration site and the kernel rule that failed, as `file:line`, so they are clickable in the build output.

## 7. Gradle realities, handled without magic

- **Scripts can still reach `project` and `tasks`.** Gradle Kotlin scripts always have `Project` as a receiver, so the API cannot hide it. The rule is enforced by facts instead: the model export lists every task not owned by a kernel or a bridged plugin, with its declaration site, as "unowned". The target is zero, and the count is reported per run.
- **Escape hatch.** Product build logic gets a typed escape hatch through the convention-author API: `ConventionScope.gradle { … }`. It is recorded in the model with its site. Scripts do not get it.
- **Language level.** Script-facing APIs use only what Gradle's embedded Kotlin compiles: extension receivers, `@DslMarker`, property delegates and default parameters. No context parameters.
- **Task names.** Gradle tasks still have names, but they come from the declaration (`val pr by verify` gives task `pr`, and kernel-internal tasks get prefixed names). Nobody types them.
- **Isolated projects.** Cross-project references are handles resolved through variants, and specs are plain data. The design therefore stays compatible with Gradle's isolated-projects mode and the configuration cache.
- **Third-party plugins.** AGP, KGP, Compose Multiplatform and Spring Boot are driven by kernels through bridges. Their tasks appear under the kernel in `--expand`. Their DSLs never reach a script.

## 8. Migration

There are no deprecated aliases. Each package converts every caller and deletes the old API.

1. **Workspace scope and the root plugin.** Covers `product`, `verify(parts)`, `artifacts(parts)`, `delivery`, `release`, `deploy`, `migrate`, `publish`, `include` (returning a handle with the included build's roles), `includeIf`, `Run` and `by` naming. Done with WP12 steps 1–4.
2. **Component plugins and scopes.** Covers `library`, `androidApp`, `appleApp`, `desktopApp`, `webApp`, `server`, `workers`, `deviceTests`, `loadTests`, `docs` and `observability`, with their slots. Done with WP13. All 67 authored scripts move.
3. **The five-layer split** for every capability, with Gradle-free kernels and typed tools. Done with WP16. The kernels for WP20–WP26 are written this way from the start.
4. **`@Entry` and test tags** replace main-class and test-class strings.
5. **Inspection.** Tree fields in the model, `reaktor build tree`, `reaktor build explain`, `reaktor build kernels`, and Hangar's tree view, all reading the same model.

Acceptance, all from model and runtime facts:
- Every node has a kernel with source, a spec and a declaration site.
- Every node from a product function has a call path.
- Zero unowned tasks from authored scripts.
- Zero ambient reads from scripts, per Gradle's configuration-cache report.
- The public API dump has no string parameter that names a build element, and no third-party DSL type.
- Kernels run without Gradle: the CLI calls the deploy kernels' plans directly.
- `reaktor build tree` and Hangar print the same tree for bestbuds, manna and reaktor. Its top level is the product's parts, the verifications and the delivery flow.
- No two nodes declared by a root share a declaration site. That proves no node in the root came from a loop.
- The other story-altitude facts are in `kernel-handoff.md` §3.7.

Report the public member count of the DSL before and after. Today it is 225 members across 30 classes plus 89 top-level functions.

The owner reviews the bestbuds root, `reaktorDesktop`, `modules/app` and one reaktor library after conversion. The test is whether each reads as §4 does.
