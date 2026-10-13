# Dependeasy boundary register

Updated 14 October 2026. These pre-existing exceptions remain visible until their
owned replacement is qualified. WP1 removes AGP `BaseAppModuleExtension` and
Gradle `ServiceRegistry`.

| File and line | Boundary | Reason | Removal condition |
|---|---|---|---|
| `gradle/src/main/kotlin/dev/shibasis/dependeasy/web/KotlinPnpmBridge.kt:24` | KGP `InternalKotlinGradlePluginApi` | KGP's npm environment bridge requires the opt-in while pnpm owns installation. Public injected `ExecOperations` executes commands. | KGP exposes the package manager extension without the opt-in. |
| `gradle/src/main/kotlin/dev/shibasis/dependeasy/verification/PhysicalDevices.kt:4` | KGP internal `KotlinTestReport` | Removes disabled simulator tests from aggregate report inputs; no simulator boots. | Replace the report projection through public test-result variants in WP3/WP9. |
| `gradle/src/main/kotlin/dev/shibasis/dependeasy/workspace/GradleTaskGraph.kt:8` | Gradle `ProjectInternal` | Prior uncommitted task-graph experiment, excluded from the build-layer commits. | WP5 deletes this reader and obtains plans from Gradle's public Tooling API. |
