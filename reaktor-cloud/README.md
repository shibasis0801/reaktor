# reaktor-cloud

Dependeasy configures this module as a Kotlin Multiplatform library with JVM,
Android and Apple targets. Cloud inventory uses the shared Reaktor tooling providers.
Build and delivery operations come from canonical Dependeasy model nodes.

## Build and check

From the Reaktor repository root:

```sh
./gradlew :reaktor-cloud:compileKotlinJvm :reaktor-cloud:jvmTest --console=plain
```

## Generated Grafana SDK

`genGrafanaSdk` runs the checksummed Pulumi CLI installed by Dependeasy, with its
version from `ToolchainVersions`. It writes `build/dependeasy/grafana-sdk`.
`compileGrafanaSdk` and `grafanaSdkJar` compile and package that output, which is a
JVM dependency of this module. Generated SDK files are not edited or vendored.

## Observability ownership

The Kotlin entry point is `dev.shibasis.reaktor.cloud.observability.MainKt`.
Dependeasy's `PulumiStackKernel` owns preview, exact-plan apply and update receipts.
Product declarations supply the stack, source inputs, named secrets and compiled
program output through the build graph; the CLI, MCP and Hangar read that model.

The current live-resource adoption remains in qualification. BestBuds' existing
observability scripts stay available until the protected import and subsequent
production no-change preview pass. A compiled program or successful disposable
stack check does not establish live migration.

Local container CI uses `dependeasy/dagger` with `run --target portableChecks`.
The unused legacy cloud operation catalog, Pulumi runner and parallel run registry
are removed; their fixture did not exercise the canonical delivery path.
