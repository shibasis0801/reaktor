# Dependeasy boundary register

Updated 14 October 2026. These pre-existing exceptions remain visible until their
owned replacement is qualified. WP1 removes AGP `BaseAppModuleExtension` and
Gradle `ServiceRegistry`.

| File and line | Boundary | Reason | Removal condition |
|---|---|---|---|
| `gradle/src/main/kotlin/dev/shibasis/dependeasy/web/KotlinPnpmBridge.kt:24` | KGP `InternalKotlinGradlePluginApi` | KGP's npm environment bridge requires the opt-in while pnpm owns installation. Public injected `ExecOperations` executes commands. | KGP exposes the package manager extension without the opt-in. |
| `gradle/src/main/kotlin/dev/shibasis/dependeasy/verification/PhysicalDevices.kt:4` | KGP internal `KotlinTestReport` | Removes disabled simulator tests from aggregate report inputs; no simulator boots. | Replace the report projection through public test-result variants in WP3/WP9. |

WP3 uses these public Gradle lifecycle constants as task handles: `LifecycleBasePlugin.BUILD_TASK_NAME`, `CHECK_TASK_NAME`, `ASSEMBLE_TASK_NAME`; `JavaPlugin.TEST_TASK_NAME`; `ApplicationPlugin.TASK_RUN_NAME`; `MavenPublishPlugin.PUBLISH_LOCAL_LIFECYCLE_TASK_NAME`; and `PublishingPlugin.PUBLISH_LIFECYCLE_TASK_NAME`. The standalone bootstrap maps its `BootstrapLifecycle` enum to these constants, publishes each module's completion variant in its owning project, and consumes those variants through ordinary project dependencies. It cannot apply its own compiled plugin without a cycle. Workspace root substitution works through the public output variants; there is no included-build task fallback. The bootstrap plugin-test variant has no fast-plugin declaration model; its missing producer provenance remains explicit during WP3 qualification.
