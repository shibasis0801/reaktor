# Dependeasy boundary register

Updated 14 October 2026. These pre-existing exceptions remain visible until their
owned replacement is qualified. WP1 removes AGP `BaseAppModuleExtension` and
Gradle `ServiceRegistry`.

| File and line | Boundary | Reason | Removal condition |
|---|---|---|---|
| `gradle/src/main/kotlin/dev/shibasis/dependeasy/web/KotlinPnpmBridge.kt:24` | KGP `InternalKotlinGradlePluginApi` | KGP's npm environment bridge requires the opt-in while pnpm owns installation. Public injected `ExecOperations` executes commands. | KGP exposes the package manager extension without the opt-in. |
| `gradle/src/main/kotlin/dev/shibasis/dependeasy/verification/PhysicalDevices.kt:4` | KGP internal `KotlinTestReport` | Removes disabled simulator tests from aggregate report inputs; no simulator boots. | Replace the report projection through public test-result variants in WP3/WP9. |

WP3 uses these public Gradle lifecycle constants as task handles: `LifecycleBasePlugin.BUILD_TASK_NAME`, `CHECK_TASK_NAME`, `ASSEMBLE_TASK_NAME`; `JavaPlugin.TEST_TASK_NAME`; `ApplicationPlugin.TASK_RUN_NAME`; `MavenPublishPlugin.PUBLISH_LOCAL_LIFECYCLE_TASK_NAME`; and `PublishingPlugin.PUBLISH_LIFECYCLE_TASK_NAME`. The standalone bootstrap maps its `BootstrapLifecycle` enum to these constants, publishes each module's completion variant in its owning project, and consumes those variants through ordinary project dependencies. It cannot apply its own compiled plugin without a cycle. Workspace root substitution works through the public output variants; there is no included-build task fallback. The bootstrap plugin-test variant has no fast-plugin declaration model; its missing producer provenance remains explicit during WP3 qualification.

WP7 maps KGP’s `embedAndSignAppleFrameworkForXcode` once in `ModuleOutputs` through public `TaskContainer.named`. KGP exposes neither a public task provider nor its Xcode staging directory; `AppleHost` explicitly projects the documented `build/xcode-frameworks` layout. Remove these mappings when KGP publishes that provider and staging directory. No KGP internal types are used. AGP APK locations use `SingleArtifact.APK` and `BuiltArtifactsLoader`; the release contract omits the exact APK until public metadata is present.

WP7 storage qualification disables compilation and generic task caching through the Apple contract. Xcode still initializes its generic store when both settings are disabled. Its installed libclang supports LLVM’s `LLVM_CAS_MAX_MAPPING_SIZE`; the Kotlin release command bounds each mapping to 128 MiB. This controls actual cache allocation without changing storage accounting or moving disposable files. The adapter boundaries are Swift Build’s `BuiltinMacros.swift`/`GenericCachingTaskAction.swift` and LLVM’s `llvm/lib/CAS/OnDiskCommon.cpp`/`OnDiskGraphDB.cpp`. Remove this bound when Xcode skips cache initialization for disabled caching.

## WP12 external call register

The workspace exposes only typed Fastlane lanes, package scripts and relative Bash
scripts until their existing adapters become kernels in WP16. Every declaration
records its replacement condition in `KernelSpec.ExternalCommand`.

| File and line | Boundary | Reason | Removal condition |
|---|---|---|---|
| `bestbuds/build.gradle.kts:78` (`credentialContainmentSmoke`) | Typed external call | Existing credential-containment script. | WP16 containment kernel. |
| `bestbuds/build.gradle.kts:121` (`webChecks`) | Typed external call | Existing product package script. | WP16 browser verification kernel. |
| `bestbuds/build.gradle.kts:122` (`bestbudsWebPerformance`) | Typed external call | Existing product package script. | WP16 Lighthouse verification kernel. |
| `bestbuds/build.gradle.kts:124` (`iosRelease`) | Typed external call | Existing product signing/archive lane. | WP16 Apple archive kernel. |
| `bestbuds/build.gradle.kts:125` (`androidRelease`) | Typed external call | Existing product signing/archive lane. | WP16 Android archive kernel. |
| `bestbuds/build.gradle.kts:154` (`iosDebug`) | Typed external call | Existing product signing/archive lane. | WP16 Apple debug kernel. |
| `manna/build.gradle.kts:33` (`credentialContainmentSmoke`) | Typed external call | Existing credential-containment script. | WP16 containment kernel. |
| `gradle/src/main/kotlin/dev/shibasis/dependeasy/workspace/WorkspaceScope.kt` (`DeliveryScope.release`) | Typed release lane binding | Invokes the lane supplied by the release declaration after its verification gate and output producers. | WP16 typed store-release kernel. |
