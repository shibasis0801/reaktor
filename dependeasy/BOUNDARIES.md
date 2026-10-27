# Dependeasy boundary register

Updated 27 October 2026. These active boundaries remain visible until their
owned replacement is qualified. WP1 already removed AGP `BaseAppModuleExtension`
and Gradle `ServiceRegistry`; neither is an active exception.

| File and line | Boundary | Reason | Removal condition |
|---|---|---|---|
| `gradle/src/main/kotlin/dev/shibasis/dependeasy/web/KotlinPnpmBridge.kt:24` | KGP `InternalKotlinGradlePluginApi` | KGP's npm environment bridge requires the opt-in while pnpm owns installation. Public injected `ExecOperations` executes commands. | KGP exposes the package manager extension without the opt-in. |
| `gradle/src/main/kotlin/dev/shibasis/dependeasy/verification/PhysicalDevices.kt:8` | KGP internal `KotlinTestReport` | Removes disabled simulator tests from aggregate report inputs; no simulator boots. | Replace the report projection through public test-result variants in WP3/WP9. |
| `gradle/src/main/kotlin/dev/shibasis/dependeasy/darwin/DarwinConfigure.kt:18` | KGP `DefaultCInteropSettings` in a public signature | The existing low-level interop escape hatch exposes KGP's concrete container element. | Replace the public signature with typed interop declarations while retaining the owning KGP binding. |
| `gradle/src/main/kotlin/dev/shibasis/dependeasy/workspace/GradleReceiptService.kt:8` | Gradle internal `BuildDefinition`, injected and passed to the receipt flow action | A receipt must say whether its own invocation was a dry run. On a configuration-cache hit, a start parameter read during configuration still holds the value from the run that stored the entry; the injected definition carries the current invocation's flag. | Gradle makes per-invocation details available to shared build services ([gradle/gradle#39267](https://github.com/gradle/gradle/issues/39267), open on 27 October 2026). |

WP3 uses these public Gradle lifecycle constants as task handles: `LifecycleBasePlugin.BUILD_TASK_NAME`, `CHECK_TASK_NAME`, `ASSEMBLE_TASK_NAME`; `JavaPlugin.TEST_TASK_NAME`; `ApplicationPlugin.TASK_RUN_NAME`; `MavenPublishPlugin.PUBLISH_LOCAL_LIFECYCLE_TASK_NAME`; and `PublishingPlugin.PUBLISH_LIFECYCLE_TASK_NAME`. The standalone bootstrap maps its `BootstrapLifecycle` enum to these constants, publishes each module's completion variant in its owning project, and consumes those variants through ordinary project dependencies. It cannot apply its own compiled plugin without a cycle. Workspace root substitution works through the public output variants; there is no included-build task fallback. The bootstrap plugin-test variant publishes a producer-owned canonical model through runtime-only JavaExec. Its buildSrc compiles only the shared declaration input policy, membership ValueSource and fixed JVM entry identity. The model records the existing Gradle DefaultTask and `:test` invocation; this verification remains a Gradle task. Exporting metadata does not execute the tests.

WP7 maps KGP’s `embedAndSignAppleFrameworkForXcode` once in `ModuleOutputs` through public `TaskContainer.named`. KGP exposes neither a public task provider nor its Xcode staging directory; `AppleHost` explicitly projects the documented `build/xcode-frameworks` layout. Remove these mappings when KGP publishes that provider and staging directory. No KGP internal types are used. AGP APK locations use `SingleArtifact.APK` and `BuiltArtifactsLoader`; the release contract omits the exact APK until public metadata is present. `ModuleOutputs` captures application variants in their Android owner; the Apple component reads those public objects after project evaluation because its owner may already have completed AGP callbacks.

WP7 storage qualification disables compilation and generic task caching through the Apple contract. Xcode still initializes its generic store when both settings are disabled. Its installed libclang supports LLVM’s `LLVM_CAS_MAX_MAPPING_SIZE`; the Kotlin release command bounds each mapping to 128 MiB. This controls actual cache allocation without changing storage accounting or moving disposable files. The adapter boundaries are Swift Build’s `BuiltinMacros.swift`/`GenericCachingTaskAction.swift` and LLVM’s `llvm/lib/CAS/OnDiskCommon.cpp`/`OnDiskGraphDB.cpp`. Remove this bound when Xcode skips cache initialization for disabled caching.

WP8 uses AGP's public `LibraryAndroidComponentsExtension.onVariants` and `variant.sources.jniLibs.addGeneratedSourceDirectory` to attach the existing owned CMake producer. This repository does not use AGP `externalNativeBuild`, so there is no AGP native-build artifact to consume. CMake's public codemodel file API supplies the exact root artifact and static closure; Prefab's `abi.json` supplies its declared ABI and library kind. Source preparation owns checkout paths. The generated preload contract supplies compiler, SDK and deployment settings before `project()`, and its target section supplies exact imports afterward. Changing contract contents invalidates the existing CMake configure tree.

## WP12 external call register

The workspace exposes only typed Fastlane lanes, package scripts and relative Bash
scripts until their adapters become Kotlin kernels. Every declaration records its
replacement condition in `KernelSpec.ExternalCommand`. Lighthouse audits already run
through the typed `lighthouse` kernel, and store releases through `release` below.

| File and line | Boundary | Reason | Removal condition |
|---|---|---|---|
| `bestbuds/build.gradle.kts:24` (`credentialContainmentSmoke`) | Typed external call | Existing credential-containment script. | Kotlin credential containment kernel. |
| `bestbuds/build.gradle.kts:110` (`iosDebug`) | Typed external call | Existing product debug signing and build lane. | Kotlin Apple debug build kernel. |
| `manna/build.gradle.kts:9` (`credentialContainmentSmoke`) | Typed external call | Existing credential-containment script. | Kotlin credential containment kernel. |
| `gradle/src/main/kotlin/dev/shibasis/dependeasy/workspace/WorkspaceScope.kt` (`DeliveryScope.release`) | Typed release lane binding | Invokes the Fastlane lane named by each `release` declaration after its verification gate and output producers. | Kotlin store release kernel. |

WP13's desktop component binds Compose 1.12.1's existing launch, JPackage,
Proguard and uber-JAR tasks after Compose creates them in its evaluation callback.
The binding stays in `desktop/DesktopApplication.kt`; scripts expose only immutable
app settings. Each launcher consumes the existing compiled `@Entry` contract
through canonical graph relationships, without reading it during model export.
Remove this lifecycle callback when Compose exposes task providers during plugin
application. Source navigation into vendor JavaExec kernels is still open; the
compiled-entry resolver already links to `JvmEntry`.

